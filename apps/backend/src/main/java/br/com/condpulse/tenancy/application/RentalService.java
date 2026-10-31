package br.com.condpulse.tenancy.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.infrastructure.MembershipStore;
import br.com.condpulse.tenancy.infrastructure.RentalStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Proprietário × inquilino (regra do responsável, 30/10/2026). A gestão (memberships.manage, AAL2) marca e desmarca a
 * unidade como alugada; enquanto alugada, o proprietário (OWNER) perde a visão da unidade e só convida e remove o
 * inquilino. O banco repete cada regra (V034); este serviço dá códigos claros e audita na mesma transação.
 */
@Service @Profile("api")
public class RentalService {
 static final Set<String> TENANT_ROLES=Set.of("RESIDENT","RESPONSIBLE");
 private final TenantTransactionExecutor transactions;
 private final RentalStore rentals;
 private final MembershipStore memberships;
 private final AuditRecorder audit;
 public RentalService(TenantTransactionExecutor transactions,RentalStore rentals,MembershipStore memberships,AuditRecorder audit) {
  this.transactions=transactions; this.rentals=rentals; this.memberships=memberships; this.audit=audit;
 }

 // ---- Gestão ----
 public List<RentalModels.ManagedRental> active(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return staff(actor,tenant,assurance,context->rentals.active(tenant));
 }
 public RentalModels.ManagedRental start(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unit,RentalModels.RentalChange request) {
  String reason=reason(request);
  return staff(actor,tenant,assurance,context->{
   if(unit==null || !memberships.activeUnit(tenant,unit))
    throw new BusinessProblem("UNIT_NOT_FOUND",404,"Unidade ativa não encontrada neste condomínio.");
   rentals.lockUnit(tenant,unit);
   if(rentals.activeFor(tenant,unit)!=null)
    throw new BusinessProblem("RENTAL_ALREADY_ACTIVE",409,"A unidade já está marcada como alugada.");
   var owners=rentals.ownerMemberships(tenant,unit);
   if(owners.isEmpty())
    throw new BusinessProblem("RENTAL_OWNER_REQUIRED",409,"Cadastre o vínculo de proprietário da unidade antes de marcar a locação.");
   if(rentals.ownerIsOccupant(tenant,unit))
    throw new BusinessProblem("RENTAL_OWNER_IS_OCCUPANT",409,"O proprietário ainda tem vínculo de morador nesta unidade. Revogue-o antes de marcar a locação.");
   for(UUID owner:owners) rentals.start(tenant,unit,owner,actor);
   audit.record(tenant,actor,"UNIT_RENTAL_STARTED","UNIT",unit,reason);
   return rentals.activeFor(tenant,unit);
  });
 }
 public void end(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unit,RentalModels.RentalChange request) {
  String reason=reason(request);
  staff(actor,tenant,assurance,context->{
   if(unit==null) throw notRented();
   rentals.lockUnit(tenant,unit);
   if(rentals.end(tenant,unit,actor)==0) throw notRented();
   audit.record(tenant,actor,"UNIT_RENTAL_ENDED","UNIT",unit,reason);
   return null;
  });
 }

