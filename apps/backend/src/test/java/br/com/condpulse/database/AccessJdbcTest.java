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
import br.com.condpulse.visits.application.AccessModels;
import br.com.condpulse.visits.application.AccessService;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
import br.com.condpulse.visits.infrastructure.AccessStore;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V014 com PostgreSQL real: entrada só com autorização vigente, saída e correção como eventos adicionais. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static VisitService visits;
 private static AccessService access;
 private static UUID concierge;

 @BeforeAll static void setup() throws Exception {
  // Duas conexões: saídas simultâneas precisam de transações concorrentes reais.
  db=new JdbcDisposableDatabase(2);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new VisitStore(db.jdbc);
  visits=new VisitService(transactions,store,new AuditRecorder(db.jdbc));
  access=new AccessService(transactions,store,new AccessStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID(); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA ACESSOS JDBC','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "access-jdbc-"+UUID.randomUUID(); }
 private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
 private VisitModels.Arrival approvedArrival(String name) {
  var arrival=visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(db.unitA,name,"12AB",null)).arrival();
  return visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("APPROVE"));
 }
 private VisitModels.Permit permit(String name,Instant from,Instant until) {
  return visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),new VisitModels.CreatePermit(name,null,null,from,until)).permit();
 }
 private AccessModels.AccessRecord enter(VisitModels.Permit permit) {
  return access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(null,permit.permitId(),"DOCUMENT_CHECKED",null)).record();
 }

 @Test void entryNeedsTheUnitAuthorizationAndHappensOncePerArrival() throws Exception {
  var waiting=visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(db.unitA,"Pessoa Aguardando",null,null)).arrival();
  assertEquals("ACCESS_ARRIVAL_NOT_AUTHORIZED",assertThrows(BusinessProblem.class,()->access.entry(concierge,db.tenantA,AAL2,key(),
   new AccessModels.RecordEntry(waiting.arrivalId(),null,"DOCUMENT_CHECKED",null))).code(),"Sem resposta da unidade nada é liberado");
  var denied=visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(db.unitA,"Pessoa Recusada",null,null)).arrival();
  visits.decide(db.accountA,db.tenantA,denied.arrivalId(),new VisitModels.Decision("DENY"));
  assertEquals("ACCESS_ARRIVAL_NOT_AUTHORIZED",assertThrows(BusinessProblem.class,()->access.entry(concierge,db.tenantA,AAL2,key(),
   new AccessModels.RecordEntry(denied.arrivalId(),null,"DOCUMENT_CHECKED",null))).code());
  assertEquals("0",db.adminString("select count(*) from cp.access_events where tenant_id=?",db.tenantA));

  var approved=approvedArrival("Pessoa Autorizada");
  String key=key();
  var request=new AccessModels.RecordEntry(approved.arrivalId(),null,"DOCUMENT_CHECKED"," Documento   conferido ");
  var first=access.entry(concierge,db.tenantA,AAL2,key,request);
  var replay=access.entry(concierge,db.tenantA,AAL2,key,request);
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(201,replay.status());
  assertEquals(first.record(),replay.record(),"Repetição devolve o mesmo registro");
  var record=first.record();
  assertEquals("MANUAL_IN",record.eventType()); assertEquals("CONCIERGE",record.source(),"Entrada manual nunca é passagem de equipamento");
  assertEquals(db.unitA,record.unitId()); assertEquals("Pessoa Autorizada",record.visitorName()); assertEquals("12AB",record.documentLast4());
  assertEquals(approved.arrivalId(),record.arrivalId()); assertNull(record.permitId()); assertEquals("Documento conferido",record.note());
  assertTrue(record.recordedByMe()); assertNull(record.exit()); assertNull(record.correction()); assertNotNull(record.enteredAt());
  assertEquals("ACCESS_ENTRY_ALREADY_RECORDED",assertThrows(BusinessProblem.class,()->access.entry(concierge,db.tenantA,AAL2,key(),
   new AccessModels.RecordEntry(approved.arrivalId(),null,"RECOGNIZED_BY_UNIT",null))).code());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='ACCESS_ENTRY_RECORDED'",record.entryId()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='ACCESS_ENTRY_RECORDED' and actor_id=?",
   record.entryId(),concierge));
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->access.entry(db.accountA,db.tenantA,AAL2,key(),request)).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->access.records(concierge,db.tenantA,AssuranceLevel.AAL1)).code());
  assertEquals("ACCESS_BASIS_REQUIRED",assertThrows(BusinessProblem.class,()->access.entry(concierge,db.tenantA,AAL2,key(),
   new AccessModels.RecordEntry(null,null,"DOCUMENT_CHECKED",null))).code());
  assertEquals("ACCESS_EVIDENCE_INVALID",assertThrows(BusinessProblem.class,()->access.entry(concierge,db.tenantA,AAL2,key(),
   new AccessModels.RecordEntry(approved.arrivalId(),null,"PASSAGE_IN",null))).code());
  // A unidade vê o próprio histórico sem a conta da portaria; outra unidade não.
  var unit=access.unitRecords(db.accountA,db.tenantA,db.unitA).records();
  assertEquals(List.of(record.entryId()),unit.stream().map(AccessModels.AccessRecord::entryId).toList());
  assertFalse(unit.getFirst().recordedByMe());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->access.unitRecords(db.accountC,db.tenantA,db.unitA)).code());
  assertTrue(access.unitRecords(db.accountC,db.tenantA,db.otherUnitA).records().isEmpty());
  assertEquals(List.of(record.entryId()),access.records(concierge,db.tenantA,AAL2).records().stream().map(AccessModels.AccessRecord::entryId).toList());
  db.assertEmptyContext();
 }
 @Test void permitAuthorizesOnlyInsideItsPeriodAndAllowsReturning() throws Exception {
  var later=permit("Visita Agendada",now().plus(Duration.ofHours(3)),now().plus(Duration.ofHours(5)));
  assertEquals("ACCESS_PERMIT_NOT_CURRENT",assertThrows(BusinessProblem.class,()->enter(later)).code(),"Antes do início o convite não autoriza");
  var current=permit("Visita em Vigor",now(),now().plus(Duration.ofHours(2)));
  var first=enter(current);
  assertEquals(current.permitId(),first.permitId()); assertNull(first.arrivalId());
  var again=enter(current);
  assertNotEquals(first.entryId(),again.entryId(),"Convite em vigor admite nova entrada no período");
  visits.revokePermit(db.accountA,db.tenantA,current.permitId(),new VisitModels.RevokePermit("Visita encerrada",0L));
  assertEquals("ACCESS_PERMIT_NOT_CURRENT",assertThrows(BusinessProblem.class,()->enter(current)).code(),"Revogado não autoriza");
  assertEquals("2",db.adminString("select count(*) from cp.access_events where permit_id=? and event_type='MANUAL_IN'",current.permitId()),
   "Entradas anteriores continuam registradas");
  db.assertEmptyContext();
 }
 @Test void exitAndCorrectionAreAdditionalEventsThatNeverEraseTheRecord() throws Exception {
  var entry=enter(permit("Visita com Saída",now(),now().plus(Duration.ofHours(2))));
  String exitKey=key();
  var exited=access.exit(concierge,db.tenantA,AAL2,entry.entryId(),exitKey,new AccessModels.RecordExit(null));
  assertNotNull(exited.record().exit()); assertNull(exited.record().exit().correction());
  assertTrue(access.exit(concierge,db.tenantA,AAL2,entry.entryId(),exitKey,new AccessModels.RecordExit(null)).replayed());
  assertEquals("ACCESS_EXIT_ALREADY_RECORDED",assertThrows(BusinessProblem.class,()->access.exit(concierge,db.tenantA,AAL2,entry.entryId(),key(),
   new AccessModels.RecordExit("De novo"))).code());
  var exitId=exited.record().exit().eventId();
  assertEquals("ACCESS_EVENT_NOT_FOUND",assertThrows(BusinessProblem.class,()->access.exit(concierge,db.tenantA,AAL2,exitId,key(),null)).code(),
   "Saída só de entrada");
  assertEquals("ACCESS_CORRECTION_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->access.correct(concierge,db.tenantA,AAL2,exitId,key(),
   new AccessModels.Correct(" "))).code());
  var exitCorrected=access.correct(concierge,db.tenantA,AAL2,exitId,key(),new AccessModels.Correct("Saída de outra pessoa"));
  assertEquals("Saída de outra pessoa",exitCorrected.record().exit().correction().reason());
  var exitAgain=access.exit(concierge,db.tenantA,AAL2,entry.entryId(),key(),new AccessModels.RecordExit(null));
  assertNotEquals(exitId,exitAgain.record().exit().eventId(),"Saída corrigida admite nova saída");
  assertNull(exitAgain.record().exit().correction());
  var entryCorrected=access.correct(concierge,db.tenantA,AAL2,entry.entryId(),key(),new AccessModels.Correct("Pessoa não entrou"));
  assertEquals("Pessoa não entrou",entryCorrected.record().correction().reason());
  assertEquals("ACCESS_ALREADY_CORRECTED",assertThrows(BusinessProblem.class,()->access.correct(concierge,db.tenantA,AAL2,entry.entryId(),key(),
   new AccessModels.Correct("Outra vez"))).code());
  var correctionId=entryCorrected.record().correction().eventId();
  assertEquals("ACCESS_CORRECTION_INVALID",assertThrows(BusinessProblem.class,()->access.correct(concierge,db.tenantA,AAL2,correctionId,key(),
   new AccessModels.Correct("Corrigir a correção"))).code());
  assertEquals("5",db.adminString("""
   select count(*) from cp.access_events where id=? or entry_event_id=? or corrects_event_id=? or corrects_event_id in
    (select id from cp.access_events where entry_event_id=?)""",entry.entryId(),entry.entryId(),entry.entryId(),entry.entryId()),
   "Entrada, duas saídas e duas correções continuam guardadas");
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where action='ACCESS_EVENT_CORRECTED' and resource_id=? and reason='Pessoa não entrou'",
   correctionId));
  db.assertEmptyContext();
 }
 @Test void correctedEntryOfAnArrivalCanBeRecordedAgainButOldApprovalsDoNotAuthorize() throws Exception {
  var approved=approvedArrival("Pessoa Corrigida");
  var wrong=access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(approved.arrivalId(),null,"DOCUMENT_CHECKED",null)).record();
  access.correct(concierge,db.tenantA,AAL2,wrong.entryId(),key(),new AccessModels.Correct("Registrada antes da hora"));
  assertEquals("ACCESS_ENTRY_CORRECTED",assertThrows(BusinessProblem.class,()->access.exit(concierge,db.tenantA,AAL2,wrong.entryId(),key(),null)).code());
  var right=access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(approved.arrivalId(),null,"RECOGNIZED_BY_UNIT",null)).record();
  assertNotEquals(wrong.entryId(),right.entryId()); assertNull(right.correction());
  var old=approvedArrival("Pessoa de Ontem");
  db.adminUpdateWithoutTriggers("update cp.arrival_requests set created_at=now()-interval '25 hours' where id=?",old.arrivalId());
  assertEquals("ACCESS_AUTHORIZATION_EXPIRED",assertThrows(BusinessProblem.class,()->access.entry(concierge,db.tenantA,AAL2,key(),
   new AccessModels.RecordEntry(old.arrivalId(),null,"DOCUMENT_CHECKED",null))).code());
  db.assertEmptyContext();
 }
 @Test void simultaneousExitsProduceOneValidExit() throws Exception {
  var entry=enter(permit("Visita Disputada",now(),now().plus(Duration.ofHours(2))));
  var start=new CountDownLatch(1);
  var pool=Executors.newFixedThreadPool(2);
  try {
   var tasks=new ArrayList<Callable<String>>();
   for(int i=0;i<2;i++) tasks.add(()->{
    start.await();
    try { access.exit(concierge,db.tenantA,AAL2,entry.entryId(),key(),null); return "OK"; }
    catch(BusinessProblem problem) { return problem.code(); }
   });
   var futures=tasks.stream().map(pool::submit).toList();
   start.countDown();
   var results=new ArrayList<String>();
   for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
   assertEquals(1,results.stream().filter("OK"::equals).count(),results.toString());
   assertEquals(List.of("ACCESS_EXIT_ALREADY_RECORDED"),results.stream().filter(r->!"OK".equals(r)).toList(),results.toString());
  } finally { pool.shutdownNow(); }
  assertEquals("1",db.adminString("select count(*) from cp.access_events where entry_event_id=? and event_type='MANUAL_OUT'",entry.entryId()));
  db.assertEmptyContext();
 }
}
