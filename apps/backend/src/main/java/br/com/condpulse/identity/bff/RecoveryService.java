package br.com.condpulse.identity.bff;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Recuperação de acesso pela web (CP-020), em três passos:
 * <ol>
 * <li>pedido do e-mail com resposta sempre neutra, em prazo fixo que não depende do provedor;</li>
 * <li>troca do hash do link por uma tentativa de 10 minutos, ligada a cookie opaco próprio, que informa se o
 * segundo fator será exigido;</li>
 * <li>nova senha (e código TOTP quando houver fator verificado). Sucesso encerra as sessões da conta no provedor e
 * na web; recusa definitiva conta tentativa; resultado incerto encerra a tentativa e as sessões, sem repetição.</li>
 * </ol>
 * "Senha alterada" só é devolvido depois de o provedor confirmar a troca para a mesma conta.
 * As proteções daqui (segundo fator, limites, cookie) valem para quem usa o BFF; o link é também credencial do
 * provedor, então a recusa de troca de senha em sessão AAL1 de conta com fator verificado precisa ser confirmada
 * no provedor (pré-condição de homologação em docs/RECUPERACAO_ACESSO.md).
 */
public final class RecoveryService {
 public static final int WINDOW_MINUTES=10;
 public static final int MINIMUM_PASSWORD=12,MAXIMUM_PASSWORD=72;
 private static final Pattern TOKEN_HASH=Pattern.compile("[A-Za-z0-9_-]{16,512}");
 private static final Pattern EMAIL=Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,255}");
 private final RecoveryStore store;
 private final SupabaseAuthClient auth;
 private final TokenCipher cipher;
 private final JwtDecoder decoder;
 private final Clock clock;
 private final Duration minimumRequest;
 private final Supplier<Duration> jitter;
 private final Dispatcher dispatcher;
 private final Sleeper sleeper;
 /** Espera usada para completar o prazo fixo do pedido; substituível em testes. */
 @FunctionalInterface public interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
 /** Executa o pedido ao provedor fora da requisição. Fila cheia lança RejectedExecutionException; a resposta não muda. */
 @FunctionalInterface public interface Dispatcher { Future<?> submit(Runnable task); }
 /** Limite do segundo fator por conta, cobrado antes de cada código; recusa lança AUTH_RATE_LIMITED. */
 @FunctionalInterface public interface SecondFactorGate { void admit(UUID account); }
 public RecoveryService(RecoveryStore store,SupabaseAuthClient auth,TokenCipher cipher,JwtDecoder decoder,Clock clock,
    Duration minimumRequest,Supplier<Duration> jitter,Dispatcher dispatcher,Sleeper sleeper) {
  this.store=store; this.auth=auth; this.cipher=cipher; this.decoder=decoder; this.clock=clock;
  this.minimumRequest=minimumRequest; this.jitter=jitter; this.dispatcher=dispatcher; this.sleeper=sleeper;
 }

 /**
  * Nunca revela se a conta existe: a resposta sai no prazo fixo (mínimo mais variação), termine ou não a chamada ao
  * provedor, que segue em segundo plano com seus próprios limites de tempo. Resultado do provedor não muda nada.
  */
 public void request(String email) {
  String normalized=normalize(email);
  Instant deadline=clock.instant().plus(minimumRequest).plus(jitter.get());
  Future<?> pending=null;
  try { pending=dispatcher.submit(()->auth.recover(normalized)); }
  catch(RejectedExecutionException saturated) { /* Fila cheia: mesma resposta neutra, sem novo envio. */ }
  try {
   if(pending!=null) {
    long wait=Math.max(0,Duration.between(clock.instant(),deadline).toMillis());
    try { pending.get(wait,TimeUnit.MILLISECONDS); }
    catch(TimeoutException | ExecutionException | CancellationException ignored) { /* Não muda a resposta. */ }
   }
   Duration remaining=Duration.between(clock.instant(),deadline);
   if(!remaining.isNegative() && !remaining.isZero()) sleeper.sleep(remaining);
  } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
 }

 public Ticket verify(String tokenHash) {
  if(tokenHash==null || !TOKEN_HASH.matcher(tokenHash).matches()) throw linkInvalid();
  var tokens=auth.verifyRecovery(tokenHash);
  try {
   Jwt jwt=decodeNew(tokens.access_token());
   UUID account=subject(jwt);
   var factors=auth.recoveryFactors(tokens.access_token());
   if(!account.equals(factors.accountId())) throw unavailable();
   // Fator verificado que a web não sabe exigir (telefone, webauthn): sem segundo fator não há troca por aqui.
   if(factors.otherVerified()) throw factorUnsupported();
   UUID factor=factors.verifiedTotp();
   String raw=SessionSecrets.newToken(),hash=SessionSecrets.hash(raw);
   String sealed=cipher.seal(tokens.access_token(),RecoveryStore.Attempt.contextFor(account,hash));
   Instant expiresAt=store.create(UUID.randomUUID(),hash,account,sealed,factor,factor!=null,WINDOW_MINUTES);
   return new Ticket(raw,factor!=null,expiresAt);
  } catch(RuntimeException ex) {
   // A sessão criada pelo link não pode sobrar no provedor quando a tentativa não foi gravada.
   auth.signOutBestEffort(tokens.access_token());
   throw ex;
  }
 }

 public Completion complete(String raw,String password,String code,SecondFactorGate gate) {
  requirePassword(password);
  String hash;
  try { hash=SessionSecrets.hash(raw); } catch(IllegalArgumentException ex) { throw sessionExpired(); }
  UUID lease=UUID.randomUUID();
  var attempt=store.claim(hash,lease);
  String access;
  try { access=cipher.open(attempt.encryptedAccessToken(),attempt.cipherContext()); }
  catch(IllegalStateException ex) { store.remove(hash); throw sessionExpired(); }
  try {
   Jwt current=decoder.decode(access);
   if(!attempt.accountId().toString().equalsIgnoreCase(current.getSubject())) { discard(hash,access); throw sessionExpired(); }
  } catch(JwtException ex) { discard(hash,access); throw sessionExpired(); }
  // Conta suspensa depois do link não troca a senha, como no login e na renovação da sessão.
  boolean active;
  try { active=store.accountActive(attempt.accountId()); }
  catch(RuntimeException ex) { release(hash,lease); throw dependencyUnavailable(); }
  if(!active) { discard(hash,access); throw accountInactive(); }
  if(attempt.mfaRequired()) access=elevate(hash,lease,attempt,access,code,gate);
  try { auth.updatePassword(access,password,attempt.accountId()); }
  catch(BusinessProblem ex) {
   switch(ex.code()) {
    case "PASSWORD_REJECTED" -> throw failed(hash,lease,access,ex);
    case "AUTH_RATE_LIMITED" -> { release(hash,lease); throw ex; }
    case "AUTH_PROVIDER_UNAVAILABLE" -> throw uncertain(hash,attempt.accountId(),access);
    default -> { discard(hash,access); throw ex; }
   }
  } catch(RuntimeException ex) { throw uncertain(hash,attempt.accountId(),access); }
  // Confirmado pelo provedor: a tentativa acaba e todas as sessões da conta são encerradas.
  remove(hash);
  boolean provider=auth.signOutGlobal(access);
  boolean local=purge(attempt.accountId());
  return new Completion(provider && local);
 }

 private String elevate(String hash,UUID lease,RecoveryStore.Attempt attempt,String access,String code,SecondFactorGate gate) {
  if(code==null || !code.matches("[0-9]{6}")) {
   release(hash,lease);
   throw new BusinessProblem("MFA_CODE_REQUIRED",422,"Informe o código de 6 dígitos do aplicativo autenticador.");
  }
  // Mesmo orçamento por conta do /auth/mfa/verify: links novos não renovam tentativas de código.
  try { gate.admit(attempt.accountId()); }
  catch(RuntimeException ex) { release(hash,lease); throw ex; }
  SupabaseAuthClient.TokenResponse elevated;
  try { elevated=auth.verifyTotp(access,attempt.factorId(),code); }
  catch(BusinessProblem ex) {
   if("MFA_CODE_REJECTED".equals(ex.code())) throw failed(hash,lease,access,ex);
   if("AUTH_RATE_LIMITED".equals(ex.code())) { release(hash,lease); throw ex; }
   discard(hash,access); throw ex;
  } catch(RuntimeException ex) { discard(hash,access); throw ex; }
  try {
   Jwt jwt=decodeNew(elevated.access_token());
   if(!attempt.accountId().equals(subject(jwt)) || AssuranceLevel.fromClaim(jwt.getClaims().get("aal"))!=AssuranceLevel.AAL2)
    throw unavailable();
   store.elevate(hash,lease,cipher.seal(elevated.access_token(),attempt.cipherContext()));
   return elevated.access_token();
  } catch(RuntimeException ex) {
   discard(hash,elevated.access_token());
   throw ex;
  }
 }
 /**
  * Recusa definitiva conta tentativa e devolve o erro a mostrar. Esgotadas as tentativas (ou sem como contá-las),
  * a tentativa acaba para o navegador com código terminal e a sessão do link é encerrada.
  */
 private BusinessProblem failed(String hash,UUID lease,String access,BusinessProblem rejection) {
  int remaining;
  try { remaining=store.fail(hash,lease); }
  catch(RuntimeException ex) { discard(hash,access); return sessionExpired(); }
  if(remaining>0) return rejection;
  auth.signOutBestEffort(access);
  return new BusinessProblem("RECOVERY_ATTEMPTS_EXHAUSTED",422,"Tentativas esgotadas para este link. Solicite um novo link.");
 }
 private BusinessProblem uncertain(String hash,UUID account,String access) {
  remove(hash);
  auth.signOutGlobal(access);
  purge(account);
  return new BusinessProblem("RECOVERY_OUTCOME_UNKNOWN",503,
   "Não foi possível confirmar a troca da senha. Tente entrar com a nova senha; se não funcionar, solicite um novo link.");
 }
 private void discard(String hash,String access) {
  remove(hash);
  auth.signOutBestEffort(access);
 }
 private void release(String hash,UUID lease) {
  try { store.release(hash,lease); } catch(RuntimeException ignored) { /* A reserva vence em segundos. */ }
 }
 private void remove(String hash) {
  try { store.remove(hash); } catch(RuntimeException ignored) { /* A tentativa expira sozinha em minutos. */ }
 }
 private boolean purge(UUID account) {
  try { store.purgeSessions(account); return true; } catch(RuntimeException ex) { return false; }
 }

 static String normalize(String email) {
  String value=email==null?"":email.strip();
  if(value.length()>254 || !EMAIL.matcher(value).matches())
   throw new BusinessProblem("RECOVERY_REQUEST_INVALID",400,"Informe um e-mail válido.");
  return value.toLowerCase(Locale.ROOT);
 }
 /** Política local mínima, conferida antes de qualquer chamada; o provedor pode exigir mais. */
 static void requirePassword(String password) {
  if(password==null) throw passwordInvalid();
  int length=password.codePointCount(0,password.length());
  if(length<MINIMUM_PASSWORD || length>MAXIMUM_PASSWORD || password.getBytes(StandardCharsets.UTF_8).length>MAXIMUM_PASSWORD
     || password.isBlank() || password.codePoints().distinct().count()<4)
   throw passwordInvalid();
 }
 private static BusinessProblem passwordInvalid() {
  return new BusinessProblem("PASSWORD_INVALID",400,
   "Use de 12 a 72 caracteres (letras acentuadas contam em dobro), com pelo menos 4 caracteres diferentes.");
 }
 private Jwt decodeNew(String access) {
  try {
   Jwt jwt=decoder.decode(access);
   if(jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant())) throw unavailable();
   return jwt;
  } catch(JwtException ex) { throw unavailable(); }
 }
 private static UUID subject(Jwt jwt) {
  try {
   UUID parsed=UUID.fromString(jwt.getSubject());
   if(parsed.toString().equalsIgnoreCase(jwt.getSubject())) return parsed;
  } catch(IllegalArgumentException | NullPointerException ignored) { /* falha fechada abaixo */ }
  throw unavailable();
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Autenticação temporariamente indisponível.");
 }
 private static BusinessProblem dependencyUnavailable() {
  return new BusinessProblem("RECOVERY_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir agora. Tente novamente em instantes.");
 }
 private static BusinessProblem accountInactive() {
  return new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
 }
 private static BusinessProblem factorUnsupported() {
  return new BusinessProblem("RECOVERY_FACTOR_UNSUPPORTED",403,
   "A recuperação pela web não está disponível para esta conta. Procure a gestão do condomínio.");
 }
 private static BusinessProblem linkInvalid() {
  return new BusinessProblem("RECOVERY_LINK_INVALID",400,"Link de recuperação inválido, expirado ou já utilizado. Solicite um novo link.");
 }
 private static BusinessProblem sessionExpired() {
  return new BusinessProblem("RECOVERY_SESSION_EXPIRED",401,"O prazo para definir a nova senha terminou. Solicite um novo link.");
 }
 public record Ticket(String opaqueToken,boolean mfaRequired,Instant expiresAt) {
  @Override public String toString() { return "RecoveryTicket[credentials=REDACTED,mfaRequired="+mfaRequired+"]"; }
 }
 public record Completion(boolean otherSessionsEnded) {}
}
