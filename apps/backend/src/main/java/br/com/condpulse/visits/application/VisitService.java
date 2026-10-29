package br.com.condpulse.visits.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Visitas (CP-025) e chegada sem convite (CP-026). Convite é autorização temporal da unidade, não prova de identidade nem
 * de entrada. Morador e responsável convidam, revogam e decidem; dependente só com permissão explícita (o banco aplica
 * a mesma regra). A portaria (capacidade com AAL2) registra a chegada, que espera a primeira decisão válida até o prazo
 * de 5 minutos do banco; sem resposta não existe autorização automática — a portaria segue o interfone ou o
 * procedimento presencial e registra a origem da confirmação.
 */
@Service @Profile("api")
public class VisitService {
 static final Set<String> QUEUE_STATES=Set.of("WAITING","APPROVED","DENIED","EXPIRED","CANCELLED");
 static final Set<String> ASSISTED_SOURCES=Set.of("INTERCOM","IN_PERSON");
 static final Duration MAX_PERMIT=Duration.ofDays(7);
 static final Duration MAX_LEAD=Duration.ofDays(90);
 static final Duration START_TOLERANCE=Duration.ofMinutes(10);
 private final TenantTransactionExecutor transactions;
 private final VisitStore store;
 private final AuditRecorder audit;
 public VisitService(TenantTransactionExecutor transactions,VisitStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 // Unidade: convites e chegadas.
 public VisitModels.PermitPage unitPermits(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   requireMember(tenant,unit,"create");
   return store.unitPermits(tenant,unit);
  });
 }
 public VisitModels.PermitMutation createPermit(UUID actor,UUID tenant,UUID unit,String key,VisitModels.CreatePermit request) {
  requireKey(key);
  if(unit==null) throw unitDenied();
  if(request==null) throw invalid("VISIT_INPUT_INVALID","Informe os dados da visita.");
  String visitor=visitorName(request.visitorName());
  String document=document(request.documentLast4());
  String purpose=request.purpose()==null || DisplayText.collapse(request.purpose()).isEmpty()?null
   :text(request.purpose(),200,"VISIT_PURPOSE_INVALID","Use até 200 caracteres na finalidade.");
  Instant from=request.validFrom(), until=request.validUntil();
  if(from==null || until==null || !until.isAfter(from) || Duration.between(from,until).compareTo(MAX_PERMIT)>0)
   throw invalid("VISIT_PERIOD_INVALID","Informe o período da visita, com fim depois do início e até 7 dias.");
  String hash=VisitRequestHash.permit(unit,visitor,document,purpose,from,until);
  String route="/v1/tenants/"+tenant+"/units/"+unit+"/permits";
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   if(!store.member(tenant,unit,"create")) throw unitDenied();
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.permit(tenant,previous.resource(),false);
    if(replay==null) throw permitNotFound();
    return new VisitModels.PermitMutation(replay,true,previous.responseStatus());
   }
   Instant now=store.now();
   if(from.isBefore(now.minus(START_TOLERANCE)) || from.isAfter(now.plus(MAX_LEAD)) || !until.isAfter(now))
    throw invalid("VISIT_PERIOD_INVALID","O convite começa a partir de agora (até 90 dias à frente) e termina no futuro.");
   UUID visitorId=UUID.randomUUID(), id=UUID.randomUUID();
   store.insertVisitor(visitorId,tenant,visitor,document,actor);
   store.insertPermit(id,tenant,visitorId,unit,purpose,actor,from,until);
   var permit=store.permit(tenant,id,false);
   if(permit==null) throw new IllegalStateException("Convite criado não foi encontrado na mesma transação.");
   audit.record(tenant,actor,"VISIT_PERMIT_CREATED","VISIT_PERMIT",id,null);
   store.event(tenant,"VISIT_PERMIT_CREATED",id,permit.version(),unit,permit.state());
   store.finish(tenant,actor,route,key,id,201);
   return new VisitModels.PermitMutation(permit,false,201);
  });
 }
 /** Revogação pela versão lida, com motivo: quem chegar depois não tem mais o convite. Expirado não é revogado. */
 public VisitModels.Permit revokePermit(UUID actor,UUID tenant,UUID permitId,VisitModels.RevokePermit request) {
  if(permitId==null) throw permitNotFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("VISIT_VERSION_REQUIRED","Atualize os convites antes de revogar.");
  String reason=text(request.reason(),500,"VISIT_REVOKE_REASON_REQUIRED","Informe o motivo da revogação, com até 500 caracteres.");
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::permitChanged),context->{
   var current=store.permit(tenant,permitId,true);
   if(current==null) throw permitNotFound();
   boolean adult=store.member(tenant,current.unitId(),null);
   if(!adult && !(current.createdByMe() && store.member(tenant,current.unitId(),"create"))) throw permitNotFound();
   if("REVOKED".equals(current.state())) throw new BusinessProblem("VISIT_PERMIT_REVOKED",409,"Este convite já foi revogado.");
   if("EXPIRED".equals(current.state())) throw new BusinessProblem("VISIT_PERMIT_EXPIRED",409,"A vigência deste convite já terminou.");
   if(current.version()!=request.expectedVersion() || !store.revoke(tenant,permitId,request.expectedVersion(),actor,reason))
    throw permitChanged();
   var revoked=store.permit(tenant,permitId,false);
   if(revoked==null) throw new IllegalStateException("Convite revogado não foi encontrado na mesma transação.");
   audit.record(tenant,actor,"VISIT_PERMIT_REVOKED","VISIT_PERMIT",permitId,reason);
   store.event(tenant,"VISIT_PERMIT_REVOKED",permitId,revoked.version(),revoked.unitId(),revoked.state());
   return revoked;
  });
 }
 public VisitModels.ArrivalPage unitArrivals(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of(),context->{
   requireMember(tenant,unit,"decide");
   return store.unitArrivals(tenant,unit);
  });
 }
 /** Resposta da unidade pelo aplicativo: vale só a primeira, dentro do prazo; depois disso, o estado final. */
 public VisitModels.Arrival decide(UUID actor,UUID tenant,UUID arrivalId,VisitModels.Decision request) {
  if(arrivalId==null) throw arrivalNotFound();
  String state=decision(request==null?null:request.decision());
  return execute(actor,tenant,AssuranceLevel.AAL1,Map.of("23514",VisitService::arrivalClosed),context->{
   var current=store.arrival(tenant,arrivalId);
   if(current==null || !store.member(tenant,current.unitId(),"decide")) throw arrivalNotFound();
   requireWaiting(current);
   if(!store.decide(tenant,arrivalId,state,actor,"APP",null)) throw closed(store.arrival(tenant,arrivalId));
   return decided(tenant,actor,arrivalId,null);
  });
 }

 // Portaria: visitas previstas, chegadas e confirmação assistida.
 public VisitModels.PermitPage expected(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unit,String visitor) {
  String filter=visitor==null || DisplayText.collapse(visitor).isEmpty()?null
   :text(visitor,80,"VISIT_FILTER_INVALID","Use até 80 caracteres visíveis na busca por nome.");
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.expected(tenant,unit,filter);
  });
 }
 public VisitModels.ArrivalPage queue(UUID actor,UUID tenant,AssuranceLevel assurance,String state) {
  if(state!=null && !QUEUE_STATES.contains(state)) throw invalid("ARRIVAL_FILTER_INVALID","Situação de chegada inválida.");
  return execute(actor,tenant,assurance,Map.of(),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   return store.queue(tenant,state);
  });
 }
 /** Pessoa aguardando na portaria: a unidade decide em até 5 minutos; sem resposta, nada é liberado automaticamente. */
 public VisitModels.ArrivalMutation register(UUID actor,UUID tenant,AssuranceLevel assurance,String key,VisitModels.RegisterArrival request) {
  requireKey(key);
  if(request==null || request.unitId()==null) throw invalid("ARRIVAL_UNIT_REQUIRED","Informe a unidade de destino.");
  String visitor=visitorName(request.visitorName());
  String document=document(request.documentLast4());
  String hash=VisitRequestHash.arrival(request.unitId(),visitor,document,request.permitId());
  String route="/v1/tenants/"+tenant+"/arrivals";
  return execute(actor,tenant,assurance,Map.of("23503",VisitService::unitUnavailable,"23514",VisitService::permitInvalid),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.arrival(tenant,previous.resource());
    if(replay==null) throw arrivalNotFound();
    return new VisitModels.ArrivalMutation(replay,true,previous.responseStatus());
   }
   if(!store.activeUnit(tenant,request.unitId())) throw unitUnavailable();
   if(request.permitId()!=null) {
    var permit=store.permit(tenant,request.permitId(),false);
    if(permit==null || !permit.unitId().equals(request.unitId())) throw permitInvalid();
   }
   UUID visitorId=UUID.randomUUID(), id=UUID.randomUUID();
   store.insertVisitor(visitorId,tenant,visitor,document,actor);
   store.insertArrival(id,tenant,request.unitId(),visitorId,request.permitId(),actor);
   var arrival=store.arrival(tenant,id);
   if(arrival==null) throw new IllegalStateException("Chegada registrada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"ARRIVAL_REQUESTED","ARRIVAL",id,null);
   store.event(tenant,"ARRIVAL_REQUESTED",id,arrival.version(),arrival.unitId(),arrival.state());
   store.finish(tenant,actor,route,key,id,201);
   return new VisitModels.ArrivalMutation(arrival,false,201);
  });
 }
 /** Confirmação assistida (interfone ou presencial) registrada pela portaria, com a origem anotada; dentro do prazo. */
 public VisitModels.Arrival assistedDecision(UUID actor,UUID tenant,AssuranceLevel assurance,UUID arrivalId,VisitModels.AssistedDecision request) {
  if(arrivalId==null) throw arrivalNotFound();
  String state=decision(request==null?null:request.decision());
  String source=request.source();
  if(source==null || !ASSISTED_SOURCES.contains(source))
   throw invalid("ARRIVAL_SOURCE_INVALID","Informe se a confirmação foi pelo interfone ou presencial.");
  String note=text(request.note(),300,"ARRIVAL_NOTE_REQUIRED","Anote quem confirmou e como, com até 300 caracteres.");
  return execute(actor,tenant,assurance,Map.of("23514",VisitService::arrivalClosed),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var current=store.arrival(tenant,arrivalId);
   if(current==null) throw arrivalNotFound();
   requireWaiting(current);
   if(!store.decide(tenant,arrivalId,state,actor,source,note)) throw closed(store.arrival(tenant,arrivalId));
   return decided(tenant,actor,arrivalId,note);
  });
 }
 /** Encerramento pela portaria da chegada ainda aguardando, inclusive vencida (pessoa foi embora, registro errado). */
 public VisitModels.Arrival cancel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID arrivalId,VisitModels.CancelArrival request) {
  if(arrivalId==null) throw arrivalNotFound();
  String reason=text(request==null?null:request.reason(),300,"ARRIVAL_CANCEL_REASON_REQUIRED","Informe o motivo do encerramento, com até 300 caracteres.");
  return execute(actor,tenant,assurance,Map.of("23514",VisitService::arrivalClosed),context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE);
   var current=store.arrival(tenant,arrivalId);
   if(current==null) throw arrivalNotFound();
   if(!"WAITING".equals(current.state()) && !"EXPIRED".equals(current.state())) throw closed(current);
   if(!store.cancel(tenant,arrivalId,actor,reason)) throw closed(store.arrival(tenant,arrivalId));
   var cancelled=store.arrival(tenant,arrivalId);
   if(cancelled==null) throw new IllegalStateException("Chegada encerrada não foi encontrada na mesma transação.");
   audit.record(tenant,actor,"ARRIVAL_CANCELLED","ARRIVAL",arrivalId,reason);
   store.event(tenant,"ARRIVAL_CANCELLED",arrivalId,cancelled.version(),cancelled.unitId(),cancelled.state());
   return cancelled;
  });
 }

 private VisitModels.Arrival decided(UUID tenant,UUID actor,UUID arrivalId,String note) {
  var result=store.arrival(tenant,arrivalId);
  if(result==null) throw new IllegalStateException("Chegada decidida não foi encontrada na mesma transação.");
  audit.record(tenant,actor,"ARRIVAL_DECIDED","ARRIVAL",arrivalId,note);
  store.event(tenant,"ARRIVAL_DECIDED",arrivalId,result.version(),result.unitId(),result.state());
  return result;
 }
 private void requireMember(UUID tenant,UUID unit,String dependentPermission) {
  if(unit==null || !store.member(tenant,unit,dependentPermission)) throw unitDenied();
 }
 private static void requireWaiting(VisitModels.Arrival arrival) {
  if(!"WAITING".equals(arrival.state())) throw closed(arrival);
 }
 /** Estado final da chegada: "expirada" depois do prazo, "já decidida" ou "encerrada" pela portaria. */
 private static BusinessProblem closed(VisitModels.Arrival arrival) {
  if(arrival==null) return arrivalNotFound();
  return switch(arrival.state()) {
   case "EXPIRED" -> new BusinessProblem("ARRIVAL_EXPIRED",409,"O prazo de resposta desta chegada terminou. Siga o procedimento da portaria.");
   case "CANCELLED" -> new BusinessProblem("ARRIVAL_CANCELLED",409,"Esta chegada foi encerrada pela portaria.");
   default -> new BusinessProblem("ARRIVAL_ALREADY_DECIDED",409,"Esta chegada já foi respondida.");
  };
 }
 private static String decision(String value) {
  if("APPROVE".equals(value)) return "APPROVED";
  if("DENY".equals(value)) return "DENIED";
  throw invalid("ARRIVAL_DECISION_INVALID","Responda autorizando ou recusando a entrada.");
 }
 private static String visitorName(String value) {
  String name=text(value,160,"VISITOR_NAME_INVALID","Informe o nome do visitante, de 2 a 160 caracteres.");
  if(name.codePointCount(0,name.length())<2) throw invalid("VISITOR_NAME_INVALID","Informe o nome do visitante, de 2 a 160 caracteres.");
  return name;
 }
 /** Só o final do documento, quando a portaria precisar conferir: 2 a 4 letras ou números. */
 private static String document(String value) {
  if(value==null || value.isBlank()) return null;
  String compact=value.strip().toUpperCase(java.util.Locale.ROOT);
  if(!compact.matches("[0-9A-Z]{2,4}")) throw invalid("VISITOR_DOCUMENT_INVALID","Informe só o final do documento, de 2 a 4 letras ou números.");
  return compact;
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
   if("42501".equals(state)) throw new BusinessProblem("VISIT_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("VISIT_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem unitDenied() { return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação."); }
 private static BusinessProblem unitUnavailable() { return new BusinessProblem("ARRIVAL_UNIT_UNAVAILABLE",404,"Unidade não disponível para a chegada."); }
 private static BusinessProblem permitNotFound() { return new BusinessProblem("VISIT_PERMIT_NOT_FOUND",404,"Convite não encontrado."); }
 private static BusinessProblem permitChanged() {
  return new BusinessProblem("VISIT_PERMIT_CHANGED",409,"O convite foi alterado. Atualize os convites antes de continuar.");
 }
 private static BusinessProblem permitInvalid() {
  return new BusinessProblem("ARRIVAL_PERMIT_INVALID",409,"O convite informado não é desta unidade. Confira a visita prevista.");
 }
 private static BusinessProblem arrivalNotFound() { return new BusinessProblem("ARRIVAL_NOT_FOUND",404,"Chegada não encontrada."); }
 private static BusinessProblem arrivalClosed() {
  return new BusinessProblem("ARRIVAL_ALREADY_DECIDED",409,"Esta chegada já foi respondida ou o prazo terminou. Atualize a lista.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
