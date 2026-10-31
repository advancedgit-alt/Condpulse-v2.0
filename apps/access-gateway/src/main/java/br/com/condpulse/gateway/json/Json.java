package br.com.condpulse.gateway.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mínimo e sem dependência externa: objetos viram {@code LinkedHashMap<String,Object>}, listas viram
 * {@code ArrayList}, números inteiros viram {@code Long} e os demais {@code BigDecimal}. Limita profundidade e tamanho
 * para que corpo de equipamento ou da plataforma não esgote memória do gateway.
 */
public final class Json {
 public static final int MAX_DEPTH=64;
 public static final int MAX_CHARS=8*1024*1024;
 private Json() {}

 public static final class JsonException extends RuntimeException {
  public JsonException(String message) { super(message); }
 }

 public static Object parse(String text) {
  if(text==null) throw new JsonException("JSON ausente.");
  if(text.length()>MAX_CHARS) throw new JsonException("JSON acima do limite.");
  var parser=new Parser(text);
  parser.ws();
  Object value=parser.value(0);
  parser.ws();
  if(parser.pos!=text.length()) throw new JsonException("Conteúdo após o JSON.");
  return value;
 }

 /** Objeto JSON obrigatório. */
 @SuppressWarnings("unchecked")
 public static Map<String,Object> object(String text) {
  Object value=parse(text);
  if(!(value instanceof Map)) throw new JsonException("Esperado objeto JSON.");
  return (Map<String,Object>)value;
 }

 @SuppressWarnings("unchecked")
 public static Map<String,Object> map(Object value) {
  if(value==null) return null;
  if(!(value instanceof Map)) throw new JsonException("Esperado objeto.");
  return (Map<String,Object>)value;
 }
 @SuppressWarnings("unchecked")
 public static List<Object> list(Object value) {
  if(value==null) return null;
  if(!(value instanceof List)) throw new JsonException("Esperada lista.");
  return (List<Object>)value;
 }
 public static String string(Map<String,Object> map,String key) {
  Object value=map==null?null:map.get(key);
  if(value==null) return null;
  if(value instanceof String s) return s;
  if(value instanceof Number || value instanceof Boolean) return String.valueOf(value);
  throw new JsonException("Campo não textual: "+key);
 }
 public static Long longValue(Map<String,Object> map,String key) {
  Object value=map==null?null:map.get(key);
  if(value==null) return null;
  if(value instanceof Long l) return l;
  if(value instanceof BigDecimal d) {
   try { return d.longValueExact(); } catch(ArithmeticException e) { throw new JsonException("Número não inteiro: "+key); }
  }
  if(value instanceof String s && s.matches("-?\\d{1,18}")) return Long.parseLong(s);
  throw new JsonException("Campo não numérico: "+key);
 }
 public static Boolean bool(Map<String,Object> map,String key) {
  Object value=map==null?null:map.get(key);
  if(value==null) return null;
  if(value instanceof Boolean b) return b;
  throw new JsonException("Campo não booleano: "+key);
 }

 public static String write(Object value) {
  var out=new StringBuilder();
  write(out,value,0);
  return out.toString();
 }

 private static void write(StringBuilder out,Object value,int depth) {
  if(depth>MAX_DEPTH) throw new JsonException("Profundidade acima do limite.");
  switch(value) {
   case null -> out.append("null");
   case String s -> quote(out,s);
   case Boolean b -> out.append(b);
   case Long l -> out.append(l);
   case Integer i -> out.append(i);
   case BigDecimal d -> out.append(d.toPlainString());
   case Double d -> {
    if(d.isNaN() || d.isInfinite()) throw new JsonException("Número inválido.");
    out.append(BigDecimal.valueOf(d).toPlainString());
   }
   case Map<?,?> m -> {
    out.append('{');
    boolean first=true;
    for(var entry:m.entrySet()) {
     if(!first) out.append(',');
     first=false;
     quote(out,String.valueOf(entry.getKey()));
     out.append(':');
     write(out,entry.getValue(),depth+1);
    }
    out.append('}');
   }
   case List<?> l -> {
    out.append('[');
    for(int i=0;i<l.size();i++) { if(i>0) out.append(','); write(out,l.get(i),depth+1); }
    out.append(']');
   }
   default -> throw new JsonException("Tipo não serializável: "+value.getClass().getSimpleName());
  }
 }

