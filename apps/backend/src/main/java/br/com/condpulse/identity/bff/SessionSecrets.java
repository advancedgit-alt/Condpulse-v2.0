package br.com.condpulse.identity.bff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class SessionSecrets {
 private static final SecureRandom RANDOM=new SecureRandom();
 private SessionSecrets() {}
 public static String newToken() {
  byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 public static String hash(String raw) {
  if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}"))
   throw new IllegalArgumentException("Sessão inválida.");
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII)));
  } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
 }
}
