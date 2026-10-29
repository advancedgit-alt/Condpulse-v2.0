package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.bff.enabled",havingValue="true")
public class BffController {
 private final WebSessionService sessions;
 private final CsrfTokenRepository csrf;
 private final Clock clock;
 private final AuthRateLimiter limits;
 public BffController(WebSessionService sessions,CsrfTokenRepository csrf,Clock clock,AuthRateLimiter limits) {
  this.sessions=sessions; this.csrf=csrf; this.clock=clock; this.limits=limits;
 }
 @GetMapping("/auth/csrf") public CsrfBody csrf(HttpServletRequest request,HttpServletResponse response) {
  response.setHeader("Cache-Control","no-store");
  CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
  if(token==null) throw new IllegalStateException("Cadeia CSRF não configurada.");
  return new CsrfBody(token.getHeaderName(),token.getToken());
 }
 @PostMapping("/auth/login") public SessionBody login(@RequestBody LoginBody body,
    HttpServletRequest request,HttpServletResponse response) {
  String previous=BffCookies.read(request);
  requireAdmission(limits.login(body.email()),response);
  var result=sessions.login(body.email(),body.password());
  if(previous!=null && !previous.equals(result.opaqueToken())) sessions.logout(previous);
  BffCookies.set(response,result.opaqueToken(),Duration.between(clock.instant(),result.expiresAt()));
  rotateCsrf(request,response);
  return body(result);
 }
 @PostMapping("/auth/refresh") public SessionBody refresh(HttpServletRequest request,HttpServletResponse response) {
  // Rate limit/dependencia falham antes do lease e nao apagam a sessao existente.
  requireAdmission(limits.refresh(BffCookies.read(request)),response);
  try {
   var result=sessions.refresh(BffCookies.read(request));
   BffCookies.set(response,result.opaqueToken(),Duration.between(clock.instant(),result.expiresAt()));
   rotateCsrf(request,response);
   return body(result);
  } catch(BusinessProblem ex) {
   if(!"SESSION_REFRESH_IN_PROGRESS".equals(ex.code())) BffCookies.clear(response);
   throw ex;
  } catch(RuntimeException ex) { BffCookies.clear(response); throw ex; }
 }
 @PostMapping("/auth/logout") public void logout(HttpServletRequest request,HttpServletResponse response) {
  try { sessions.logout(BffCookies.read(request)); }
  finally {
   BffCookies.clear(response); csrf.saveToken(null,request,response);
  }
  response.setStatus(204);
 }
 @GetMapping("/auth/mfa") public MfaBody mfa(HttpServletRequest request,HttpServletResponse response) {
  response.setHeader("Cache-Control","no-store");
  String raw=BffCookies.read(request);
  requireAdmission(limits.mfaStatus(sessions.accountOf(raw)),response);
  var status=sessions.mfaStatus(raw);
  return new MfaBody(status.assuranceLevel().name(),status.factors());
 }
 @PostMapping("/auth/mfa/enroll") public EnrollmentBody enroll(HttpServletRequest request,HttpServletResponse response) {
  String raw=BffCookies.read(request);
  requireAdmission(limits.mfaEnroll(sessions.accountOf(raw)),response);
  response.setHeader("Cache-Control","no-store");
  var enrollment=sessions.enrollTotp(raw);
  return new EnrollmentBody(enrollment.factorId(),enrollment.otpauthUri(),enrollment.secret(),enrollment.qrCodeDataUri());
 }
 @PostMapping("/auth/mfa/verify") public SessionBody verify(@RequestBody MfaVerifyBody body,
    HttpServletRequest request,HttpServletResponse response) {
  String raw=BffCookies.read(request);
  requireAdmission(limits.mfaVerify(sessions.accountOf(raw)),response);
  var result=sessions.verifyTotp(raw,body.factorId(),body.code());
  // Elevação AAL2 troca o cookie opaco e o CSRF; a sessão anterior deixa de existir.
  BffCookies.set(response,result.opaqueToken(),Duration.between(clock.instant(),result.expiresAt()));
  rotateCsrf(request,response);
  return body(result);
 }
 private void rotateCsrf(HttpServletRequest request,HttpServletResponse response) {
  var token=csrf.generateToken(request); csrf.saveToken(token,request,response);
 }
 private void requireAdmission(AuthRateLimiter.Admission admission,HttpServletResponse response) {
  if(!admission.allowed()) {
   response.setHeader("Retry-After",Long.toString(admission.retryAfterSeconds()));
   response.setHeader("Cache-Control","no-store");
   throw new BusinessProblem("AUTH_RATE_LIMITED",429,"Aguarde antes de tentar novamente.");
  }
 }
 private SessionBody body(WebSessionService.SessionTicket ticket) {
  return new SessionBody(ticket.accountId(),ticket.expiresAt());
 }
 public record LoginBody(String email,String password) {
  @Override public String toString() { return "LoginBody[credentials=REDACTED]"; }
 }
 public record MfaVerifyBody(String factorId,String code) {
  @Override public String toString() { return "MfaVerifyBody[factorId="+factorId+",code=REDACTED]"; }
 }
 public record MfaBody(String assuranceLevel,List<SupabaseAuthClient.Factor> factors) {}
 public record EnrollmentBody(UUID factorId,String otpauthUri,String secret,String qrCodeDataUri) {
  @Override public String toString() { return "EnrollmentBody[factorId="+factorId+",secret=REDACTED]"; }
 }
 public record CsrfBody(String headerName,String token) {}
 public record SessionBody(UUID accountId,Instant expiresAt) {}
}
