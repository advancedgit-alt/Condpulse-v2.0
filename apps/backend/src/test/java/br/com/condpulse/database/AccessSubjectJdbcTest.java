package br.com.condpulse.database;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.accesssubjects.application.SubjectModels;
import br.com.condpulse.accesssubjects.application.SubjectService;
import br.com.condpulse.accesssubjects.infrastructure.SubjectStore;
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
 * V038 com PostgreSQL real e o login da API: sujeitos e contextos (AC-005), dois condomínios. Casos HAC-029 (mesma pessoa
 * em condomínios diferentes), HAC-030 (múltiplos vínculos sem união permissiva) e HAC-011 (cargo de gestão sem contexto físico).
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessSubjectJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),CONCIERGE=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static SubjectService subjects;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  subjects=new SubjectService(new TenantTransactionExecutor(db.jdbc,db.manager),new SubjectStore(db.jdbc),new AuditRecorder(db.jdbc));
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(CONCIERGE);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,CONCIERGE);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO SUJEITO JDBC','MANAGER','ACTIVE'),"
   +"(?,?,'PORTARIA SUJEITO JDBC','CONCIERGE','ACTIVE')",db.tenantA,MANAGER,db.tenantA,CONCIERGE);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterEach void clear() throws Exception {
  db.adminUpdate("delete from cp.audit_log where tenant_id in (?,?) and action like 'SUBJECT\\_%'",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.subject_contexts where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_subjects where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.visit_permits where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.visitors where tenant_id in (?,?)",db.tenantA,db.tenantB);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static SubjectModels.Subject of(UUID account) {
  return subjects.list(MANAGER,db.tenantA,AAL2).subjects().stream().filter(s->account.equals(s.accountId())).findFirst().orElseThrow();
 }

 @Test void managementMaterializesOnlyPhysicalContextsIdempotently() throws Exception {
  assertEquals("MFA_REQUIRED",code(()->subjects.materializeMemberships(MANAGER,db.tenantA,AAL1)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->subjects.materializeMemberships(db.accountA,db.tenantA,AAL2)),"Morador não materializa");
  var first=subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  assertEquals(3,first.subjectsCreated(),"Moradores A e C e a portaria; gestão não vira sujeito físico");
  assertEquals(3,first.contextsCreated());
  assertEquals(new SubjectModels.Materialized(0,0,0,0),subjects.materializeMemberships(MANAGER,db.tenantA,AAL2),"Repetir não duplica");
  assertTrue(subjects.list(MANAGER,db.tenantA,AAL2).subjects().stream().noneMatch(s->MANAGER.equals(s.accountId())),"HAC-011");
  var resident=of(db.accountA);
  assertEquals("RESIDENT",resident.contexts().getFirst().contextKind());
  assertEquals(db.unitA,resident.contexts().getFirst().unitId());
  assertEquals(1,resident.eligibleContextIds().size());
  assertEquals("EMPLOYEE",of(CONCIERGE).contexts().getFirst().contextKind());
  assertEquals("0",db.adminString("select count(*) from cp.access_subjects where tenant_id=?",db.tenantB),"HAC-029: nada atravessa para B");
  // Origem mudou: o contexto antigo é substituído na próxima materialização, sem job.
  db.adminUpdate("update cp.memberships set version=version+1 where tenant_id=? and account_id=?",db.tenantA,db.accountA);
  assertTrue(of(db.accountA).eligibleContextIds().isEmpty(),"Projeção desatualizada não vale");
  assertEquals(new SubjectModels.Materialized(0,1,1,0),subjects.materializeMemberships(MANAGER,db.tenantA,AAL2));
  assertEquals(1,of(db.accountA).eligibleContextIds().size());
  db.assertEmptyContext();
 }

 @Test void blockIsTransversalAndContextRevocationIsLocal() throws Exception {
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  var resident=of(db.accountA);
  UUID functional=UUID.randomUUID();
  Instant until=Instant.now().plus(Duration.ofDays(10));
  var request=new SubjectModels.FunctionalAuthorization(functional,"SERVICE_PROVIDER","os:manutencao-jdbc-01",until);
  subjects.addFunctional(MANAGER,db.tenantA,AAL2,resident.subjectId(),request);
  subjects.addFunctional(MANAGER,db.tenantA,AAL2,resident.subjectId(),request);
  assertEquals("ACCESS_SUBJECT_CONTEXT_ID_CONFLICT",code(()->subjects.addFunctional(MANAGER,db.tenantA,AAL2,resident.subjectId(),
   new SubjectModels.FunctionalAuthorization(functional,"SERVICE_PROVIDER","os:manutencao-jdbc-01",until.plusSeconds(60)))),"Repetição exige o pedido inteiro");
  assertEquals("ACCESS_SUBJECT_CONTEXT_KIND_INVALID",code(()->subjects.addFunctional(MANAGER,db.tenantA,AAL2,resident.subjectId(),
   new SubjectModels.FunctionalAuthorization(UUID.randomUUID(),"RESIDENT","os:manutencao-jdbc-02",until))));
  assertEquals(2,of(db.accountA).eligibleContextIds().size());
  assertEquals(resident.contexts().getFirst().contextId(),of(db.accountA).eligibleContextIds().getFirst(),"Residência antes do prestador");

  long functionalVersion=of(db.accountA).contexts().stream().filter(c->c.contextId().equals(functional)).findFirst().orElseThrow().version();
  subjects.closeContext(MANAGER,db.tenantA,AAL2,functional,new SubjectModels.CloseContext("Fim do servico",functionalVersion));
  assertEquals(1,of(db.accountA).eligibleContextIds().size(),"HAC-030: revogar o contexto não derruba a residência");
  assertEquals("ACCESS_SUBJECT_STALE",code(()->subjects.closeContext(MANAGER,db.tenantA,AAL2,functional,
   new SubjectModels.CloseContext("Fim do servico",functionalVersion))));

  assertEquals("ACCESS_SUBJECT_STALE",code(()->subjects.block(MANAGER,db.tenantA,AAL2,resident.subjectId(),new SubjectModels.Block("Ordem do sindico",9L))));
  subjects.block(MANAGER,db.tenantA,AAL2,resident.subjectId(),new SubjectModels.Block("Ordem do sindico",resident.version()));
  var blocked=of(db.accountA);
  assertEquals("BLOCKED",blocked.status()); assertEquals(1,blocked.restrictionEpoch()); assertTrue(blocked.eligibleContextIds().isEmpty());
  assertEquals("STAFF_ACCESS_DENIED",code(()->subjects.unblock(db.accountA,db.tenantA,AAL2,resident.subjectId(),new SubjectModels.Unblock(1L))));
  subjects.unblock(MANAGER,db.tenantA,AAL2,resident.subjectId(),new SubjectModels.Unblock(blocked.version()));
  var active=of(db.accountA);
  assertEquals(2,active.restrictionEpoch()); assertEquals(1,active.eligibleContextIds().size());
  assertEquals("ACCESS_SUBJECT_NOT_FOUND",code(()->subjects.block(MANAGER,db.tenantA,AAL2,UUID.randomUUID(),new SubjectModels.Block("Ordem",0L))));
  db.assertEmptyContext();
 }

 @Test void revokedContextStaysRevokedUntilExplicitReauthorization() throws Exception {
  subjects.materializeMemberships(MANAGER,db.tenantA,AAL2);
  var residence=of(db.accountA).contexts().getFirst();
  subjects.closeContext(MANAGER,db.tenantA,AAL2,residence.contextId(),new SubjectModels.CloseContext("Ordem da gestao",residence.version()));
  assertEquals(new SubjectModels.Materialized(0,0,0,1),subjects.materializeMemberships(MANAGER,db.tenantA,AAL2),
   "Materialização não desfaz a revogação");
  assertTrue(of(db.accountA).eligibleContextIds().isEmpty());
  assertEquals("STAFF_ACCESS_DENIED",code(()->subjects.reauthorize(CONCIERGE,db.tenantA,AAL2,residence.contextId(),new SubjectModels.Reauthorize("Volta"))));
  subjects.reauthorize(MANAGER,db.tenantA,AAL2,residence.contextId(),new SubjectModels.Reauthorize("Decisao da assembleia"));
  subjects.reauthorize(MANAGER,db.tenantA,AAL2,residence.contextId(),new SubjectModels.Reauthorize("Decisao da assembleia"));
  assertEquals(1,of(db.accountA).eligibleContextIds().size(),"Reautorização explícita devolve um contexto, sem duplicar");
  assertEquals("ACCESS_SUBJECT_NOT_REAUTHORIZABLE",code(()->subjects.reauthorize(MANAGER,db.tenantA,AAL2,UUID.randomUUID(),
   new SubjectModels.Reauthorize("Volta"))));
  db.assertEmptyContext();
 }

 @Test void conciergeMaterializesVisitorsOfCurrentInvitations() throws Exception {
  UUID visitor=UUID.randomUUID(),permit=UUID.randomUUID();
  db.adminUpdate("insert into cp.visitors(id,tenant_id,full_name,created_by) values(?,?,'VISITANTE JDBC',?)",visitor,db.tenantA,db.accountA);
  db.adminUpdate("insert into cp.visit_permits(id,tenant_id,visitor_id,unit_id,created_by,valid_from,valid_until) values(?,?,?,?,?,now(),now()+interval '1 day')",
   permit,db.tenantA,visitor,db.unitA,db.accountA);
  assertEquals("STAFF_ACCESS_DENIED",code(()->subjects.materializePermits(MANAGER,db.tenantA,AAL2)),"Gestão não lê convites");
  assertEquals(new SubjectModels.Materialized(1,1,0,0),subjects.materializePermits(CONCIERGE,db.tenantA,AAL2));
  assertEquals(new SubjectModels.Materialized(0,0,0,0),subjects.materializePermits(CONCIERGE,db.tenantA,AAL2));
  var listed=subjects.list(MANAGER,db.tenantA,AAL2).subjects().stream().filter(s->visitor.equals(s.visitorId())).findFirst().orElseThrow();
  assertEquals("VISITOR",listed.contexts().getFirst().contextKind());
  assertEquals(permit,listed.contexts().getFirst().permitId());
  assertTrue(listed.contexts().getFirst().current(),"A gestão vê a vigência do visitante sem ler o convite");
  assertEquals(1,listed.eligibleContextIds().size());
  db.assertEmptyContext();
 }
}
