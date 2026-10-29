package br.com.condpulse.visits.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Token do link de convite (CP-037): 32 bytes aleatórios em Base64 URL sem preenchimento (43 caracteres). O banco guarda
 * só o SHA-256 em hexadecimal; o token bruto nunca vai para log, banco ou URL persistida (a web o leva no fragmento).
 */
public final class PermitLinkTokens {
 private static final SecureRandom RANDOM=new SecureRandom();
 private static final Pattern FORMAT=Pattern.compile("[A-Za-z0-9_-]{43}");
 private PermitLinkTokens() {}
 public static String newToken() {
  byte[] bytes=new byte[32];
  RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 public static boolean wellFormed(String token) { return token!=null && FORMAT.matcher(token).matches(); }
 public static String hash(String token) {
  if(!wellFormed(token)) throw new IllegalArgumentException("Token de link inválido.");
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
  } catch(NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 indisponível."); }
 }
}
