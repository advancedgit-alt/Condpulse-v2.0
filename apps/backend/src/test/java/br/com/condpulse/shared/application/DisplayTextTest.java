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
}
