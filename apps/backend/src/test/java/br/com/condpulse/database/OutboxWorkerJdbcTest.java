package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.notifications.application.InboxEffect;
import br.com.condpulse.notifications.application.NotificationService;
import br.com.condpulse.notifications.application.OutboxEffect;
import br.com.condpulse.notifications.application.OutboxModels;
import br.com.condpulse.notifications.application.OutboxWorker;
import br.com.condpulse.notifications.application.RetryPolicy;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.notifications.infrastructure.InboxStore;
import br.com.condpulse.notifications.infrastructure.NotificationStore;
import br.com.condpulse.notifications.infrastructure.OutboxStore;
import br.com.condpulse.moves.application.MoveModels;
import br.com.condpulse.moves.application.MoveService;
import br.com.condpulse.moves.infrastructure.MoveStore;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
import br.com.condpulse.parcels.infrastructure.ParcelStore;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.requests.application.RequestService;
import br.com.condpulse.requests.infrastructure.RequestStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V015 com PostgreSQL real e login próprio do worker (cp_ci_worker, só cp_worker): arrendamento, efeito e conclusão na
 * mesma transação, reprocessamento sem duplicar (AC-049), backoff, falha visível, efeito não idempotente sem nova
 * tentativa cega e caixa interna lida/aberta pela API (cp_ci_login).
 */