 // ---- Proprietário ----
 public RentalModels.OwnerView view(UUID actor,UUID tenant,UUID unit) {
  return owner(actor,tenant,unit,false,context->{
   Instant since=rentals.ownerRentedSince(tenant,unit);
   String label=rentals.unitLabel(tenant,unit);
   if(since==null) return new RentalModels.OwnerView(unit,label,false,null,List.of(),List.of());
   return new RentalModels.OwnerView(unit,label,true,since,rentals.occupants(tenant,unit),rentals.invitations(tenant,unit));
  });
 }
 public MembershipModels.InvitationCreated invite(UUID actor,UUID tenant,UUID unit,RentalModels.TenantInvitation request) {
  if(request==null) throw invalid("INVITATION_INPUT_INVALID","Informe os dados do convite.");
  if(request.role()==null || !TENANT_ROLES.contains(request.role()))
   throw invalid("INVITATION_ROLE_INVALID","O proprietário convida somente inquilino (morador ou responsável).");
  String name=UnitManagementService.text(request.displayName(),160,"INVITATION_NAME_INVALID","Informe o nome com até 160 caracteres.");
  String email=InvitationService.normalizedEmail(request.email());
  int days=request.validDays()==null?7:request.validDays();
  if(days<1 || days>14) throw invalid("INVITATION_VALIDITY_INVALID","A validade deve ter entre 1 e 14 dias.");
  UUID id=UUID.randomUUID();
  String token=InvitationService.newToken();
  return owner(actor,tenant,unit,true,context->{
   if(!memberships.activeUnit(tenant,unit))
    throw new BusinessProblem("UNIT_NOT_FOUND",404,"Unidade ativa não encontrada neste condomínio.");
   Instant expires=memberships.insertInvitation(id,tenant,unit,request.role(),name,InvitationService.emailHash(id,email),
    InvitationService.sha256(token),days,actor);
   audit.record(tenant,actor,"INVITATION_CREATED","INVITATION",id,"Inquilino convidado pelo proprietário");
   return new MembershipModels.InvitationCreated(id,token,expires);
  });
 }
 public void revokeInvitation(UUID actor,UUID tenant,UUID unit,UUID invitation) {
  owner(actor,tenant,unit,true,context->{
   if(invitation==null || !rentals.revokeInvitation(tenant,unit,invitation))
    throw new BusinessProblem("INVITATION_NOT_PENDING",409,"O convite não está mais pendente.");
   audit.record(tenant,actor,"INVITATION_REVOKED","INVITATION",invitation,"Revogado pelo proprietário");
   return null;
  });
 }
 public RentalModels.Occupant remove(UUID actor,UUID tenant,UUID unit,UUID membership,RentalModels.OccupantRemoval request) {
  if(membership==null || request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw new BusinessProblem("MEMBERSHIP_VERSION_REQUIRED",400,"Informe a versão lida do vínculo.");
  String reason=UnitManagementService.optional(request.reason(),250,"AUDIT_REASON_INVALID","O motivo deve ter até 250 caracteres.");
  if(reason==null) throw new BusinessProblem("MEMBERSHIP_REASON_REQUIRED",400,"Informe o motivo da remoção.");
  long expected=request.expectedVersion();
  return owner(actor,tenant,unit,true,context->{
   var current=rentals.occupant(tenant,unit,membership);
   if(current==null) throw new BusinessProblem("MEMBERSHIP_NOT_FOUND",404,"Vínculo não encontrado nesta unidade.");
   if(current.version()!=expected || !memberships.revoke(tenant,membership,expected))
    throw new BusinessProblem("MEMBERSHIP_CHANGED",409,"O vínculo foi alterado. Atualize os dados antes de remover.");
   audit.record(tenant,actor,"MEMBERSHIP_REVOKED","MEMBERSHIP",membership,"Removido pelo proprietário: "+reason);
   // Credenciais do vínculo encerrado deixam de valer na mesma transação, como na revogação pela gestão.
   for(UUID credential:memberships.revokeCredentials(tenant,membership,actor,"Vínculo encerrado pelo proprietário: "+reason))
    audit.record(tenant,actor,"CREDENTIAL_REVOKED","ACCESS_CREDENTIAL",credential,"Vínculo encerrado");
   memberships.event(tenant,"MEMBERSHIP_REVOKED",membership,expected+1);
   return rentals.occupant(tenant,unit,membership);
  });
 }

 private <T> T staff(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS);
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   throw translate(error,"STAFF_ACCESS_DENIED");
  }
 }
 /** Vínculo OWNER vigente da unidade; ações exigem locação em vigor (o banco confere de novo pela tabela de locação). */
 private <T> T owner(UUID actor,UUID tenant,UUID unit,boolean requireRented,Function<TenantContext,T> action) {
  if(unit==null) throw ownerDenied();
  try {
   return transactions.execute(actor,tenant,context->{
    if(context.memberships().stream().noneMatch(m->unit.equals(m.unitId()) && "OWNER".equals(m.role()))) throw ownerDenied();
    if(requireRented && rentals.ownerRentedSince(tenant,unit)==null)
     throw new BusinessProblem("RENTAL_NOT_ACTIVE",409,"A unidade não está marcada como alugada pela gestão.");
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   throw translate(error,"UNIT_ACCESS_DENIED");
  }
 }
 private static BusinessProblem translate(DataAccessException error,String denied) {
  String state=SqlStates.of(error);
  if("42501".equals(state)) return "UNIT_ACCESS_DENIED".equals(denied)?ownerDenied()
   :new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
  if("23505".equals(state)) return new BusinessProblem("RENTAL_ALREADY_ACTIVE",409,"A unidade já está marcada como alugada.");
  if("23514".equals(state)) return new BusinessProblem("RENTAL_CONFLICT",409,"Os vínculos da unidade mudaram. Atualize os dados e tente novamente.");
  if("23503".equals(state)) return new BusinessProblem("UNIT_NOT_FOUND",404,"Unidade ativa não encontrada neste condomínio.");
  return new BusinessProblem("RENTAL_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
 }
 private static String reason(RentalModels.RentalChange request) {
  String reason=UnitManagementService.optional(request==null?null:request.reason(),500,"AUDIT_REASON_INVALID","O motivo deve ter até 500 caracteres.");
  if(reason==null) throw new BusinessProblem("RENTAL_REASON_REQUIRED",400,"Informe o motivo.");
  return reason;
 }
 private static BusinessProblem notRented() {
  return new BusinessProblem("RENTAL_NOT_ACTIVE",409,"A unidade não está marcada como alugada.");
 }
 private static BusinessProblem ownerDenied() {
  return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
