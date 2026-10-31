"""Generate editable SVG brand templates and exact-size PNG previews.

Logos are copied as paths from the supplied brand kit, without redrawing.
Rendering uses a temporary SVG with Lato glyph outlines to avoid font fallback.
The delivered SVGs retain editable text and embedded original font files.
"""
from pathlib import Path
import base64
import copy
import hashlib
import json
import xml.etree.ElementTree as ET

import fitz
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

BASE = Path(__file__).resolve().parent
VISUAL = BASE.parent
BRAND = VISUAL / "brand-assets" / "svg"
FONT_DIR = VISUAL / "fonts"
NAVY, TEAL, WHITE = "#102A43", "#007F86", "#FFFFFF"
NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)

FONTS = {
    "400": TTFont(FONT_DIR / "Lato-Regular.ttf"),
    "700": TTFont(FONT_DIR / "Lato-Bold.ttf"),
}
SOURCE_LOGOS = {
    "white": BRAND / "condpulse-horizontal-mono-branca.svg",
    "positive": BRAND / "condpulse-horizontal-positiva.svg",
}
FONT_CSS = "\n".join(
    f"@font-face{{font-family:'Lato';font-style:normal;font-weight:{weight};"
    f"src:url(data:font/ttf;base64,{base64.b64encode((FONT_DIR / file).read_bytes()).decode()}) format('truetype');}}"
    for weight, file in [("400", "Lato-Regular.ttf"), ("700", "Lato-Bold.ttf")]
)


def node(tag, attrs=None, parent=None, text=None):
    result = ET.Element(f"{{{NS}}}{tag}", {k.replace("_", "-"): str(v) for k, v in (attrs or {}).items()})
    if text is not None:
        result.text = text
    if parent is not None:
        parent.append(result)
    return result


def text_width(text, size, weight="400", spacing=0):
    font = FONTS[str(weight)]
    cmap = font.getBestCmap()
    return sum(font["hmtx"][cmap[ord(c)]][0] for c in text) * size / font["head"].unitsPerEm + max(0, len(text)-1) * spacing


