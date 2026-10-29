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
