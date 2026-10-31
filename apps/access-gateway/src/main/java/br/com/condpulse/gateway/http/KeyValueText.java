package br.com.condpulse.gateway.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.json.Json;

/** Formatos de texto de CGI: linhas {@code chave=valor} e blocos multipart de eventos {@code Code=...;data={...}}. */
public final class KeyValueText {
 private KeyValueText() {}

 /** Linhas {@code chave=valor}; linha sem '=' é ignorada; chave repetida mantém o primeiro valor. */
 public static Map<String,String> lines(String text) {
  var out=new LinkedHashMap<String,String>();
  for(String line:text.split("\\r?\\n")) {
   int eq=line.indexOf('=');
   if(eq<=0) continue;
   out.putIfAbsent(line.substring(0,eq).trim(),line.substring(eq+1).trim());
  }
  return out;
 }

 /** Bloco de evento: Code, action, index e data (JSON, quando houver). */
 public record EventBlock(String code,String action,String index,Map<String,Object> data) {}

 /**
  * Separa um corpo multipart delimitado por {@code --<boundary>} e lê cada bloco
  * {@code Code=X;action=Y;index=N;data={...}}. Bloco que não segue o formato é ignorado (contado pelo chamador).
  */
 public static List<EventBlock> eventBlocks(String body,String boundary) {
  var out=new ArrayList<EventBlock>();
  for(String part:body.split("--"+java.util.regex.Pattern.quote(boundary))) {
   int start=part.indexOf("Code=");
   if(start<0) continue;
   String content=part.substring(start).trim();
   String code=null,action=null,index=null;
   Map<String,Object> data=null;
   int dataAt=content.indexOf(";data=");
   String head=dataAt<0?content:content.substring(0,dataAt);
   for(String field:head.split(";")) {
    int eq=field.indexOf('=');
    if(eq<0) continue;
    String k=field.substring(0,eq).trim(), v=field.substring(eq+1).trim();
    switch(k) { case "Code" -> code=v; case "action" -> action=v; case "index" -> index=v; default -> {} }
   }
   if(dataAt>=0) {
    String json=content.substring(dataAt+6).trim();
    int end=json.lastIndexOf('}');
    if(end>0) {
     try { data=Json.object(json.substring(0,end+1)); } catch(Json.JsonException e) { data=null; }
    }
   }
   if(code!=null && code.matches("[A-Za-z0-9_]{1,64}")) out.add(new EventBlock(code,action,index,data));
  }
  return out;
 }
}