class Art:
    def __init__(self, name, width, height, title):
        self.name, self.width, self.height = name, width, height
        self.root = node("svg", {"width": width, "height": height, "viewBox": f"0 0 {width} {height}", "role": "img", "aria-label": title})
        node("title", parent=self.root, text=title)
        node("desc", parent=self.root, text="Template original CondPulse. Texto editável em Lato; logo vetorial preservado a partir do kit da marca.")
        defs = node("defs", parent=self.root)
        node("style", parent=defs, text=FONT_CSS)
        self.text_boxes = []
        self.logos = []

    def group(self, identity, transform=None):
        attrs = {"id": identity}
        if transform:
            attrs["transform"] = transform
        return node("g", attrs, self.root)

    def rect(self, x, y, w, h, fill, radius=0, stroke=None, sw=1, opacity=None, parent=None):
        attrs = {"x": x, "y": y, "width": w, "height": h, "fill": fill, "rx": radius}
        if stroke: attrs.update({"stroke": stroke, "stroke-width": sw})
        if opacity is not None: attrs["opacity"] = opacity
        return node("rect", attrs, parent if parent is not None else self.root)

    def line(self, x1, y1, x2, y2, color, sw=2, opacity=1, parent=None):
        return node("line", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "stroke": color, "stroke-width": sw, "stroke-linecap": "round", "opacity": opacity}, parent if parent is not None else self.root)

    def path(self, d, color=TEAL, sw=3, fill="none", opacity=1, parent=None):
        return node("path", {"d": d, "stroke": color, "stroke-width": sw, "stroke-linecap": "round", "stroke-linejoin": "round", "fill": fill, "opacity": opacity}, parent if parent is not None else self.root)

    def circle(self, cx, cy, r, fill="none", stroke=TEAL, sw=3, parent=None):
        return node("circle", {"cx": cx, "cy": cy, "r": r, "fill": fill, "stroke": stroke, "stroke-width": sw}, parent if parent is not None else self.root)

    def text(self, text, x, y, size, color=WHITE, weight="400", identity=None, max_width=None, parent=None, spacing=0):
        width = text_width(text, size, weight, spacing)
        if max_width is not None and width > max_width + 0.5:
            raise ValueError(f"Text exceeds layout: {self.name}: {text}: {width:.1f}>{max_width}")
        if x + width > self.width + 0.5 or x < 0 or y > self.height:
            raise ValueError(f"Text outside canvas: {self.name}: {text}")
        attrs = {"x": x, "y": y, "font-family": "Lato", "font-size": size, "font-weight": weight, "fill": color}
        if identity: attrs["id"] = identity
        if spacing: attrs["letter-spacing"] = spacing
        self.text_boxes.append({"text": text, "x": x, "baseline": y, "width": round(width,2), "size": size})
        return node("text", attrs, parent if parent is not None else self.root, text)

    def logo(self, variant, x, y, width):
        source = ET.parse(SOURCE_LOGOS[variant]).getroot()
        g = self.group("logo-condpulse", f"translate({x} {y}) scale({width/724:.10f})")
        for el in source:
            if el.tag.endswith("path"):
                g.append(copy.deepcopy(el))
        self.logos.append({"variant": variant, "source": SOURCE_LOGOS[variant].name, "sha256": hashlib.sha256(SOURCE_LOGOS[variant].read_bytes()).hexdigest(), "x":x,"y":y,"width":width})

    def arrow(self, x, y, color, size=28, parent=None):
        self.line(x, y, x+size, y, color, 2.7, parent=parent)
        self.path(f"M {x+size-9} {y-9} L {x+size} {y} L {x+size-9} {y+9}", color, 2.7, parent=parent)

    def cta(self, x, y, w, h, size=30, fill=TEAL, color=WHITE):
        g=self.group("cta-conheca-condpulse")
        self.rect(x,y,w,h,fill,14,parent=g)
        self.text("Conheça o CondPulse",x+28,y+h/2+size*.34,size,color,"700",max_width=w-100,parent=g)
        self.arrow(x+w-57,y+h/2,color,25,parent=g)

    def icon(self, kind, x, y, size=48, color=TEAL, parent=None):
        container=node("g",{"transform":f"translate({x} {y}) scale({size/48})"},parent if parent is not None else self.root)
        if kind == "people":
            self.circle(18,13,6.2,stroke=color,sw=2.5,parent=container)
            self.circle(34,16,4.8,stroke=color,sw=2.5,parent=container)
            self.path("M5 38 V33 C5 24 31 24 31 33 V38 Z M35 27 C42 27 44 30 44 35 V38 H36",color,2.5,parent=container)
        elif kind == "calendar":
            self.rect(5,9,38,33,"none",5,color,2.5,parent=container)
            self.line(5,20,43,20,color,2.5,parent=container)
            self.line(15,4,15,14,color,2.5,parent=container)
            self.line(33,4,33,14,color,2.5,parent=container)
            self.path("M16 31 L22 36 L33 26",color,2.8,parent=container)
        else:
            self.path("M6 7 H42 V33 H23 L12 42 V33 H6 Z",color,2.5,parent=container)
            self.line(14,16,34,16,color,2.5,parent=container)
            self.line(14,24,29,24,color,2.5,parent=container)

    def tall_card(self, x,y,w,h,title,kind):
        g=self.group(f"card-{kind}")
        self.rect(x,y,w,h,WHITE,22,parent=g)
        self.icon(kind,x+26,y+24,48,parent=g)
        self.text(title,x+26,y+113,27,NAVY,"700",max_width=w-40,parent=g)
        self.line(x+27,y+149,x+w-27,y+149,NAVY,6,.12,parent=g)
        self.line(x+27,y+171,x+w-80,y+171,NAVY,6,.12,parent=g)

    def wide_card(self,x,y,w,h,title,kind):
        g=self.group(f"card-{kind}")
        self.rect(x,y,w,h,WHITE,22,parent=g)
        self.rect(x+24,y+24,70,70,TEAL,18,opacity=.08,parent=g)
        self.icon(kind,x+35,y+34,48,parent=g)
        self.text(title,x+120,y+64,32,NAVY,"700",max_width=w-220,parent=g)
        self.line(x+121,y+91,x+w-86,y+91,NAVY,6,.12,parent=g)
        self.circle(x+w-43,y+h/2,17,fill=TEAL,stroke=TEAL,sw=0,parent=g)
        self.path(f"M{x+w-49} {y+h/2} L{x+w-44} {y+h/2+5} L{x+w-36} {y+h/2-5}",WHITE,2.8,parent=g)

    def outline_architecture(self,x,y,w,h,opacity=.23):
        g=self.group("linhas-arquitetonicas")
        g.set("opacity",str(opacity))
        self.path(f"M{x} {y+h} V{y+h*.33} H{x+w*.26} V{y} H{x+w*.64} V{y+h*.2} H{x+w} V{y+h}",TEAL,3,parent=g)
        for xx in [x+w*.36,x+w*.48,x+w*.76,x+w*.87]:
            self.line(xx,y+h*.39,xx,y+h*.75,TEAL,3,parent=g)
        self.line(x-30,y+h,x+w+30,y+h,TEAL,3,parent=g)

    def save(self):
        svg_file=BASE/f"{self.name}.svg"
        ET.ElementTree(self.root).write(svg_file,encoding="utf-8",xml_declaration=True)
        render_root=copy.deepcopy(self.root)
        # Only the rendering copy receives glyph outlines. Editable sources stay text-based.
        for parent in render_root.iter():
            for child in list(parent):
                if child.tag==f"{{{NS}}}style":
                    parent.remove(child)
                if child.tag!=f"{{{NS}}}text":
                    continue
                string=child.text or ""
                size=float(child.get("font-size"));weight=child.get("font-weight","400")
                font=FONTS[weight];glyphs=font.getGlyphSet();cmap=font.getBestCmap()
                scale=size/font["head"].unitsPerEm
                cursor=float(child.get("x"));y=float(child.get("y"));spacing=float(child.get("letter-spacing","0"))
                group=node("g",{"aria-label":string})
                for character in string:
                    name=cmap[ord(character)];pen=SVGPathPen(glyphs);glyphs[name].draw(pen)
                    data=pen.getCommands()
                    if data:
                        node("path",{"d":data,"fill":child.get("fill"),"transform":f"translate({cursor:.6f} {y}) scale({scale:.9f} {-scale:.9f})"},group)
                    cursor+=font["hmtx"][name][0]*scale+spacing
                position=list(parent).index(child);parent.remove(child);parent.insert(position,group)
        svg_bytes=ET.tostring(render_root,encoding="utf-8")
        doc=fitz.open(stream=svg_bytes,filetype="svg")
        pdf=fitz.open("pdf",doc.convert_to_pdf());page=pdf[0]
        pix=page.get_pixmap(matrix=fitz.Matrix(self.width/page.rect.width,self.height/page.rect.height),alpha=False)
        pix.save(BASE/f"{self.name}.png")
        if (pix.width,pix.height)!=(self.width,self.height):
            raise ValueError("Unexpected PNG dimensions")
        return {"name":self.name,"svg":svg_file.name,"png":f"{self.name}.png","width":self.width,"height":self.height,"logo_sources":self.logos,"text_boxes":self.text_boxes}


