package br.com.condpulse.shared.application;

import java.util.regex.Pattern;

/**
 * Texto exibido a pessoas (nomes, motivos). Qualquer sequência de espaço Unicode vira um espaço, o comprimento é contado
 * em code points (como o length do PostgreSQL) e caracteres de controle, de formatação invisível (por exemplo U+200B e
 * U+202E), de uso privado, substitutos isolados e não atribuídos são recusados. A web aplica a mesma regra.
 */
public final class DisplayText {
 private static final Pattern WHITESPACE=Pattern.compile("(?U)\\s+");
 private DisplayText() {}

 /** Espaços Unicode reduzidos a um espaço, sem espaço nas pontas. Não valida. */
 public static String collapse(String value) {
  return WHITESPACE.matcher(value).replaceAll(" ").strip();
 }
 /** Forma normalizada, ou null quando vazia, maior que o limite ou com caractere não exibível. */
 public static String normalize(String value,int maximum) {
  if(value==null) return null;
  String normalized=collapse(value);
  if(normalized.isEmpty() || normalized.codePointCount(0,normalized.length())>maximum || !visible(normalized)) return null;
  return normalized;
 }
 /**
  * Texto de várias linhas (corpo de aviso): cada linha normalizada como em {@link #collapse}, quebras de linha mantidas
  * (no máximo uma linha em branco seguida), sem linhas em branco nas pontas. Null quando vazio, maior que o limite (code
  * points, quebras incluídas) ou com caractere não exibível.
  */
 public static String normalizeMultiline(String value,int maximum) {
  if(value==null) return null;
  var lines=new java.util.ArrayList<String>();
  boolean blank=false;
  for(String line:value.split("\r\n|\r|\n|\u2028|\u2029",-1)) {
   String normalized=collapse(line);
   if(normalized.isEmpty()) { if(!lines.isEmpty() && !blank) { lines.add(""); blank=true; } continue; }
   if(!visible(normalized)) return null;
   lines.add(normalized); blank=false;
  }
  while(!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
  String joined=String.join("\n",lines);
  if(joined.isEmpty() || joined.codePointCount(0,joined.length())>maximum) return null;
  return joined;
 }
 public static boolean visible(String value) {
  return value.codePoints().allMatch(DisplayText::visibleCodePoint);
 }
 static boolean visibleCodePoint(int codePoint) {
  return switch(Character.getType(codePoint)) {
   case Character.CONTROL,Character.FORMAT,Character.PRIVATE_USE,Character.SURROGATE,Character.UNASSIGNED->false;
   default->true;
  };
 }
}
