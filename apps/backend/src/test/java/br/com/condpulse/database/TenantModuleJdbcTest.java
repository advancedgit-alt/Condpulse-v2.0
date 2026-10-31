package br.com.condpulse.database;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.application.CurrentIdentityQuery;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.application.TenantModuleModels;
import br.com.condpulse.platform.application.TenantModuleService;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.platform.infrastructure.TenantModuleStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import br.com.condpulse.tenancy.application.TenantModuleGate;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V045 com PostgreSQL real e o login da API: módulos por condomínio decididos pela central (tenant_modules.manage, AAL2,
 * motivo, versão e trilha exigida no COMMIT), conferidos pelo executor do condomínio na mesma transação e expostos em /v1/me.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class TenantModuleJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final TenantModuleFlags ALL=new TenantModuleFlags(EnumSet.allOf(TenantModule.class));
 private static JdbcDisposableDatabase db;
 private static PlatformTransactionExecutor platform;
 private static TenantModuleService modules;
 private static TenantTransactionExecutor tenants;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  platform=new PlatformTransactionExecutor(db.jdbc,db.manager);
  modules=new TenantModuleService(platform,new PlatformStore(db.jdbc),new TenantModuleStore(db.jdbc),ALL);
  tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.grantPlatformAdmin(db.accountA); db.assertEmptyContext();
 }
 @AfterEach void clear() throws Exception { RequestContextHolder.resetRequestAttributes(); db.clearPlatformFixtures(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static int open(UUID account,UUID tenant) { return tenants.<Integer>execute(account,tenant,context->1); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static TenantModuleModels.ChangeTenantModule change(String module,boolean enabled,long version) {
  return new TenantModuleModels.ChangeTenantModule(module,enabled,version,"Ajuste ficticio do ensaio JDBC");
 }
 private static TenantModuleModels.TenantModuleState state(UUID tenant,String module) {
  return modules.list(db.accountA,AAL2,tenant).modules().stream().filter(m->m.module().equals(module)).findFirst().orElseThrow();
 }
 private static void route(TenantModule...required) {
  var request=new MockHttpServletRequest();
  request.setAttribute(TenantModuleGate.REQUIRED_ATTRIBUTE,required.length==0?EnumSet.noneOf(TenantModule.class):EnumSet.of(required[0],required));
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
 }

 @Test void defaultKeepsEveryModuleReleasedUntilTheCentralChangesIt() throws Exception {
  var page=modules.list(db.accountA,AAL2,db.tenantA);
  assertEquals(8,page.modules().size());
  assertTrue(page.modules().stream().allMatch(m->m.defaultState() && m.tenantEnabled() && m.enabled() && m.version()==0));
  route(TenantModule.PARCELS);
  assertEquals(1,open(db.accountA,db.tenantA),"Sem linha o condomínio segue liberado");
  assertEquals("MFA_REQUIRED",code(()->modules.list(db.accountA,AAL1,db.tenantA)));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->modules.list(db.accountB,AAL2,db.tenantA)),"Morador não lê a central");
  assertEquals("TENANT_NOT_FOUND",code(()->modules.list(db.accountA,AAL2,UUID.randomUUID())));
  assertEquals("0",db.adminString("select count(*) from platform.tenant_modules where tenant_id in (?,?)",db.tenantA,db.tenantB));
  db.assertEmptyContext();
 }

 @Test void centralDisablesByVersionWithAuditAndTheRouteAnswersModuleDisabled() throws Exception {
  assertEquals("MFA_REQUIRED",code(()->modules.change(db.accountA,AAL1,db.tenantA,change("parcels",false,0))));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->modules.change(db.accountB,AAL2,db.tenantA,change("parcels",false,0))));
  assertEquals("TENANT_MODULE_INVALID",code(()->modules.change(db.accountA,AAL2,db.tenantA,change("facial",false,0))));
  assertEquals("AUDIT_REASON_REQUIRED",code(()->modules.change(db.accountA,AAL2,db.tenantA,
   new TenantModuleModels.ChangeTenantModule("parcels",false,0L," "))));
  assertEquals("TENANT_MODULE_VERSION_REQUIRED",code(()->modules.change(db.accountA,AAL2,db.tenantA,
   new TenantModuleModels.ChangeTenantModule("parcels",false,null,"Motivo ficticio"))));
  assertEquals("TENANT_NOT_FOUND",code(()->modules.change(db.accountA,AAL2,UUID.randomUUID(),change("parcels",false,0))));

  var disabled=modules.change(db.accountA,AAL2,db.tenantA,change("parcels",false,0));
  assertTrue(disabled.applied());
  assertFalse(disabled.module().tenantEnabled()); assertFalse(disabled.module().enabled()); assertFalse(disabled.module().defaultState());
  assertEquals(1,disabled.module().version()); assertEquals(db.accountA,disabled.module().updatedBy());
  assertEquals("1",db.adminString("select count(*) from platform.platform_audit_log where tenant_id=? and action='TENANT_MODULE_DISABLED' "
   +"and details->>'module'='parcels' and details->>'version'='1'",db.tenantA));
  assertFalse(modules.change(db.accountA,AAL2,db.tenantA,change("parcels",false,0)).applied(),"Pedido igual ao estado é repetição");
  assertEquals("TENANT_MODULE_CHANGED",code(()->modules.change(db.accountA,AAL2,db.tenantA,change("parcels",true,0))),"Versão antiga");
  assertTrue(state(db.tenantB,"parcels").enabled(),"Outro condomínio não muda");

  route(TenantModule.PARCELS);
  assertEquals("MODULE_DISABLED",code(()->tenants.execute(db.accountA,db.tenantA,context->1)));
  assertEquals("TENANT_ACCESS_DENIED",code(()->tenants.execute(db.accountB,db.tenantA,context->1)),
   "Quem não tem vínculo recebe 404 antes de saber do módulo");
  assertEquals(1,open(db.accountB,db.tenantB));
  route(TenantModule.PARCELS,TenantModule.VISITS);
  assertEquals(1,open(db.accountA,db.tenantA),"Rota de dois módulos vale com qualquer um liberado");
  route(TenantModule.RESERVATIONS);
  assertEquals(1,open(db.accountA,db.tenantA));
  route();
  assertEquals("MODULE_DISABLED",code(()->tenants.execute(db.accountA,db.tenantA,context->1)),"Conjunto vazio falha fechado");
  RequestContextHolder.resetRequestAttributes();
  assertEquals(1,open(db.accountA,db.tenantA),"Fora de rota marcada (worker, jobs) nada é exigido");

  var me=db.tx.execute(status->new CurrentIdentityQuery(db.jdbc,ALL).execute(db.accountA));
  var modulesA=me.tenantModules().stream().filter(t->t.tenantId().equals(db.tenantA)).findFirst().orElseThrow();
  assertFalse(modulesA.enabled().contains("parcels"));
  assertTrue(modulesA.enabled().containsAll(List.of("reservations","visits","finance","facial_access")));
  var onlyParcels=new TenantModuleFlags(EnumSet.of(TenantModule.PARCELS,TenantModule.VISITS));
  var meB=db.tx.execute(status->new CurrentIdentityQuery(db.jdbc,onlyParcels).execute(db.accountB));
  assertEquals(List.of("parcels","visits"),meB.tenantModules().getFirst().enabled(),"Flag global continua mandando no menu");

  var enabled=modules.change(db.accountA,AAL2,db.tenantA,change("parcels",true,1));
  assertTrue(enabled.applied()); assertEquals(2,enabled.module().version()); assertTrue(enabled.module().enabled());
  route(TenantModule.PARCELS);
  assertEquals(1,open(db.accountA,db.tenantA),"Religar vale na próxima requisição");
  assertEquals("1",db.adminString("select count(*) from platform.platform_audit_log where tenant_id=? and action='TENANT_MODULE_ENABLED'",db.tenantA));
  db.assertEmptyContext();
 }

 @Test void databaseRequiresTheCapabilityTheActorAndTheAuditEvent() throws Exception {
  // Sem capacidade publicada (só ator): INSERT recusado pela política.
  var noCapability=assertThrows(DataAccessException.class,()->db.context(db.accountA,null,null,()->db.jdbc.update(
   "insert into platform.tenant_modules(tenant_id,module,enabled,version,reason,updated_by) values(?,'finance',false,1,'Motivo ficticio',?)",
   db.tenantA,db.accountA)));
  assertEquals("42501",SqlStates.of(noCapability));
  // Com capacidade mas sem o evento da trilha: o COMMIT aborta.
  var noAudit=assertThrows(DataAccessException.class,()->platform.execute(db.accountA,AAL2,context->db.jdbc.update(
   "insert into platform.tenant_modules(tenant_id,module,enabled,version,reason,updated_by) values(?,'finance',false,1,'Motivo ficticio',?)",
   db.tenantA,db.accountA)));
  assertEquals("23514",SqlStates.of(noAudit));
  // Outro autor que não o ator publicado: recusado.
  var otherActor=assertThrows(DataAccessException.class,()->platform.execute(db.accountA,AAL2,context->db.jdbc.update(
   "insert into platform.tenant_modules(tenant_id,module,enabled,version,reason,updated_by) values(?,'finance',false,1,'Motivo ficticio',?)",
   db.tenantA,db.accountB)));
  assertEquals("42501",SqlStates.of(otherActor));
  modules.change(db.accountA,AAL2,db.tenantA,change("finance",false,0));
  // Salto de versão e apagamento são recusados mesmo com a capacidade.
  var jump=assertThrows(DataAccessException.class,()->platform.execute(db.accountA,AAL2,context->db.jdbc.update(
   "update platform.tenant_modules set version=5,updated_by=? where tenant_id=? and module='finance'",db.accountA,db.tenantA)));
  assertEquals("23514",SqlStates.of(jump));
  var delete=assertThrows(DataAccessException.class,()->platform.execute(db.accountA,AAL2,context->db.jdbc.update(
   "delete from platform.tenant_modules where tenant_id=?",db.tenantA)));
  assertEquals("42501",SqlStates.of(delete));
  // Membro lê o próprio condomínio; não lê o de outro.
  assertEquals("1",db.context(db.accountA,db.tenantA,null,()->db.jdbc.queryForObject(
   "select count(*) from platform.tenant_modules",String.class)));
  assertEquals("0",db.context(db.accountB,null,null,()->db.jdbc.queryForObject(
   "select count(*) from platform.tenant_modules",String.class)));
  db.assertEmptyContext();
 }
}