def feed():
    a=Art("condpulse-feed-1080x1350",1080,1350,"CondPulse — A rotina do seu condomínio, organizada.")
    a.rect(0,0,1080,1350,NAVY)
    a.outline_architecture(796,210,290,435,.24)
    a.logo("white",80,60,350)
    a.rect(80,216,64,7,TEAL,3)
    a.text("A rotina do seu",80,337,74,weight="700",identity="titulo-linha-1",max_width=890)
    a.text("condomínio,",80,429,74,weight="700",identity="titulo-linha-2",max_width=890)
    a.text("organizada.",80,521,74,weight="700",identity="titulo-linha-3",max_width=890)
    a.text("Pessoas, reservas e comunicação",80,635,33,identity="apoio-linha-1",max_width=920)
    a.text("em um só lugar.",80,682,33,identity="apoio-linha-2",max_width=920)
    a.tall_card(80,806,290,225,"Pessoas","people")
    a.tall_card(395,806,290,225,"Reservas","calendar")
    a.tall_card(710,806,290,225,"Comunicação","message")
    a.cta(80,1130,454,78,31)
    a.line(80,1277,1000,1277,WHITE,1,.16)
    return a


def story():
    a=Art("condpulse-story-1080x1920",1080,1920,"CondPulse — Pessoas, reservas e comunicação em um só lugar.")
    a.rect(0,0,1080,1920,NAVY)
    a.outline_architecture(740,330,340,610,.22)
    a.logo("white",90,178,370)
    a.rect(90,358,64,7,TEAL,3)
    a.text("A rotina do seu",90,486,82,weight="700",identity="titulo-linha-1",max_width=900)
    a.text("condomínio,",90,588,82,weight="700",identity="titulo-linha-2",max_width=900)
    a.text("organizada.",90,690,82,weight="700",identity="titulo-linha-3",max_width=900)
    a.text("Pessoas, reservas e comunicação",90,812,36,identity="apoio-linha-1",max_width=900)
    a.text("em um só lugar.",90,861,36,identity="apoio-linha-2",max_width=900)
    a.wide_card(90,980,900,128,"Pessoas","people")
    a.wide_card(90,1138,900,128,"Reservas","calendar")
    a.wide_card(90,1296,900,128,"Comunicação","message")
    a.cta(90,1535,570,94,36,WHITE,NAVY)
    a.line(90,1735,990,1735,WHITE,1,.16)
    return a


