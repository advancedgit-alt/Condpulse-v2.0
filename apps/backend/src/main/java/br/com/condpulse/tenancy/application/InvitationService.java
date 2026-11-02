package br.com.condpulse.tenancy.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.infrastructure.MembershipStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Convite individual de validade curta (CP-020). A gestão emite e revoga; o convidado autenticado, com o
 * mesmo e-mail, cria somente o próprio vínculo PENDING. A ativação continua sendo decisão da gestão.
 */
@Service @Profile("api")
public class InvitationService {
 static final Set<String> ROLES=Set.of("RESIDENT","RESPONSIBLE","DEPENDENT","OWNER","CONCIERGE","MAINTENANCE","MANAGER");
 private static final Set<String> RESIDENTIAL=Set.of("RESIDENT","RESPONSIBLE","DEPENDENT","OWNER");
 private static final SecureRandom RANDOM=new SecureRandom();
 private final TenantTransactionExecutor transactions;
 private final MembershipStore store;
 private final AuditRecorder audit;
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public InvitationService(TenantTransactionExecutor transactions,MembershipStore store,AuditRecorder audit,
     JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.transactions=transactions; this.store=store; this.audit=audit; this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }

 public MembershipModels.InvitationCreated create(UUID actor,UUID tenant,AssuranceLevel assurance,
     MembershipModels.CreateInvitation request) {
  if(request==null) throw invalid("INVITATION_INPUT_INVALID","Informe os dados do convite.");
  if(request.role()==null || !ROLES.contains(request.role()))
   throw invalid("INVITATION_ROLE_INVALID","Papel não disponível para convite.");
  boolean residential=RESIDENTIAL.contains(request.role());
  if(residential && request.unitId()==null) throw invalid("INVITATION_UNIT_REQUIRED","Informe a unidade do convite.");
  if(!residential && request.unitId()!=null) throw invalid("INVITATION_UNIT_INVALID","Equipe não é vinculada a unidade.");
  String name=UnitManagementService.text(request.displayName(),160,"INVITATION_NAME_INVALID","Informe o nome com até 160 caracteres.");
  String email=normalizedEmail(request.email());
  int days=request.validDays()==null?7:request.validDays();
  if(days<1 || days>14) throw invalid("INVITATION_VALIDITY_INVALID","A validade deve ter entre 1 e 14 dias.");
  UUID id=UUID.randomUUID();
  String token=newToken();
  return staff(actor,tenant,assurance,context->{
   if(residential && !store.activeUnit(tenant,request.unitId()))
    throw new BusinessProblem("UNIT_NOT_FOUND",404,"Unidade ativa não encontrada neste condomínio.");
   Instant expires=store.insertInvitation(id,tenant,request.unitId(),request.role(),name,emailHash(id,email),sha256(token),days,actor);
   audit.record(tenant,actor,"INVITATION_CREATED","INVITATION",id,null);
   return new MembershipModels.InvitationCreated(id,token,expires);
  });
 }
 public List<MembershipModels.Invitation> invitations(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return staff(actor,tenant,assurance,context->store.invitations(tenant));
 }
 public void revoke(UUID actor,UUID tenant,AssuranceLevel assurance,UUID invitationId) {
  staff(actor,tenant,assurance,context->{
   if(invitationId==null || !store.revokeInvitation(tenant,invitationId))
    throw new BusinessProblem("INVITATION_NOT_PENDING",409,"O convite não está mais pendente.");
   audit.record(tenant,actor,"INVITATION_REVOKED","INVITATION",invitationId,null);
   return null;
  });
 }

 /** Aceite pelo próprio convidado. O condomínio vem do convite gravado, nunca do cliente. */
 public MembershipModels.InvitationAccepted accept(UUID account,String jwtEmail,MembershipModels.AcceptInvitation request) {
  if(account==null) throw notAvailable();
  String token=request==null?null:request.token();
  if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw notAvailable();
  String email;
  try { email=normalizedEmail(jwtEmail); } catch(BusinessProblem invalid) { throw notAvailable(); }
  String tokenHash=sha256(token);
  try {
   return tx.execute(status->{
    jdbc.queryForObject("select set_config('app.session_hash','',true)",String.class);
    jdbc.queryForObject("select set_config('app.capabilities','',true)",String.class);
    jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
    jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,account.toString());
    jdbc.queryForObject("select set_config('app.invitation_hash',?,true)",String.class,tokenHash);
    String accountStatus=store.accountStatus(account);
    if(accountStatus==null) {
     // Outro aceite simultâneo da mesma conta pode ter criado o espelho: relê em vez de colidir.
     store.insertAccount(account);
     accountStatus=store.accountStatus(account);
    }
    if(!"ACTIVE".equals(accountStatus))
     throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
    var invitation=store.lockPresented(tokenHash);
    if(invitation==null || !MessageDigest.isEqual(invitation.emailHash().getBytes(StandardCharsets.US_ASCII),
       emailHash(invitation.id(),email).getBytes(StandardCharsets.US_ASCII)))
     throw notAvailable();
    // Condomínio suspenso pela plataforma não recebe vínculo novo (o banco também recusa o INSERT).
    if(!store.tenantActive(invitation.tenantId())) throw notAvailable();
    jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,invitation.tenantId().toString());
    // Unidade inativada depois do convite não recebe vínculo novo.
    if(invitation.unitId()!=null && !store.activeUnit(invitation.tenantId(),invitation.unitId())) throw notAvailable();
    UUID membership=UUID.randomUUID();
    store.insertPendingMembership(membership,invitation,account);
    if(!store.completeInvitation(invitation.id(),account,membership)) throw notAvailable();
    audit.record(invitation.tenantId(),account,"INVITATION_ACCEPTED","INVITATION",invitation.id(),null);
    store.event(invitation.tenantId(),"MEMBERSHIP_REQUESTED",membership,0);
    return new MembershipModels.InvitationAccepted(invitation.tenantId(),membership,"PENDING");
   });
  } catch(DataAccessException error) {
   if("23505".equals(SqlStates.of(error)))
    throw new BusinessProblem("MEMBERSHIP_ALREADY_EXISTS",409,"Você já possui este vínculo no condomínio.");
   if("42501".equals(SqlStates.of(error))) throw notAvailable();
   if("23514".equals(SqlStates.of(error))) throw MembershipManagementService.rentalConflict();
   throw new BusinessProblem("INVITATION_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir o convite. Tente novamente.");
  }
 }

 private <T> T staff(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23503".equals(state)) throw new BusinessProblem("UNIT_NOT_FOUND",404,"Unidade ativa não encontrada neste condomínio.");
   if("23514".equals(state)) throw invalid("INVITATION_INPUT_INVALID","Os dados do convite não atendem às regras.");
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("MANAGEMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
 static String normalizedEmail(String value) {
  if(value==null) throw invalid("INVITATION_EMAIL_INVALID","Informe um e-mail válido.");
  String email=value.strip().toLowerCase(Locale.ROOT);
  if(email.length()>320 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
   throw invalid("INVITATION_EMAIL_INVALID","Informe um e-mail válido.");
  return email;
 }
 static String emailHash(UUID invitation,String normalizedEmail) { return sha256(invitation+":"+normalizedEmail); }
 static String newToken() {
  byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 static String sha256(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notAvailable() {
  return new BusinessProblem("INVITATION_NOT_AVAILABLE",404,
   "Convite inválido, expirado, já utilizado ou emitido para outro e-mail.");
 }
}
