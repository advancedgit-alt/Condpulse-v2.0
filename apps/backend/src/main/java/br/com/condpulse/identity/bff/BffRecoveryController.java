package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recuperação de acesso (CP-020), atrás de CP_BFF_ENABLED e CP_BFF_RECOVERY_ENABLED. Todas as rotas exigem Origin e
 * CSRF da cadeia BFF e respondem sem cache. O link do e-mail chega à página web pelo fragmento e só o hash é enviado.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.bff.enabled","condpulse.bff.recovery-enabled"},havingValue="true")
public class BffRecoveryController {
 /** Erros que encerram a tentativa: o cookie de recuperação deixa de servir e é apagado. */
 private static final Set<String> TERMINAL=Set.of("RECOVERY_SESSION_EXPIRED","RECOVERY_OUTCOME_UNKNOWN",
  "RECOVERY_NOT_ALLOWED","RECOVERY_ATTEMPTS_EXHAUSTED","ACCOUNT_INACTIVE","MFA_FACTOR_NOT_FOUND","MFA_OPERATION_REJECTED",
  "SESSION_REFRESH_REQUIRED","AUTH_PROVIDER_UNAVAILABLE");
 private final RecoveryService recovery;
 private final AuthRateLimiter limits;
 private final CsrfTokenRepository csrf;
 private final Clock clock;
 public BffRecoveryController(RecoveryService recovery,AuthRateLimiter limits,CsrfTokenRepository csrf,Clock clock) {
  this.recovery=recovery; this.limits=limits; this.csrf=csrf; this.clock=clock;
 }
 @PostMapping("/auth/recovery") @ResponseStatus(HttpStatus.ACCEPTED)
 public RequestedBody request(@RequestBody EmailBody body,HttpServletResponse response) {
  String email=body==null?null:body.email();
  BffController.requireAdmission(limits.recoveryRequest(email),response);
  recovery.request(email);
  return new RequestedBody("RECOVERY_REQUESTED");
 }
 @PostMapping("/auth/recovery/verify")
 public VerifiedBody verify(@RequestBody TokenBody body,HttpServletResponse response) {
  String token=body==null?null:body.tokenHash();
  BffController.requireAdmission(limits.recoveryVerify(token),response);
  var ticket=recovery.verify(token);
  BffCookies.setRecovery(response,ticket.opaqueToken(),Duration.between(clock.instant(),ticket.expiresAt()));
  return new VerifiedBody(ticket.mfaRequired(),ticket.expiresAt());
 }
 @PostMapping("/auth/recovery/complete")
 public CompletedBody complete(@RequestBody PasswordBody body,HttpServletRequest request,HttpServletResponse response) {
  String raw=BffCookies.readRecovery(request);
  BffController.requireAdmission(limits.recoveryComplete(raw),response);
  if(raw==null)
   throw new BusinessProblem("RECOVERY_SESSION_EXPIRED",401,"O prazo para definir a nova senha terminou. Solicite um novo link.");
  try {
   // Código TOTP usa o mesmo limite por conta do /auth/mfa/verify, com Retry-After.
   var result=recovery.complete(raw,body==null?null:body.password(),body==null?null:body.code(),
    account->BffController.requireAdmission(limits.mfaVerify(account),response));
   // Todas as sessões da conta foram encerradas: o navegador também perde sessão e CSRF e entra de novo.
   BffCookies.clearRecovery(response); BffCookies.clear(response); csrf.saveToken(null,request,response);
   return new CompletedBody("PASSWORD_CHANGED",result.otherSessionsEnded());
  } catch(BusinessProblem ex) {
   if(TERMINAL.contains(ex.code())) BffCookies.clearRecovery(response);
   throw ex;
  }
 }
 public record EmailBody(String email) {
  @Override public String toString() { return "EmailBody[email=REDACTED]"; }
 }
 public record TokenBody(String tokenHash) {
  @Override public String toString() { return "TokenBody[tokenHash=REDACTED]"; }
 }
 public record PasswordBody(String password,String code) {
  @Override public String toString() { return "PasswordBody[credentials=REDACTED]"; }
 }
 public record RequestedBody(String status) {}
 public record VerifiedBody(boolean mfaRequired,Instant expiresAt) {}
 public record CompletedBody(String status,boolean otherSessionsEnded) {}
}
