package br.com.condpulse.database;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.requests.application.RequestService;
import br.com.condpulse.requests.infrastructure.RequestStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V017 com PostgreSQL real: a conversa fica no pedido certo, pedido de outra unidade ou condomínio é "não encontrado"
 * mesmo com o ID conhecido, comentário interno nunca chega à unidade e a resolução permanece na conversa.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class RequestJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static RequestService requests;
 private static UUID manager,concierge,responsible;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  requests=new RequestService(new TenantTransactionExecutor(db.jdbc,db.manager),new RequestStore(db.jdbc),new AuditRecorder(db.jdbc));
  manager=UUID.randomUUID(); concierge=UUID.randomUUID(); responsible=UUID.randomUUID();
  for(UUID account:List.of(manager,concierge,responsible)) db.extraAccounts.add(account);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE'),(?,'ACTIVE')",manager,concierge,responsible);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values
    (?,?,null,'GESTAO PEDIDOS JDBC','MANAGER','ACTIVE'),(?,?,null,'PORTARIA PEDIDOS JDBC','CONCIERGE','ACTIVE'),
    (?,?,?,'RESPONSAVEL JDBC A','RESPONSIBLE','ACTIVE')
   """,db.tenantA,manager,db.tenantA,concierge,db.tenantA,responsible,db.unitA);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "request-jdbc-"+UUID.randomUUID(); }
 private static RequestModels.UnitRequest open(UUID account,String title) {
  return requests.open(account,db.tenantA,db.unitA,key(),new RequestModels.Open("MAINTENANCE",title,"Água no teto do banheiro.")).request();
 }
 private static RequestModels.Message unitSays(UUID account,UUID request,String body) {
  return requests.unitMessage(account,db.tenantA,request,key(),new RequestModels.UnitMessage(body)).message();
 }
 private static RequestModels.Message staffSays(UUID request,String body,String visibility) {
  return requests.staffMessage(manager,db.tenantA,AAL2,request,key(),new RequestModels.StaffMessage(body,visibility)).message();
 }
 private static RequestModels.ManagedRequest update(UUID request,String state,UUID assignee,LocalDate due,String resolution,long version) {
  return requests.update(manager,db.tenantA,AAL2,request,new RequestModels.Update(state,assignee,due,resolution,version));
 }
 @FunctionalInterface private interface Call { Object run() throws Exception; }
 private static String code(Call action) {
  return assertThrows(BusinessProblem.class,action::run).code();
 }
 private static UUID membership(UUID account) throws Exception {
  return UUID.fromString(db.adminString("select id from cp.memberships where tenant_id=? and account_id=?",db.tenantA,account));
 }

 @Test void conversationStaysInItsRequestAndKnownIdsDoNotCrossUnitsOrTenants() throws Exception {
  String key=key();
  var payload=new RequestModels.Open("MAINTENANCE","  Vazamento   no banheiro ","Água no teto.\r\n\r\n\r\nDesde ontem.\n");
  var first=requests.open(db.accountA,db.tenantA,db.unitA,key,payload);
  var replay=requests.open(db.accountA,db.tenantA,db.unitA,key,payload);
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(first.request(),replay.request());
  var created=first.request();
  assertEquals("Vazamento no banheiro",created.title()); assertEquals("Água no teto.\n\nDesde ontem.",created.description());
  assertEquals("OPEN",created.state()); assertEquals("PESSOA FICTICIA JDBC A",created.requesterName()); assertTrue(created.openedByMe());
  assertEquals("1",db.adminString("select count(*) from cp.service_requests where tenant_id=?",db.tenantA),"Repetição não duplica o pedido");
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='REQUEST_OPENED'",created.requestId()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='REQUEST_OPENED' and actor_id=?",
   created.requestId(),db.accountA));
  // O outro adulto da unidade acompanha; outra unidade e outro condomínio não alcançam nem pelo ID.
  var seen=requests.unitRequest(responsible,db.tenantA,created.requestId());
  assertFalse(seen.openedByMe()); assertEquals("PESSOA FICTICIA JDBC A",seen.requesterName());
  assertEquals(List.of(created.requestId()),requests.unitRequests(responsible,db.tenantA,db.unitA).requests().stream()
   .map(RequestModels.UnitSummary::requestId).toList());
  assertEquals("UNIT_ACCESS_DENIED",code(()->requests.unitRequests(db.accountC,db.tenantA,db.unitA)));
  assertEquals("REQUEST_NOT_FOUND",code(()->requests.unitRequest(db.accountC,db.tenantA,created.requestId())));
  assertEquals("REQUEST_NOT_FOUND",code(()->unitSays(db.accountC,created.requestId(),"Mensagem de outra unidade")));
  assertEquals("REQUEST_NOT_FOUND",code(()->requests.unitRequest(db.accountB,db.tenantB,created.requestId())));
  assertEquals("UNIT_ACCESS_DENIED",code(()->requests.open(db.accountC,db.tenantA,db.unitA,key(),payload)),"Outra unidade não abre pela unidade");
  // A conversa fica no pedido em que foi gravada.
  var other=open(db.accountA,"Lâmpada queimada no hall");
  var said=unitSays(db.accountA,created.requestId(),"Pode vir à tarde.");
  assertEquals("UNIT",said.authorRole()); assertEquals("PESSOA FICTICIA JDBC A",said.authorName()); assertTrue(said.mine());
  assertEquals(List.of(said.messageId()),requests.unitRequest(responsible,db.tenantA,created.requestId()).messages().stream()
   .map(RequestModels.Message::messageId).toList());
  assertTrue(requests.unitRequest(db.accountA,db.tenantA,other.requestId()).messages().isEmpty(),"Mensagem não aparece em outro pedido");
  assertEquals("1",db.adminString("select count(*) from cp.request_messages where request_id=?",created.requestId()));
  assertEquals("0",db.adminString("select count(*) from cp.request_messages where request_id=?",other.requestId()));
  assertEquals("REQUEST_TITLE_INVALID",code(()->open(db.accountA,"Oi")));
  assertEquals("REQUEST_CATEGORY_INVALID",code(()->requests.open(db.accountA,db.tenantA,db.unitA,key(),
   new RequestModels.Open("PLUMBING","Categoria inexistente","Texto."))));
  assertEquals("REQUEST_DESCRIPTION_INVALID",code(()->requests.open(db.accountA,db.tenantA,db.unitA,key(),
   new RequestModels.Open("OTHER","Texto invisível","Texto​escondido"))));
  db.assertEmptyContext();
 }

 @Test void staffTriagesCommentsInternallyAndTheResolutionStaysInTheConversation() throws Exception {
  var request=open(db.accountA,"Portão da garagem fazendo barulho");
  var listed=requests.managed(manager,db.tenantA,AAL2,null).requests();
  assertEquals(List.of(request.requestId()),listed.stream().map(RequestModels.ManagedSummary::requestId).toList());
  assertEquals("JDBC A",listed.getFirst().unitLabel());
  var detail=requests.managedRequest(manager,db.tenantA,AAL2,request.requestId());
  assertEquals("PESSOA FICTICIA JDBC A",detail.requesterName());
  assertTrue(detail.team().stream().map(RequestModels.TeamMember::name).toList().containsAll(List.of("GESTAO PEDIDOS JDBC","PORTARIA PEDIDOS JDBC")));
  assertTrue(detail.team().stream().noneMatch(member->member.name().startsWith("PESSOA FICTICIA")),"Equipe sem vínculos residenciais");
  // Comentário interno nunca chega à unidade; mensagem visível chega sem o nome da pessoa da equipe.
  var internal=staffSays(request.requestId(),"Chamar a empresa do portão.","INTERNAL");
  var visible=staffSays(request.requestId(),"Vamos verificar amanhã.","RESIDENT");
  assertEquals("GESTAO PEDIDOS JDBC",internal.authorName());
  var unitView=requests.unitRequest(db.accountA,db.tenantA,request.requestId()).messages();
  assertEquals(List.of(visible.messageId()),unitView.stream().map(RequestModels.Message::messageId).toList());
  assertNull(unitView.getFirst().authorName()); assertEquals("STAFF",unitView.getFirst().authorRole());
  assertEquals(2,requests.managedRequest(manager,db.tenantA,AAL2,request.requestId()).messages().size());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='REQUEST_MESSAGE_ADDED'",request.requestId()),
   "Comentário interno não gera evento para a unidade");
  // Triagem com responsável da equipe e prazo comunicado, pela versão lida.
  LocalDate today=LocalDate.now(ZoneId.of("America/Sao_Paulo"));
  UUID porter=membership(concierge);
  assertEquals("REQUEST_ASSIGNEE_INVALID",code(()->update(request.requestId(),"TRIAGED",membership(db.accountA),null,null,0)));
  assertEquals("REQUEST_DUE_INVALID",code(()->update(request.requestId(),"TRIAGED",porter,today.minusDays(1),null,0)));
  assertEquals("REQUEST_TRANSITION_INVALID",code(()->update(request.requestId(),"RESOLVED",porter,null,"Resolvido",0)));
  var triaged=update(request.requestId(),"TRIAGED",porter,today.plusDays(3),null,0);
  assertEquals("TRIAGED",triaged.state()); assertEquals(1,triaged.version());
  assertEquals("PORTARIA PEDIDOS JDBC",triaged.assigneeName()); assertEquals(today.plusDays(3),triaged.dueOn());
  var resident=requests.unitRequest(db.accountA,db.tenantA,request.requestId());
  assertTrue(resident.assigned()); assertEquals(today.plusDays(3),resident.dueOn());
  assertEquals("REQUEST_CHANGED",code(()->update(request.requestId(),"IN_PROGRESS",porter,today.plusDays(3),null,0)),"Versão lida");
  assertEquals("REQUEST_UNCHANGED",code(()->update(request.requestId(),"TRIAGED",porter,today.plusDays(3),null,1)));
  assertEquals("REQUEST_RESOLUTION_REQUIRED",code(()->update(request.requestId(),"RESOLVED",porter,today.plusDays(3),null,1)));
  var resolved=update(request.requestId(),"RESOLVED",porter,today.plusDays(3),"Roldana trocada.",1);
  assertEquals("RESOLVED",resolved.state()); assertEquals("Roldana trocada.",resolved.resolution()); assertNotNull(resolved.resolvedAt());
  assertTrue(requests.unitRequest(db.accountA,db.tenantA,request.requestId()).messages().stream()
   .anyMatch(message->"Resolução: Roldana trocada.".equals(message.body())),"Resolução entra na conversa visível");
  // Reaberto perde a resolução vigente, que continua na conversa.
  var reopened=update(request.requestId(),"IN_PROGRESS",porter,today.plusDays(3),null,2);
  assertNull(reopened.resolution()); assertNull(reopened.resolvedAt());
  assertTrue(reopened.messages().stream().anyMatch(message->"Resolução: Roldana trocada.".equals(message.body())));
  // Encerrado preserva o histórico e encerra a conversa.
  var closed=update(request.requestId(),"CLOSED",porter,today.plusDays(3),null,3);
  assertEquals("CLOSED",closed.state()); assertNotNull(closed.closedAt());
  assertEquals("REQUEST_CLOSED",code(()->unitSays(db.accountA,request.requestId(),"Ainda faz barulho.")));
  assertEquals("REQUEST_CLOSED",code(()->staffSays(request.requestId(),"Mais uma mensagem.","RESIDENT")));
  assertEquals("REQUEST_CLOSED",code(()->update(request.requestId(),"IN_PROGRESS",porter,null,null,4)));
  assertEquals(2,requests.unitRequest(db.accountA,db.tenantA,request.requestId()).messages().size(),
   "Unidade continua vendo a conversa visível: mensagem e resolução, sem o comentário interno");
  assertEquals(3,requests.managedRequest(manager,db.tenantA,AAL2,request.requestId()).messages().size());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='REQUEST_ASSIGNED'",request.requestId()));
  assertEquals("4",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='REQUEST_STATE_CHANGED' and actor_id=?",
   request.requestId(),manager));
  db.assertEmptyContext();
 }

 @Test void staffOperationsNeedTheCapabilityWithMfaAndMessagesAreIdempotent() throws Exception {
  var request=open(db.accountA,"Barulho no apartamento de cima");
  assertEquals("MFA_REQUIRED",code(()->requests.managed(manager,db.tenantA,AssuranceLevel.AAL1,null)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.managed(db.accountA,db.tenantA,AAL2,null)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.managedRequest(concierge,db.tenantA,AAL2,request.requestId())),"Portaria não gerencia pedidos");
  assertEquals("REQUEST_FILTER_INVALID",code(()->requests.managed(manager,db.tenantA,AAL2,"PENDING")));
  assertEquals(List.of(request.requestId()),requests.managed(manager,db.tenantA,AAL2,"OPEN").requests().stream()
   .map(RequestModels.ManagedSummary::requestId).toList());
  assertTrue(requests.managed(manager,db.tenantA,AAL2,"CLOSED").requests().isEmpty());
  assertEquals("REQUEST_NOT_FOUND",code(()->requests.managedRequest(manager,db.tenantA,AAL2,UUID.randomUUID())));
  String key=key();
  var first=requests.unitMessage(db.accountA,db.tenantA,request.requestId(),key,new RequestModels.UnitMessage("Começou às 22h."));
  var again=requests.unitMessage(db.accountA,db.tenantA,request.requestId(),key,new RequestModels.UnitMessage("Começou às 22h."));
  assertFalse(first.replayed()); assertTrue(again.replayed()); assertEquals(first.message(),again.message());
  assertEquals("IDEMPOTENCY_KEY_REUSED",code(()->requests.unitMessage(db.accountA,db.tenantA,request.requestId(),key,
   new RequestModels.UnitMessage("Outro texto."))));
  assertEquals("1",db.adminString("select count(*) from cp.request_messages where request_id=?",request.requestId()));
  assertEquals("REQUEST_VISIBILITY_INVALID",code(()->staffSays(request.requestId(),"Sem visibilidade.","PUBLIC")));
  assertEquals("REQUEST_MESSAGE_INVALID",code(()->unitSays(db.accountA,request.requestId(),"   ")));
  db.assertEmptyContext();
 }
}
