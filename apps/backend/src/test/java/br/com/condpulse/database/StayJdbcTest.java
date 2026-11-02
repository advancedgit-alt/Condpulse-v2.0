package br.com.condpulse.database;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.stays.application.StayAlertJob;
import br.com.condpulse.stays.application.StayModels;
import br.com.condpulse.stays.application.StayService;
import br.com.condpulse.stays.infrastructure.StayStore;
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

/**
 * V055 (CPX-015 a CPX-019) com PostgreSQL real, o login da API e o do worker: a entrada abre a permanência, a saída
 * fecha, o morador da unidade confirma a saída, a portaria justifica, o worker grava N1/N2 uma vez e o fechamento do dia
 * lista as entradas sem saída, com SENSITIVE_VIEWED nas leituras da equipe.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class StayJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;
 private static VisitService visits;
 private static AccessService access;
 private static StayService stays;
 private static StayAlertJob job;
 private static UUID concierge,manager;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  workerDb=new JdbcWorkerPool();
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new VisitStore(db.jdbc);
  visits=new VisitService(transactions,store,new AuditRecorder(db.jdbc));
  access=new AccessService(transactions,store,new AccessStore(db.jdbc),new AuditRecorder(db.jdbc));
  stays=new StayService(transactions,new StayStore(db.jdbc),store,new SensitiveViewRecorder(db.jdbc,true));
  job=new StayAlertJob(new WorkerTransactions(workerDb.jdbc,workerDb.manager),workerDb.jdbc);
  concierge=UUID.randomUUID(); manager=UUID.randomUUID(); db.extraAccounts.add(concierge); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",concierge,manager);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
   values(?,?,null,'PORTARIA PERMANENCIA JDBC','CONCIERGE','ACTIVE'),(?,?,null,'GESTAO PERMANENCIA JDBC','MANAGER','ACTIVE')
   """,db.tenantA,concierge,db.tenantA,manager);
 }
 @BeforeEach void reset() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext(); workerDb.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }
 private static String key() { return "stay-jdbc-"+UUID.randomUUID(); }
 private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
 private static long events(UUID stay,String type) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.security_log where subject_type='STAY' and subject_id=? and event_type=?",stay,type));
 }
 private static UUID stayOf(UUID entry) throws Exception {
  return UUID.fromString(db.adminString("select id from cp.stays where entry_event_id=?",entry));
 }
 private AccessModels.AccessRecord enterByPermit(String name) {
  var permit=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),new VisitModels.CreatePermit(name,null,null,now(),now().plus(Duration.ofHours(2)))).permit();
  return access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(null,permit.permitId(),"DOCUMENT_CHECKED",null)).record();
 }
 private AccessModels.AccessRecord enterByArrival(String name,String kind) {
  var arrival=visits.register(concierge,db.tenantA,AAL2,key(),new VisitModels.RegisterArrival(db.unitA,name,null,null)).arrival();
  visits.decide(db.accountA,db.tenantA,arrival.arrivalId(),new VisitModels.Decision("APPROVE"));
  return access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(arrival.arrivalId(),null,"DOCUMENT_CHECKED",null,kind)).record();
 }

 @Test void entryOpensStayAndOnlyTheUnitResidentConfirmsTheExit() throws Exception {
  var entry=enterByPermit("Visita Permanencia");
  UUID stay=stayOf(entry.entryId());
  long viewed=Long.parseLong(db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' and details->>'view'='STAYS_NOW'",db.tenantA));
  var open=stays.open(concierge,db.tenantA,AAL2,false).stays();
  var listed=open.stream().filter(s->s.stayId().equals(stay)).findFirst().orElseThrow();
  assertEquals("VISITOR",listed.kind()); assertEquals("MANUAL",listed.origin()); assertEquals("PERMIT",listed.basis());
  assertEquals("Visita Permanencia",listed.visitorName()); assertEquals("NONE",listed.level()); assertFalse(listed.overdue());
  assertEquals(viewed+1,Long.parseLong(db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' and details->>'view'='STAYS_NOW'",db.tenantA)),
   "Leitura do painel pela equipe grava SENSITIVE_VIEWED");
  assertEquals(1,events(stay,"STAY_OPENED"));
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->stays.open(concierge,db.tenantA,AssuranceLevel.AAL1,false)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->stays.open(db.accountA,db.tenantA,AAL2,false)).code());
  // Unidade: vê a própria permanência, sem anotações da equipe; outra unidade não vê nem confirma.
  assertTrue(stays.unitOpen(db.accountA,db.tenantA,db.unitA).stays().stream().anyMatch(s->s.stayId().equals(stay)));
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->stays.unitOpen(db.accountC,db.tenantA,db.unitA)).code());
  assertEquals("STAY_CONFIRMATION_DENIED",assertThrows(BusinessProblem.class,()->stays.confirmExit(db.accountC,db.tenantA,db.unitA,stay)).code());
  assertEquals("STAY_NOT_FOUND",assertThrows(BusinessProblem.class,()->stays.confirmExit(db.accountC,db.tenantA,db.otherUnitA,stay)).code());
  var confirmed=stays.confirmExit(db.accountA,db.tenantA,db.unitA,stay);
  assertFalse(confirmed.replayed()); assertEquals("CLOSED",confirmed.stay().state()); assertEquals("RESIDENT_CONFIRMED",confirmed.stay().closure());
  assertTrue(stays.confirmExit(db.accountA,db.tenantA,db.unitA,stay).replayed(),"Repetição devolve o mesmo resultado");
  assertEquals("RESIDENT_APP",db.adminString("select source from cp.security_log where subject_id=? and event_type='STAY_CLOSED'",stay));
  // A portaria ainda registra a saída manual da entrada; a permanência continua fechada pelo morador.
  access.exit(concierge,db.tenantA,AAL2,entry.entryId(),key(),null);
  assertEquals("CLOSED:RESIDENT_CONFIRMED",db.adminString("select state||':'||closure from cp.stays where id=?",stay));
  assertEquals(1,events(stay,"STAY_CLOSED"));
  db.assertEmptyContext();
 }

 @Test void workerAlertsOnceAndJustificationStopsEscalation() throws Exception {
  var entry=enterByArrival("Entregador Permanencia","DELIVERY");
  UUID stay=stayOf(entry.entryId());
  assertEquals("DELIVERY:PLATFORM_DEFAULT",db.adminString("select kind||':'||expected_source from cp.stays where id=?",stay));
  db.adminUpdateWithoutTriggers("update cp.stays set entered_at=now()-interval '2 hours',expected_exit_at=now()-interval '30 minutes',"
   +"l1_at=now()-interval '5 minutes',l2_at=now()+interval '1 hour' where id=?",stay);
  assertTrue(job.runOnce().stays()>=1);
  assertEquals(1,events(stay,"STAY_DUE")); assertEquals(1,events(stay,"STAY_OVERDUE_L1")); assertEquals(0,events(stay,"STAY_OVERDUE_L2"));
  job.runOnce();
  assertEquals(1,events(stay,"STAY_DUE"),"Passada repetida não grava de novo"); assertEquals(1,events(stay,"STAY_OVERDUE_L1"));
  var overdue=stays.open(concierge,db.tenantA,AAL2,true).stays().stream().filter(s->s.stayId().equals(stay)).findFirst().orElseThrow();
  assertTrue(overdue.overdue()); assertEquals("L1",overdue.level()); assertEquals("L1",overdue.recordedLevel());
  assertEquals("STAY_JUSTIFICATION_REQUIRED",assertThrows(BusinessProblem.class,()->stays.justify(concierge,db.tenantA,AAL2,stay,new StayModels.Justify(" "))).code());
  var justified=stays.justify(concierge,db.tenantA,AAL2,stay,new StayModels.Justify("Entregador aguardando o morador"));
  assertTrue(justified.stay().justified()); assertFalse(justified.replayed());
  assertTrue(stays.justify(concierge,db.tenantA,AAL2,stay,new StayModels.Justify("Entregador aguardando o morador")).replayed());
  assertEquals("STAY_ALREADY_JUSTIFIED",assertThrows(BusinessProblem.class,()->stays.justify(concierge,db.tenantA,AAL2,stay,new StayModels.Justify("Outro motivo"))).code());
  assertNull(stays.unitOpen(db.accountA,db.tenantA,db.unitA).stays().stream().filter(s->s.stayId().equals(stay)).findFirst().orElseThrow().justification(),
   "Justificativa é anotação da equipe");
  db.adminUpdateWithoutTriggers("update cp.stays set l2_at=now()-interval '1 minute' where id=?",stay);
  job.runOnce();
  assertEquals(0,events(stay,"STAY_OVERDUE_L2"),"Permanência justificada não escala");
  assertEquals("CONCIERGE",db.adminString("select source from cp.security_log where subject_id=? and event_type='STAY_JUSTIFIED'",stay));
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void dayCloseListsEntriesWithoutExitAndManualExitCloses() throws Exception {
  var left=enterByPermit("Visita Que Saiu");
  var stayed=enterByPermit("Visita Sem Saida");
  var forged=assertThrows(org.springframework.dao.DataAccessException.class,()->new WorkerTransactions(workerDb.jdbc,workerDb.manager).inTenant(db.tenantA,
   ()->workerDb.jdbc.update("insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) values(?,'STAY_OVERDUE',?,0,jsonb_build_object('unitId',?::text)) on conflict do nothing",db.tenantA,stay,db.otherUnitA)));
  assertEquals("42501",br.com.condpulse.shared.infrastructure.SqlStates.of(forged),"Outro destinatário é barrado por RLS, não pelo índice de duplicação");
  access.exit(concierge,db.tenantA,AAL2,left.entryId(),key(),null);
  assertEquals("CLOSED:EXIT_RECORDED",db.adminString("select state||':'||closure from cp.stays where entry_event_id=?",left.entryId()));
  var report=stays.dayClose(concierge,db.tenantA,AAL2,null);
  var ids=report.entries().stream().map(StayModels.Stay::entryEventId).toList();
  assertTrue(ids.contains(stayed.entryId())); assertFalse(ids.contains(left.entryId()));
  assertEquals(report.entries().size(),report.stillOpen()); assertEquals(64,report.listHash().length());
  assertEquals(report.listHash(),db.adminString("select details->>'listHash' from cp.security_log where tenant_id=? and event_type='SENSITIVE_VIEWED' "
   +"and details->>'view'='STAY_DAY_CLOSE' order by seq desc limit 1",db.tenantA),"Evidência do relatório no Log de Segurança");
  assertEquals("STAY_DAY_CLOSE_DATE_INVALID",assertThrows(BusinessProblem.class,()->stays.dayClose(concierge,db.tenantA,AAL2,LocalDate.now().plusDays(2))).code());
  // Correção da entrada anula a permanência: sai do relatório.
  access.correct(concierge,db.tenantA,AAL2,stayed.entryId(),key(),new AccessModels.Correct("Pessoa não entrou"));
  assertFalse(stays.dayClose(concierge,db.tenantA,AAL2,null).entries().stream().anyMatch(s->s.entryEventId().equals(stayed.entryId())));
  db.assertEmptyContext();
 }

 @Test void managerChangesTimeRulesAndEntryValidatesKind() throws Exception {
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->stays.rules(concierge,db.tenantA,AAL2)).code());
  var initial=stays.rules(manager,db.tenantA,AAL2).rules();
  assertEquals(StayModels.KINDS,initial.stream().map(StayModels.Rule::kind).toList());
  assertTrue(initial.stream().allMatch(r->"PLATFORM_DEFAULT".equals(r.source()) && r.version()==null));
  assertEquals("STAY_RULE_INVALID",assertThrows(BusinessProblem.class,()->stays.changeRule(manager,db.tenantA,AAL2,"DELIVERY",
   new StayModels.RuleChange(20,30,10,null))).code());
  var created=stays.changeRule(manager,db.tenantA,AAL2,"DELIVERY",new StayModels.RuleChange(20,5,15,null)).rules();
  var delivery=created.stream().filter(r->r.kind().equals("DELIVERY")).findFirst().orElseThrow();
  assertEquals("TENANT_RULE",delivery.source()); assertEquals(Long.valueOf(0),delivery.version()); assertEquals(20,delivery.maxMinutes());
  assertEquals("STAY_RULE_VERSION_CONFLICT",assertThrows(BusinessProblem.class,()->stays.changeRule(manager,db.tenantA,AAL2,"DELIVERY",
   new StayModels.RuleChange(25,5,15,null))).code());
  stays.changeRule(manager,db.tenantA,AAL2,"DELIVERY",new StayModels.RuleChange(25,5,15,0L));
  assertEquals("STAY_RULE_VERSION_CONFLICT",assertThrows(BusinessProblem.class,()->stays.changeRule(manager,db.tenantA,AAL2,"DELIVERY",
   new StayModels.RuleChange(30,5,15,0L))).code());
  assertEquals("2",db.adminString("select count(*) from cp.security_log where tenant_id=? and event_type='RULE_CHANGED' and details->>'kind'='DELIVERY'",db.tenantA));
  var entry=enterByArrival("Entregador Regra","DELIVERY");
  assertEquals("TENANT_RULE:true",db.adminString("select expected_source||':'||(expected_exit_at=entered_at+interval '25 minutes') from cp.stays where entry_event_id=?",entry.entryId()));
  assertEquals("STAY_KIND_INVALID",assertThrows(BusinessProblem.class,()->enterByArrival("Tipo Errado","PEDESTRE")).code());
  db.adminUpdateWithoutTriggers("delete from cp.stay_rules where tenant_id=?",db.tenantA);
  db.assertEmptyContext();
 }

 @Test void dailyScheduleUsesCondominiumZoneAndHasExactBoundaries() throws Exception {
  assertEquals("20:00",db.adminString("select to_char(cp.stay_daily_exit('2030-01-02T11:00:00Z'::timestamptz,'America/Sao_Paulo','08:00'::time,'17:00'::time) at time zone 'UTC','HH24:MI')"));
  assertEquals("17:00",db.adminString("select to_char(cp.stay_daily_exit('2030-01-02T11:00:00Z'::timestamptz,'UTC','08:00'::time,'17:00'::time) at time zone 'UTC','HH24:MI')"));
  var before=assertThrows(java.sql.SQLException.class,()->db.adminString("select cp.stay_daily_exit('2030-01-02T10:59:59Z'::timestamptz,'America/Sao_Paulo','08:00'::time,'17:00'::time)::text"));
  assertEquals("P5701",before.getSQLState());
  var after=assertThrows(java.sql.SQLException.class,()->db.adminString("select cp.stay_daily_exit('2030-01-02T20:00:00Z'::timestamptz,'America/Sao_Paulo','08:00'::time,'17:00'::time)::text"));
  assertEquals("P5701",after.getSQLState());
  assertNull(db.adminString("select cp.stay_daily_exit('2030-01-02T00:00:00Z'::timestamptz,'America/Sao_Paulo',null,null)::text"));
 }

 @Test void scheduleIsVersionedAndLegacyUpdatesPreserveActivationAndHours() throws Exception {
  var configured=stays.changeRule(manager,db.tenantA,AAL2,"SERVICE_PROVIDER",new StayModels.RuleChange(540,30,60,null,true,true,"08:00","17:00"))
   .rules().stream().filter(r->r.kind().equals("SERVICE_PROVIDER")).findFirst().orElseThrow();
  assertTrue(configured.enabled()); assertTrue(configured.startsLocal().startsWith("08:00")); assertTrue(configured.endsLocal().startsWith("17:00"));
  assertEquals("STAY_SCHEDULE_INVALID",assertThrows(BusinessProblem.class,()->stays.changeRule(manager,db.tenantA,AAL2,"SERVICE_PROVIDER",
   new StayModels.RuleChange(540,30,60,0L,true,true,"17:00","08:00"))).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->stays.changeRule(manager,db.tenantA,AssuranceLevel.AAL1,"SERVICE_PROVIDER",
   new StayModels.RuleChange(540,30,60,0L,false,false,null,null))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->stays.changeRule(db.accountA,db.tenantA,AAL2,"SERVICE_PROVIDER",
   new StayModels.RuleChange(540,30,60,0L,false,false,null,null))).code());
  var preserved=stays.changeRule(manager,db.tenantA,AAL2,"SERVICE_PROVIDER",new StayModels.RuleChange(600,30,60,0L))
   .rules().stream().filter(r->r.kind().equals("SERVICE_PROVIDER")).findFirst().orElseThrow();
  assertTrue(preserved.enabled()); assertEquals(configured.startsLocal(),preserved.startsLocal());
  assertEquals("STAY_RULE_VERSION_CONFLICT",assertThrows(BusinessProblem.class,()->stays.changeRule(manager,db.tenantA,AAL2,"SERVICE_PROVIDER",
   new StayModels.RuleChange(540,30,60,0L,false,false,null,null))).code());
  assertEquals("true:08:00:00:17:00:00",db.adminString("select (details->>'enabled')||':'||(details->>'startsLocal')||':'||(details->>'endsLocal') from cp.security_log where tenant_id=? and event_type='RULE_CHANGED' and details->>'kind'='SERVICE_PROVIDER' order by seq desc limit 1",db.tenantA));
 }

 @Test void disabledControlKeepsTheStayButSuppressesAlertsAndReactivationIsIdempotent() throws Exception {
  stays.changeRule(manager,db.tenantA,AAL2,"VISITOR",new StayModels.RuleChange(240,0,60,null,false,false,null,null));
  var until=now().plusSeconds(8);
  var permit=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),new VisitModels.CreatePermit("Controle Desligado JDBC",null,null,now(),until)).permit();
  var entry=access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(null,permit.permitId(),"DOCUMENT_CHECKED",null)).record();
  UUID stay=stayOf(entry.entryId());
  assertFalse(stays.unitOpen(db.accountA,db.tenantA,db.unitA).stays().stream().filter(r->r.stayId().equals(stay)).findFirst().orElseThrow().controlEnabled());
  // O relógio real vence; nenhum gatilho, RLS ou relógio foi alterado para produzir o atraso.
  long wait=Duration.between(Instant.now(),until.plusMillis(300)).toMillis(); if(wait>0) Thread.sleep(wait);
  job.runOnce(); assertEquals(0,events(stay,"STAY_DUE")); assertEquals(0,events(stay,"STAY_OVERDUE_L1"));
  assertEquals("OPEN",db.adminString("select state from cp.stays where id=?",stay));
  stays.changeRule(manager,db.tenantA,AAL2,"VISITOR",new StayModels.RuleChange(240,0,60,0L,true,false,null,null));
  job.runOnce(); job.runOnce(); assertEquals(1,events(stay,"STAY_DUE")); assertEquals(1,events(stay,"STAY_OVERDUE_L1"));
  access.exit(concierge,db.tenantA,AAL2,entry.entryId(),key(),null);
  assertEquals("CLOSED",db.adminString("select state from cp.stays where id=?",stay));
 }

 @Test void overdueResidentNoticeIsDeliveredOnceAndClosedStayIsSkipped() throws Exception {
  stays.changeRule(manager,db.tenantA,AAL2,"VISITOR",new StayModels.RuleChange(240,0,60,null,true,false,null,null));
  var until=now().plusSeconds(8);
  var first=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),new VisitModels.CreatePermit("Aviso Permanencia JDBC",null,null,now(),until)).permit();
  var second=visits.createPermit(db.accountA,db.tenantA,db.unitA,key(),new VisitModels.CreatePermit("Saida Antes Aviso JDBC",null,null,now(),until)).permit();
  var entry=access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(null,first.permitId(),"DOCUMENT_CHECKED",null)).record();
  var left=access.entry(concierge,db.tenantA,AAL2,key(),new AccessModels.RecordEntry(null,second.permitId(),"DOCUMENT_CHECKED",null)).record();
  var stay=stayOf(entry.entryId()); var closedStay=stayOf(left.entryId());
  var premature=assertThrows(org.springframework.dao.DataAccessException.class,()->new TenantTransactionExecutor(db.jdbc,db.manager)
   .execute(db.accountA,db.tenantA,AssuranceLevel.AAL1,ctx->db.jdbc.update("insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) values(?,'STAY_OVERDUE',?,0,jsonb_build_object('unitId',?::text)) on conflict do nothing",db.tenantA,stay,db.unitA)));
  assertEquals("42501",br.com.condpulse.shared.infrastructure.SqlStates.of(premature),"API não pode consumir a chave de aviso antes da fonte real");

  long wait=Duration.between(Instant.now(),until.plusMillis(300)).toMillis(); if(wait>0) Thread.sleep(wait);
  job.runOnce(); job.runOnce();
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='STAY_OVERDUE'",stay));
  var forged=assertThrows(org.springframework.dao.DataAccessException.class,()->new WorkerTransactions(workerDb.jdbc,workerDb.manager).inTenant(db.tenantA,
   ()->workerDb.jdbc.update("insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) values(?,'STAY_OVERDUE',?,0,jsonb_build_object('unitId',?::text)) on conflict do nothing",db.tenantA,stay,db.otherUnitA)));
  assertEquals("42501",br.com.condpulse.shared.infrastructure.SqlStates.of(forged),"Outro destinatário é barrado por RLS, não pelo índice de duplicação");
  access.exit(concierge,db.tenantA,AAL2,left.entryId(),key(),null);
  var delivery=new br.com.condpulse.notifications.application.OutboxWorker(
   new WorkerTransactions(workerDb.jdbc,workerDb.manager),new br.com.condpulse.notifications.infrastructure.OutboxStore(workerDb.jdbc),
   java.util.List.of(new br.com.condpulse.notifications.application.InboxEffect(new br.com.condpulse.notifications.infrastructure.InboxStore(workerDb.jdbc))));
  delivery.runTenant(db.tenantA); delivery.runTenant(db.tenantA);
  assertEquals("1",db.adminString("select count(*) from cp.notifications where resource_id=? and kind='STAY_OVERDUE'",stay));
  assertEquals("0",db.adminString("select count(*) from cp.notifications where resource_id=? and kind='STAY_OVERDUE'",closedStay));
  assertEquals("SKIPPED",db.adminString("select state from cp.outbox where aggregate_id=? and event_type='STAY_OVERDUE'",closedStay));
  var notifications=new br.com.condpulse.notifications.application.NotificationService(new TenantTransactionExecutor(db.jdbc,db.manager),
   new br.com.condpulse.notifications.infrastructure.NotificationStore(db.jdbc));
  var page=notifications.unit(db.accountA,db.tenantA,db.unitA);
  var notice=page.items().stream().filter(n->n.resourceId().equals(stay)).findFirst().orElseThrow();
  assertEquals("AVAILABLE",notice.state()); assertEquals("OPEN",notice.resourceState());
  assertFalse(notifications.unit(db.accountC,db.tenantA,db.otherUnitA).items().stream().anyMatch(n->n.resourceId().equals(stay)));
  assertEquals("READ_IN_APP",notifications.open(db.accountA,db.tenantA,notice.notificationId()).state());
  assertEquals("1",db.adminString("select count(*) from cp.notifications where resource_id=? and kind='STAY_OVERDUE'",stay));
 }

 @Test void configuredWindowDeniesActualEntryAndSwitchOffAllowsItWithoutErasingHistory() throws Exception {
  int hour=Instant.now().atZone(java.time.ZoneId.of("America/Sao_Paulo")).getHour();
  String start=hour<12?"18:00":"01:00",end=hour<12?"19:00":"02:00";
  stays.changeRule(manager,db.tenantA,AAL2,"SERVICE_PROVIDER",new StayModels.RuleChange(540,30,60,null,true,true,start,end));
  assertEquals("STAY_ENTRY_OUTSIDE_SCHEDULE",assertThrows(BusinessProblem.class,()->enterByArrival("Horario Negado JDBC","SERVICE_PROVIDER")).code());
  assertEquals("0",db.adminString("select count(*) from cp.access_events where tenant_id=? and event_type='MANUAL_IN'",db.tenantA));
  stays.changeRule(manager,db.tenantA,AAL2,"SERVICE_PROVIDER",new StayModels.RuleChange(540,30,60,0L,false,true,start,end));
  var admitted=enterByArrival("Horario Desligado JDBC","SERVICE_PROVIDER");
  UUID stay=stayOf(admitted.entryId());
  assertEquals("OPEN",db.adminString("select state from cp.stays where id=?",stay));
  assertFalse(stays.unitOpen(db.accountA,db.tenantA,db.unitA).stays().stream().filter(x->x.stayId().equals(stay)).findFirst().orElseThrow().controlEnabled());
  access.exit(concierge,db.tenantA,AAL2,admitted.entryId(),key(),null);
  assertEquals("CLOSED",db.adminString("select state from cp.stays where id=?",stay));
 }
}
