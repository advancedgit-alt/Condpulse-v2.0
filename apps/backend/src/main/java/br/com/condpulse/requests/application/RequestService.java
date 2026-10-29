package br.com.condpulse.requests.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.requests.infrastructure.RequestStore;
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
 * Pedidos da unidade e conversa vinculada (CP-033, sem anexos). Morador ou responsável com vínculo vigente abre o
 * pedido pela unidade e conversa nele; a gestão (capacidade requests.manage, AAL2) acompanha o condomínio, escreve
 * mensagem visível ou comentário interno, define responsável da equipe e prazo comunicado e muda o andamento pela
 * versão lida. A conversa pertence ao pedido em que foi gravada; pedido de outra unidade ou condomínio, mesmo com o ID
 * conhecido, é "não encontrado". O banco aplica as mesmas regras.
 */
@Service @Profile("api")
public class RequestService {
 static final Set<String> FILTERS=Set.of("ACTIVE","ALL","OPEN","TRIAGED","IN_PROGRESS","WAITING_RESIDENT","RESOLVED","CLOSED");
 private static final Map<String,Set<String>> TRANSITIONS=Map.of(
  "OPEN",Set.of("TRIAGED","IN_PROGRESS","CLOSED"),
  "TRIAGED",Set.of("IN_PROGRESS","WAITING_RESIDENT","RESOLVED","CLOSED"),
  "IN_PROGRESS",Set.of("WAITING_RESIDENT","RESOLVED","CLOSED"),
  "WAITING_RESIDENT",Set.of("IN_PROGRESS","RESOLVED","CLOSED"),
  "RESOLVED",Set.of("IN_PROGRESS","CLOSED"),
  "CLOSED",Set.of());
 private final TenantTransactionExecutor transactions;
 private final RequestStore store;
 private final AuditRecorder audit;
 public RequestService(TenantTransactionExecutor transactions,RequestStore store,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.audit=audit;
 }

