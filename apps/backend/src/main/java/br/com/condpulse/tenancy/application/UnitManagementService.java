package br.com.condpulse.tenancy.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.infrastructure.UnitManagementStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Gestão local de blocos e unidades: gestor com vínculo vigente e AAL2, auditoria na mesma transação. */
@Service @Profile("api")
public class UnitManagementService {
 private static final Set<String> STATUSES=Set.of("ACTIVE","INACTIVE");
 private final TenantTransactionExecutor transactions;
 private final UnitManagementStore store;
 private final AuditRecorder audit;
 public UnitManagementService(TenantTransactionExecutor transactions,UnitManagementStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 public List<UnitManagementModels.Block> blocks(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,null,context->store.blocks(tenant));
 }
 public UnitManagementModels.Block createBlock(UUID actor,UUID tenant,AssuranceLevel assurance,
     UnitManagementModels.CreateBlock request) {
  String name=text(request==null?null:request.name(),60,"BLOCK_NAME_INVALID","Informe o nome do bloco com até 60 caracteres.");
  return execute(actor,tenant,assurance,BLOCK_TAKEN,context->{
   var block=store.insertBlock(tenant,name);
   audit.record(tenant,actor,"BLOCK_CREATED","BLOCK",block.blockId(),null);
   return block;
  });
 }
 public UnitManagementModels.UnitPage units(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,null,context->store.units(tenant));
 }
 public UnitManagementModels.Unit createUnit(UUID actor,UUID tenant,AssuranceLevel assurance,
     UnitManagementModels.CreateUnit request) {
  if(request==null) throw invalid("UNIT_LABEL_INVALID","Informe a identificação da unidade.");
  String label=text(request.label(),40,"UNIT_LABEL_INVALID","Informe a identificação da unidade com até 40 caracteres.");
  return execute(actor,tenant,assurance,UNIT_TAKEN,context->{
   if(request.blockId()!=null && !store.blockExists(tenant,request.blockId())) throw blockNotFound();
   var unit=store.insertUnit(tenant,label,request.blockId());
   if(unit==null) throw new IllegalStateException("Unidade criada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"UNIT_CREATED","UNIT",unit.unitId(),null);
   return unit;
  });
 }
 public UnitManagementModels.Unit updateUnit(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unitId,
     UnitManagementModels.UpdateUnit request) {
  if(unitId==null || request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("UNIT_VERSION_REQUIRED","Informe a versão lida da unidade.");
  String label=text(request.label(),40,"UNIT_LABEL_INVALID","Informe a identificação da unidade com até 40 caracteres.");
  if(request.status()==null || !STATUSES.contains(request.status()))
   throw invalid("UNIT_STATUS_INVALID","Situação da unidade inválida.");
  String reason=optional(request.reason(),500,"AUDIT_REASON_INVALID","O motivo deve ter até 500 caracteres.");
  return execute(actor,tenant,assurance,UNIT_TAKEN,context->{
   var current=store.find(tenant,unitId);
   if(current==null) throw unitNotFound();
   if(request.blockId()!=null && !store.blockExists(tenant,request.blockId())) throw blockNotFound();
   if(current.version()!=request.expectedVersion()
      || !store.updateUnit(tenant,unitId,label,request.blockId(),request.status(),request.expectedVersion()))
    throw new BusinessProblem("UNIT_CHANGED",409,"A unidade foi alterada. Atualize os dados antes de salvar.");
   // Uma ação por mudança: inativar e renomear na mesma gravação deixam os dois registros.
   var actions=new java.util.ArrayList<String>();
   if(!current.status().equals(request.status()))
    actions.add("INACTIVE".equals(request.status())?"UNIT_DEACTIVATED":"UNIT_REACTIVATED");
   if(!current.label().equals(label) || !java.util.Objects.equals(current.blockId(),request.blockId()) || actions.isEmpty())
    actions.add("UNIT_UPDATED");
   for(String action:actions) audit.record(tenant,actor,action,"UNIT",unitId,reason);
   return store.find(tenant,unitId);
  });
 }

 private static final String BLOCK_TAKEN="BLOCK_NAME_TAKEN",UNIT_TAKEN="UNIT_LABEL_TAKEN";
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,String duplicate,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,context->{
    context.requireCapability(StaffCapability.MANAGE_UNITS);
    return action.apply(context);
   });
  } catch(DataAccessException error) {
   // Constraints abortam a transação; traduzir somente depois do rollback do executor.
   String state=SqlStates.of(error);
   if("23505".equals(state) && BLOCK_TAKEN.equals(duplicate))
    throw new BusinessProblem(BLOCK_TAKEN,409,"Já existe um bloco com este nome.");
   if("23505".equals(state) && UNIT_TAKEN.equals(duplicate))
    throw new BusinessProblem(UNIT_TAKEN,409,"Já existe uma unidade com esta identificação no bloco.");
   if("23503".equals(state)) throw blockNotFound();
   if("23514".equals(state)) throw invalid("MANAGEMENT_INPUT_INVALID","Os dados informados não atendem às regras do cadastro.");
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("MANAGEMENT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Tente novamente.");
  }
 }
 static String text(String value,int maximum,String code,String message) {
  if(value==null) throw invalid(code,message);
  String normalized=value.strip().replaceAll("\\s+"," ");
  if(normalized.isEmpty() || normalized.length()>maximum || normalized.chars().anyMatch(Character::isISOControl))
   throw invalid(code,message);
  return normalized;
 }
 static String optional(String value,int maximum,String code,String message) {
  if(value==null || value.isBlank()) return null;
  return text(value,maximum,code,message);
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem unitNotFound() {
  return new BusinessProblem("UNIT_NOT_FOUND",404,"Unidade não encontrada neste condomínio.");
 }
 private static BusinessProblem blockNotFound() {
  return new BusinessProblem("BLOCK_NOT_FOUND",404,"Bloco não encontrado neste condomínio.");
 }
}
