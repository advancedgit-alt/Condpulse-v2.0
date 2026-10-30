package br.com.condpulse.parcels.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Segredo do servidor que protege o código de retirada delegada (PRD, "Delegar": HMAC/segredo do servidor). O banco guarda
 * só HMAC-SHA256(segredo, "delegação:código"); sem o segredo, a tabela não permite testar os códigos possíveis. A chave
 * (CP_PARCEL_CODE_KEY, 32 bytes em Base64) vem do cofre, é própria desta finalidade e não reaproveita as chaves do BFF.
 * Sem chave configurada a delegação fica indisponível (falha fechada), nunca com proteção menor.
 */
public final class DelegationCodeKey {
 private final byte[] key;

 public DelegationCodeKey(String encodedKey,String bffEncryptionKey,String bffRateLimitKey) {
  if(encodedKey==null || encodedKey.isBlank()) { key=null; return; }
  byte[] decoded;
  try { decoded=Base64.getDecoder().decode(encodedKey); }
  catch(IllegalArgumentException error) { throw invalid(); }
  if(decoded.length!=32 || !Base64.getEncoder().encodeToString(decoded).equals(encodedKey)
     || same(decoded,bffEncryptionKey) || same(decoded,bffRateLimitKey)) throw invalid();
  key=decoded;
 }

 public boolean available() { return key!=null; }

 /** Autenticador do código para a delegação, em hexadecimal minúsculo (64 caracteres). */
 public String mac(UUID delegationId,String code) {
  if(key==null) throw new IllegalStateException("Chave do código de retirada não configurada.");
  if(delegationId==null || code==null) throw new IllegalArgumentException("Delegação e código são obrigatórios.");
  try {
   Mac hmac=Mac.getInstance("HmacSHA256");
   hmac.init(new SecretKeySpec(key,"HmacSHA256"));
   return HexFormat.of().formatHex(hmac.doFinal((delegationId+":"+code).getBytes(StandardCharsets.UTF_8)));
  } catch(GeneralSecurityException error) {
   throw new IllegalStateException("HMAC-SHA256 indisponível.",error);
  }
 }

 private static boolean same(byte[] key,String other) {
  if(other==null || other.isBlank()) return false;
  try { return MessageDigest.isEqual(key,Base64.getDecoder().decode(other)); }
  catch(IllegalArgumentException error) { return false; }
 }
 private static IllegalArgumentException invalid() {
  return new IllegalArgumentException("Configure CP_PARCEL_CODE_KEY com 32 bytes em Base64, independente das chaves do BFF.");
 }
}