 private static void quote(StringBuilder out,String s) {
  out.append('"');
  for(int i=0;i<s.length();i++) {
   char c=s.charAt(i);
   switch(c) {
    case '"' -> out.append("\\\"");
    case '\\' -> out.append("\\\\");
    case '\n' -> out.append("\\n");
    case '\r' -> out.append("\\r");
    case '\t' -> out.append("\\t");
    case '\b' -> out.append("\\b");
    case '\f' -> out.append("\\f");
    default -> {
     if(c<0x20) out.append(String.format("\\u%04x",(int)c)); else out.append(c);
    }
   }
  }
  out.append('"');
 }

 private static final class Parser {
  final String s; int pos;
  Parser(String s) { this.s=s; }
  void ws() { while(pos<s.length() && " \t\r\n".indexOf(s.charAt(pos))>=0) pos++; }
  char peek() { if(pos>=s.length()) throw new JsonException("JSON incompleto."); return s.charAt(pos); }
  Object value(int depth) {
   if(depth>MAX_DEPTH) throw new JsonException("Profundidade acima do limite.");
   char c=peek();
   return switch(c) {
    case '{' -> object(depth);
    case '[' -> array(depth);
    case '"' -> string();
    case 't' -> literal("true",Boolean.TRUE);
    case 'f' -> literal("false",Boolean.FALSE);
    case 'n' -> literal("null",null);
    default -> number();
   };
  }
  Object literal(String word,Object value) {
   if(!s.startsWith(word,pos)) throw new JsonException("Literal inválido.");
   pos+=word.length();
   return value;
  }
  Map<String,Object> object(int depth) {
   pos++;
   var map=new LinkedHashMap<String,Object>();
   ws();
   if(peek()=='}') { pos++; return map; }
   while(true) {
    ws();
    if(peek()!='"') throw new JsonException("Chave esperada.");
    String key=string();
    ws();
    if(peek()!=':') throw new JsonException("Dois-pontos esperado.");
    pos++;
    ws();
    if(map.containsKey(key)) throw new JsonException("Chave repetida.");
    map.put(key,value(depth+1));
    ws();
    char c=peek(); pos++;
    if(c=='}') return map;
    if(c!=',') throw new JsonException("Vírgula esperada.");
   }
  }
  List<Object> array(int depth) {
   pos++;
   var list=new ArrayList<Object>();
   ws();
   if(peek()==']') { pos++; return list; }
   while(true) {
    ws();
    list.add(value(depth+1));
    ws();
    char c=peek(); pos++;
    if(c==']') return list;
    if(c!=',') throw new JsonException("Vírgula esperada.");
   }
  }
  String string() {
   pos++;
   var out=new StringBuilder();
   while(true) {
    char c=peek(); pos++;
    if(c=='"') return out.toString();
    if(c<0x20) throw new JsonException("Caractere de controle em texto.");
    if(c!='\\') { out.append(c); continue; }
    char e=peek(); pos++;
    switch(e) {
     case '"' -> out.append('"');
     case '\\' -> out.append('\\');
     case '/' -> out.append('/');
     case 'b' -> out.append('\b');
     case 'f' -> out.append('\f');
     case 'n' -> out.append('\n');
     case 'r' -> out.append('\r');
     case 't' -> out.append('\t');
     case 'u' -> {
      if(pos+4>s.length()) throw new JsonException("Escape incompleto.");
      try { out.append((char)Integer.parseInt(s.substring(pos,pos+4),16)); }
      catch(NumberFormatException ex) { throw new JsonException("Escape inválido."); }
      pos+=4;
     }
     default -> throw new JsonException("Escape inválido.");
    }
   }
  }
  Object number() {
   int start=pos;
   if(peek()=='-') pos++;
   while(pos<s.length() && "0123456789.eE+-".indexOf(s.charAt(pos))>=0) pos++;
   String text=s.substring(start,pos);
   if(text.isEmpty() || text.equals("-")) throw new JsonException("Valor inválido.");
   try {
    if(text.matches("-?(0|[1-9]\\d{0,17})")) return Long.parseLong(text);
    return new BigDecimal(text);
   } catch(NumberFormatException e) { throw new JsonException("Número inválido."); }
  }
 }
}
