package br.com.condpulse.platform.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.MembershipModels;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aceite do convite de primeiro gestor emitido pelo painel master. A conta Auth autenticada, com o mesmo e-mail do
 * convite, cria somente o próprio vínculo MANAGER ativo: a decisão foi da plataforma ao emitir o convite (AAL2,
 * auditada) e a prova de posse do e-mail confirmado é o login no Auth. O condomínio vem do convite gravado, nunca do
 * cliente. Nenhuma credencial administrativa do Auth participa.
 */
@Service @Profile("api")
public class ManagerInvitationService {
 private final JdbcTemplate jdbc;
 private final AuditRecorder audit;
 private final TransactionTemplate tx;
 public ManagerInvitationService(JdbcTemplate jdbc,AuditRecorder audit,PlatformTransactionManager manager) {
  this.jdbc=jdbc; this.audit=audit;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }
 private record Presented(UUID id,UUID tenantId,String displayName,String emailHash) {}

 public MembershipModels.InvitationAccepted accept(UUID account,String jwtEmail,PlatformModels.AcceptManagerInvitation request) {
  if(account==null) throw notAvailable();
  String token=request==null?null:request.token();
  if(token==null || !token.matches(ManagerInvitationTokens.TOKEN_PATTERN)) throw notAvailable();
  String email;
  try { email=ManagerInvitationTokens.normalizedEmail(jwtEmail); } catch(BusinessProblem invalid) { throw notAvailable(); }
  String tokenHash=ManagerInvitationTokens.sha256(token);
  try {
   return tx.execute(status->{
    for(String setting:new String[]{"app.session_hash","app.capabilities","app.tenant_id","app.platform_capabilities",
      "app.invitation_hash","app.recovery_hash","app.recovery_purge"})
     jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
    jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,account.toString());
    jdbc.queryForObject("select set_config('app.manager_invitation_hash',?,true)",String.class,tokenHash);
    String accountStatus=accountStatus(account);
    if(accountStatus==null) {
     // Primeiro acesso: espelho local da identidade Auth já validada (a política só aceita a própria conta, ativa).
     jdbc.update("insert into platform.accounts(id,status) values(?,'ACTIVE') on conflict do nothing",account);
     accountStatus=accountStatus(account);
    }
    if(!"ACTIVE".equals(accountStatus))
     throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
    var rows=jdbc.query("""
     select id,tenant_id,display_name,email_hash from platform.manager_invitations
     where token_hash=? and status='PENDING' and expires_at>now() for update
     """,(r,n)->new Presented(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("display_name"),
      r.getString("email_hash")),tokenHash);
    if(rows.size()!=1) throw notAvailable();
    var invitation=rows.getFirst();
    if(!MessageDigest.isEqual(invitation.emailHash().getBytes(StandardCharsets.US_ASCII),
      ManagerInvitationTokens.emailHash(invitation.id(),email).getBytes(StandardCharsets.US_ASCII))) throw notAvailable();
    // Condomínio suspenso não recebe gestor novo (o banco também recusa o vínculo).
    Integer active=jdbc.queryForObject("select count(*) from platform.tenants where id=? and active",Integer.class,invitation.tenantId());
    if(active==null || active!=1) throw notAvailable();
    jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,invitation.tenantId().toString());
    UUID membership=UUID.randomUUID();
    jdbc.update("""
     insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,null,?,'MANAGER','ACTIVE')
     """,membership,invitation.tenantId(),account,invitation.displayName());
    int completed=jdbc.update("""
     update platform.manager_invitations set status='ACCEPTED',accepted_by=?,accepted_at=now(),membership_id=?,version=version+1
     where id=? and status='PENDING'
     """,account,membership,invitation.id());
    if(completed!=1) throw notAvailable();
    audit.record(invitation.tenantId(),account,"MANAGER_INVITATION_ACCEPTED","MANAGER_INVITATION",invitation.id(),null);
    return new MembershipModels.InvitationAccepted(invitation.tenantId(),membership,"ACTIVE");
   });
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23505".equals(state))
    throw new BusinessProblem("MEMBERSHIP_ALREADY_EXISTS",409,"Você já possui este vínculo no condomínio.");
   if("42501".equals(state)) throw notAvailable();
   throw new BusinessProblem("INVITATION_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir o convite. Tente novamente.");
  }
 }
 private String accountStatus(UUID account) {
  var rows=jdbc.queryForList("select status from platform.accounts where id=?",String.class,account);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static BusinessProblem notAvailable() {
  return new BusinessProblem("INVITATION_NOT_AVAILABLE",404,
   "Convite inválido, expirado, já utilizado ou emitido para outro e-mail.");
 }
}
