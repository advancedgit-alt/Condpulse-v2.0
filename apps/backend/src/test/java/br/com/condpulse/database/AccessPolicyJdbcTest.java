package br.com.condpulse.database;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

/** V043 com PostgreSQL real e o login da API: política, revisão, validação contra o destino comissionado, aprovação e simulação. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessPolicyJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),MASTER=UUID.randomUUID();
 private static final UUID HALL=UUID.randomUUID(),GYM=UUID.randomUUID(),POINT=UUID.randomUUID(),CONNECTOR=UUID.randomUUID(),DEVICE=UUID.randomUUID(),
  CHANNEL=UUID.randomUUID(),ACCEPTANCE=UUID.randomUUID(),BINDING=UUID.randomUUID(),HOMOLOGATION=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static PolicyService policies;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  policies=new PolicyService(new TenantTransactionExecutor(db.jdbc,db.manager),new PolicyStore(db.jdbc),new AuditRecorder(db.jdbc));
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(MASTER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,MASTER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO POLITICA JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.assertEmptyContext();
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  db.adminUpdateWithoutTriggers("insert into cp.access_areas(id,tenant_id,code,name,area_kind,timezone,status,created_by,updated_by) values"
   +"(?,?,'HALLP','Hall','RESIDENTIAL_ROUTE',?,'ACTIVE',?,?),(?,?,'ACADP','Academia','COMMON_AREA',?,'ACTIVE',?,?)",
   HALL,db.tenantA,zone,MANAGER,MANAGER,GYM,db.tenantA,zone,MANAGER,MANAGER);
  db.adminUpdateWithoutTriggers("insert into cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,status,"
   +"created_by,updated_by) values(?,?,'PACAD','Porta da academia',?,?,'DOOR','FROM_TO','FREE_EGRESS','COMMISSIONED',?,?)",POINT,db.tenantA,HALL,GYM,MASTER,MASTER);
  String profile=db.adminString("select id::text from platform.access_model_profiles order by catalog_code limit 1");
  String caps="{"+String.join(",",java.util.stream.Stream.of("doorContactEvents","eventBackfill","faceDeletion","faceEnrollment","localValidity",
   "offlineRestriction","onlineDecisionBeforeRelay","passageEvents","perDoorRights","safeRecovery","scopedRemoteOpen","secureTransport")
   .map(n->"\""+n+"\":{\"documented\":true,\"observed\":null,\"approved\":null}").toList())+"}";
  db.adminUpdateWithoutTriggers("insert into platform.access_homologations(id,model_profile_id,firmware_exact,adapter_name,adapter_version,decision_mode,"
   +"topology,contract_schema_version,capabilities,limits,reason,requested_by,status,approved_at,approved_by,approved_capabilities) values(?,?::uuid,'4.3.1',"
   +"'control-id-rest','0.1.0','EDGE_ONLINE','LOCAL_GATEWAY','v1',?::jsonb,'{}','Combinacao piloto JDBC',?,'APROVADO_PILOTO',now(),?,"
   +"array['faceEnrollment','faceDeletion','perDoorRights','secureTransport','offlineRestriction'])",HOMOLOGATION,profile,caps,MASTER,MASTER);
  db.adminUpdateWithoutTriggers("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Conector politica')",CONNECTOR,db.tenantA);
  db.adminUpdateWithoutTriggers("insert into cp.access_devices(id,tenant_id,connector_id,external_id,label) values(?,?,?,'ctrl-p','Controlador')",
   DEVICE,db.tenantA,CONNECTOR);
  db.adminUpdateWithoutTriggers("insert into cp.device_channels(id,tenant_id,device_id,native_namespace,external_channel,channel_kind,status,created_by) "
   +"values(?,?,?,'relay','1','ACCESS_ACTUATOR','VERIFIED',?)",CHANNEL,db.tenantA,DEVICE,MASTER);
  db.adminUpdateWithoutTriggers("insert into cp.installation_acceptances(id,tenant_id,installation_revision,status,plant_ref,plant_hash,topology_manifest_ref,"
   +"topology_hash,capability_envelope_hash,test_suite_version,test_report_ref,critical_tests_pending,critical_tests_failed,emergency_test_ref,"
   +"rollback_plan_ref,created_by,effective_at,technical_grant_id,technical_approver_account_id,technical_signed_at,rules_approver_membership_id,"
   +"rules_approver_account_id,rules_signed_at) values(?,?,1,'APPROVED','planta/p',repeat('a',64),'top/p',repeat('b',64),repeat('c',64),'hac','rel/p',0,0,"
   +"'emg/p','rb/p',?,now(),gen_random_uuid(),?,now(),gen_random_uuid(),?,now())",ACCEPTANCE,db.tenantA,MASTER,MASTER,MANAGER);
  db.adminUpdateWithoutTriggers("insert into cp.point_bindings(id,tenant_id,point_id,actuator_channel_id,controller_device_id,homologation_id,binding_revision,"
   +"installation_acceptance_id,direction_map,status,valid_from,wiring_evidence_ref,configuration_hash,commissioned_fingerprint,created_by) "
   +"values(?,?,?,?,?,?,3,?,'{}','ACTIVE',now(),'lig/p',repeat('d',64),repeat('e',64),?)",BINDING,db.tenantA,POINT,CHANNEL,DEVICE,HOMOLOGATION,ACCEPTANCE,MASTER);
 }
 @AfterEach void clear() throws Exception {
  db.adminUpdate("delete from cp.audit_log where tenant_id in (?,?) and action like 'POLICY\\_%'",db.tenantA,db.tenantB);
  db.adminUpdate("with v as (delete from cp.access_policy_validations where tenant_id in (?,?)) delete from cp.access_policy_versions where tenant_id in (?,?)",
   db.tenantA,db.tenantB,db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_policies where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdateWithoutTriggers("delete from cp.point_bindings where id=?",BINDING);
  db.adminUpdateWithoutTriggers("delete from cp.installation_acceptances where id=?",ACCEPTANCE);
  db.adminUpdateWithoutTriggers("delete from cp.device_channels where id=?",CHANNEL);
  db.adminUpdateWithoutTriggers("delete from cp.access_devices where id=?",DEVICE);
  db.adminUpdateWithoutTriggers("delete from cp.access_connectors where id=?",CONNECTOR);
  db.adminUpdateWithoutTriggers("delete from cp.access_points where id=?",POINT);
  db.adminUpdateWithoutTriggers("delete from cp.access_areas where id in (?,?)",HALL,GYM);
  db.adminUpdateWithoutTriggers("delete from platform.access_homologations where id=?",HOMOLOGATION);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static PolicyModels.PolicyDocument gym(String zone) {
  return new PolicyModels.PolicyDocument("1.0","Academia 18+",GYM,zone,"ALL",List.of("RESIDENT","DEPENDENT"),"DENY",18,true,
   List.of(new PolicyModels.WeeklyWindow(List.of("MON","TUE","WED","THU","FRI","SAT","SUN"),"06:00","22:00",false)),List.of(),"HOLIDAY_OVERRIDES_WEEKLY",
   new PolicyModels.ReservationRule("NOT_REQUIRED",0,0),new PolicyModels.CompanionRule("NONE",0),new PolicyModels.OfflineRule("SAFE_LOCAL_SUBSET",300,true),
   new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY");
 }

 @Test void managerValidatesAgainstCommissionedPointsApprovesAndSimulatesWithoutCommands() throws Exception {
  String zone=db.adminString("select time_zone from platform.tenants where id=?",db.tenantA);
  UUID policyId=UUID.randomUUID(),versionId=UUID.randomUUID();
  var create=new PolicyModels.CreatePolicy(policyId,GYM,"Academia");
  assertEquals("MFA_REQUIRED",code(()->policies.createPolicy(MANAGER,db.tenantA,AAL1,create)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->policies.createPolicy(db.accountA,db.tenantA,AAL2,create)));
  var policy=policies.createPolicy(MANAGER,db.tenantA,AAL2,create);
  assertEquals(policy,policies.createPolicy(MANAGER,db.tenantA,AAL2,create),"Mesmo pedido é repetição");
  assertEquals("POLICY_CONFLICT",code(()->policies.createPolicy(MANAGER,db.tenantA,AAL2,new PolicyModels.CreatePolicy(UUID.randomUUID(),GYM,"Outra"))));

  assertEquals("POLICY_AREA_MISMATCH",code(()->policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,
   new PolicyModels.PolicyDocument("1.0","X",HALL,zone,"ALL",List.of("RESIDENT"),"DENY",null,true,gym(zone).weeklyWindows(),List.of(),
    "HOLIDAY_OVERRIDES_WEEKLY",gym(zone).reservation(),gym(zone).companion(),gym(zone).offline(),gym(zone).failure(),"OFF",null,true,null,"DENY"),
   "Regra da academia ficticia"))));
  var draft=policies.createVersion(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.CreateVersion(versionId,gym(zone),"Regra da academia ficticia"));
  assertEquals("DRAFT",draft.state()); assertEquals(1,draft.revisionNumber()); assertEquals(64,draft.canonicalHash().length());
  assertEquals(gym(zone),draft.document(),"Documento lido igual ao gravado");

  // HAC-043: capacidade não aprovada bloqueia; a aprovação depende de validação aprovada.
  var blocked=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId));
  assertEquals("BLOCKED",blocked.status());
  assertEquals(List.of(POINT),blocked.affectedPointIds());
  assertTrue(blocked.issues().stream().anyMatch(i->"CAPABILITY_MISSING".equals(i.code()) && "localValidity".equals(i.capability())));
  var afterBlocked=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().getFirst();
  assertEquals("DRAFT",afterBlocked.state(),"Validação bloqueada mantém a revisão em DRAFT");
  assertEquals(blocked.validationId(),afterBlocked.validationId(),"Impeditivos ficam registrados");
  long draftVersion=afterBlocked.version();
  assertEquals("POLICY_STALE",code(()->policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(draftVersion))));
  db.adminUpdateWithoutTriggers("update platform.access_homologations set approved_capabilities=array['faceEnrollment','faceDeletion','perDoorRights',"
   +"'secureTransport','offlineRestriction','localValidity'] where id=?",HOMOLOGATION);
  var validated=policies.validate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.ValidateRequest(versionId));
  assertEquals("VALIDATED",validated.status()); assertEquals(1,validated.installationRevision());
  assertTrue(validated.expiresAt().isAfter(validated.validatedAt()));
  var current=policies.versions(MANAGER,db.tenantA,AAL2,policyId).versions().getFirst();
  assertEquals("VALIDATED",current.state()); assertEquals(validated.validationId(),current.validationId());
  var approved=policies.approve(MANAGER,db.tenantA,AAL2,policyId,versionId,new PolicyModels.VersionAction(current.version()));
  assertEquals("APPROVED",approved.state()); assertEquals(MANAGER,approved.approvedBy());

  // Simulação sintética: nunca cria comando físico.
  Instant saturday10=java.time.ZonedDateTime.of(2026,10,31,10,0,0,0,java.time.ZoneId.of(zone)).toInstant();
  var adult=policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest("SYNTHETIC",versionId,POINT,
   new PolicyModels.SyntheticSubject("RESIDENT","VERIFIED_MEETS_MINIMUM",false,false,false),saturday10));
  assertEquals("ALLOW",adult.result(),String.join(",",adult.reasonCodes()));
  assertTrue(adult.simulated()); assertFalse(adult.hardwareCommandCreated()); assertEquals(3,adult.bindingRevision());
  var minor=policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest("SYNTHETIC",versionId,POINT,
   new PolicyModels.SyntheticSubject("DEPENDENT","VERIFIED_BELOW_MINIMUM",false,false,false),saturday10));
  assertEquals("DENY",minor.result()); assertTrue(minor.reasonCodes().contains("AGE_BELOW_MINIMUM"),"HAC-031");
  assertEquals("POLICY_SIMULATION_MODE_UNSUPPORTED",code(()->policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest(
   "AUTHORIZED_EXISTING_SUBJECT",versionId,POINT,null,saturday10))));
  assertEquals("POLICY_POINT_NOT_IN_AREA",code(()->policies.simulate(MANAGER,db.tenantA,AAL2,policyId,new PolicyModels.SimulateRequest("SYNTHETIC",versionId,
   UUID.randomUUID(),new PolicyModels.SyntheticSubject("RESIDENT","UNKNOWN",false,false,false),saturday10))));
  assertEquals("0",db.adminString("select count(*)::text from cp.outbox where tenant_id=? and event_type like 'access%'",db.tenantA),"Simulação não cria comando nem evento");
  assertEquals("TENANT_ACCESS_DENIED",code(()->policies.policies(MANAGER,db.tenantB,AAL2)),"HAC-007");
  db.assertEmptyContext();
 }
}
