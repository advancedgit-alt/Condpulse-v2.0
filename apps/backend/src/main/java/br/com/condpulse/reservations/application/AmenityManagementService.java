package br.com.condpulse.reservations.application;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.infrastructure.AmenityManagementStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Configuração de áreas e regras pela gestão local, com versão de regra e auditoria. */
@Service @Profile("api")
public class AmenityManagementService {
 private final TenantTransactionExecutor transactions;
 private final AmenityManagementStore store;
 private final AuditRecorder audit;
 public AmenityManagementService(TenantTransactionExecutor transactions,AmenityManagementStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public List<AmenityManagementModels.ManagedAmenity> amenities(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->store.amenities(tenant));
 }
 public AmenityManagementModels.ManagedAmenity create(UUID actor,UUID tenant,AssuranceLevel assurance,
     AmenityManagementModels.AmenityInput input) {
  var value=AmenityManagementModels.validate(input,true);
  return execute(actor,tenant,assurance,context->{
   UUID id=store.insert(tenant,value);
   audit.record(tenant,actor,"AMENITY_CREATED","AMENITY",id,value.reason());
   return required(store.find(tenant,id));
  });
 }
 public AmenityManagementModels.ManagedAmenity update(UUID actor,UUID tenant,AssuranceLevel assurance,UUID amenityId,
     AmenityManagementModels.AmenityInput input) {
  if(amenityId==null || input==null || input.expectedVersion()==null || input.expectedVersion()<0)
   throw new BusinessProblem("AMENITY_VERSION_REQUIRED",400,"Informe a versão lida da área.");
  var value=AmenityManagementModels.validate(input,false);
  long expected=input.expectedVersion();
  return execute(actor,tenant,assurance,context->{
   var current=store.find(tenant,amenityId);
   if(current==null) throw new BusinessProblem("AMENITY_NOT_FOUND",404,"Área não encontrada neste condomínio.");
   if(current.version()!=expected || !store.update(tenant,amenityId,expected,value))
    throw new BusinessProblem("AMENITY_CHANGED",409,"A área foi alterada. Atualize os dados antes de salvar.");
   var updated=required(store.find(tenant,amenityId));
   String action=updated.policyVersion()!=current.policyVersion()?"AMENITY_POLICY_VERSIONED"
    :current.active()!=updated.active()?(updated.active()?"AMENITY_REACTIVATED":"AMENITY_DEACTIVATED"):"AMENITY_UPDATED";
   audit.record(tenant,actor,action,"AMENITY",amenityId,value.reason());
   return updated;
  });
 }
 private static AmenityManagementModels.ManagedAmenity required(AmenityManagementModels.ManagedAmenity value) {
  if(value==null) throw new IllegalStateException("Área alterada não foi encontrada na mesma transação.");
  return value;
 }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.MANAGE_AMENITIES);
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23505".equals(state)) throw new BusinessProblem("AMENITY_NAME_TAKEN",409,"Já existe uma área com este nome.");
   if("23514".equals(state) || "22P02".equals(state))
    throw new BusinessProblem("AMENITY_INPUT_INVALID",400,"Os dados informados não atendem às regras da área.");
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("MANAGEMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
}
