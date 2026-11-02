package br.com.condpulse.visits.application;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.infrastructure.AccessStore;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Registro manual de entrada e saída de visitantes (CP-028) pela portaria (capacidade com AAL2). Entrada somente com
 * autorização vigente — chegada autorizada pela unidade nas últimas 24 horas ou convite em vigor — e uma entrada válida
 * por chegada; saída de entrada registrada, uma válida por vez; correção humana como evento adicional, com motivo. O
 * registro é manual e da portaria, nunca "passagem detectada por equipamento". O banco aplica as mesmas regras.
 */
@Service @Profile("api")
public class AccessService {
 static final Set<String> ENTRY_EVIDENCE=Set.of("DOCUMENT_CHECKED","RECOGNIZED_BY_UNIT");
 static final Duration ARRIVAL_WINDOW=Duration.ofHours(24);
 /** Tipos de permanência (V055, cp.stay_kinds). */
 static final Set<String> STAY_KINDS=Set.of("VISITOR","SERVICE_PROVIDER","DELIVERY","CONSTRUCTION");
 private final TenantTransactionExecutor transactions;
 private final VisitStore visits;
 private final AccessStore store;
 private final AuditRecorder audit;
 private final SensitiveViewRecorder views;
 public AccessService(TenantTransactionExecutor transactions,VisitStore visits,AccessStore store,AuditRecorder audit) {
  this(transactions,visits,store,audit,SensitiveViewRecorder.disabled());
 }
 @Autowired
 public AccessService(TenantTransactionExecutor transactions,VisitStore visits,AccessStore store,AuditRecorder audit,SensitiveViewRecorder views) {
  this.transactions=transactions; this.visits=visits; this.store=store; this.audit=audit; this.views=java.util.Objects.requireNonNull(views);
 }

