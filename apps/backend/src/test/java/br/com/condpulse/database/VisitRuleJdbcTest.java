package br.com.condpulse.database;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
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

/** V022 com PostgreSQL real: regra de prestador gera janelas no fuso do condomínio; fora delas não há convite. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class VisitRuleJdbcTest {
 private static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
 private static JdbcDisposableDatabase db;
 private static VisitService visits;
 private static UUID concierge;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  visits=new VisitService(new TenantTransactionExecutor(db.jdbc,db.manager),new VisitStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID(); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA REGRAS JDBC','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "rule-jdbc-"+UUID.randomUUID(); }
 private static LocalDate today() { return LocalDate.now(ZONE); }
 private static VisitModels.CreateRule allDay(List<Integer> days) {
  return new VisitModels.CreateRule("Prestador Fictício","9z","Limpeza",days,"00:00","23:59",today(),today().plusDays(30));
 }

 @Test void ruleGeneratesLocalWindowsTheConciergeSeesTodayAndRepeatingTheKeyDoesNotDuplicate() throws Exception {
  String key=key();
  var first=visits.createRule(db.accountA,db.tenantA,db.unitA,key,allDay(List.of(1,2,3,4,5,6,7)));
  var replay=visits.createRule(db.accountA,db.tenantA,db.unitA,key,allDay(List.of(1,2,3,4,5,6,7)));
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(first.rule(),replay.rule());
  var rule=first.rule();
  assertEquals("ACTIVE",rule.state()); assertEquals(List.of(1,2,3,4,5,6,7),rule.weekDays());
  assertEquals("00:00",rule.startTime()); assertEquals("23:59",rule.endTime());
  assertNotNull(rule.nextWindowFrom());
  assertEquals(today().atTime(0,0).atZone(ZONE).toInstant(),rule.nextWindowFrom(),"Janela de hoje no horário local do condomínio");
  assertEquals("8",db.adminString("select count(*) from cp.visit_permits where rule_id=?",rule.ruleId()),"Hoje e os 7 dias seguintes");
  var expected=visits.expected(concierge,db.tenantA,AssuranceLevel.AAL2,db.unitA,null).permits();
  assertTrue(expected.stream().anyMatch(p->rule.ruleId().equals(p.ruleId()) && "ACTIVE".equals(p.state())),"Portaria vê a janela de hoje");
  assertEquals("1",db.adminString("select count(*) from cp.visit_rules where tenant_id=?",db.tenantA));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='VISIT_RULE_CREATED'",rule.ruleId()));
  db.assertEmptyContext();
 }

 @Test void outsideTheRuleDaysThereIsNoPermitForTheConcierge() throws Exception {
  int tomorrow=today().plusDays(1).getDayOfWeek().getValue();
  var rule=visits.createRule(db.accountA,db.tenantA,db.unitA,key(),allDay(List.of(tomorrow))).rule();
  var expectedToday=visits.expected(concierge,db.tenantA,AssuranceLevel.AAL2,db.unitA,null).permits().stream()
   .filter(p->rule.ruleId().equals(p.ruleId())).toList();
  assertTrue(expectedToday.stream().noneMatch(p->"ACTIVE".equals(p.state())),"Hoje não é dia da regra: nada em vigor");
  assertEquals(today().plusDays(1).atStartOfDay(ZONE).toInstant(),rule.nextWindowFrom(),"Próxima janela é amanhã");
  db.assertEmptyContext();
 }

 @Test void invalidRulesOtherUnitAndOtherTenantAreRefused() throws Exception {
  assertEquals("VISIT_RULE_DAYS_INVALID",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountA,db.tenantA,db.unitA,key(),
   allDay(List.of(1,1)))).code());
  assertEquals("VISIT_RULE_DAYS_INVALID",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountA,db.tenantA,db.unitA,key(),
   allDay(List.of(8)))).code());
  assertEquals("VISIT_RULE_TIME_INVALID",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountA,db.tenantA,db.unitA,key(),
   new VisitModels.CreateRule("Prestador Fictício",null,null,List.of(1),"18:00","08:00",today(),today().plusDays(5)))).code());
  assertEquals("VISIT_RULE_PERIOD_INVALID",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountA,db.tenantA,db.unitA,key(),
   new VisitModels.CreateRule("Prestador Fictício",null,null,List.of(1),"08:00","18:00",today(),today().plusDays(181)))).code());
  assertEquals("VISIT_RULE_PERIOD_INVALID",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountA,db.tenantA,db.unitA,key(),
   new VisitModels.CreateRule("Prestador Fictício",null,null,List.of(1),"08:00","18:00",today().minusDays(1),today().plusDays(5)))).code());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountC,db.tenantA,db.unitA,key(),
   allDay(List.of(1)))).code());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.createRule(db.accountB,db.tenantA,db.unitA,key(),
   allDay(List.of(1)))).code());
  assertEquals("0",db.adminString("select count(*) from cp.visit_rules where tenant_id=?",db.tenantA));
  db.assertEmptyContext();
 }

 @Test void revokingTheRuleRevokesItsWindowsAndTheConciergeStopsSeeingThem() throws Exception {
  var rule=visits.createRule(db.accountA,db.tenantA,db.unitA,key(),allDay(List.of(1,2,3,4,5,6,7))).rule();
  assertEquals("VISIT_RULE_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.revokeRule(db.accountC,db.tenantA,rule.ruleId(),
   new VisitModels.RevokeRule("Outra unidade",0L))).code());
  assertEquals("VISIT_RULE_CHANGED",assertThrows(BusinessProblem.class,()->visits.revokeRule(db.accountA,db.tenantA,rule.ruleId(),
   new VisitModels.RevokeRule("Versão errada",5L))).code());
  var revoked=visits.revokeRule(db.accountA,db.tenantA,rule.ruleId(),new VisitModels.RevokeRule("Contrato encerrado",0L));
  assertEquals("REVOKED",revoked.state()); assertNull(revoked.nextWindowFrom());
  assertEquals("0",db.adminString("select count(*) from cp.visit_permits where rule_id=? and state='ACTIVE'",rule.ruleId()));
  assertTrue(visits.expected(concierge,db.tenantA,AssuranceLevel.AAL2,db.unitA,null).permits().stream()
   .noneMatch(p->rule.ruleId().equals(p.ruleId())),"Portaria não vê janelas de regra revogada");
  assertEquals("VISIT_RULE_REVOKED",assertThrows(BusinessProblem.class,()->visits.revokeRule(db.accountA,db.tenantA,rule.ruleId(),
   new VisitModels.RevokeRule("De novo",1L))).code());
  assertEquals(1,visits.unitRules(db.accountA,db.tenantA,db.unitA).rules().size());
  db.assertEmptyContext();
 }
}
