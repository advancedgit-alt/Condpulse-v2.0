package br.com.condpulse.identity.bff;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenCipherTest {
 private final String key=Base64.getEncoder().encodeToString(new byte[32]);
 @Test void roundTripRequiresTheSameAuthenticatedContext() {
  var cipher=new TokenCipher(key);
  var encrypted=cipher.seal("synthetic-refresh-token","account/session/refresh");
  assertFalse(encrypted.contains("synthetic-refresh-token"));
  assertEquals("synthetic-refresh-token",cipher.open(encrypted,"account/session/refresh"));
  assertThrows(IllegalStateException.class,()->cipher.open(encrypted,"another-account/session/refresh"));
 }
 @Test void encryptionUsesADifferentNoncePerCall() {
  var cipher=new TokenCipher(key);
  assertNotEquals(cipher.seal("synthetic","context"),cipher.seal("synthetic","context"));
 }
 @Test void ciphertextTamperingIsRejected() {
  var cipher=new TokenCipher(key);
  var encrypted=cipher.seal("synthetic","context");
  var bytes=Base64.getUrlDecoder().decode(encrypted.substring(3));
  bytes[bytes.length-1]^=1;
  var changed="v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  assertThrows(IllegalStateException.class,()->cipher.open(changed,"context"));
 }
 @Test void wrongKeyAndUnsupportedFormatFailClosed() {
  var cipher=new TokenCipher(key);
  var secondKey=new byte[32]; secondKey[0]=1;
  var other=new TokenCipher(Base64.getEncoder().encodeToString(secondKey));
  var encrypted=cipher.seal("synthetic","context");
  assertThrows(IllegalStateException.class,()->other.open(encrypted,"context"));
  assertThrows(IllegalStateException.class,()->cipher.open("v2.synthetic","context"));
 }
 @Test void keyMustContainExactly256Bits() {
  assertThrows(IllegalArgumentException.class,()->new TokenCipher("not-base64"));
  assertThrows(IllegalArgumentException.class,()->new TokenCipher(Base64.getEncoder().encodeToString(new byte[16])));
 }
}
