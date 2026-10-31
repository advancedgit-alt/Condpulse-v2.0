package br.com.condpulse.accessedge.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Credencial de máquina do gateway (V048). O valor é gerado no gateway (≥ 256 bits, base64url, 43 a 128 caracteres) e
 * só o SHA-256 dele chega ao banco. Formato fora do padrão nunca é calculado nem registrado: vira "sem credencial".
 */
public final class EdgeCredentials {
 /** Cabeçalho da credencial; nunca registrado em log. */
 public static final String HEADER="X-CondPulse-Edge-Credential";
 private static final String TOKEN="[A-Za-z0-9_-]{43,128}";
 private static final String HASH="[0-9a-f]{64}";
 private EdgeCredentials() {}

 /** Hash da credencial apresentada, ou vazio quando ausente ou fora do formato. */
 public static Optional<String> hashOf(String presented) {
  if(presented==null || presented.length()>128 || !presented.matches(TOKEN)) return Optional.empty();
  return Optional.of(sha256Hex(presented.getBytes(StandardCharsets.UTF_8)));
 }
 public static boolean validHash(String hash) { return hash!=null && hash.matches(HASH); }
 /** Impressão curta para conferência visual pelo MASTER; não serve para autenticar. */
 public static String fingerprint(String hash) { return validHash(hash)?hash.substring(0,12):null; }

 public static String sha256Hex(byte[] value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
}
