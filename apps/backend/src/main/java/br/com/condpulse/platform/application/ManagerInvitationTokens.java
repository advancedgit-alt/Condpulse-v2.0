package br.com.condpulse.platform.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;

/**
 * Token opaco do convite de gestor (mesmo formato do convite CP-020 e do link CP-037): 32 bytes aleatórios em base64url,
 * guardado só como SHA-256. O e-mail convidado também só é guardado como SHA-256 de "<id>:<e-mail normalizado>".
 */
public final class ManagerInvitationTokens {
 public static final String TOKEN_PATTERN="[A-Za-z0-9_-]{43}";
 private static final SecureRandom RANDOM=new SecureRandom();
 private ManagerInvitationTokens() {}
 public static String newToken() {
  byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 public static String sha256(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
 }
 public static String emailHash(UUID invitation,String normalizedEmail) { return sha256(invitation+":"+normalizedEmail); }
 /** Mesma normalização do convite da gestão: sem espaços nas pontas, minúsculas, até 320 caracteres. */
 public static String normalizedEmail(String value) {
  if(value==null) throw invalidEmail();
  String email=value.strip().toLowerCase(Locale.ROOT);
  if(email.length()>320 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalidEmail();
  return email;
 }
 private static BusinessProblem invalidEmail() {
  return new BusinessProblem("MANAGER_EMAIL_INVALID",400,"Informe um e-mail válido para o gestor.");
 }
}
