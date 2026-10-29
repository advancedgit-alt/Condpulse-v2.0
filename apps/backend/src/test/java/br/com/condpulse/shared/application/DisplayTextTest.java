package br.com.condpulse.shared.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Mesma regra da web (apps/web/src/api/text.ts): code points, espaços Unicode e caracteres invisíveis. */
class DisplayTextTest {
 @Test void collapsesUnicodeWhitespaceAndCountsCodePoints() {
  assertEquals("Condomínio Sol",DisplayText.normalize(" Condomínio  \tSol　\n",160));
  String houses="🏠".repeat(160);
  assertEquals(houses,DisplayText.normalize(houses,160),"160 code points cabem mesmo com 320 unidades UTF-16");
  assertNull(DisplayText.normalize(houses+"🏠",160));
  assertEquals("a b",DisplayText.normalize("a\u0085b",160),"NEL é espaço Unicode");
 }
 @Test void refusesEmptyControlAndInvisibleFormattingCharacters() {
  for(String value:new String[]{null,"","   ","  ","​","a​b","a‮b","a⁦b","a﻿b","a\u0000b","a\u0007b",
    "ab","a\ud800b","a͸b"})
   assertNull(DisplayText.normalize(value,160),String.valueOf(value));
  assertEquals("Condomínio São João — Bloco 2 🏠",DisplayText.normalize("Condomínio São João — Bloco 2 🏠",160));
 }
 @Test void collapseNeverValidates() {
  assertEquals("",DisplayText.collapse("   "));
  assertEquals("a​b",DisplayText.collapse("a​b"));
 }
 @Test void multilineKeepsParagraphsButNotInvisibleCharacters() {
  assertEquals("Assembleia ficticia\n\nPauta: obras",DisplayText.normalizeMultiline("  Assembleia   ficticia \r\n\r\n\n\n Pauta:\tobras \n\n",5000));
  assertEquals("Linha um\nLinha dois",DisplayText.normalizeMultiline("Linha um\u2028Linha dois",5000));
  assertNull(DisplayText.normalizeMultiline(" \n \r\n ",5000));
  assertNull(DisplayText.normalizeMultiline("Texto\u200bescondido",5000));
  assertNull(DisplayText.normalizeMultiline("Texto com\u0007controle",5000));
  assertNull(DisplayText.normalizeMultiline("abc\ndef",6),"quebra de linha conta no limite");
  assertEquals("abc\nde",DisplayText.normalizeMultiline("abc\nde",6));
  assertNull(DisplayText.normalizeMultiline(null,10));
 }
}
