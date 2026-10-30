package br.com.condpulse.database;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.moves.application.MoveModels;
import br.com.condpulse.moves.application.MoveService;
import br.com.condpulse.moves.infrastructure.MoveStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V025 com PostgreSQL real: regras da gestão, pedido da unidade no fuso do condomínio, decisão e visão da portaria. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class MoveJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
 private static JdbcDisposableDatabase db;
 private static MoveService moves;
 private static UUID manager,concierge;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  moves=new MoveService(new TenantTransactionExecutor(db.jdbc,db.manager),new MoveStore(db.jdbc),new AuditRecorder(db.jdbc));
  manager=UUID.randomUUID(); concierge=UUID.randomUUID(); db.extraAccounts.add(manager); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",manager,concierge);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
   values(?,?,null,'GESTAO MUDANCA JDBC','MANAGER','ACTIVE'),(?,?,null,'PORTARIA MUDANCA JDBC','CONCIERGE','ACTIVE')
   """,db.tenantA,manager,db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "move-jdbc-"+UUID.randomUUID(); }
 private static java.time.Instant at(int hour) { return LocalDate.now(ZONE).plusDays(3).atTime(hour,0).atZone(ZONE).toInstant(); }
 private static MoveModels.Rules configure() {
  return moves.updateRules(manager,db.tenantA,AAL2,new MoveModels.RulesUpdate("Use o elevador de serviço com proteção.",List.of(1,2,3,4,5,6,7),
   "08:00","18:00",24,240,null));
 }
 private static MoveModels.Create request(int from,int until,int version) {
  return new MoveModels.Create("MOVE_IN",at(from),at(until)," Transportadora   Fictícia ","abc-1d23","Sofá e caixas",version);
 }

 @Test void rulesRequestDecisionAndConciergeView() throws Exception {
  assertNull(moves.rules(db.accountA,db.tenantA,AssuranceLevel.AAL1).rules());
  assertEquals("MOVE_RULES_NOT_CONFIGURED",assertThrows(BusinessProblem.class,()->moves.create(db.accountA,db.tenantA,db.unitA,key(),request(9,12,1))).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->moves.updateRules(manager,db.tenantA,AssuranceLevel.AAL1,
   new MoveModels.RulesUpdate("x",List.of(1),"08:00","18:00",0,60,null))).code());
  var rules=configure();
  assertEquals(1,rules.version()); assertEquals("08:00",rules.startTime());
  assertEquals("Use o elevador de serviço com proteção.",moves.rules(db.accountA,db.tenantA,AssuranceLevel.AAL1).rules().rulesText());
  String createKey=key();
  var created=moves.create(db.accountA,db.tenantA,db.unitA,createKey,request(9,12,1));
  var replay=moves.create(db.accountA,db.tenantA,db.unitA,createKey,request(9,12,1));
  assertFalse(created.replayed()); assertTrue(replay.replayed()); assertEquals(created.move(),replay.move());
  var move=created.move();
  assertEquals("REQUESTED",move.state()); assertEquals("Transportadora Fictícia",move.providerName()); assertEquals("ABC1D23",move.vehiclePlate());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='MOVE_REQUESTED' and aggregate_id=?",move.moveId()));
  assertTrue(moves.concierge(concierge,db.tenantA,AAL2,at(0),at(23)).moves().isEmpty(),"Portaria não vê pedido não aprovado");
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->moves.unitMoves(db.accountC,db.tenantA,db.unitA)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->moves.decide(db.accountA,db.tenantA,AAL2,move.moveId(),
   new MoveModels.Decision("APPROVE",null,move.version()))).code());
  assertEquals("MOVE_NOTE_REQUIRED",assertThrows(BusinessProblem.class,()->moves.decide(manager,db.tenantA,AAL2,move.moveId(),
   new MoveModels.Decision("REJECT"," ",move.version()))).code());
  var approved=moves.decide(manager,db.tenantA,AAL2,move.moveId(),new MoveModels.Decision("APPROVE","Use a garagem",move.version()));
  assertEquals("APPROVED",approved.state()); assertEquals("Use a garagem",approved.decisionNote());
  assertEquals(approved,moves.decide(manager,db.tenantA,AAL2,move.moveId(),new MoveModels.Decision("APPROVE","Use a garagem",move.version())),
   "Repetir a decisão devolve o estado");
  assertEquals(List.of(move.moveId()),moves.concierge(concierge,db.tenantA,AAL2,at(0),at(23)).moves().stream().map(MoveModels.Move::moveId).toList());
  var cancelled=moves.cancel(db.accountA,db.tenantA,AssuranceLevel.AAL1,move.moveId(),new MoveModels.Cancel(null,approved.version()));
  assertEquals("CANCELLED",cancelled.state());
  assertTrue(moves.concierge(concierge,db.tenantA,AAL2,at(0),at(23)).moves().isEmpty(),"Cancelada sai da portaria");
  db.assertEmptyContext();
 }

 @Test void requestsWhosePeriodAlreadyStartedLeaveTheDecisionQueue() throws Exception {
  configure();
  var stale=moves.create(db.accountA,db.tenantA,db.unitA,key(),request(9,10,1)).move();
  var fresh=moves.create(db.accountA,db.tenantA,db.unitA,key(),request(11,12,1)).move();
  // Pedido esquecido: o período começou sem decisão. Não pode mais ser decidido, então não ocupa a fila da gestão.
  db.adminUpdateWithoutTriggers("update cp.move_requests set starts_at=clock_timestamp()-interval '1 hour',ends_at=clock_timestamp()+interval '1 hour' where id=?",
   stale.moveId());
  assertEquals(List.of(fresh.moveId()),moves.managed(manager,db.tenantA,AAL2,"REQUESTED").moves().stream().map(MoveModels.Move::moveId).toList());
  assertTrue(moves.managed(manager,db.tenantA,AAL2,null).moves().stream().anyMatch(item->item.moveId().equals(stale.moveId())),
   "Continua visível na lista geral");
  db.assertEmptyContext();
 }

 @Test void periodsOutsideTheRulesAndChangedRulesAreRefused() throws Exception {
  configure();
  assertEquals("MOVE_WINDOW_OUTSIDE_RULES",assertThrows(BusinessProblem.class,()->moves.create(db.accountA,db.tenantA,db.unitA,key(),request(7,9,1))).code());
  assertEquals("MOVE_WINDOW_OUTSIDE_RULES",assertThrows(BusinessProblem.class,()->moves.create(db.accountA,db.tenantA,db.unitA,key(),request(9,14,1))).code(),
   "Mais que a duração máxima");
  assertEquals("MOVE_WINDOW_OUTSIDE_RULES",assertThrows(BusinessProblem.class,()->moves.create(db.accountA,db.tenantA,db.unitA,key(),
   new MoveModels.Create("MOVE_IN",java.time.Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
    java.time.Instant.now().plusSeconds(10800).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),"Fulano",null,null,1))).code(),"Sem antecedência");
  assertEquals("MOVE_PLATE_INVALID",assertThrows(BusinessProblem.class,()->moves.create(db.accountA,db.tenantA,db.unitA,key(),
   new MoveModels.Create("MOVE_IN",at(9),at(10),"Fulano","AB",null,1))).code());
  var current=moves.rules(manager,db.tenantA,AAL2).rules();
  moves.updateRules(manager,db.tenantA,AAL2,new MoveModels.RulesUpdate("Novas regras",List.of(1,2,3,4,5),"09:00","17:00",48,180,current.version()));
  assertEquals("MOVE_RULES_CHANGED",assertThrows(BusinessProblem.class,()->moves.updateRules(manager,db.tenantA,AAL2,
   new MoveModels.RulesUpdate("Outra",List.of(1),"09:00","17:00",0,60,current.version()))).code());
  assertEquals("MOVE_RULES_CHANGED",assertThrows(BusinessProblem.class,()->moves.create(db.accountA,db.tenantA,db.unitA,key(),request(10,11,1))).code());
  assertEquals("0",db.adminString("select count(*) from cp.move_requests where tenant_id=?",db.tenantA));
  db.assertEmptyContext();
 }
}