 public AccessModels.RecordPage records(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var page=store.recent(tenant);
   // Histórico de acesso do condomínio lido pela equipe (CPX-011): SENSITIVE_VIEWED na mesma transação.
   views.record(tenant,"CONCIERGE","ACCESS_HISTORY",null,null,null,Map.of("count",page.records().size()));
   return page;
  });
 }
 /** Histórico da unidade (adulto ou dependente com permissão de decidir), sem contas da equipe. */
 public AccessModels.RecordPage unitRecords(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   if(unit==null || !visits.member(tenant,unit,"decide")) throw unitDenied();
   return store.unitRecords(tenant,unit);
  });
 }

 /** Entrada de quem tem autorização vigente agora; não há entrada sem chegada autorizada ou convite em vigor. */
 public AccessModels.RecordMutation entry(UUID actor,UUID tenant,AssuranceLevel assurance,String key,AccessModels.RecordEntry request) {
  requireKey(key);
  if(request==null || (request.arrivalId()==null)==(request.permitId()==null))
   throw invalid("ACCESS_BASIS_REQUIRED","Informe a chegada autorizada ou o convite em vigor.");
  if(request.evidence()==null || !ENTRY_EVIDENCE.contains(request.evidence()))
   throw invalid("ACCESS_EVIDENCE_INVALID","Informe como a pessoa foi conferida.");
  String note=optionalNote(request.note());
  if(request.stayKind()!=null && !STAY_KINDS.contains(request.stayKind()))
   throw invalid("STAY_KIND_INVALID","Tipo de permanência inválido: use visitante, prestador, entregador ou obra.");
  String hash=VisitRequestHash.entry(request.arrivalId(),request.permitId(),request.evidence(),note,request.stayKind());
  String route="/v1/tenants/"+tenant+"/access-events/entries";
  return execute(actor,tenant,assurance,Map.of("23505",AccessService::entryRecorded,"23514",AccessService::stateChanged,"P5701",AccessService::outsideStaySchedule),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=visits.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return replay(tenant,previous);
   if(request.arrivalId()!=null) {
    var arrival=visits.arrival(tenant,request.arrivalId());
    if(arrival==null) throw new BusinessProblem("ARRIVAL_NOT_FOUND",404,"Chegada não encontrada.");
    if(!"APPROVED".equals(arrival.state()))
     throw new BusinessProblem("ACCESS_ARRIVAL_NOT_AUTHORIZED",409,"Esta chegada não tem autorização da unidade: nada é liberado.");
    if(!arrival.createdAt().isAfter(visits.now().minus(ARRIVAL_WINDOW)))
     throw new BusinessProblem("ACCESS_AUTHORIZATION_EXPIRED",409,"A autorização desta chegada não vale mais para entrada. Registre nova chegada.");
    // Mesma regra do convite (unidade ativa): unidade desativada depois da autorização não recebe entrada.
    if(!visits.activeUnit(tenant,arrival.unitId()))
     throw new BusinessProblem("ACCESS_UNIT_INACTIVE",409,"A unidade desta chegada não está ativa: nada é liberado. Siga o procedimento do condomínio.");
    if(store.validEntryForArrival(tenant,request.arrivalId())) throw entryRecorded();
   } else {
    var permit=visits.permit(tenant,request.permitId(),false);
    if(permit==null) throw new BusinessProblem("VISIT_PERMIT_NOT_FOUND",404,"Convite não encontrado.");
    if(!"ACTIVE".equals(permit.state()) || !visits.issuerActive(tenant,permit.permitId()))
     throw new BusinessProblem("ACCESS_PERMIT_NOT_CURRENT",409,"O convite não vale agora (antes do início, vencido, revogado ou de quem perdeu o vínculo).");
    // Janela de prestador recorrente abre sempre permanência de prestador (V055).
    if(permit.ruleId()!=null && request.stayKind()!=null && !"SERVICE_PROVIDER".equals(request.stayKind()))
     throw invalid("STAY_KIND_INVALID","Janela de prestador recorrente é sempre de prestador.");
   }
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,request.arrivalId(),request.permitId(),"MANUAL_IN",null,null,request.evidence(),note,actor,request.stayKind());
   var record=require(store.record(tenant,id));
   audit.record(tenant,actor,"ACCESS_ENTRY_RECORDED","ACCESS_EVENT",id,null);
   store.event(tenant,"ACCESS_ENTRY_RECORDED",id,record.unitId(),"MANUAL_IN");
   visits.finish(tenant,actor,route,key,id,201);
   return new AccessModels.RecordMutation(record,false,201);
  });
 }
 /** Saída de uma entrada registrada e não corrigida; uma saída válida por vez. */
 public AccessModels.RecordMutation exit(UUID actor,UUID tenant,AssuranceLevel assurance,UUID entryId,String key,AccessModels.RecordExit request) {
  requireKey(key);
  if(entryId==null) throw notFound();
  String note=optionalNote(request==null?null:request.note());
  String hash=VisitRequestHash.exit(entryId,note);
  String route="/v1/tenants/"+tenant+"/access-events/"+entryId+"/exit";
  return execute(actor,tenant,assurance,Map.of("23505",AccessService::exitRecorded,"23514",AccessService::stateChanged),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=visits.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return replay(tenant,previous);
   var target=store.ref(tenant,entryId);
   if(target==null || !"MANUAL_IN".equals(target.type())) throw notFound();
   if(store.corrected(tenant,entryId))
    throw new BusinessProblem("ACCESS_ENTRY_CORRECTED",409,"Esta entrada foi corrigida e não recebe saída.");
   if(store.validExit(tenant,entryId)) throw exitRecorded();
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,null,null,"MANUAL_OUT",entryId,null,"EXIT_OBSERVED",note,actor);
   var record=require(store.record(tenant,entryId));
   audit.record(tenant,actor,"ACCESS_EXIT_RECORDED","ACCESS_EVENT",id,null);
   store.event(tenant,"ACCESS_EXIT_RECORDED",id,record.unitId(),"MANUAL_OUT");
   visits.finish(tenant,actor,route,key,id,201);
   return new AccessModels.RecordMutation(record,false,201);
  });
 }
 /** Correção humana de entrada ou saída: evento adicional com motivo; o registro corrigido continua guardado. */
 public AccessModels.RecordMutation correct(UUID actor,UUID tenant,AssuranceLevel assurance,UUID eventId,String key,AccessModels.Correct request) {
  requireKey(key);
  if(eventId==null) throw notFound();
  String reason=text(request==null?null:request.reason(),300,"ACCESS_CORRECTION_REASON_REQUIRED",
   "Informe o motivo da correção, com até 300 caracteres.");
  String hash=VisitRequestHash.correction(eventId,reason);
  String route="/v1/tenants/"+tenant+"/access-events/"+eventId+"/correction";
  return execute(actor,tenant,assurance,Map.of("23505",AccessService::alreadyCorrected,"23514",AccessService::stateChanged),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=visits.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) return replay(tenant,previous);
   var target=store.ref(tenant,eventId);
   if(target==null) throw notFound();
   if("CORRECTION".equals(target.type()))
    throw new BusinessProblem("ACCESS_CORRECTION_INVALID",409,"Uma correção não é corrigida. Corrija a entrada ou a saída.");
   if(store.corrected(tenant,eventId)) throw alreadyCorrected();
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,null,null,"CORRECTION",null,eventId,"OPERATOR_CORRECTION",reason,actor);
   var record=require(store.record(tenant,target.entryId()));
   audit.record(tenant,actor,"ACCESS_EVENT_CORRECTED","ACCESS_EVENT",id,reason);
   store.event(tenant,"ACCESS_EVENT_CORRECTED",id,record.unitId(),"CORRECTION");
   visits.finish(tenant,actor,route,key,id,201);
   return new AccessModels.RecordMutation(record,false,201);
  });
 }

 /** Repetição com a mesma chave e os mesmos dados: o registro atual da entrada a que o evento pertence. */
 private AccessModels.RecordMutation replay(UUID tenant,VisitStore.IdempotentReply previous) {
  var ref=store.ref(tenant,previous.resource());
  var record=ref==null?null:store.record(tenant,ref.entryId());
  if(record==null) throw notFound();
  return new AccessModels.RecordMutation(record,true,previous.responseStatus());
 }
 private static AccessModels.AccessRecord require(AccessModels.AccessRecord record) {
  if(record==null) throw new IllegalStateException("Registro de acesso gravado não foi encontrado na mesma transação.");
  return record;
 }
 private static String optionalNote(String value) {
  return value==null || DisplayText.collapse(value).isEmpty()?null
   :text(value,300,"ACCESS_NOTE_INVALID","Use até 300 caracteres na observação.");
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static String text(String value,int maximum,String code,String message) {
  String normalized=DisplayText.normalize(value,maximum);
  if(normalized==null) throw invalid(code,message);
  return normalized;
 }
 /**
  * Constraints, gatilhos e políticas abortam a transação; traduzir somente depois do rollback do executor e pelo
  * significado de cada operação. Prazo da transação esgotado é resultado incerto (503).
  */
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Map<String,Supplier<BusinessProblem>> conflicts,
   Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   var mapped=state==null?null:conflicts.get(state);
   if(mapped!=null) throw mapped.get();
   if("42501".equals(state)) throw new BusinessProblem("ACCESS_FORBIDDEN",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("ACCESS_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem unitDenied() { return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação."); }
 private static BusinessProblem notFound() { return new BusinessProblem("ACCESS_EVENT_NOT_FOUND",404,"Registro de acesso não encontrado."); }
 private static BusinessProblem entryRecorded() {
  return new BusinessProblem("ACCESS_ENTRY_ALREADY_RECORDED",409,"A entrada desta chegada já foi registrada. Atualize a fila.");
 }
 private static BusinessProblem exitRecorded() {
  return new BusinessProblem("ACCESS_EXIT_ALREADY_RECORDED",409,"A saída desta entrada já foi registrada. Atualize a lista.");
 }
 private static BusinessProblem alreadyCorrected() {
  return new BusinessProblem("ACCESS_ALREADY_CORRECTED",409,"Este registro já foi corrigido. Atualize a lista.");
 }
 private static BusinessProblem outsideStaySchedule() { return new BusinessProblem("STAY_ENTRY_OUTSIDE_SCHEDULE",409,
  "Entrada fora do horário permitido para esta categoria. Confira a regra do condomínio."); }
 private static BusinessProblem stateChanged() {
  return new BusinessProblem("ACCESS_STATE_CHANGED",409,"A autorização ou o registro mudou. Atualize a lista antes de repetir.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
