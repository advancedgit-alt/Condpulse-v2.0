package br.com.condpulse.parcels.application;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DelegationCodeKeyTest {
 private static String key(int start) {
  byte[] bytes=new byte[32];
  for(int i=0;i<32;i++) bytes[i]=(byte)(start+i);
  return Base64.getEncoder().encodeToString(bytes);
 }
 private static final UUID DELEGATION=UUID.fromString("41000000-0000-4000-8000-000000000001");

 @Test void authenticatorDependsOnTheServerSecretAndTheDelegation() {
  var key=new DelegationCodeKey(key(0),key(100),key(200));
  assertTrue(key.available());
  String mac=key.mac(DELEGATION,"12345678");
  assertTrue(mac.matches("[0-9a-f]{64}"));
  // Mesmo vetor do Python usado nos testes do banco: hmac.new(bytes(range(32)), b"<id>:12345678", sha256).hexdigest()
  assertEquals("7dcacf5d254411f4b5002765202147525fd829c2f348b52e1b4cab6b780c153f",mac);
  assertFalse(mac.equals(key.mac(UUID.fromString("41000000-0000-4000-8000-000000000002"),"12345678")),"Autenticador preso à delegação");
  assertFalse(mac.equals(new DelegationCodeKey(key(1),key(100),key(200)).mac(DELEGATION,"12345678")),"Outro segredo, outro autenticador");
  assertFalse(mac.equals(key.mac(DELEGATION,"12345679")));
 }

 @Test void missingKeyIsUnavailableAndNeverAuthenticates() {
  for(String missing:new String[]{null,"","  "}) {
   var key=new DelegationCodeKey(missing,key(100),key(200));
   assertFalse(key.available());
   assertThrows(IllegalStateException.class,()->key.mac(DELEGATION,"12345678"));
  }
 }

 @Test void invalidOrReusedKeysAreRefused() {
  assertThrows(IllegalArgumentException.class,()->new DelegationCodeKey("curta",key(100),key(200)));
  assertThrows(IllegalArgumentException.class,()->new DelegationCodeKey(Base64.getEncoder().encodeToString(new byte[16]),key(100),key(200)));
  // Não reaproveita a chave de cifra nem a de limite do BFF.
  assertThrows(IllegalArgumentException.class,()->new DelegationCodeKey(key(100),key(100),key(200)));
  assertThrows(IllegalArgumentException.class,()->new DelegationCodeKey(key(200),key(100),key(200)));
  assertTrue(new DelegationCodeKey(key(0),null,"").available(),"BFF desligado não impede a chave própria");
 }
}