def ad():
    a=Art("condpulse-anuncio-1200x628",1200,628,"CondPulse — A rotina do seu condomínio, organizada.")
    a.rect(0,0,1200,628,NAVY)
    a.outline_architecture(821,-35,380,713,.26)
    a.logo("white",56,30,273)
    a.text("A rotina do seu",56,205,55,weight="700",identity="titulo-linha-1",max_width=660)
    a.text("condomínio, organizada.",56,273,55,weight="700",identity="titulo-linha-2",max_width=660)
    a.text("Pessoas, reservas e comunicação",56,361,26,identity="apoio-linha-1",max_width=650)
    a.text("em um só lugar.",56,399,26,identity="apoio-linha-2",max_width=650)
    a.cta(56,477,386,65,25)
    for x,y,title,kind in [(812,92,"Pessoas","people"),(774,255,"Reservas","calendar"),(812,418,"Comunicação","message")]:
        g=a.group(f"card-{kind}")
        a.rect(x,y,332,124,WHITE,20,parent=g)
        a.icon(kind,x+25,y+29,48,parent=g)
        a.text(title,x+94,y+55,25,NAVY,"700",max_width=220,parent=g)
        a.line(x+95,y+82,x+279,y+82,NAVY,5,.14,parent=g)
    return a


def signature():
    a=Art("condpulse-assinatura-email-600x180",600,180,"CondPulse — assinatura de e-mail")
    a.rect(0,0,600,180,WHITE)
    a.logo("positive",24,56,182)
    a.line(229,28,229,152,TEAL,2)
    a.text("A rotina do seu condomínio,",251,46,16,NAVY,"700",identity="titulo-linha-1",max_width=325)
    a.text("organizada.",251,67,16,NAVY,"700",identity="titulo-linha-2",max_width=325)
    a.text("Pessoas, reservas e comunicação",251,96,12,NAVY,identity="apoio-linha-1",max_width=325)
    a.text("em um só lugar.",251,113,12,NAVY,identity="apoio-linha-2",max_width=325)
    a.text("Conheça o CondPulse",251,144,13,TEAL,"700",identity="cta-texto",max_width=250)
    a.arrow(407,139,TEAL,17)
    a.line(24,169,576,169,TEAL,2)
    return a


if __name__ == "__main__":
    BASE.mkdir(parents=True,exist_ok=True)
    outputs=[f().save() for f in (feed,story,ad,signature)]
    manifest={
        "brand":"CondPulse",
        "purpose":"Amostras editáveis para adaptação de comunicação da marca; não publicadas.",
        "palette":{"navy":NAVY,"teal":TEAL,"white":WHITE},
        "fonts":["Lato Regular","Lato Bold"],
        "font_note":"TTFs originais incorporados aos SVGs. Para edição, instalar também as fontes fornecidas no pacote.",
        "logo_note":"Paths dos SVGs do kit copiados sem redesenho ou alteração de cores. Usadas versões mono branca e positiva.",
        "cta":"Conheça o CondPulse",
        "url":None,
        "external_images":False,
        "files":outputs,
    }
    (BASE/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
    print("Generated",len(outputs),"SVGs and PNGs")
