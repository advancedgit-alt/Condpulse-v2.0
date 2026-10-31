#!/usr/bin/env python3
"""Recalculate exact-token sRGB contrast checks; standard library only.

Run: python validate-contrast.py
Writes contrast-report.json and contrast-report.md next to this file.
Exit code 1 means at least one intended required color pair failed.
This is not a WCAG certification, browser audit, or physical-device homologation.
"""
from pathlib import Path
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'design-tokens.json'
raw = SOURCE.read_bytes()
tokens = json.loads(raw)


def luminance(hex_color):
    if not isinstance(hex_color, str) or not re.fullmatch(r'#[0-9A-Fa-f]{6}', hex_color):
        raise ValueError(f'Only opaque 6-digit sRGB is supported: {hex_color!r}')
    channels = [int(hex_color[i:i+2], 16) / 255 for i in (1, 3, 5)]
    linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast(foreground, background):
    a, b = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (a + 0.05) / (b + 0.05)


records = []
thresholds = {
    'text-normal-AA': {'threshold': 4.5, 'sc': '1.4.3', 'level': 'AA', 'scope': 'Contraste de texto comum'},
    'text-large-AA': {'threshold': 3.0, 'sc': '1.4.3', 'level': 'AA', 'scope': 'Texto grande conforme definição WCAG'},
    'non-text-AA': {'threshold': 3.0, 'sc': '1.4.11', 'level': 'AA', 'scope': 'Informação visual necessária de controles/estados/gráficos'},
    'focus-contrast-AAA-portion': {'threshold': 3.0, 'sc': '2.4.13', 'level': 'AAA', 'scope': 'Só a parcela de contraste entre pixels; área/visibilidade não são testadas'},
}


def add(mode, label, fg_name, bg_name, criterion, category='required', note=''):
    theme = tokens['themes'][mode]
    fg = theme[fg_name] if not fg_name.startswith('#') else fg_name
    bg = theme[bg_name] if not bg_name.startswith('#') else bg_name
    ratio = contrast(fg, bg)
    definition = thresholds.get(criterion)
    threshold = definition['threshold'] if definition else None
    passes = None if threshold is None else ratio >= threshold  # Never round before comparison.
    records.append({
        'id': f'{mode}-{len(records) + 1:03}', 'mode': mode, 'label': label,
        'foregroundToken': fg_name, 'foreground': fg, 'backgroundToken': bg_name, 'background': bg,
        'relativeLuminanceForeground': luminance(fg), 'relativeLuminanceBackground': luminance(bg),
        'ratio': ratio, 'displayRatio': f'{ratio:.3f}:1',
        'criterion': criterion, 'successCriterion': definition['sc'] if definition else None,
        'level': definition['level'] if definition else None, 'threshold': threshold,
        'pass': passes, 'category': category, 'note': note,
    })


for mode in ('light', 'dark'):
    surfaces = ('surface.canvas', 'surface.panel', 'surface.alternate')
    for text in ('text.primary', 'text.secondary', 'text.link'):
        for surface in surfaces:
            add(mode, f'{text} sobre {surface}', text, surface, 'text-normal-AA')
    add(mode, 'Placeholder informativo sobre campo', 'text.secondary', 'surface.panel', 'text-normal-AA')
    for bg in ('action.primary.background', 'action.primary.hover'):
        add(mode, f'Texto da ação sobre {bg}', 'action.primary.foreground', bg, 'text-normal-AA')
        for surface in surfaces:
            add(mode, f'Limite da ação {bg} contra {surface}', bg, surface, 'non-text-AA')
    for surface in surfaces:
        add(mode, f'Borda de controle contra {surface}', 'border.control', surface, 'non-text-AA')
        add(mode, f'Foco contra {surface}, com offset', 'focus.ring', surface, 'non-text-AA',
            note='Outline 2px com offset 3px. O anel precisa ficar sobre esta superfície, sem clipping.')
        add(mode, f'Foco: contraste entre estados em {surface}', 'focus.ring', surface, 'focus-contrast-AAA-portion',
            category='supplementary', note='Só contraste. Não comprova área de perímetro, foco não oculto nem conformidade AAA completa.')
        add(mode, f'Divisor decorativo contra {surface}', 'border.decorative', surface, None,
            category='decorative', note='Sem limiar isolado: não pode ser o único limite necessário para reconhecer um controle.')
    for text in ('text.inverse', 'text.inverseSecondary', 'text.inverseLink'):
        add(mode, f'{text} sobre superfície inversa', text, 'surface.inverse', 'text-normal-AA')
    add(mode, 'Foco da superfície inversa', 'focus.inverse', 'surface.inverse', 'non-text-AA',
        note='Usar focus.inverse, não presumir que focus.ring funciona sobre navy.')
    for state in ('success', 'warning', 'danger', 'info'):
        fg, bg = f'status.{state}.foreground', f'status.{state}.background'
        add(mode, f'Rótulo do estado {state}', fg, bg, 'text-normal-AA')
        add(mode, f'Ícone significativo do estado {state}', fg, bg, 'non-text-AA')
        add(mode, f'Texto principal no aviso {state}', 'text.primary', bg, 'text-normal-AA')
        add(mode, f'Texto secundário no aviso {state}', 'text.secondary', bg, 'text-normal-AA')
        add(mode, f'Linha de gráfico {state} sobre painel', fg, 'surface.panel', 'non-text-AA',
            note='Rótulos e marcadores adicionais são necessários; esta medição não diferencia séries entre si.')
        add(mode, f'Fundo do aviso {state} contra painel', bg, 'surface.panel', None,
            category='decorative', note='O significado vem do texto/ícone; a superfície suave não é a única informação de estado.')
    for surface in surfaces:
        add(mode, f'USO INADEQUADO: divisor como única borda de campo em {surface}', 'border.decorative', surface, 'non-text-AA',
            category='diagnostic', note='Esta combinação não é autorizada para o uso indicado; usar border.control.')
    add(mode, 'USO INADEQUADO: anel de foco tocando diretamente o botão primário', 'focus.ring', 'action.primary.background', 'non-text-AA',
        category='diagnostic', note='Usar offset com superfície medida no intervalo; não colar o anel diretamente na cor primária.')
    add(mode, 'Diagnóstico: foco padrão contra superfície inversa', 'focus.ring', 'surface.inverse', 'non-text-AA',
        category='diagnostic', note='A variante oficial nessa superfície é focus.inverse, independentemente deste resultado numérico.')
    if mode == 'light':
        add(mode, 'USO INADEQUADO: teal institucional como texto comum no canvas', '#007F86', 'surface.canvas', 'text-normal-AA',
            category='diagnostic', note='Usar text.link #00666D; a razão não pode ser arredondada para 4,5.')
    else:
        add(mode, 'USO INADEQUADO: texto branco no botão claro do modo escuro', '#FFFFFF', 'action.primary.background', 'text-normal-AA',
            category='diagnostic', note='Usar action.primary.foreground #0D1724.')

