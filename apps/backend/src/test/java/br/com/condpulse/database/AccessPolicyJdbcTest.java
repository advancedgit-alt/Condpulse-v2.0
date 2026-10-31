package br.com.condpulse.database;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessmap.application.MapModels;
import br.com.condpulse.accesspolicy.application.PolicyModels;
import br.com.condpulse.accesspolicy.application.PolicyService;
import br.com.condpulse.accesspolicy.infrastructure.PolicyStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V043 com PostgreSQL real e o login da API. A instalação é montada pelos fluxos reais (catálogo, mapa e instalação, com
 * gatilhos ligados): política, revisão, validação contra a passagem comissionada, aprovação presa aos fatos e simulação.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessPolicyJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),MASTER=UUID.randomUUID();
 private static final List<String> APPROVED=List.of("faceEnrollment","faceDeletion","perDoorRights","secureTransport","offlineRestriction","localValidity");
 private static JdbcDisposableDatabase db;
 private static PolicyService policies;
 private static FacialInstallationFixture fixture;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  policies=new PolicyService(new TenantTransactionExecutor(db.jdbc,db.manager),new PolicyStore(db.jdbc),new AuditRecorder(db.jdbc));
  fixture=new FacialInstallationFixture(db);
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(MASTER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,MASTER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO POLITICA JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  fixture.clear(); db.restoreActiveFixtures(); db.assertEmptyContext();
  fixture.grantMaster(MASTER);
 }
 @AfterEach void clear() throws Exception { fixture.clear(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static PolicyModels.PolicyDocument rule(String zone,UUID area,String companion) {
  return new PolicyModels.PolicyDocument("1.0","Academia 18+",area,zone,"ALL",List.of("RESIDENT","DEPENDENT"),"DENY",18,true,
   List.of(new PolicyModels.WeeklyWindow(List.of("MON","TUE","WED","THU","FRI","SAT","SUN"),"06:00","22:00",false)),List.of(),"HOLIDAY_OVERRIDES_WEEKLY",
   new PolicyModels.ReservationRule("NOT_REQUIRED",0,0),new PolicyModels.CompanionRule(companion,"NONE".equals(companion)?0:30),
   new PolicyModels.OfflineRule("SAFE_LOCAL_SUBSET",300,true),new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY");
 }
 private static long pointVersion(UUID point) {
  return fixture.map.technicalPoints(MASTER,db.tenantA,AAL2).points().stream().filter(p->p.pointId().equals(point)).findFirst().orElseThrow().version();
 }
 private static long versionOf(UUID policy,UUID version) {
  return policies.versions(MANAGER,db.tenantA,AAL2,policy).versions().stream().filter(v->v.versionId().equals(version)).findFirst().orElseThrow().version();
 }

 @Test void approvalFollowsCurrentFactsAndSimulationNeverCommands() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.3.1");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID policyId=UUID.randomUUID(),blockedId=UUID.randomUUID(),versionId=UUID.randomUUID(),secondId=UUID.randomUUID();
  var create=new PolicyModels.CreatePolicy(policyId,inst.area(),"Academia");
  assertEquals("MFA_REQUIRED",code(()->policies.createPolicy(MANAGER,db.tenantA,AAL1,create)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->policies.createPolicy(db.accountA,db.tenantA,AAL2,create)));
  var policy=policies.createPolicy(MANAGER,db.tenantA,AAL2,create);
  assertEquals(policy,policies.createPolicy(MANAGER,db.tenantA,AAL2,create),"Mesmo pedido é repetição");
  assertEquals("POLICY_CONFLICT",code(()->policies.createPolicy(MANAGER,db.tenantA,AAL2,new PolicyModels.CreatePolicy(UUID.randomUUID(),inst.area(),"Outra"))));
  assertEquals("POLICY_AREA_MISMATCH",code(()->policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,
   rule(zone,inst.hall(),"NONE"),"Regra da academia ficticia"))));

  // HAC-043: atendimento por equipe exige passageEvents, não aprovado para a combinação: BLOCKED e revisão em DRAFT.
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(blockedId,rule(zone,inst.area(),"ASSISTED_BY_STAFF"),
   "Regra com atendimento ficticia"));
  var blocked=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(blockedId));
  assertEquals("BLOCKED",blocked.status()); assertEquals(List.of(inst.point()),blocked.affectedPointIds());
  assertTrue(blocked.issues().stream().anyMatch(i->"CAPABILITY_MISSING".equals(i.code()) && "passageEvents".equals(i.capability())));
  var afterBlocked=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().stream().filter(v->v.versionId().equals(blockedId)).findFirst().orElseThrow();
  assertEquals("DRAFT",afterBlocked.state()); assertEquals(blocked.validationId(),afterBlocked.validationId());
  assertEquals("POLICY_VALIDATION_STALE",code(()->policies.approve(MANAGER,db.tenantA,AAL2,policyId,blockedId,new PolicyModels.VersionAction(afterBlocked.version()))));

  var draft=policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,rule(zone,inst.area(),"NONE"),"Regra da academia ficticia"));
  assertEquals(rule(zone,inst.area(),"NONE"),draft.document(),"Documento lido igual ao gravado");
  var validated=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId));
  assertEquals("VALIDATED",validated.status(),validated.issues().toString()); assertEquals(1,validated.installationRevision());

  // Fatos mudam depois da validação: passagem nova não comissionada invalida a aprovação; ao retirá-la, os fatos voltam.
  UUID extra=UUID.randomUUID();
  fixture.map.createPoint(MASTER,db.tenantA,AAL2,new MapModels.CreatePoint(extra,"PNOVA","Porta nova",inst.hall(),inst.area(),"DOOR","FROM_TO","FREE_EGRESS",
   null,null,"MAPPED"));
  assertEquals("POLICY_VALIDATION_STALE",code(()->policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(versionOf(policyId,versionId)))));
  fixture.map.updatePoint(MASTER,db.tenantA,AAL2,extra,new MapModels.UpdatePoint("Porta nova",inst.hall(),inst.area(),"DOOR","FROM_TO","FREE_EGRESS",null,null,
   "RETIRED",0L));
  var approved=policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(versionOf(policyId,versionId)));
  assertEquals("APPROVED",approved.state()); assertEquals(MANAGER,approved.approvedBy());

  // Simulação sintética: nunca cria comando físico.
  Instant saturday10=java.time.ZonedDateTime.of(2026,10,31,10,0,0,0,java.time.ZoneId.of(zone)).toInstant();
  var adult=policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest("SYNTHETIC",versionId,inst.point(),
   new PolicyModels.SyntheticSubject("RESIDENT","VERIFIED_MEETS_MINIMUM",false,false,false),saturday10));
  assertEquals("ALLOW",adult.result(),String.join(",",adult.reasonCodes()));
  assertTrue(adult.simulated()); assertFalse(adult.hardwareCommandCreated()); assertEquals(1,adult.bindingRevision());
  var minor=policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest("SYNTHETIC",versionId,inst.point(),
   new PolicyModels.SyntheticSubject("DEPENDENT","VERIFIED_BELOW_MINIMUM",false,false,false),saturday10));
  assertEquals("DENY",minor.result()); assertTrue(minor.reasonCodes().contains("AGE_BELOW_MINIMUM"),"HAC-031");
  assertEquals("POLICY_SIMULATION_MODE_UNSUPPORTED",code(()->policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest(
   "AUTHORIZED_EXISTING_SUBJECT",versionId,inst.point(),null,saturday10))));
  assertEquals("POLICY_POINT_NOT_IN_AREA",code(()->policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest("SYNTHETIC",versionId,
   UUID.randomUUID(),new PolicyModels.SyntheticSubject("RESIDENT","UNKNOWN",false,false,false),saturday10))));
  assertEquals("0",db.adminString("select count(*)::text from cp.outbox where tenant_id=? and event_type like 'access%'",db.tenantA),"Simulação não cria comando");

  // Passagem validada que volta a MAPPED por mudança física: a aprovação exige validar de novo.
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(secondId,rule(zone,inst.area(),"NONE"),"Segunda regra ficticia"));
  assertEquals("VALIDATED",policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(secondId)).status());
  fixture.map.updatePoint(MASTER,db.tenantA,AAL2,inst.point(),new MapModels.UpdatePoint("Porta ficticia",inst.hall(),inst.area(),"DOOR","BOTH","FREE_EGRESS",
   null,null,"COMMISSIONED",pointVersion(inst.point())));
  assertEquals("MAPPED",db.adminString("select status from cp.access_points where id=?",inst.point()));
  assertEquals("POLICY_VALIDATION_STALE",code(()->policies.approve(MANAGER,db.tenantA,AAL2,policyId,secondId,new PolicyModels.VersionAction(versionOf(policyId,secondId)))));
  assertEquals("TENANT_ACCESS_DENIED",code(()->policies.policies(MANAGER,db.tenantB,AAL2)),"HAC-007");
  db.assertEmptyContext();
 }

 @Test void largeInstallationPersistsAnAggregatedBlockedReport() throws Exception {
  var inst=fixture.commissioned(MANAGER,MASTER,APPROVED,"4.3.2");
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  for(int i=0;i<320;i++)
   fixture.map.createPoint(MASTER,db.tenantA,AAL2,new MapModels.CreatePoint(UUID.randomUUID(),"PX"+i,"Porta "+i,inst.hall(),inst.area(),"DOOR","FROM_TO",
    "FREE_EGRESS",null,null,"MAPPED"));
  UUID policyId=UUID.randomUUID(),versionId=UUID.randomUUID();
  policies.createPolicy(MANAGER,db.tenantA,AAL2,new PolicyModels.CreatePolicy(policyId,inst.area(),"Academia"));
  policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,rule(zone,inst.area(),"ASSISTED_BY_STAFF"),
   "Regra da academia ficticia"));
  var report=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId));
  assertEquals("BLOCKED",report.status(),"Relatório entregue, não 422");
  assertEquals(321,report.affectedPointIds().size());
  assertTrue(report.issues().size()<=300);
  assertEquals(320,report.issues().stream().filter(i->"NOT_COMMISSIONED".equals(i.code())).findFirst().orElseThrow().occurrences());
  assertTrue(report.issues().stream().anyMatch(i->"CAPABILITY_MISSING".equals(i.code()) && "passageEvents".equals(i.capability())));
  assertEquals("DRAFT",policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().getFirst().state());
  db.assertEmptyContext();
 }
}
