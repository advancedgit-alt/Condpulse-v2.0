package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.platform.application.ManagerInvitationService;
import br.com.condpulse.platform.application.PlatformModels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Primeiro acesso pelo convite de primeiro gestor (painel master), atrás de CP_BFF_ENABLED e CP_PLATFORM_ENABLED. Igual
 * ao primeiro acesso por convite da gestão (BffInvitationController): senha do provedor e token seguem juntos, o aceite
 * cria o espelho local e o vínculo MANAGER ativo, e só então a sessão web é criada. Origin, CSRF e o limite de
 * tentativas do login valem também para esta rota.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.bff.enabled","condpulse.platform.enabled"},havingValue="true")
public class BffManagerInvitationController {
 private static final System.Logger LOG=System.getLogger(BffManagerInvitationController.class.getName());
 private final WebSessionService sessions;
 private final ManagerInvitationService invitations;
 private final CsrfTokenRepository csrf;
 private final Clock clock;
 private final AuthRateLimiter limits;
 public BffManagerInvitationController(WebSessionService sessions,ManagerInvitationService invitations,CsrfTokenRepository csrf,Clock clock,
    AuthRateLimiter limits) {
  this.sessions=sessions; this.invitations=invitations; this.csrf=csrf; this.clock=clock; this.limits=limits;
 }
 @PostMapping("/auth/manager-invitation/accept")
 public ResponseEntity<FirstAccessBody> accept(@RequestBody FirstAccessRequest body,HttpServletRequest request,HttpServletResponse response) {
  String email=body==null?null:body.email();
  BffController.requireAdmission(limits.login(email),response);
  String token=body==null?null:body.token();
  // Token malformado nunca chega ao provedor de senha.
  if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))
   throw new BusinessProblem("INVITATION_NOT_AVAILABLE",404,"Convite inválido, expirado, já utilizado ou emitido para outro e-mail.");
  // Cookie duplicado é recusado (401) antes do provedor de senha.
  String previous=BffCookies.read(request);
  var result=sessions.loginAcceptingInvitation(email,body.password(),
   (account,jwtEmail)->invitations.accept(account,jwtEmail,new PlatformModels.AcceptManagerInvitation(token)));
  var session=result.session();
  // Sessão nova substitui a anterior; sem sessão nova, a anterior (talvez de outra conta) também sai do navegador.
  if(session!=null) BffCookies.set(response,session.opaqueToken(),Duration.between(clock.instant(),session.expiresAt()));
  else if(previous!=null) BffCookies.clear(response);
  if(session!=null || previous!=null) { var rotated=csrf.generateToken(request); csrf.saveToken(rotated,request,response); }
  // O aceite já está gravado: falha ao encerrar a sessão anterior fica no log e não vira erro para quem entrou.
  if(previous!=null && (session==null || !previous.equals(session.opaqueToken()))) {
   try { sessions.logout(previous); }
   catch(RuntimeException error) {
    LOG.log(System.Logger.Level.WARNING,"Sessão anterior não encerrada no primeiro acesso: {0}",error.getClass().getSimpleName());
   }
  }
  var accepted=result.accepted();
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new FirstAccessBody(accepted.tenantId(),
   accepted.membershipId(),accepted.status(),session!=null,session==null?null:session.accountId(),
   session==null?null:session.expiresAt()));
 }
 public record FirstAccessRequest(String email,String password,String token) {
  @Override public String toString() { return "FirstAccessRequest[credentials=REDACTED]"; }
 }
 public record FirstAccessBody(UUID tenantId,UUID membershipId,String status,boolean sessionStarted,UUID accountId,Instant expiresAt) {}
}
