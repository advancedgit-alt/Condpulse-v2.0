package br.com.condpulse.accessage.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import br.com.condpulse.shared.application.BusinessProblem;

/**
 * Proteção da data de nascimento (AC-006): AES-256-GCM no serviço, com chave fora do banco (CP_AGE_EVIDENCE_KEY, base64
 * de 32 bytes, cadastrada pelo responsável; nunca no repositório nem em log). O texto cifrado é ligado ao condomínio, ao
 * sujeito e à evidência (dados associados): copiar o envelope para outra linha não decifra. Sem chave, a evidência por
 * data fica indisponível (falha fechada) e a atestação continua funcionando.
 */
public final class BirthDateCipher {
 public static final String KEY_REF="env:CP_AGE_EVIDENCE_KEY";
 private static final int IV=12,TAG_BITS=128;
 private final SecretKeySpec key;
 private final String version;
 private final SecureRandom random=new SecureRandom();

 private BirthDateCipher(SecretKeySpec key,String version) { this.key=key; this.version=version; }
 /** Chave ausente ou inválida devolve cifrador indisponível; o valor nunca aparece em mensagem. */
 public static BirthDateCipher fromBase64(String encoded,String version) {
  if(version==null || !version.matches("[A-Za-z0-9._-]{1,64}")) return new BirthDateCipher(null,"v1");
  if(encoded==null || encoded.isBlank()) return new BirthDateCipher(null,version);
  try {
   byte[] raw=Base64.getDecoder().decode(encoded.strip());
   if(raw.length!=32) return new BirthDateCipher(null,version);
   return new BirthDateCipher(new SecretKeySpec(raw,"AES"),version);
  } catch(IllegalArgumentException invalid) { return new BirthDateCipher(null,version); }
 }
 public boolean available() { return key!=null; }
 public String version() { return version; }

 public byte[] encrypt(LocalDate birth,UUID tenant,UUID subject,UUID evidence) {
  require();
  try {
   byte[] iv=new byte[IV]; random.nextBytes(iv);
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(TAG_BITS,iv));
   cipher.updateAAD(aad(tenant,subject,evidence));
   byte[] sealed=cipher.doFinal(birth.toString().getBytes(StandardCharsets.US_ASCII));
   return ByteBuffer.allocate(IV+sealed.length).put(iv).put(sealed).array();
  } catch(GeneralSecurityException failure) { throw unavailable(); }
 }
 public LocalDate decrypt(byte[] envelope,UUID tenant,UUID subject,UUID evidence) {
  require();
  if(envelope==null || envelope.length<=IV) throw unavailable();
  try {
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(TAG_BITS,envelope,0,IV));
   cipher.updateAAD(aad(tenant,subject,evidence));
   return LocalDate.parse(new String(cipher.doFinal(envelope,IV,envelope.length-IV),StandardCharsets.US_ASCII));
  } catch(GeneralSecurityException | RuntimeException failure) { throw unavailable(); }
 }
 private static byte[] aad(UUID tenant,UUID subject,UUID evidence) {
  return ("condpulse-age-v1|"+tenant+"|"+subject+"|"+evidence).getBytes(StandardCharsets.US_ASCII);
 }
 private void require() { if(key==null) throw unavailable(); }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("AGE_EVIDENCE_KEY_UNAVAILABLE",503,"A proteção da data de nascimento não está configurada ou não confere.");
 }
}