@EnabledIfEnvironmentVariable(named="CP_TEST_WORKER_DB_USER",matches="cp_ci_worker")
class OutboxWorkerJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;
 private static ParcelService parcels;
 private static VisitService visits;
 private static NotificationService inbox;
 private static WorkerTransactions transactions;
 private static OutboxStore outbox;
 private static InboxStore inboxStore;
 private static OutboxWorker worker;
 private static RequestService requests;
 private static MoveService moves;
 private static UUID concierge,dependent,manager;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  workerDb=new JdbcWorkerPool();
  var api=new TenantTransactionExecutor(db.jdbc,db.manager);
  parcels=new ParcelService(api,new ParcelStore(db.jdbc),new AuditRecorder(db.jdbc));
  visits=new VisitService(api,new VisitStore(db.jdbc),new AuditRecorder(db.jdbc));
  inbox=new NotificationService(api,new NotificationStore(db.jdbc));
  transactions=new WorkerTransactions(workerDb.jdbc,workerDb.manager);
  outbox=new OutboxStore(workerDb.jdbc);
  inboxStore=new InboxStore(workerDb.jdbc);
  worker=new OutboxWorker(transactions,outbox,List.of(new InboxEffect(inboxStore)));
  requests=new RequestService(api,new RequestStore(db.jdbc),new AuditRecorder(db.jdbc));
  moves=new MoveService(api,new MoveStore(db.jdbc),new AuditRecorder(db.jdbc));
  manager=UUID.randomUUID(); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO WORKER JDBC','MANAGER','ACTIVE')",
   db.tenantA,manager);
  concierge=UUID.randomUUID(); dependent=UUID.randomUUID();
  db.extraAccounts.add(concierge); db.extraAccounts.add(dependent);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",concierge,dependent);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA WORKER JDBC','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,'DEPENDENTE WORKER JDBC','DEPENDENT','ACTIVE')",
   db.tenantA,dependent,db.unitA);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearReservationFixtures();
  db.adminUpdate("update cp.memberships set permissions='{}'::jsonb where tenant_id=? and account_id=?",db.tenantA,dependent);
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }

 private static String key() { return "worker-jdbc-"+UUID.randomUUID(); }
 private ParcelModels.Parcel receive(UUID unit) {
  return parcels.receive(concierge,db.tenantA,AAL2,key(),new ParcelModels.Receive(unit,1,"Caixa fictícia","Armário fictício",null)).parcel();
 }
 private static String task(UUID aggregate,String type) throws Exception {
  return db.adminString("select state||':'||attempts from cp.outbox where tenant_id=? and aggregate_id=? and event_type=?",db.tenantA,aggregate,type);
 }
 private static long notices(UUID account) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.notifications where tenant_id=? and account_id=?",db.tenantA,account));
 }
 private static UUID adminEvent(String type,UUID aggregate,String payload) throws Exception {
  UUID id=UUID.randomUUID();
  db.adminUpdate("insert into cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload) values(?,?,?,?,0,?::jsonb)",
   id,db.tenantA,type,aggregate,payload);
  return id;
 }
 private static List<OutboxModels.LeasedEvent> lease() {
  return transactions.inTenant(db.tenantA,()->outbox.lease(db.tenantA,10,60,RetryPolicy.MAX_ATTEMPTS));
 }
 private static void expireLease(UUID event) throws Exception {
  db.adminUpdateWithoutTriggers("update cp.outbox set lease_until=clock_timestamp()-interval '1 second' where id=?",event);
 }

 @Test void parcelNoticeReachesEligibleResidentsOnceAndOnlyOpeningMarksItRead() throws Exception {
  var parcel=receive(db.unitA);
  assertEquals(1,worker.runTenant(db.tenantA));
  assertEquals("DELIVERED:1",task(parcel.parcelId(),"PARCEL_RECEIVED"));
  assertEquals("true:true:true",db.adminString("""
   select (processed_at is not null)||':'||(lease_until is null)||':'||(last_error is null) from cp.outbox
   where tenant_id=? and aggregate_id=? and event_type='PARCEL_RECEIVED'
   """,db.tenantA,parcel.parcelId()));
  assertEquals(1,notices(db.accountA),"Morador da unidade recebe o aviso");
  assertEquals(0,notices(dependent),"Dependente sem permissão de encomendas não recebe");
  assertEquals(0,notices(db.accountC),"Morador de outra unidade não recebe");
  assertEquals(0,worker.runTenant(db.tenantA),"Tarefa concluída não volta para a fila");
  assertEquals(0,worker.runTenant(db.tenantB),"Outro condomínio não alcança a tarefa");
  workerDb.assertEmptyContext();

  var page=inbox.unit(db.accountA,db.tenantA,db.unitA);
  assertEquals(1,page.items().size()); assertEquals(1,page.unread());
  var notice=page.items().getFirst();
  assertEquals("PARCEL_RECEIVED",notice.kind()); assertEquals(parcel.parcelId(),notice.resourceId());
  assertEquals("RECEIVED",notice.resourceState()); assertEquals("AVAILABLE",notice.state()); assertNull(notice.readAt());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->inbox.unit(db.accountC,db.tenantA,db.unitA)).code());
  assertEquals("NOTIFICATION_NOT_FOUND",assertThrows(BusinessProblem.class,()->inbox.open(db.accountC,db.tenantA,notice.notificationId())).code(),
   "Outra pessoa não abre o aviso pelo ID");
  assertEquals("NOTIFICATION_NOT_FOUND",assertThrows(BusinessProblem.class,()->inbox.open(db.accountB,db.tenantB,notice.notificationId())).code());
  assertEquals(1,inbox.unit(db.accountA,db.tenantA,db.unitA).unread(),"Tentativas alheias não marcam leitura");
  var opened=inbox.open(db.accountA,db.tenantA,notice.notificationId());
  assertEquals("READ_IN_APP",opened.state()); assertNotNull(opened.readAt());
  assertEquals(opened.readAt(),inbox.open(db.accountA,db.tenantA,notice.notificationId()).readAt(),"Repetir a abertura mantém a leitura gravada");
  assertEquals(0,inbox.unit(db.accountA,db.tenantA,db.unitA).unread());
  db.assertEmptyContext();
 }

 @Test void dependentNeedsTheExplicitPermissionAndArrivalNoticeGoesToWhoCanAnswer() throws Exception {
  db.adminUpdate("update cp.memberships set permissions='{\"parcels\":{\"view\":true}}'::jsonb where tenant_id=? and account_id=?",db.tenantA,dependent);
  var parcel=receive(db.unitA);
  var arrival=visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(db.unitA,"Pessoa Fictícia",null,null)).arrival();
  assertEquals(2,worker.runTenant(db.tenantA));
  assertEquals("DELIVERED:1",task(parcel.parcelId(),"PARCEL_RECEIVED"));
  assertEquals("DELIVERED:1",task(arrival.arrivalId(),"ARRIVAL_REQUESTED"));
  assertEquals(2,notices(db.accountA),"Morador recebe encomenda e chegada");
  assertEquals(1,notices(dependent),"Dependente com parcels.view recebe só a encomenda; sem visits.decide não responde chegada");
  var dependentPage=inbox.unit(dependent,db.tenantA,db.unitA);
  assertEquals(List.of("PARCEL_RECEIVED"),dependentPage.items().stream().map(item->item.kind()).toList());
  var arrivalNotice=inbox.unit(db.accountA,db.tenantA,db.unitA).items().stream().filter(item->"ARRIVAL_WAITING".equals(item.kind())).findFirst().orElseThrow();
  assertEquals("WAITING",arrivalNotice.resourceState());
  visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("APPROVE"));
  var answered=inbox.unit(db.accountA,db.tenantA,db.unitA).items().stream().filter(item->"ARRIVAL_WAITING".equals(item.kind())).findFirst().orElseThrow();
  assertEquals("APPROVED",answered.resourceState(),"A caixa mostra a situação atual, não a do momento do aviso");
  assertEquals("AVAILABLE",answered.state(),"Responder pela tela não é abrir o aviso");
  // Sem a permissão, o aviso deixa de aparecer para o dependente.
  db.adminUpdate("update cp.memberships set permissions='{}'::jsonb where tenant_id=? and account_id=?",db.tenantA,dependent);
  assertTrue(inbox.unit(dependent,db.tenantA,db.unitA).items().isEmpty());
 }

 @Test void staffReplyRequestProgressAndMoveDecisionNotifyTheUnitButNotItsOwnMessages() throws Exception {
  var request=requests.open(db.accountA,db.tenantA,db.unitA,key(),new RequestModels.Open("MAINTENANCE","Vazamento fictício","Água no teto.")).request();
  requests.unitMessage(db.accountA,db.tenantA,request.requestId(),key(),new RequestModels.UnitMessage("Mais detalhes da unidade."));
  requests.staffMessage(manager,db.tenantA,AAL2,request.requestId(),key(),new RequestModels.StaffMessage("Comentário interno.","INTERNAL"));
  requests.staffMessage(manager,db.tenantA,AAL2,request.requestId(),key(),new RequestModels.StaffMessage("Vamos verificar amanhã.","RESIDENT"));
  var head=requests.managedRequest(manager,db.tenantA,AAL2,request.requestId());
  requests.update(manager,db.tenantA,AAL2,request.requestId(),new RequestModels.Update("IN_PROGRESS",null,null,null,head.version()));
  worker.runTenant(db.tenantA);
  assertEquals("SKIPPED:1",task(request.requestId(),"REQUEST_OPENED"),"Abertura pela própria unidade não é aviso");
  assertEquals("1",db.adminString("select count(*) from cp.outbox where tenant_id=? and aggregate_id=? and event_type='REQUEST_MESSAGE_ADDED' and state='SKIPPED'",
   db.tenantA,request.requestId()),"Mensagem da própria unidade não gera aviso");
  assertEquals("1",db.adminString("select count(*) from cp.outbox where tenant_id=? and aggregate_id=? and event_type='REQUEST_MESSAGE_ADDED' and state='DELIVERED'",
   db.tenantA,request.requestId()),"Resposta visível da equipe vira aviso; comentário interno não gera evento");
  assertEquals("DELIVERED:1",task(request.requestId(),"REQUEST_UPDATED"));
  assertEquals(2,notices(db.accountA),"Morador recebe resposta e andamento");
  assertEquals(0,notices(dependent),"Dependente não acompanha pedidos");
  assertEquals(0,notices(db.accountC),"Outra unidade não recebe");
  var page=inbox.unit(db.accountA,db.tenantA,db.unitA);
  assertTrue(page.items().stream().allMatch(item->"REQUEST_UPDATED".equals(item.kind()) && "IN_PROGRESS".equals(item.resourceState())
   && request.requestId().equals(item.resourceId())));

  var zone=java.time.ZoneId.of("America/Sao_Paulo");
  var day=java.time.LocalDate.now(zone).plusDays(3);
  moves.updateRules(manager,db.tenantA,AAL2,new MoveModels.RulesUpdate("Use o elevador de serviço.",List.of(1,2,3,4,5,6,7),"08:00","18:00",24,240,null));
  var move=moves.create(db.accountA,db.tenantA,db.unitA,key(),new MoveModels.Create("MOVE_IN",day.atTime(9,0).atZone(zone).toInstant(),
   day.atTime(12,0).atZone(zone).toInstant(),"Transportadora Fictícia",null,null,1)).move();
  moves.decide(manager,db.tenantA,AAL2,move.moveId(),new MoveModels.Decision("APPROVE",null,move.version()));
  worker.runTenant(db.tenantA);
  assertEquals("SKIPPED:1",task(move.moveId(),"MOVE_REQUESTED"),"Pedido de mudança da própria unidade não é aviso");
  assertEquals("DELIVERED:1",task(move.moveId(),"MOVE_APPROVED"));
  var notice=inbox.unit(db.accountA,db.tenantA,db.unitA).items().stream().filter(item->"MOVE_DECIDED".equals(item.kind())).findFirst().orElseThrow();
  assertEquals("APPROVED",notice.resourceState()); assertEquals(move.moveId(),notice.resourceId());
  assertEquals(0,notices(dependent),"Dependente não acompanha mudanças");
  // Decisão que deixou de valer antes do worker (pedido cancelado) não vira aviso.
  var second=moves.create(db.accountA,db.tenantA,db.unitA,key(),new MoveModels.Create("MOVE_OUT",day.atTime(13,0).atZone(zone).toInstant(),
   day.atTime(15,0).atZone(zone).toInstant(),"Transportadora Fictícia",null,null,1)).move();
  moves.decide(manager,db.tenantA,AAL2,second.moveId(),new MoveModels.Decision("REJECT","Data indisponível",second.version()));
  db.adminUpdateWithoutTriggers("update cp.move_requests set state='CANCELLED',cancelled_by=? where id=?",db.accountA,second.moveId());
  worker.runTenant(db.tenantA);
  assertEquals("SKIPPED:1",task(second.moveId(),"MOVE_REJECTED"));
  assertEquals(3,notices(db.accountA));
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void closedRecordsAndEventsWithoutConsumerAreSkippedNotDelivered() throws Exception {
  var parcel=receive(db.unitA);
  parcels.voidParcel(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),new ParcelModels.VoidRequest("Registro na unidade errada",parcel.version()));
  var arrival=visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(db.unitA,"Pessoa Respondida",null,null)).arrival();
  visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("DENY"));
  int pending=Integer.parseInt(db.adminString("select count(*) from cp.outbox where tenant_id=? and state='PENDING'",db.tenantA));
  assertTrue(pending>=4,"Recebimento, anulação, chegada e decisão geram tarefas: "+pending);
  assertEquals(pending,worker.runTenant(db.tenantA));
  assertEquals("SKIPPED:1",task(parcel.parcelId(),"PARCEL_RECEIVED"),"Encomenda anulada antes do worker não gera aviso");
  assertEquals("SKIPPED:1",task(arrival.arrivalId(),"ARRIVAL_REQUESTED"),"Chegada já respondida não gera aviso");
  assertEquals("SKIPPED:1",task(arrival.arrivalId(),"ARRIVAL_DECIDED"),"Evento sem consumidor fica ignorado, nunca entregue");
  assertEquals("0",db.adminString("select count(*) from cp.outbox where tenant_id=? and state<>'SKIPPED'",db.tenantA));
  assertEquals("0",db.adminString("select count(*) from cp.notifications where tenant_id=?",db.tenantA));
 }

 @Test void effectWithoutAckIsReprocessedWithoutDuplicatingTheNotice() throws Exception {
  var parcel=receive(db.unitA);
  var leased=lease();
  assertEquals(1,leased.size()); assertEquals(1,leased.getFirst().attempts()); assertEquals(db.unitA,leased.getFirst().unitId());
  // Queda depois do efeito e antes da confirmação: o efeito ficou gravado e a tarefa ficou arrendada.
  var effect=new InboxEffect(inboxStore);
  assertEquals(OutboxModels.Outcome.DELIVERED,transactions.inTenant(db.tenantA,()->effect.apply(db.tenantA,leased.getFirst())));
  assertEquals(1,notices(db.accountA));
  assertEquals("LEASED:1",task(parcel.parcelId(),"PARCEL_RECEIVED"));
  assertEquals(0,worker.runTenant(db.tenantA),"Arrendamento vigente não é tomado por outra passada");
  expireLease(leased.getFirst().id());
  assertEquals(1,worker.runTenant(db.tenantA));
  assertEquals("DELIVERED:2",task(parcel.parcelId(),"PARCEL_RECEIVED"));
  assertEquals(1,notices(db.accountA),"Reprocessar não duplica o aviso (AC-049)");
 }

 @Test void workerThatLostTheLeaseConfirmsNothingAndRunsNoEffect() throws Exception {
  var parcel=receive(db.unitA);
  var first=lease().getFirst();
  expireLease(first.id());
  var second=lease().getFirst();
  assertEquals(first.id(),second.id()); assertEquals(2,second.attempts());
  var real=new InboxEffect(inboxStore);
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  OutboxEffect counting=new OutboxEffect() {
   @Override public boolean handles(String type) { return real.handles(type); }
   @Override public boolean idempotent() { return true; }
   @Override public OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event) { calls.incrementAndGet(); return real.apply(tenant,event); }
  };
  var countingWorker=new OutboxWorker(transactions,outbox,List.of(counting));
  countingWorker.process(db.tenantA,first);
  assertEquals(0,calls.get(),"Worker atrasado nem começa o efeito: a trava cercada pela tentativa recusa a tarefa");
  assertEquals("LEASED:2",task(parcel.parcelId(),"PARCEL_RECEIVED"),"Worker atrasado não conclui a tarefa de outro");
  assertEquals(0,notices(db.accountA));
  countingWorker.process(db.tenantA,second);
  assertEquals(1,calls.get());
  assertEquals("DELIVERED:2",task(parcel.parcelId(),"PARCEL_RECEIVED"));
  assertEquals(1,notices(db.accountA));
 }

 @Test void failuresBackOffWithSanitizedErrorAndEndVisibleInFailed() throws Exception {
  OutboxEffect failing=new OutboxEffect() {
   @Override public boolean handles(String type) { return "TEST_TEMPORARY".equals(type); }
   @Override public boolean idempotent() { return true; }
   @Override public OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event) {
    throw new DataIntegrityViolationException("PESSOA FICTICIA com dado que nao pode ir para a tarefa");
   }
  };
  var failingWorker=new OutboxWorker(transactions,outbox,List.of(failing));
  UUID aggregate=UUID.randomUUID();
  UUID event=adminEvent("TEST_TEMPORARY",aggregate,"{}");
  assertEquals(1,failingWorker.runTenant(db.tenantA));
  assertEquals("PENDING:1",task(aggregate,"TEST_TEMPORARY"));
  assertEquals("Falha de banco",db.adminString("select last_error from cp.outbox where id=?",event),"Erro saneado, sem a mensagem original");
  double wait=Double.parseDouble(db.adminString("select extract(epoch from available_at-clock_timestamp()) from cp.outbox where id=?",event));
  assertTrue(wait>6 && wait<=12.5,"Primeira espera de 10 s com variação de até 20%: "+wait);
  assertEquals(0,failingWorker.runTenant(db.tenantA),"Antes do horário a tarefa não volta");
  // Última tentativa permitida: a falha seguinte fica visível em FAILED.
  db.adminUpdateWithoutTriggers("update cp.outbox set attempts=?,available_at=clock_timestamp()-interval '1 second' where id=?",
   RetryPolicy.MAX_ATTEMPTS-1,event);
  assertEquals(1,failingWorker.runTenant(db.tenantA));
  assertEquals("FAILED:"+RetryPolicy.MAX_ATTEMPTS,task(aggregate,"TEST_TEMPORARY"));
  assertEquals("Falha de banco:true",db.adminString("select last_error||':'||(processed_at is not null) from cp.outbox where id=?",event));
  assertEquals(0,failingWorker.runTenant(db.tenantA),"Falha definitiva não volta sozinha para a fila");
 }

 @Test void nonIdempotentEffectIsNotRetriedBlindly() throws Exception {
  OutboxEffect external=new OutboxEffect() {
   @Override public boolean handles(String type) { return "TEST_EXTERNAL".equals(type); }
   @Override public boolean idempotent() { return false; }
   @Override public OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event) { throw new IllegalStateException("resposta incerta"); }
  };
  UUID aggregate=UUID.randomUUID();
  UUID event=adminEvent("TEST_EXTERNAL",aggregate,"{}");
  assertEquals(1,new OutboxWorker(transactions,outbox,List.of(external)).runTenant(db.tenantA));
  assertEquals("FAILED:1",task(aggregate,"TEST_EXTERNAL"),"Sem nova tentativa automática");
  String error=db.adminString("select last_error from cp.outbox where id=?",event);
  assertTrue(error.startsWith("Efeito não idempotente com falha") && error.endsWith("Erro inesperado (IllegalStateException)"),error);
 }

 @Test void permanentProblemsFailAtOnceAndExhaustedLeasesBecomeVisible() throws Exception {
  UUID noUnit=UUID.randomUUID(),missing=UUID.randomUUID(),exhausted=UUID.randomUUID();
  UUID first=adminEvent("PARCEL_RECEIVED",noUnit,"{}");
  UUID second=adminEvent("PARCEL_RECEIVED",missing,"{\"unitId\":\""+db.unitA+"\"}");
  UUID third=adminEvent("TEST_STUCK",exhausted,"{}");
  db.adminUpdateWithoutTriggers("update cp.outbox set state='LEASED',attempts=?,lease_until=clock_timestamp()-interval '1 second' where id=?",
   RetryPolicy.MAX_ATTEMPTS,third);
  assertEquals(2,worker.runTenant(db.tenantA));
  assertEquals("FAILED:1:Evento sem unidade válida no payload.",
   db.adminString("select state||':'||attempts||':'||last_error from cp.outbox where id=?",first));
  assertEquals("FAILED:1:Registro do evento não encontrado no condomínio.",
   db.adminString("select state||':'||attempts||':'||last_error from cp.outbox where id=?",second));
  assertEquals("FAILED:"+RetryPolicy.MAX_ATTEMPTS,task(exhausted,"TEST_STUCK"));
  assertTrue(db.adminString("select last_error from cp.outbox where id=?",third).startsWith("Tentativas esgotadas"));
  assertEquals("0",db.adminString("select count(*) from cp.notifications where tenant_id=?",db.tenantA));
 }

 @Test void catalogListsOnlyActiveTenantsWithoutTheirData() throws Exception {
  var parcel=receive(db.unitA);
  assertTrue(transactions.catalog(outbox::activeTenants).containsAll(List.of(db.tenantA,db.tenantB)));
  db.adminUpdate("update platform.tenants set active=false where id=?",db.tenantB);
  assertFalse(transactions.catalog(outbox::activeTenants).contains(db.tenantB),"Condomínio inativo fica fora do catálogo");
  assertEquals(0,worker.runTenant(db.tenantB));
  assertEquals("PENDING:0",task(parcel.parcelId(),"PARCEL_RECEIVED"),"Passada de outro condomínio não toca a tarefa");
  assertEquals(0L,(long)transactions.catalog(()->workerDb.jdbc.queryForObject("select count(*) from cp.outbox",Long.class)),
   "Sem condomínio em contexto o worker não vê tarefas");
  workerDb.assertEmptyContext();
 }
}
