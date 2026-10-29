package br.com.condpulse.visits.application;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermitLinkTokensTest {
 @Test void tokensAreLongRandomUrlSafeAndHashedToHex() {
  var seen=new HashSet<String>();
  for(int i=0;i<200;i++) {
   String token=PermitLinkTokens.newToken();
   assertTrue(token.matches("[A-Za-z0-9_-]{43}"),token);
   assertTrue(seen.add(token),"Sem repetição");
   String hash=PermitLinkTokens.hash(token);
   assertTrue(hash.matches("[0-9a-f]{64}")); assertEquals(hash,PermitLinkTokens.hash(token));
   assertFalse(hash.contains(token));
  }
  // Vetor conhecido: SHA-256 de 43 letras "a" (conferido com hashlib).
  assertEquals("66d34fba71f8f450f7e45598853e53bfc23bbd129027cbb131a2f4ffd7878cd0",PermitLinkTokens.hash("a".repeat(43)));
 }
 @Test void malformedTokensAreRejected() {
  for(String bad:new String[]{null,"","curto","a".repeat(42),"a".repeat(44),"a".repeat(42)+"=","a".repeat(42)+"/"}) {
   assertFalse(PermitLinkTokens.wellFormed(bad),String.valueOf(bad));
   String value=bad;
   assertThrows(IllegalArgumentException.class,()->PermitLinkTokens.hash(value));
  }
 }
}