required = [r for r in records if r['category'] == 'required']
required_failures = [r for r in required if r['pass'] is False]
summary = {
    'requiredPairChecks': len(required),
    'requiredPasses': sum(r['pass'] is True for r in required),
    'requiredFailures': len(required_failures),
    'supplementaryContrastOnlyChecks': sum(r['category'] == 'supplementary' for r in records),
    'decorativeMeasurementsWithoutPassFail': sum(r['category'] == 'decorative' for r in records),
    'diagnosticChecks': sum(r['category'] == 'diagnostic' for r in records),
    'diagnosticFailures': sum(r['category'] == 'diagnostic' and r['pass'] is False for r in records),
    'intendedColorPairsPass': not required_failures,
    'fullSystemWcagConformance': 'NOT_EVALUATED',
    'productionHomologation': 'NOT_PERFORMED',
}
report = {
    'title': 'CondPulse — relatório de contraste de pares da proposta',
    'status': 'PROPOSTA; avaliação numérica de pares opacos, sem certificação do sistema',
    'date': '2026-10-31', 'tokensVersion': tokens['version'],
    'tokensFileSha256': hashlib.sha256(raw).hexdigest(),
    'method': {
        'colorSpace': 'sRGB, opaco, HEX de 6 dígitos',
        'transferFunction': 'c<=0.04045: c/12.92; caso contrário: ((c+0.055)/1.055)^2.4',
        'luminance': '0.2126*Rlin + 0.7152*Glin + 0.0722*Blin',
        'contrast': '(Lmais_clara+0.05)/(Lmais_escura+0.05)',
        'comparison': 'IEEE-754 double; compare ratio >= threshold BEFORE formatting; no rounding tolerance',
        'displayRounding': 'Somente displayRatio e tabelas usam 3 casas decimais; ratio preserva o resultado numérico.',
    },
    'criteria': thresholds,
    'summary': summary,
    'limits': [
        'Não verifica tamanho/peso real da fonte, semântica, teclado, leitor de tela, reflow, clipping, zoom, erro, alvo de toque, dados ou autorização.',
        'Transparência, sombra, gradiente, imagem de fundo e opacidade global alteram o resultado e não são cobertos.',
        'Passagens de contraste da linha do gráfico não validam a distinção entre séries. Usar rótulos, símbolos e tabela alternativa.',
        'Foco: o contraste assume outline desenhado sobre a superfície indicada com offset; área e pixels reais precisam de teste em navegador.',
        'Os pares diagnosticamente inadequados são intencionais para documentar usos proibidos. Não entram como pares aprovados.',
        'Fundo/divisor decorativo tem pass=null. Sua função precisa realmente ser decorativa.',
        'Modo escuro mantém hover primário como alias do normal; verificar o feedback completo do componente antes da implantação.',
    ],
    'sources': [
        'https://www.w3.org/TR/WCAG22/#contrast-minimum',
        'https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html',
        'https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html',
        'https://www.w3.org/WAI/WCAG22/Understanding/focus-appearance.html',
    ],
    'checks': records,
}
(ROOT / 'contrast-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

md = [
    '# CondPulse — relatório de contraste da proposta', '',
    '**Status: proposta técnica. Não é homologação da aplicação nem certificação WCAG.**', '',
    f"Data: {report['date']} · Tokens: {tokens['version']} · Fonte: `design-tokens.json`.", '',
    '## Resultado', '',
    f"**{summary['requiredPasses']} de {summary['requiredPairChecks']} verificações de pares obrigatórios passaram**, dentro dos usos e condições especificados.", '',
    f"Há {summary['decorativeMeasurementsWithoutPassFail']} medições decorativas sem limiar, {summary['supplementaryContrastOnlyChecks']} verificações complementares de contraste de foco e {summary['diagnosticChecks']} diagnósticos de usos inadequados ({summary['diagnosticFailures']} abaixo do limiar).", '',
    'A aplicação completa não foi executada ou avaliada por este script. Valores repetidos em usos diferentes aparecem como verificações distintas.', '',
    '## Método', '',
    'Cores sRGB opacas em HEX de 6 dígitos. Cada canal é linearizado; a luminância usa 0,2126R + 0,7152G + 0,0722B. A razão é (Lclara + 0,05)/(Lescura + 0,05). O comparador usa o valor numérico integral disponível antes de qualquer formatação. A tabela exibe três casas por legibilidade.', '',
    'Critérios: texto comum AA 4,5:1 (1.4.3); informação visual não textual necessária AA 3:1 (1.4.11). O teste complementar de foco usa apenas a parcela de contraste do critério AAA 2.4.13, sem validar área ou visibilidade. Bordas puramente decorativas não recebem falso requisito de 3:1.', '',
    '## Decisões decorrentes', '',
    '- Texto branco sobre o botão #007F86 é permitido pelo par calculado. O mesmo teal como texto pequeno sobre #F4F7FA falha: usar link #00666D.',
    '- No modo escuro, o botão #5DD6C7 usa texto #0D1724; não usar branco nesse botão.',
    '- Campos usam a borda `border.control`; `border.decorative` é reservada a divisores/superfícies que não dependem dela para serem reconhecidos.',
    '- O foco usa outline de 2px e offset de 3px sobre a superfície medida. Em navy/superfície inversa, usar `focus.inverse`. Verificar clipping, área e estados no navegador.',
    '- Links têm sublinhado ou outro identificador persistente; o significado não depende somente da cor.',
    '- Estados de erro, alerta e sucesso incluem texto/ícone. Cor não confirma uma abertura física ou sincronização.', '',
]
for mode in ('light', 'dark'):
    md += [f"## Modo {'claro' if mode == 'light' else 'escuro'} — pares obrigatórios", '',
           '| Uso | Frente | Fundo | Razão | Critério | Resultado |', '|---|---|---|---:|---|---|']
    for r in records:
        if r['mode'] == mode and r['category'] == 'required':
            result = 'PASSA' if r['pass'] else 'FALHA'
            md.append(f"| {r['label']} | `{r['foreground']}` | `{r['background']}` | {r['displayRatio']} | {r['successCriterion']} ≥ {r['threshold']:g} | {result} |")
    md.append('')
md += ['## Combinações diagnosticadas — não copiar como padrão', '',
       '| Modo | Uso diagnosticado | Frente / fundo | Razão | Limiar | Resultado numérico |', '|---|---|---|---:|---:|---|']
for r in records:
    if r['category'] == 'diagnostic':
        result = 'Abaixo do limiar' if not r['pass'] else 'Acima do limiar; usar variante indicada'
        md.append(f"| {r['mode']} | {r['label']} | `{r['foreground']}` / `{r['background']}` | {r['displayRatio']} | {r['threshold']:g} | {result} |")
md += ['', '## Decoração e foco complementar', '',
       'As medições de divisores/fundos decorativos e as verificações complementares de foco estão registradas individualmente no JSON. `pass: null` identifica decoração sem limiar aplicável; não significa aprovação para converter a cor em borda de campo.', '',
       '## Limites', '']
md += ['- ' + note for note in report['limits']]
md += ['', '## Reproduzir', '', '```bash', 'python validate-contrast.py', '```', '',
       'O script usa apenas a biblioteca padrão do Python e grava os dois relatórios. Código de saída 1 indica falha em algum par obrigatório previsto; diagnósticos de combinações proibidas são preservados, mas não confundidos com pares válidos.', '',
       '## Fontes', '']
md += ['- ' + url for url in report['sources']]
md += ['', f"SHA-256 do arquivo de tokens avaliado: `{report['tokensFileSha256']}`", '']
(ROOT / 'contrast-report.md').write_text('\n'.join(md))
print(json.dumps(summary, ensure_ascii=False))
for r in required_failures:
    print(f"FAIL {r['mode']} {r['label']}: {r['ratio']} < {r['threshold']}")
sys.exit(1 if required_failures else 0)
