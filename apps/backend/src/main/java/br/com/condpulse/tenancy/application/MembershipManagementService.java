package br.com.condpulse.tenancy.application;

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
import org.springframework.stereotype.Service;

/** Aprovação, rejeição e revogação de vínculos pela gestão local, com auditoria e evento de revogação. */
@Service @Profile("api")
public class MembershipManagementService {
 private static final Set<String> STATUSES=Set.of("PENDING","ACTIVE","SUSPENDED","REVOKED");
 private final TenantTransactionExecutor transactions;
 private final MembershipStore store;
 private final AuditRecorder audit;
 public MembershipManagementService(TenantTransactionExecutor transactions,MembershipStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public MembershipModels.MembershipPage memberships(UUID actor,UUID tenant,AssuranceLevel assurance,String status) {
  if(status!=null && !STATUSES.contains(status))
   throw new BusinessProblem("MEMBERSHIP_STATUS_INVALID",400,"Situação de vínculo inválida.");
  return execute(actor,tenant,assurance,context->store.memberships(tenant,status));
 }
 public MembershipModels.Membership decide(UUID actor,UUID tenant,AssuranceLevel assurance,UUID membershipId,
     MembershipModels.Decision request) {
  if(membershipId==null || request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw new BusinessProblem("MEMBERSHIP_VERSION_REQUIRED",400,"Informe a versão lida do vínculo.");
  boolean approve="APPROVE".equals(request.decision());
  if(!approve && !"REJECT".equals(request.decision()))
   throw new BusinessProblem("MEMBERSHIP_DECISION_INVALID",400,"Informe APPROVE ou REJECT.");
  String reason=UnitManagementService.optional(request.reason(),500,"AUDIT_REASON_INVALID","O motivo deve ter até 500 caracteres.");
  if(!approve && reason==null)
   throw new BusinessProblem("MEMBERSHIP_REASON_REQUIRED",400,"Informe o motivo da rejeição.");
  long expected=request.expectedVersion();
  return execute(actor,tenant,assurance,context->{
   var current=store.membership(tenant,membershipId);
   if(current==null) throw notFound();
   if(actor.equals(store.accountOf(tenant,membershipId)))
    throw new BusinessProblem("MEMBERSHIP_SELF_DECISION_DENIED",409,"Outro gestor precisa decidir sobre o seu próprio vínculo.");
   if(!"PENDING".equals(current.status()) || current.version()!=expected
      || !store.transition(tenant,membershipId,expected,"PENDING",approve?"ACTIVE":"REVOKED"))
    throw changed();
   audit.record(tenant,actor,approve?"MEMBERSHIP_APPROVED":"MEMBERSHIP_REJECTED","MEMBERSHIP",membershipId,reason);
   return store.membership(tenant,membershipId);
  });
 }
 public MembershipModels.Membership revoke(UUID actor,UUID tenant,AssuranceLevel assurance,UUID membershipId,
     MembershipModels.Revocation request) {
  if(membershipId==null || request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw new BusinessProblem("MEMBERSHIP_VERSION_REQUIRED",400,"Informe a versão lida do vínculo.");
  String reason=UnitManagementService.optional(request.reason(),500,"AUDIT_REASON_INVALID","O motivo deve ter até 500 caracteres.");
  if(reason==null) throw new BusinessProblem("MEMBERSHIP_REASON_REQUIRED",400,"Informe o motivo da revogação.");
  long expected=request.expectedVersion();
  return execute(actor,tenant,assurance,context->{
   var current=store.membership(tenant,membershipId);
   if(current==null) throw notFound();
   if(actor.equals(store.accountOf(tenant,membershipId)) && "MANAGER".equals(current.role()))
    throw new BusinessProblem("MEMBERSHIP_SELF_REVOCATION_DENIED",409,"Outro gestor precisa revogar o seu vínculo de gestão.");
   if("MANAGER".equals(current.role())) {
    // Revogações cruzadas entre gestores são serializadas; depois da trava o ator precisa continuar gestor ativo.
    store.lockManagers(tenant);
    if(!store.activeManager(tenant,actor))
     throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
    current=store.membership(tenant,membershipId);
    if(current==null) throw notFound();
    if("ACTIVE".equals(current.status()) && store.otherActiveManagers(tenant,membershipId)==0)
     throw new BusinessProblem("LAST_MANAGER_REQUIRED",409,"O condomínio precisa manter ao menos um gestor ativo.");
   }
   if(current.version()!=expected || !store.revoke(tenant,membershipId,expected)) throw changed();
   audit.record(tenant,actor,"MEMBERSHIP_REVOKED","MEMBERSHIP",membershipId,reason);
   // Revogação de credenciais físicas e sessões derivadas é processada a partir deste evento.
   store.event(tenant,"MEMBERSHIP_REVOKED",membershipId,expected+1);
   return store.membership(tenant,membershipId);
  });
 }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   if("42501".equals(SqlStates.of(error)))
    throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("MANAGEMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
 private static BusinessProblem notFound() {
  return new BusinessProblem("MEMBERSHIP_NOT_FOUND",404,"Vínculo não encontrado neste condomínio.");
 }
 private static BusinessProblem changed() {
  return new BusinessProblem("MEMBERSHIP_CHANGED",409,"O vínculo foi alterado. Atualize os dados antes de decidir.");
 }
}
