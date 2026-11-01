package br.com.condpulse.database;

import java.util.EnumSet;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.application.CurrentIdentityQuery;
import br.com.condpulse.platform.application.DemoTenantModels;
import br.com.condpulse.platform.application.DemoTenantService;
import br.com.condpulse.platform.application.PlatformService;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.DemoTenantStore;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V055 com PostgreSQL real: condomínio demo criado e resetado pela central, isolado e sem papel para conta real. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class DemoTenantJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static DemoTenantService demo;
 private static PlatformService platform;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  var executor=new PlatformTransactionExecutor(db.jdbc,db.manager);
  demo=new DemoTenantService(executor,new DemoTenantStore(db.jdbc));
  platform=new PlatformService(executor,new PlatformStore(db.jdbc),true,false,false,true);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.assertEmptyContext(); }
 @AfterEach void clean() throws Exception { db.clearPlatformFixtures(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "jdbc-demo-"+UUID.randomUUID(); }
 private static DemoTenantModels.ProvisionDemo request(UUID expected) {
  return new DemoTenantModels.ProvisionDemo(expected,"Ensaio JDBC fictício","DEMO");
 }
 private static String code(Runnable action) { return assertThrows(BusinessProblem.class,action::run).code(); }

 @Test void centralCreatesResetsAndNeverTouchesRealTenants() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  assertTrue(platform.session(db.accountA,AAL2).capabilities().contains("platform.demo.manage"),"capacidade com a flag ligada");
  assertFalse(new PlatformService(new PlatformTransactionExecutor(db.jdbc,db.manager),new PlatformStore(db.jdbc)).session(db.accountA,AAL2)
   .capabilities().contains("platform.demo.manage"),"flag desligada esconde a capacidade");
  assertNull(demo.state(db.accountA,AAL2).current());
  assertEquals("MFA_REQUIRED",code(()->demo.state(db.accountA,AssuranceLevel.AAL1)));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->demo.state(db.accountB,AAL2)));
  assertEquals("DEMO_RESET_REAL_TENANT",code(()->demo.provision(db.accountA,AAL2,key(),request(db.tenantA))));

  String first=key();
  var created=demo.provision(db.accountA,AAL2,first,request(null));
  assertTrue(created.applied());
  assertNull(created.retiredTenantId());
  var generation=created.generation();
  assertEquals(1,generation.generation());
  assertTrue(generation.tenantName().startsWith("[DEMO] "));
  assertTrue(generation.partnerName().startsWith("[DEMO] "));
  assertEquals(Long.valueOf(32),generation.counts().get("units"));
  var replay=demo.provision(db.accountA,AAL2,first,request(null));
  assertFalse(replay.applied());
  assertEquals(generation.tenantId(),replay.generation().tenantId());
  assertEquals("DEMO_KEY_REUSED",code(()->demo.provision(db.accountA,AAL2,first,
   new DemoTenantModels.ProvisionDemo(null,"Outro motivo fictício","DEMO"))));
  assertEquals("DEMO_CHANGED",code(()->demo.provision(db.accountA,AAL2,key(),request(null))));

  // Central vê o demo marcado no catálogo; nenhuma conta real tem vínculo nele.
  var listed=platform.tenants(db.accountA,AAL2,null).tenants().stream().filter(t->t.tenantId().equals(generation.tenantId())).findFirst();
  assertEquals("DEMO",listed.orElseThrow().origin());
  var me=new CurrentIdentityQuery(db.jdbc,new TenantModuleFlags(EnumSet.allOf(TenantModule.class)),true);
  assertTrue(db.tx.execute(status->me.execute(db.accountA)).demoTenantIds().isEmpty());
  // Vínculo explícito de uma conta real (aqui direto pelo dono, no lugar do convite da central): o /v1/me marca DEMO.
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTORA REAL JDBC','MANAGER','ACTIVE')",
   generation.tenantId(),db.accountB);
  var identity=db.tx.execute(status->me.execute(db.accountB));
  assertTrue(identity.demoTenantIds().contains(generation.tenantId()));
  assertFalse(identity.demoTenantIds().contains(db.tenantB),"condomínio real nunca aparece como demo");

  var reset=demo.provision(db.accountA,AAL2,key(),request(generation.tenantId()));
  assertTrue(reset.applied());
  assertEquals(generation.tenantId(),reset.retiredTenantId());
  assertEquals(2,reset.generation().generation());
  var state=demo.state(db.accountA,AAL2);
  assertEquals(reset.generation().tenantId(),state.current().tenantId());
  assertEquals("RETIRED",state.retired().getFirst().status());
  assertNotNull(state.retired().getFirst().retiredAt());
  assertFalse(db.tx.execute(status->me.execute(db.accountB)).demoTenantIds().contains(generation.tenantId()),
   "demo aposentado fica suspenso: o vínculo real perde o acesso");
  assertEquals("DEMO_CHANGED",code(()->demo.provision(db.accountA,AAL2,key(),request(generation.tenantId()))));
  assertEquals("DEMO_RESET_REAL_TENANT",code(()->demo.provision(db.accountA,AAL2,key(),request(db.tenantA))));
 }

 @Test void invalidRequestsAreRefusedBeforeTheDatabase() {
  assertEquals("IDEMPOTENCY_KEY_INVALID",code(()->demo.provision(db.accountA,AAL2,"curta",request(null))));
  assertEquals("DEMO_CONFIRMATION_REQUIRED",code(()->demo.provision(db.accountA,AAL2,key(),
   new DemoTenantModels.ProvisionDemo(null,"Motivo fictício","demo"))));
  assertEquals("AUDIT_REASON_REQUIRED",code(()->demo.provision(db.accountA,AAL2,key(),
   new DemoTenantModels.ProvisionDemo(null," ","DEMO"))));
 }
}
