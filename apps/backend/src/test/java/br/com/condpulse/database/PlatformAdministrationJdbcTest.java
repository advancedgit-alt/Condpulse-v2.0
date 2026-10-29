package br.com.condpulse.database;

import java.util.List;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformModels;
import br.com.condpulse.platform.application.PlatformService;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/** V010 com PostgreSQL real: papel de plataforma, catálogo e estado dos condomínios e auditoria da plataforma. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class PlatformAdministrationJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static PlatformService platform;
 private static TenantTransactionExecutor tenants;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  platform=new PlatformService(new PlatformTransactionExecutor(db.jdbc,db.manager),new PlatformStore(db.jdbc));
  tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures();
  db.adminUpdate("update platform.tenants set name=case when id=? then 'FICTICIO JDBC A' else 'FICTICIO JDBC B' end,active=true,"
   +"version=0 where id in (?,?)",db.tenantA,db.tenantA,db.tenantB);
  db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private PlatformModels.Tenant listed(java.util.UUID tenant) {
  return platform.tenants(db.accountA,AAL2).tenants().stream().filter(t->t.tenantId().equals(tenant)).findFirst().orElseThrow();
 }

 @Test void accountWithoutPlatformRoleIsDeniedEvenWithAal2AndLocalMembership() {
  assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->platform.tenants(db.accountA,AAL2)).code());
  assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->platform.session(db.accountA,AAL2)).code());
  db.assertEmptyContext();
 }
 @Test void aal1PlatformAdminOnlyLearnsThatTheSecondFactorIsRequired() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var session=platform.session(db.accountA,AssuranceLevel.AAL1);
  assertTrue(session.mfaRequired()); assertTrue(session.capabilities().isEmpty()); assertEquals("PLATFORM_ADMIN",session.role());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->platform.tenants(db.accountA,AssuranceLevel.AAL1)).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->platform.updateTenant(db.accountA,AssuranceLevel.AAL1,db.tenantB,
   new PlatformModels.UpdateTenant("Outro nome",true,0L,"Ajuste"))).code());
  assertEquals("FICTICIO JDBC B",db.adminString("select name from platform.tenants where id=?",db.tenantB));
  db.assertEmptyContext();
 }
 @Test void platformAdminRenamesSuspendsAndReactivatesByVersionWithOneAuditEventPerChange() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var session=platform.session(db.accountA,AAL2);
  assertEquals(List.of("platform.audit.read","tenants.manage","tenants.read"),session.capabilities()); assertFalse(session.mfaRequired());
  var before=listed(db.tenantB);
  assertTrue(before.active()); assertEquals(0,before.version()); assertNull(before.onboarding());
  var renamed=platform.updateTenant(db.accountA,AAL2,db.tenantB,
   new PlatformModels.UpdateTenant("  Condomínio   Renomeado ",true,0L,"Ajuste de cadastro"));
  assertTrue(renamed.applied()); assertEquals("Condomínio Renomeado",renamed.tenant().name()); assertEquals(1,renamed.tenant().version());
  var replay=platform.updateTenant(db.accountA,AAL2,db.tenantB,new PlatformModels.UpdateTenant("Condomínio Renomeado",true,0L,"Repetição"));
  assertFalse(replay.applied(),"Pedido igual ao estado atual não grava nem audita"); assertEquals(1,replay.tenant().version());
  assertEquals("TENANT_CHANGED",assertThrows(BusinessProblem.class,()->platform.updateTenant(db.accountA,AAL2,db.tenantB,
   new PlatformModels.UpdateTenant("Outro nome",true,0L,"Versão antiga"))).code());
  var suspended=platform.updateTenant(db.accountA,AAL2,db.tenantB,
   new PlatformModels.UpdateTenant("Condomínio Renomeado",false,1L,"Contrato encerrado"));
  assertTrue(suspended.applied()); assertFalse(suspended.tenant().active());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->tenants.execute(db.accountB,db.tenantB,context->1)).code(),
   "Suspensão tira o condomínio do contexto dos moradores na próxima requisição");
  var reactivated=platform.updateTenant(db.accountA,AAL2,db.tenantB,
   new PlatformModels.UpdateTenant("Condomínio Renomeado",true,2L,"Contrato renovado"));
  assertTrue(reactivated.tenant().active()); assertEquals(3,reactivated.tenant().version());
  int reopened=tenants.execute(db.accountB,db.tenantB,context->1);
  assertEquals(1,reopened,"Reativação devolve o contexto aos moradores");
  var events=platform.audit(db.accountA,AAL2,null,10).events().stream().filter(e->db.tenantB.equals(e.tenantId())).toList();
  assertEquals(List.of("TENANT_REACTIVATED","TENANT_SUSPENDED","TENANT_RENAMED"),events.stream().map(PlatformModels.AuditEvent::action).toList());
  var rename=events.getLast();
  assertEquals(db.accountA,rename.actorId()); assertEquals("Ajuste de cadastro",rename.reason());
  assertEquals("FICTICIO JDBC B",rename.details().get("previousName")); assertEquals("Condomínio Renomeado",rename.details().get("name"));
  db.assertEmptyContext();
 }
 @Test void auditPagesByCursorWithoutRepeatingEvents() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  long version=0;
  for(String name:new String[]{"Nome 1","Nome 2","Nome 3"})
   version=platform.updateTenant(db.accountA,AAL2,db.tenantA,new PlatformModels.UpdateTenant(name,true,version,"Ensaio")).tenant().version();
  var first=platform.audit(db.accountA,AAL2,null,2);
  assertEquals(2,first.events().size()); assertNotNull(first.nextCursor());
  var second=platform.audit(db.accountA,AAL2,first.nextCursor(),2);
  var seen=new java.util.HashSet<java.util.UUID>();
  first.events().forEach(e->seen.add(e.eventId()));
  assertTrue(second.events().stream().noneMatch(e->seen.contains(e.eventId())));
  assertEquals("AUDIT_CURSOR_INVALID",assertThrows(BusinessProblem.class,()->platform.audit(db.accountA,AAL2,"forjado",2)).code());
  assertEquals("AUDIT_LIMIT_INVALID",assertThrows(BusinessProblem.class,()->platform.audit(db.accountA,AAL2,null,101)).code());
  db.assertEmptyContext();
 }
 @Test void localManagerCapabilitiesNeverReachPlatformPowers() throws Exception {
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO FICTICIA','MANAGER','ACTIVE')",
   db.tenantA,db.accountC);
  try {
   assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->platform.tenants(db.accountC,AAL2)).code());
   int visible=tenants.execute(db.accountC,db.tenantA,AAL2,context->db.jdbc.queryForObject("select count(*) from platform.tenants",Integer.class));
   assertEquals(1,visible,"Gestor local vê só o próprio condomínio");
   assertThrows(DataAccessException.class,()->tenants.execute(db.accountC,db.tenantA,AAL2,context->db.jdbc.update(
    "update platform.tenants set name='GESTOR LOCAL' where id=?",db.tenantA)),"Gestor local não altera o catálogo");
   assertEquals("FICTICIO JDBC A",db.adminString("select name from platform.tenants where id=?",db.tenantA));
  } finally {
   db.adminUpdate("delete from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantA,db.accountC);
  }
  db.assertEmptyContext();
 }
 @Test void revokedRoleOrSuspendedAccountLosesAccessOnTheNextRequest() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  assertNotNull(listed(db.tenantA));
  db.adminUpdate("update platform.platform_roles set status='REVOKED',revoked_at=now() where account_id=?",db.accountA);
  assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->platform.tenants(db.accountA,AAL2)).code());
  db.adminUpdate("update platform.platform_roles set status='ACTIVE',revoked_at=null where account_id=?",db.accountA);
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountA);
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,()->platform.tenants(db.accountA,AAL2)).code());
  db.assertEmptyContext();
 }
 @Test void unknownTenantAndInvalidInputAreRefusedWithoutChanges() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  assertEquals("TENANT_NOT_FOUND",assertThrows(BusinessProblem.class,()->platform.updateTenant(db.accountA,AAL2,java.util.UUID.randomUUID(),
   new PlatformModels.UpdateTenant("Nome",true,0L,"Ajuste"))).code());
  assertEquals("AUDIT_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->platform.updateTenant(db.accountA,AAL2,db.tenantA,
   new PlatformModels.UpdateTenant("Nome",true,0L,"  "))).code());
  assertEquals("TENANT_NAME_INVALID",assertThrows(BusinessProblem.class,()->platform.updateTenant(db.accountA,AAL2,db.tenantA,
   new PlatformModels.UpdateTenant("x".repeat(161),true,0L,"Ajuste"))).code());
  assertEquals("TENANT_VERSION_REQUIRED",assertThrows(BusinessProblem.class,()->platform.updateTenant(db.accountA,AAL2,db.tenantA,
   new PlatformModels.UpdateTenant("Nome",true,null,"Ajuste"))).code());
  assertEquals("0",db.adminString("select count(*) from platform.platform_audit_log where tenant_id=?",db.tenantA));
  db.assertEmptyContext();
 }
}
