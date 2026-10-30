package br.com.condpulse.occurrences.application;

import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.occurrences.infrastructure.OccurrenceStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Ocorrências da portaria (CP-027): a portaria e a gestão registram o que precisa de verificação e encerram com a
 * resolução, pela versão lida. Registro interno da equipe: nunca aparece para moradores; nada é apagado.
 */
@Service @Profile("api")
public class OccurrenceService {
 private final TenantTransactionExecutor transactions;
 private final OccurrenceStore store;
 private final AuditRecorder audit;
 public OccurrenceService(TenantTransactionExecutor transactions,OccurrenceStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }
 public OccurrenceModels.Page page(UUID actor,UUID tenant,AssuranceLevel assurance,boolean includeResolved) {
  return execute(actor,tenant,assurance,context->{ requireStaff(context); return store.page(tenant,includeResolved); });
 }
 public OccurrenceModels.Created create(UUID actor,UUID tenant,AssuranceLevel assurance,OccurrenceModels.Create request) {
  if(request==null || request.occurrenceId()==null) throw invalid("OCCURRENCE_INPUT_INVALID","Informe a categoria, o título e a descrição.");
  if(request.category()==null || !OccurrenceModels.CATEGORIES.contains(request.category()))
   throw invalid("OCCURRENCE_CATEGORY_INVALID","Escolha uma categoria válida.");
  String title=DisplayText.normalize(request.title(),120);
  if(title==null || title.codePointCount(0,title.length())<3) throw invalid("OCCURRENCE_TITLE_INVALID","Informe o título com 3 a 120 caracteres.");
  String description=DisplayText.normalizeMultiline(request.description(),2000);
  if(description==null) throw invalid("OCCURRENCE_DESCRIPTION_INVALID","Descreva a ocorrência com até 2.000 caracteres, sem caracteres invisíveis.");
  return execute(actor,tenant,assurance,context->{
   requireStaff(context);
   if(request.unitId()!=null && !store.unitExists(tenant,request.unitId())) throw invalid("OCCURRENCE_UNIT_INVALID","Escolha uma unidade deste condomínio.");
   boolean fresh=store.insert(tenant,request,title,description);
   var found=store.find(tenant,request.occurrenceId());
   // Repetição: mesmo identificador, mesma autoria e mesmo conteúdo (inclusive a unidade). Outro conteúdo com o mesmo identificador é conflito.
   if(found==null || (!fresh && (!found.createdByMe() || !found.title().equals(title) || !found.description().equals(description)
      || !found.category().equals(request.category()) || !java.util.Objects.equals(found.unitId(),request.unitId()))))
    throw new BusinessProblem("OCCURRENCE_ID_CONFLICT",409,"Este registro já existe com outro conteúdo. Recarregue a lista.");
   if(fresh) audit.record(tenant,actor,"OCCURRENCE_OPENED","CONCIERGE_OCCURRENCE",found.occurrenceId(),null);
   return new OccurrenceModels.Created(found,!fresh);
  });
 }
 public OccurrenceModels.Occurrence resolve(UUID actor,UUID tenant,AssuranceLevel assurance,UUID id,OccurrenceModels.Resolve request) {
  if(id==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("OCCURRENCE_VERSION_REQUIRED","Atualize a ocorrência antes de encerrar.");
  String resolution=DisplayText.normalizeMultiline(request.resolution(),1000);
  if(resolution==null) throw invalid("OCCURRENCE_RESOLUTION_INVALID","Descreva a resolução com até 1.000 caracteres.");
  return execute(actor,tenant,assurance,context->{
   requireStaff(context);
   var current=store.find(tenant,id);
   if(current==null) throw notFound();
   if(!"OPEN".equals(current.state())) {
    // Repetição natural: já encerrada com a mesma resolução devolve o estado atual.
    if(resolution.equals(current.resolution())) return current;
    throw new BusinessProblem("OCCURRENCE_RESOLVED",409,"A ocorrência já foi encerrada. Atualize a lista.");
   }
   if(current.version()!=request.expectedVersion() || !store.resolve(tenant,id,resolution,request.expectedVersion()))
    throw new BusinessProblem("OCCURRENCE_CHANGED",409,"A ocorrência foi alterada. Atualize antes de encerrar.");
   audit.record(tenant,actor,"OCCURRENCE_RESOLVED","CONCIERGE_OCCURRENCE",id,null);
   return store.find(tenant,id);
  });
 }
 private static void requireStaff(TenantContext context) {
  var granted=context.grantedCapabilities();
  if(granted.contains(StaffCapability.OPERATE_CONCIERGE)) context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
  else context.requireCapability(StaffCapability.MANAGE_REQUESTS);
 }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23514".equals(state)) throw invalid("OCCURRENCE_INPUT_INVALID","Os dados da ocorrência não atendem às regras do registro.");
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) { throw unavailable(); }
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("OCCURRENCE_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("OCCURRENCE_NOT_FOUND",404,"Ocorrência não encontrada."); }
}
