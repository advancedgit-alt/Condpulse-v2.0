package br.com.condpulse.identity.bff;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM com nonce por operação e contexto autenticado para impedir troca de linhas. */
public final class TokenCipher {
 private static final SecureRandom RANDOM=new SecureRandom();
 private final SecretKeySpec key;

 public TokenCipher(String base64Key) {
  byte[] decoded;
  try { decoded=Base64.getDecoder().decode(base64Key); }
  catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Chave BFF inválida."); }
  if(decoded.length!=32) throw new IllegalArgumentException("A chave BFF deve ter 256 bits.");
  key=new SecretKeySpec(decoded,"AES");
  Arrays.fill(decoded,(byte)0);
 }
 public String seal(String value,String context) {
  try {
   byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce);
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
   cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
   byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
   byte[] payload=new byte[nonce.length+encrypted.length];
   System.arraycopy(nonce,0,payload,0,nonce.length);
   System.arraycopy(encrypted,0,payload,nonce.length,encrypted.length);
   return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
  } catch(GeneralSecurityException ex) { throw new IllegalStateException("Não foi possível proteger a sessão."); }
 }
 public String open(String value,String context) {
  try {
   if(!value.startsWith("v1.")) throw new GeneralSecurityException();
   byte[] payload=Base64.getUrlDecoder().decode(value.substring(3));
   if(payload.length<29) throw new GeneralSecurityException();
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(payload,0,12)));
   cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
   return new String(cipher.doFinal(Arrays.copyOfRange(payload,12,payload.length)),StandardCharsets.UTF_8);
  } catch(GeneralSecurityException|IllegalArgumentException ex) {
   throw new IllegalStateException("A sessão não pôde ser validada.");
  }
 }
}
