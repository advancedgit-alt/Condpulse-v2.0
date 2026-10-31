package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalGrantModels;
import br.com.condpulse.accessgovernance.application.TechnicalGrantService;
import br.com.condpulse.accessgovernance.application.TechnicalScope;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessgovernance.infrastructure.TechnicalGrantStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V037 com PostgreSQL real e o login da API: concessão técnica em três níveis (AC-003), dois condomínios. Casos do pacote
 * (homologacao/testes_homologacao.json), nível CONTRATO: HAC-007 (isolamento), HAC-009 (gestor sem ação técnica), HAC-010
 * (MASTER de implantação só no escopo delegado), HAC-012 (revogação/inativação fecham a sessão) e HAC-018 (trilha).
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class TechnicalGrantJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MASTER=UUID.randomUUID(),MANAGER=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static TechnicalGrantService grants;
 private static TechnicalTransactionExecutor technical;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  technical=new TechnicalTransactionExecutor(db.jdbc,db.manager);
  grants=new TechnicalGrantService(new PlatformTransactionExecutor(db.jdbc,db.manager),new TenantTransactionExecutor(db.jdbc,db.manager),
   technical,new TechnicalGrantStore(db.jdbc),new PlatformStore(db.jdbc),30);
  db.extraAccounts.add(MASTER); db.extraAccounts.add(MANAGER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MASTER,MANAGER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO TECNICA JDBC','MANAGER','ACTIVE')",
   db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.grantPlatformAdmin(db.accountA); db.assertEmptyContext();
 }
 @AfterEach void clear() throws Exception {
  db.adminUpdate("delete from cp.technical_grants where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.clearPlatformFixtures();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static TechnicalGrantModels.IssueTechnicalGrant request(UUID id,UUID account,List<String> scopes) {
  return new TechnicalGrantModels.IssueTechnicalGrant(id,account,"MASTER_IMPLANTACAO",scopes,7,"Implantacao piloto ficticia JDBC");
 }

 @Test void platformIssuesMasterImplantationOnlyForTheChosenTenant() throws Exception {
  UUID id=UUID.randomUUID();
  var scopes=List.of("installation.bind_channel","installation.manage");
  assertEquals("MFA_REQUIRED",code(()->grants.issue(db.accountA,AAL1,db.tenantA,request(id,MASTER,scopes))));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->grants.issue(MANAGER,AAL2,db.tenantA,request(id,MASTER,scopes))));
  assertEquals("TECHNICAL_GRANT_SELF",code(()->grants.issue(db.accountA,AAL2,db.tenantA,request(id,db.accountA,scopes))));
  assertEquals("TECHNICAL_GRANT_SCOPES_INVALID",code(()->grants.issue(db.accountA,AAL2,db.tenantA,request(id,MASTER,List.of("*")))));
  var issued=grants.issue(db.accountA,AAL2,db.tenantA,request(id,MASTER,scopes));
  assertTrue(issued.applied()); assertEquals("ACTIVE",issued.grant().status()); assertTrue(issued.grant().current());
  assertEquals(scopes,issued.grant().scopes()); assertEquals("PLATFORM_API",issued.grant().grantedVia());
  var replay=grants.issue(db.accountA,AAL2,db.tenantA,request(id,MASTER,List.of("installation.manage","installation.bind_channel")));
  assertFalse(replay.applied(),"Mesmo pedido devolve a concessão já gravada");
  assertEquals("TECHNICAL_GRANT_ID_CONFLICT",code(()->grants.issue(db.accountA,AAL2,db.tenantA,request(id,MASTER,List.of("installation.read")))));
  assertEquals("1",db.adminString("select count(*) from platform.platform_audit_log where action='TECHNICAL_GRANT_ISSUED' and tenant_id=?",db.tenantA));

  var session=grants.session(MASTER,db.tenantA,AAL2);
  assertEquals(scopes,session.scopes());
  assertEquals("MFA_REQUIRED",code(()->grants.session(MASTER,db.tenantA,AAL1)));
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.session(MASTER,db.tenantB,AAL2)),"HAC-007: concessão não atravessa condomínio");
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.session(MANAGER,db.tenantA,AAL2)),"HAC-009: gestor não tem sessão técnica");
  // O banco confere de novo: escopo publicado só vale se concedido e vigente.
  Boolean granted=technical.execute(MASTER,db.tenantA,AAL2,context->{
   context.require(TechnicalScope.INSTALLATION_BIND_CHANNEL);
   return db.jdbc.queryForObject("select platform.has_technical_scope('installation.bind_channel')",Boolean.class);
  });
  assertEquals(Boolean.TRUE,granted);
  assertEquals("TECHNICAL_SCOPE_REQUIRED",code(()->technical.execute(MASTER,db.tenantA,AAL2,context->{
   context.require(TechnicalScope.CONNECTOR_ROTATE_SECRET); return null; })));
  db.assertEmptyContext();

  assertEquals(1,grants.managerList(MANAGER,db.tenantA,AAL2).grants().size(),"Gestor consulta quem tem concessão técnica");
  assertEquals("MFA_REQUIRED",code(()->grants.managerList(MANAGER,db.tenantA,AAL1)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->grants.managerList(db.accountC,db.tenantA,AAL2)),"Morador não consulta");
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.managerList(MANAGER,db.tenantB,AAL2)));
  assertEquals(1,grants.platformList(db.accountA,AAL2,db.tenantA).grants().size());
  assertTrue(grants.platformList(db.accountA,AAL2,db.tenantB).grants().isEmpty());
  db.assertEmptyContext();
 }

 @Test void revocationIsFinalByVersionAndClosesTheTechnicalSession() throws Exception {
  UUID id=UUID.randomUUID();
  grants.issue(db.accountA,AAL2,db.tenantA,request(id,MASTER,null));
  assertEquals(TechnicalScope.implantationDefaults().stream().map(TechnicalScope::key).sorted().toList(),
   grants.session(MASTER,db.tenantA,AAL2).scopes(),"Sem escopos explícitos valem os padrões de implantação");
  assertEquals("TECHNICAL_GRANT_STALE",code(()->grants.revoke(db.accountA,AAL2,db.tenantA,id,
   new TechnicalGrantModels.RevokeTechnicalGrant(5L,"Fim do ensaio"))));
  assertEquals("TENANT_NOT_FOUND",code(()->grants.revoke(db.accountA,AAL2,db.tenantB,id,
   new TechnicalGrantModels.RevokeTechnicalGrant(0L,"Fim do ensaio"))));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->grants.revoke(MANAGER,AAL2,db.tenantA,id,
   new TechnicalGrantModels.RevokeTechnicalGrant(0L,"Fim do ensaio"))),"HAC-009: gestor não revoga");
  var revoked=grants.revoke(db.accountA,AAL2,db.tenantA,id,new TechnicalGrantModels.RevokeTechnicalGrant(0L,"Fim do ensaio"));
  assertEquals("REVOKED",revoked.status()); assertFalse(revoked.current()); assertNotNull(revoked.revokedAt()); assertEquals(1,revoked.version());
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.session(MASTER,db.tenantA,AAL2)),"HAC-012: revogada fecha a sessão técnica");
  assertEquals("TECHNICAL_GRANT_STALE",code(()->grants.revoke(db.accountA,AAL2,db.tenantA,id,
   new TechnicalGrantModels.RevokeTechnicalGrant(1L,"Fim do ensaio"))));
  assertEquals("1",db.adminString("select count(*) from platform.platform_audit_log where action='TECHNICAL_GRANT_REVOKED' and tenant_id=?",db.tenantA));
  db.assertEmptyContext();
 }

 @Test void expiredGrantStopsWithoutAnyJob() throws Exception {
  UUID id=UUID.randomUUID();
  grants.issue(db.accountA,AAL2,db.tenantA,request(id,MASTER,List.of("installation.read")));
  db.adminUpdateWithoutTriggers("update cp.technical_grants set valid_from=now()-interval '3 days',valid_until=now()-interval '1 day' where id=?",id);
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.session(MASTER,db.tenantA,AAL2)));
  assertFalse(grants.platformList(db.accountA,AAL2,db.tenantA).grants().getFirst().current());
  db.assertEmptyContext();
 }

 @Test void suspendedTenantAndInactiveAccountCloseTheTechnicalSession() throws Exception {
  grants.issue(db.accountA,AAL2,db.tenantA,request(UUID.randomUUID(),MASTER,List.of("installation.read")));
  db.adminUpdate("update platform.tenants set active=false where id=?",db.tenantA);
  assertEquals("TENANT_ACCESS_DENIED",code(()->grants.session(MASTER,db.tenantA,AAL2)));
  db.adminUpdate("update platform.tenants set active=true where id=?",db.tenantA);
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",MASTER);
  try { assertEquals("ACCOUNT_INACTIVE",code(()->grants.session(MASTER,db.tenantA,AAL2))); }
  finally { db.adminUpdate("update platform.accounts set status='ACTIVE' where id=?",MASTER); }
  db.assertEmptyContext();
 }
}
