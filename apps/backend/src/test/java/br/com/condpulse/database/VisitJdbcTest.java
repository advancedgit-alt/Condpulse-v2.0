package br.com.condpulse.database;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.identity.application.AssuranceLevel;
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
import static org.junit.jupiter.api.Assertions.*;

/** V013 com PostgreSQL real: convite da unidade, chegada sem convite, primeira decisão válida no prazo e portaria. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class VisitJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static VisitService visits;
 private static UUID concierge;

 @BeforeAll static void setup() throws Exception {
  // Duas conexões: decisões simultâneas precisam de transações concorrentes reais.
  db=new JdbcDisposableDatabase(2);
  visits=new VisitService(new TenantTransactionExecutor(db.jdbc,db.manager),new VisitStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID(); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA VISITAS JDBC','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "visit-jdbc-"+UUID.randomUUID(); }
 private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
 private static VisitModels.CreatePermit permit(String name) {
  return new VisitModels.CreatePermit(name,null,"Visita de ensaio",now(),now().plus(Duration.ofHours(2)));
 }
 private VisitModels.Arrival arrive(UUID unit,String name) {
  return visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(unit,name,null,null)).arrival();
 }

 @Test void residentInvitesOnceForTheOwnUnitAndTheConciergeSeesTheExpectedVisit() throws Exception {
  String key=key();
  var request=new VisitModels.CreatePermit("  Visita   Fictícia ","12ab","Aniversário",now(),now().plus(Duration.ofHours(3)));
  var first=visits.createPermit(db.accountA,db.tenantA,db.unitA,key,request);
  var replay=visits.createPermit(db.accountA,db.tenantA,db.unitA,key,request);
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(201,replay.status());
  assertEquals(first.permit(),replay.permit(),"Repetição devolve o mesmo convite");
  var permit=first.permit();
  assertEquals("Visita Fictícia",permit.visitorName()); assertEquals("12AB",permit.documentLast4()); assertEquals("ACTIVE",permit.state());
  assertTrue(permit.createdByMe()); assertEquals(0,permit.version());
  assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(BusinessProblem.class,()->visits.createPermit(db.accountA,db.tenantA,db.unitA,key,
   permit("Outra Pessoa"))).code());
  assertEquals("1",db.adminString("select count(*) from cp.visit_permits where tenant_id=?",db.tenantA));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='VISIT_PERMIT_CREATED'",permit.permitId()));
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.unitPermits(db.accountC,db.tenantA,db.unitA)).code());
  assertTrue(visits.unitPermits(db.accountC,db.tenantA,db.otherUnitA).permits().isEmpty(),"Outra unidade não vê o convite");
  assertEquals(List.of(permit.permitId()),visits.expected(concierge,db.tenantA,AAL2,null,"fictí").permits().stream()
   .map(VisitModels.Permit::permitId).toList());
  assertTrue(visits.expected(concierge,db.tenantA,AAL2,db.otherUnitA,null).permits().isEmpty());
  assertTrue(visits.expected(concierge,db.tenantA,AAL2,null,"visita_").permits().isEmpty(),"Sublinhado na busca é literal");
  // Ainda não começou: aparece como agendado (relógio do banco) e só entra nas previstas nas 24 horas anteriores ao início.
  var soon=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),
   new VisitModels.CreatePermit("Visita Agendada",null,null,now().plus(Duration.ofHours(3)),now().plus(Duration.ofHours(5)))).permit();
  var later=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),
   new VisitModels.CreatePermit("Visita Distante",null,null,now().plus(Duration.ofDays(2)),now().plus(Duration.ofDays(2).plusHours(2)))).permit();
  assertEquals("SCHEDULED",soon.state()); assertEquals("SCHEDULED",later.state());
  assertEquals(List.of(permit.permitId(),soon.permitId()),visits.expected(concierge,db.tenantA,AAL2,db.unitA,null).permits().stream()
   .map(VisitModels.Permit::permitId).toList(),"Previstas: vale agora ou começa nas próximas 24 horas, pela ordem de início");
  var postponed=visits.revokePermit(db.accountA,db.tenantA,later.permitId(),new VisitModels.RevokePermit("Visita adiada",0L));
  assertEquals("REVOKED",postponed.state(),"Convite agendado também é revogado");
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->visits.expected(concierge,db.tenantA,AssuranceLevel.AAL1,null,null)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.expected(db.accountA,db.tenantA,AAL2,null,null)).code());
  for(var invalid:List.of(new VisitModels.CreatePermit("Pessoa",null,null,now(),now()),
    new VisitModels.CreatePermit("Pessoa",null,null,now(),now().plus(Duration.ofDays(8))),
    new VisitModels.CreatePermit("Pessoa",null,null,now().minus(Duration.ofHours(2)),now().plus(Duration.ofHours(1)))))
   assertEquals("VISIT_PERIOD_INVALID",assertThrows(BusinessProblem.class,()->visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),invalid)).code());
  assertEquals("VISITOR_DOCUMENT_INVALID",assertThrows(BusinessProblem.class,()->visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),
   new VisitModels.CreatePermit("Pessoa","12345",null,now(),now().plus(Duration.ofHours(1))))).code());
  assertEquals("VISITOR_NAME_INVALID",assertThrows(BusinessProblem.class,()->visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),
   permit("A"))).code());
  db.assertEmptyContext();
 }
 @Test void permitIsRevokedOnceWithReasonByTheUnit() throws Exception {
  var permit=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),permit("Pessoa Revogada")).permit();
  assertEquals("VISIT_PERMIT_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.revokePermit(db.accountC,db.tenantA,permit.permitId(),
   new VisitModels.RevokePermit("Outra unidade",0L))).code());
  assertEquals("VISIT_PERMIT_CHANGED",assertThrows(BusinessProblem.class,()->visits.revokePermit(db.accountA,db.tenantA,permit.permitId(),
   new VisitModels.RevokePermit("Versão antiga",3L))).code());
  assertEquals("VISIT_REVOKE_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->visits.revokePermit(db.accountA,db.tenantA,permit.permitId(),
   new VisitModels.RevokePermit(" ",0L))).code());
  var revoked=visits.revokePermit(db.accountA,db.tenantA,permit.permitId(),new VisitModels.RevokePermit("Visita desmarcada",0L));
  assertEquals("REVOKED",revoked.state()); assertEquals(1,revoked.version()); assertEquals("Visita desmarcada",revoked.revokeReason());
  assertNotNull(revoked.revokedAt());
  assertEquals("VISIT_PERMIT_REVOKED",assertThrows(BusinessProblem.class,()->visits.revokePermit(db.accountA,db.tenantA,permit.permitId(),
   new VisitModels.RevokePermit("De novo",1L))).code());
  assertTrue(visits.expected(concierge,db.tenantA,AAL2,null,null).permits().isEmpty(),"Convite revogado sai das visitas previstas");
  assertEquals("Visita desmarcada",db.adminString("select reason from cp.audit_log where resource_id=? and action='VISIT_PERMIT_REVOKED' and actor_id=?",
   permit.permitId(),db.accountA));
  db.assertEmptyContext();
 }
 @Test void arrivalWaitsForTheFirstValidDecisionWithinTheDeadline() throws Exception {
  String key=key();
  var request=new VisitModels.RegisterArrival(db.unitA,"Entregador Fictício",null,null);
  var registered=visits.register(concierge,db.tenantA,AAL2,key,request);
  var replay=visits.register(concierge,db.tenantA,AAL2,key,request);
  assertTrue(replay.replayed()); assertEquals(registered.arrival(),replay.arrival());
  var arrival=registered.arrival();
  assertEquals("WAITING",arrival.state());
  long seconds=Duration.between(Instant.now(),arrival.expiresAt()).toSeconds();
  assertTrue(seconds>240 && seconds<=300,"Prazo de 5 minutos pelo banco: "+seconds);
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.register(db.accountA,db.tenantA,AAL2,key(),request)).code());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.unitArrivals(db.accountC,db.tenantA,db.unitA)).code());
  assertEquals(List.of(arrival.arrivalId()),visits.unitArrivals(db.accountA,db.tenantA,db.unitA).arrivals().stream()
   .map(VisitModels.Arrival::arrivalId).toList());
  assertEquals("ARRIVAL_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.decide(db.accountC,db.tenantA,arrival.arrivalId(),
   new VisitModels.Decision("APPROVE"))).code(),"Outra unidade não responde");
  var approved=visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("APPROVE"));
  assertEquals("APPROVED",approved.state()); assertEquals("APP",approved.decisionSource()); assertTrue(approved.decidedByMe());
  assertEquals("ARRIVAL_ALREADY_DECIDED",assertThrows(BusinessProblem.class,()->visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),
   new VisitModels.Decision("DENY"))).code());
  assertEquals("ARRIVAL_ALREADY_DECIDED",assertThrows(BusinessProblem.class,()->visits.assistedDecision(concierge,db.tenantA,AAL2,arrival.arrivalId(),
   new VisitModels.AssistedDecision("DENY","INTERCOM","Interfone"))).code());
  var queue=visits.queue(concierge,db.tenantA,AAL2,"APPROVED").arrivals();
  assertEquals(List.of(arrival.arrivalId()),queue.stream().map(VisitModels.Arrival::arrivalId).toList());
  assertFalse(queue.getFirst().decidedByMe(),"A portaria vê a decisão sem a conta de quem respondeu");
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='ARRIVAL_DECIDED'",arrival.arrivalId()));
  db.assertEmptyContext();
 }
 @Test void withoutAnswerNothingIsReleasedAndTheConciergeRecordsTheAssistedConfirmation() throws Exception {
  var assisted=arrive(db.unitA,"Prestador Fictício");
  assertEquals("ARRIVAL_NOTE_REQUIRED",assertThrows(BusinessProblem.class,()->visits.assistedDecision(concierge,db.tenantA,AAL2,
   assisted.arrivalId(),new VisitModels.AssistedDecision("APPROVE","INTERCOM"," "))).code());
  assertEquals("ARRIVAL_SOURCE_INVALID",assertThrows(BusinessProblem.class,()->visits.assistedDecision(concierge,db.tenantA,AAL2,
   assisted.arrivalId(),new VisitModels.AssistedDecision("APPROVE","APP","Pelo app"))).code());
  var denied=visits.assistedDecision(concierge,db.tenantA,AAL2,assisted.arrivalId(),
   new VisitModels.AssistedDecision("DENY","INTERCOM","Moradora recusou pelo interfone"));
  assertEquals("DENIED",denied.state()); assertEquals("INTERCOM",denied.decisionSource()); assertEquals("Moradora recusou pelo interfone",denied.decisionNote());
  var late=arrive(db.unitA,"Visita Atrasada");
  db.adminUpdateWithoutTriggers("update cp.arrival_requests set expires_at=now()-interval '1 minute',created_at=now()-interval '10 minutes' where id=?",
   late.arrivalId());
  assertEquals("EXPIRED",visits.unitArrivals(db.accountA,db.tenantA,db.unitA).arrivals().stream()
   .filter(item->item.arrivalId().equals(late.arrivalId())).findFirst().orElseThrow().state(),"Prazo vencido aparece como expirado");
  assertEquals("ARRIVAL_EXPIRED",assertThrows(BusinessProblem.class,()->visits.decide(db.accountA,db.tenantA,late.arrivalId(),
   new VisitModels.Decision("APPROVE"))).code(),"Resposta atrasada não decide");
  // Sem resposta no prazo, a portaria segue o interfone e registra a origem da confirmação.
  var confirmed=visits.assistedDecision(concierge,db.tenantA,AAL2,late.arrivalId(),
   new VisitModels.AssistedDecision("APPROVE","IN_PERSON","Morador desceu e confirmou na portaria"));
  assertEquals("APPROVED",confirmed.state()); assertEquals("IN_PERSON",confirmed.decisionSource());
  var gone=arrive(db.unitA,"Visita que Foi Embora");
  db.adminUpdateWithoutTriggers("update cp.arrival_requests set expires_at=now()-interval '1 minute',created_at=now()-interval '10 minutes' where id=?",
   gone.arrivalId());
  var cancelled=visits.cancel(concierge,db.tenantA,AAL2,gone.arrivalId(),new VisitModels.CancelArrival("Visitante foi embora"));
  assertEquals("CANCELLED",cancelled.state()); assertEquals("CONCIERGE",cancelled.decisionSource());
  assertEquals("ARRIVAL_CANCELLED",assertThrows(BusinessProblem.class,()->visits.cancel(concierge,db.tenantA,AAL2,gone.arrivalId(),
   new VisitModels.CancelArrival("De novo"))).code());
  assertEquals("ARRIVAL_ALREADY_DECIDED",assertThrows(BusinessProblem.class,()->visits.cancel(concierge,db.tenantA,AAL2,late.arrivalId(),
   new VisitModels.CancelArrival("Já decidida"))).code());
  var otherPermit=visits.createPermit(db.accountC,db.tenantA,db.otherUnitA,key(),permit("Convidado de Outra Unidade")).permit();
  assertEquals("ARRIVAL_PERMIT_INVALID",assertThrows(BusinessProblem.class,()->visits.register(concierge,db.tenantA,AAL2,key(),
   new VisitModels.RegisterArrival(db.unitA,"Pessoa Fictícia",null,otherPermit.permitId()))).code());
  db.assertEmptyContext();
 }
 @Test void simultaneousAnswersProduceOneDecision() throws Exception {
  var arrival=arrive(db.unitA,"Visita Disputada");
  var start=new CountDownLatch(1);
  var pool=Executors.newFixedThreadPool(2);
  try {
   var tasks=new ArrayList<Callable<String>>();
   tasks.add(()->{
    start.await();
    try { visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("APPROVE")); return "OK"; }
    catch(BusinessProblem problem) { return problem.code(); }
   });
   tasks.add(()->{
    start.await();
    try { visits.assistedDecision(concierge,db.tenantA,AAL2,arrival.arrivalId(),new VisitModels.AssistedDecision("DENY","INTERCOM","Interfone")); return "OK"; }
    catch(BusinessProblem problem) { return problem.code(); }
   });
   var futures=tasks.stream().map(pool::submit).toList();
   start.countDown();
   var results=new ArrayList<String>();
   for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
   assertEquals(1,results.stream().filter("OK"::equals).count(),results.toString());
   assertEquals(List.of("ARRIVAL_ALREADY_DECIDED"),results.stream().filter(r->!"OK".equals(r)).toList(),results.toString());
  } finally { pool.shutdownNow(); }
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='ARRIVAL_DECIDED'",arrival.arrivalId()));
  db.assertEmptyContext();
 }
 @Test void dependentInvitesAndAnswersOnlyWithExplicitPermission() throws Exception {
  UUID dependent=UUID.randomUUID(); db.extraAccounts.add(dependent);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",dependent);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,'DEPENDENTE VISITAS JDBC','DEPENDENT','ACTIVE')",
   db.tenantA,dependent,db.unitA);
  try {
   var adult=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),permit("Convidado do Adulto")).permit();
   assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.createPermit(dependent,db.tenantA,db.unitA,key(),
    permit("Convidado do Dependente"))).code());
   assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.unitArrivals(dependent,db.tenantA,db.unitA)).code());
   db.adminUpdate("update cp.memberships set permissions='{\"visits\":{\"create\":true}}'::jsonb where account_id=?",dependent);
   var own=visits.createPermit(dependent,db.tenantA,db.unitA,key(),permit("Convidado do Dependente")).permit();
   assertEquals(List.of(own.permitId()),visits.unitPermits(dependent,db.tenantA,db.unitA).permits().stream().map(VisitModels.Permit::permitId).toList(),
    "Dependente vê só os convites que criou");
   assertEquals(2,visits.unitPermits(db.accountA,db.tenantA,db.unitA).permits().size(),"Adulto vê todos os convites da unidade");
   assertEquals("VISIT_PERMIT_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.revokePermit(dependent,db.tenantA,adult.permitId(),
    new VisitModels.RevokePermit("Não é meu",0L))).code());
   var arrival=arrive(db.unitA,"Chegada do Dependente");
   assertEquals("ARRIVAL_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.decide(dependent,db.tenantA,arrival.arrivalId(),
    new VisitModels.Decision("APPROVE"))).code());
   db.adminUpdate("update cp.memberships set permissions='{\"visits\":{\"create\":true,\"decide\":true}}'::jsonb where account_id=?",dependent);
   assertEquals("APPROVED",visits.decide(dependent,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("APPROVE")).state());
  } finally {
   db.clearReservationFixtures();
   db.adminUpdate("delete from cp.memberships where account_id=?",dependent);
  }
  db.assertEmptyContext();
 }
}
