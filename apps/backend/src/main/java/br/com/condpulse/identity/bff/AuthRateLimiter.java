package br.com.condpulse.identity.bff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.dao.DataAccessException;

/** Limites compartilhados entre instancias, sem identidade ou credencial no banco. */
public final class AuthRateLimiter {
 private final byte[] key;
 private final AuthRateStore store;
 public AuthRateLimiter(String encodedKey,String encodedCipherKey,AuthRateStore store) {
  try {
   key=Base64.getDecoder().decode(encodedKey);
   byte[] cipherKey=Base64.getDecoder().decode(encodedCipherKey);
   if(key.length!=32 || !Base64.getEncoder().encodeToString(key).equals(encodedKey)
      || MessageDigest.isEqual(key,cipherKey)) throw new IllegalArgumentException();
  } catch(RuntimeException error) {
   throw new IllegalArgumentException("Configure uma chave HMAC de 32 bytes independente da cifra.");
  }
  this.store=java.util.Objects.requireNonNull(store);
 }
 public record Admission(boolean allowed,long retryAfterSeconds) {
  public Admission {
   if((allowed && retryAfterSeconds!=0) || (!allowed && (retryAfterSeconds<1 || retryAfterSeconds>900)))
    throw new IllegalArgumentException("Resultado de limite invalido.");
  }
  public static Admission permitted() { return new Admission(true,0); }
 }
 public record Bucket(String key,int limit,int seconds) {
  public Bucket {
   if(key==null || !key.matches("[0-9a-f]{64}") || limit<1 || limit>600 || seconds<1 || seconds>900)
    throw new IllegalArgumentException("Configuracao de limite invalida.");
  }
  @Override public String toString() { return "AuthRateLimiter.Bucket[redacted]"; }
 }
 public Admission login(String email) {
  String principal=email==null || email.length()>320 ? "invalid" : email.strip().toLowerCase(Locale.ROOT);
  return admit("login",principal,120,5,900);
 }
 public Admission refresh(String opaqueSession) {
  String principal=opaqueSession!=null && opaqueSession.matches("[A-Za-z0-9_-]{43}") ? opaqueSession : "anonymous";
  return admit("refresh",principal,600,30,60);
 }
 /**
  * Segundo fator por conta (HMAC do UUID, sem identidade em claro), cobrado somente depois de validar a sessao:
  * novas sessoes da mesma conta nao renovam tentativas e requisicoes anonimas nao consomem o limite global.
  */
 public Admission mfaVerify(java.util.UUID account) { return admit("mfa-verify",accountPrincipal(account),300,5,900); }
 public Admission mfaEnroll(java.util.UUID account) { return admit("mfa-enroll",accountPrincipal(account),300,5,900); }
 public Admission mfaStatus(java.util.UUID account) { return admit("mfa-status",accountPrincipal(account),600,30,60); }
 /** Recuperação: pedido por e-mail (3 a cada 15 min), link por hash e conclusão por cookie próprio. */
 public Admission recoveryRequest(String email) {
  String principal=email==null || email.length()>320 ? "invalid" : email.strip().toLowerCase(Locale.ROOT);
  return admit("recovery-request",principal,60,3,900);
 }
 public Admission recoveryVerify(String tokenHash) {
  String principal=tokenHash!=null && tokenHash.length()<=512 ? tokenHash : "invalid";
  return admit("recovery-verify",principal,120,5,900);
 }
 public Admission recoveryComplete(String opaqueRecovery) {
  String principal=opaqueRecovery!=null && opaqueRecovery.matches("[A-Za-z0-9_-]{43}") ? opaqueRecovery : "anonymous";
  return admit("recovery-complete",principal,120,10,900);
 }
 /** Link de convite aberto sem conta (CP-037): limite global e por hash do token (nunca o token). */
 public Admission permitLinkView(String tokenHash) {
  String principal=tokenHash!=null && tokenHash.matches("[0-9a-f]{64}") ? tokenHash : "invalid";
  return admit("permit-link-view",principal,120,20,900);
 }
 private static String accountPrincipal(java.util.UUID account) {
  if(account==null) throw new IllegalArgumentException("Conta obrigatoria para limite de segundo fator.");
  return account.toString();
 }
 private Admission admit(String operation,String principal,int globalLimit,int subjectLimit,int subjectSeconds) {
  // O bucket global e sempre o primeiro: variacao do identificador nao cria linhas ilimitadas.
  List<Bucket> buckets=List.of(new Bucket(digest(operation+"|global"),globalLimit,60),
    new Bucket(digest(operation+"|subject|"+principal),subjectLimit,subjectSeconds));
  try { return store.admit(buckets); }
  catch(DataAccessException | org.springframework.transaction.TransactionException error) {
   throw new BusinessProblem("AUTH_RATE_LIMIT_UNAVAILABLE",503,"A autenticacao esta temporariamente indisponivel.");
  }
 }
 private String digest(String value) {
  try {
   Mac mac=Mac.getInstance("HmacSHA256");
   mac.init(new SecretKeySpec(key,"HmacSHA256"));
   return HexFormat.of().formatHex(mac.doFinal(("condpulse.auth-rate.v1|"+value).getBytes(StandardCharsets.UTF_8)));
  } catch(java.security.GeneralSecurityException error) { throw new IllegalStateException("HMAC indisponivel."); }
 }
 @Override public String toString() { return "AuthRateLimiter[redacted]"; }
}