 // Unidade.
 public RequestModels.UnitPage unitRequests(UUID actor,UUID tenant,UUID unit) {
  return resident(actor,tenant,Map.of(),context->{
   requireMember(tenant,unit);
   return store.unitRequests(tenant,unit);
  });
 }
 public RequestModels.OpenResult open(UUID actor,UUID tenant,UUID unit,String key,RequestModels.Open request) {
  requireKey(key);
  if(request==null) throw invalid("SERVICE_REQUEST_INPUT_INVALID","Informe a categoria, o título e a descrição do pedido.");
  String category=category(request.category());
  String title=title(request.title());
  String description=multiline(request.description(),2000,"SERVICE_REQUEST_DESCRIPTION_INVALID",
   "Descreva o pedido com até 2.000 caracteres, sem caracteres invisíveis.");
  String hash=hash("SERVICE_REQUEST_OPEN",category,title,description);
  String route="/v1/tenants/"+tenant+"/units/"+unit+"/requests";
  return resident(actor,tenant,Map.of("42501",RequestService::unitDenied),context->{
   requireMember(tenant,unit);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.unitRequest(tenant,previous.resource());
    if(replay==null) throw notFound();
    return new RequestModels.OpenResult(replay,true,previous.responseStatus());
   }
   UUID id=UUID.randomUUID();
   store.insert(id,tenant,unit,actor,category,title,description);
   audit.record(tenant,actor,"REQUEST_OPENED","SERVICE_REQUEST",id,null);
   store.event(tenant,"REQUEST_OPENED",id,0,unit,null);
   store.finish(tenant,actor,route,key,id,201);
   return new RequestModels.OpenResult(require(store.unitRequest(tenant,id)),false,201);
  });
 }
 /** Detalhe para a unidade: só com o vínculo vigente na unidade do pedido; senão, não encontrado. */
 public RequestModels.UnitRequest unitRequest(UUID actor,UUID tenant,UUID requestId) {
  if(requestId==null) throw notFound();
  return resident(actor,tenant,Map.of(),context->{
   var found=store.unitRequest(tenant,requestId);
   if(found==null) throw notFound();
   return found;
  });
 }
 public RequestModels.MessageResult unitMessage(UUID actor,UUID tenant,UUID requestId,String key,RequestModels.UnitMessage request) {
  if(requestId==null) throw notFound();
  requireKey(key);
  String body=body(request==null?null:request.body());
  String route="/v1/tenants/"+tenant+"/requests/"+requestId+"/messages";
  return resident(actor,tenant,Map.of("23514",RequestService::closed,"42501",RequestService::notFound),context->{
   var previous=store.begin(tenant,actor,route,key,hash("SERVICE_REQUEST_UNIT_MESSAGE",body));
   if(!previous.fresh()) return replayedMessage(tenant,previous,false);
   var found=store.unitRequest(tenant,requestId);
   if(found==null) throw notFound();
   if("CLOSED".equals(found.state())) throw closed();
   UUID id=UUID.randomUUID();
   var message=store.insertMessage(id,tenant,requestId,actor,"UNIT","RESIDENT",body,false);
   store.event(tenant,"REQUEST_MESSAGE_ADDED",requestId,versionOf(tenant,requestId),found.unitId(),id);
   store.finish(tenant,actor,route,key,id,201);
   return new RequestModels.MessageResult(requireMessage(message),false,201);
  });
 }

 // Gestão.
 public RequestModels.ManagedPage managed(UUID actor,UUID tenant,AssuranceLevel assurance,String filter) {
  String normalized=filter==null || filter.isBlank()?"ACTIVE":filter;
  if(!FILTERS.contains(normalized)) throw invalid("SERVICE_REQUEST_FILTER_INVALID","Filtro de andamento inválido.");
  return staff(actor,tenant,assurance,Map.of(),context->store.managed(tenant,normalized));
 }
 public RequestModels.ManagedRequest managedRequest(UUID actor,UUID tenant,AssuranceLevel assurance,UUID requestId) {
  if(requestId==null) throw notFound();
  return staff(actor,tenant,assurance,Map.of(),context->{
   var found=store.managedRequest(tenant,requestId);
   if(found==null) throw notFound();
   return found;
  });
 }
 public RequestModels.MessageResult staffMessage(UUID actor,UUID tenant,AssuranceLevel assurance,UUID requestId,String key,
     RequestModels.StaffMessage request) {
  if(requestId==null) throw notFound();
  requireKey(key);
  String body=body(request==null?null:request.body());
  String visibility=request==null?null:request.visibility();
  if(!"RESIDENT".equals(visibility) && !"INTERNAL".equals(visibility))
   throw invalid("SERVICE_REQUEST_VISIBILITY_INVALID","Escolha se a mensagem é visível para a unidade ou comentário interno.");
  String route="/v1/tenants/"+tenant+"/management/requests/"+requestId+"/messages";
  return staff(actor,tenant,assurance,Map.of("23514",RequestService::closed),context->{
   var previous=store.begin(tenant,actor,route,key,hash("SERVICE_REQUEST_STAFF_MESSAGE",visibility,body));
   if(!previous.fresh()) return replayedMessage(tenant,previous,true);
   var head=store.head(tenant,requestId);
   if(head==null) throw notFound();
   if("CLOSED".equals(head.state())) throw closed();
   UUID id=UUID.randomUUID();
   var message=store.insertMessage(id,tenant,requestId,actor,"STAFF",visibility,body,true);
   // Comentário interno não gera evento para a unidade.
   if("RESIDENT".equals(visibility)) store.event(tenant,"REQUEST_MESSAGE_ADDED",requestId,head.version(),head.unitId(),id);
   store.finish(tenant,actor,route,key,id,201);
   return new RequestModels.MessageResult(requireMessage(message),false,201);
  });
 }
 /**
  * Andamento, responsável, prazo e resolução pela versão lida. Ao concluir, a resolução também entra na conversa como
  * mensagem visível da equipe: reaberto o pedido, a resolução vigente sai do pedido, mas continua no histórico.
  */
 public RequestModels.ManagedRequest update(UUID actor,UUID tenant,AssuranceLevel assurance,UUID requestId,RequestModels.Update request) {
  if(requestId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("SERVICE_REQUEST_VERSION_REQUIRED","Atualize o pedido antes de salvar.");
  String state=request.state();
  if(state==null || !RequestModels.STATES.contains(state)) throw invalid("SERVICE_REQUEST_STATE_INVALID","Escolha um andamento válido.");
  String resolution=request.resolution()==null || DisplayText.collapse(request.resolution()).isEmpty()?null
   :multiline(request.resolution(),1000,"SERVICE_REQUEST_RESOLUTION_INVALID","Use até 1.000 caracteres na resolução, sem caracteres invisíveis.");
  return staff(actor,tenant,assurance,Map.of("23514",RequestService::changed,"23503",RequestService::assigneeInvalid),context->{
   var head=store.head(tenant,requestId);
   if(head==null) throw notFound();
   if("CLOSED".equals(head.state())) throw closed();
   if(head.version()!=request.expectedVersion()) throw changed();
   boolean stateChanged=!state.equals(head.state());
   if(stateChanged && !TRANSITIONS.get(head.state()).contains(state))
    throw new BusinessProblem("SERVICE_REQUEST_TRANSITION_INVALID",409,"Este andamento não pode ir para o escolhido. Atualize o pedido.");
   boolean resolving=stateChanged && "RESOLVED".equals(state);
   if(resolving && resolution==null)
    throw invalid("SERVICE_REQUEST_RESOLUTION_REQUIRED","Informe a resolução para concluir o pedido.");
   // Fora da conclusão a resolução enviada não vale: resolvido mantém a registrada; os outros andamentos não têm.
   String nextResolution=resolving?resolution:"RESOLVED".equals(state)?head.resolution():null;
   UUID assignee=request.assigneeMembershipId();
   if(assignee!=null && !Objects.equals(assignee,head.assigneeMembershipId())
     && store.team(tenant).stream().noneMatch(member->member.membershipId().equals(assignee))) throw assigneeInvalid();
   LocalDate due=request.dueOn();
   if(due!=null && !due.equals(head.dueOn())) {
    LocalDate today=LocalDate.now(context.timeZone());
    if(due.isBefore(today) || due.isAfter(today.plusYears(1)))
     throw invalid("SERVICE_REQUEST_DUE_INVALID","O prazo comunicado vai de hoje até um ano.");
   }
   boolean assignmentChanged=!Objects.equals(assignee,head.assigneeMembershipId()) || !Objects.equals(due,head.dueOn());
   if(!stateChanged && !assignmentChanged) throw invalid("SERVICE_REQUEST_UNCHANGED","Nada mudou no pedido.");
   if(!store.update(tenant,requestId,head.version(),state,assignee,due,nextResolution)) throw changed();
   long version=head.version()+1;
   if(stateChanged) audit.record(tenant,actor,"REQUEST_STATE_CHANGED","SERVICE_REQUEST",requestId,null);
   if(assignmentChanged) audit.record(tenant,actor,"REQUEST_ASSIGNED","SERVICE_REQUEST",requestId,null);
   if(resolving) store.insertMessage(UUID.randomUUID(),tenant,requestId,actor,"STAFF","RESIDENT","Resolução: "+resolution,true);
   store.event(tenant,"REQUEST_UPDATED",requestId,version,head.unitId(),null);
   return require(store.managedRequest(tenant,requestId));
  });
 }

 private long versionOf(UUID tenant,UUID requestId) {
  var head=store.head(tenant,requestId);
  if(head==null) throw notFound();
  return head.version();
 }
 private RequestModels.MessageResult replayedMessage(UUID tenant,RequestStore.IdempotentReply previous,boolean staffView) {
  var message=previous.resource()==null?null:store.message(tenant,previous.resource(),staffView);
  if(message==null) throw notFound();
  return new RequestModels.MessageResult(message,true,previous.responseStatus());
 }
 private void requireMember(UUID tenant,UUID unit) {
  if(unit==null || !store.unitMember(tenant,unit)) throw unitDenied();
 }
 private static String category(String value) {
  if(value==null || !RequestModels.CATEGORIES.contains(value)) throw invalid("SERVICE_REQUEST_CATEGORY_INVALID","Escolha uma categoria válida.");
  return value;
 }
 private static String title(String value) {
  String normalized=DisplayText.normalize(value,120);
  if(normalized==null || normalized.codePointCount(0,normalized.length())<3)
   throw invalid("SERVICE_REQUEST_TITLE_INVALID","Informe o título do pedido, de 3 a 120 caracteres.");
  return normalized;
 }
 private static String body(String value) {
  return multiline(value,2000,"SERVICE_REQUEST_MESSAGE_INVALID","Escreva a mensagem com até 2.000 caracteres, sem caracteres invisíveis.");
 }
 private static String multiline(String value,int maximum,String code,String message) {
  String normalized=DisplayText.normalizeMultiline(value,maximum);
  if(normalized==null) throw invalid(code,message);
  return normalized;
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 /** Hash dos campos normalizados com o tamanho de cada um: repetir com a mesma chave e os mesmos dados devolve o mesmo resultado. */
 static String hash(String kind,String...fields) {
  var value=new StringBuilder(kind);
  for(String field:fields) value.append('|').append(field.length()).append(':').append(field);
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
 private static RequestModels.UnitRequest require(RequestModels.UnitRequest value) {
  if(value==null) throw new IllegalStateException("Pedido gravado não foi encontrado na mesma transação.");
  return value;
 }
 private static RequestModels.ManagedRequest require(RequestModels.ManagedRequest value) {
  if(value==null) throw new IllegalStateException("Pedido gravado não foi encontrado na mesma transação.");
  return value;
 }
 private static RequestModels.Message requireMessage(RequestModels.Message value) {
  if(value==null) throw new IllegalStateException("Mensagem gravada não foi encontrada na mesma transação.");
  return value;
 }
 private <T> T staff(UUID actor,UUID tenant,AssuranceLevel assurance,Map<String,Supplier<BusinessProblem>> conflicts,Function<TenantContext,T> action) {
  return execute(actor,tenant,assurance,conflicts,context->{
   context.requireCapability(StaffCapability.MANAGE_REQUESTS);
   return action.apply(context);
  });
 }
 /** Unidade em AAL1: nenhuma capacidade de equipe é publicada, e o banco aplica só o vínculo da unidade. */
 private <T> T resident(UUID actor,UUID tenant,Map<String,Supplier<BusinessProblem>> conflicts,Function<TenantContext,T> action) {
  return execute(actor,tenant,AssuranceLevel.AAL1,conflicts,action);
 }
 /**
  * Constraints, gatilhos e políticas abortam a transação; traduzir somente depois do rollback do executor. Prazo da
  * transação esgotado é resultado incerto (503).
  */
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Map<String,Supplier<BusinessProblem>> conflicts,Function<TenantContext,T> action) {
  try {
   return transactions.execute(actor,tenant,assurance,action);
  } catch(DataAccessException error) {
   String state=SqlStates.of(error);
   var mapped=state==null?null:conflicts.get(state);
   if(mapped!=null) throw mapped.get();
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw unavailable();
  } catch(TransactionException error) {
   throw unavailable();
  }
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("SERVICE_REQUEST_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
 }
 private static BusinessProblem notFound() { return new BusinessProblem("SERVICE_REQUEST_NOT_FOUND",404,"Pedido não encontrado."); }
 private static BusinessProblem unitDenied() {
  return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }
 private static BusinessProblem closed() {
  return new BusinessProblem("SERVICE_REQUEST_CLOSED",409,"O pedido foi encerrado; abra um novo pedido se precisar.");
 }
 private static BusinessProblem changed() {
  return new BusinessProblem("SERVICE_REQUEST_CHANGED",409,"O pedido foi alterado. Atualize antes de continuar.");
 }
 private static BusinessProblem assigneeInvalid() {
  return new BusinessProblem("SERVICE_REQUEST_ASSIGNEE_INVALID",400,"Escolha como responsável alguém da equipe com vínculo vigente.");
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
}
