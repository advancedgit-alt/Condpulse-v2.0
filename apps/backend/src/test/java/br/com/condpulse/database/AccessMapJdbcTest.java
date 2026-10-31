package br.com.condpulse.database;

import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessmap.application.MapModels;
import br.com.condpulse.accessmap.application.MapService;
import br.com.condpulse.accessmap.infrastructure.MapStore;
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

/** V041 com PostgreSQL real e o login da API: áreas (gestão) e pontos (MASTER de implantação). HAC-007, HAC-009, HAC-019, HAC-027. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessMapJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),MASTER=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static MapService map;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  map=new MapService(new TenantTransactionExecutor(db.jdbc,db.manager),new TechnicalTransactionExecutor(db.jdbc,db.manager),new MapStore(db.jdbc),
   new AuditRecorder(db.jdbc));
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(MASTER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,MASTER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO MAPA JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.assertEmptyContext();
  db.adminUpdate("insert into cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) "
   +"values(gen_random_uuid(),?,?,'MASTER_IMPLANTACAO',ARRAY['installation.read','installation.manage'],now(),now()+interval '10 days','OPERATIONS','Implantacao ficticia JDBC')",
   db.tenantA,MASTER);
 }
 @AfterEach void clear() throws Exception {
  db.adminUpdate("delete from cp.audit_log where tenant_id in (?,?) and (action like 'AREA\\_%' or action like 'POINT\\_%')",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_points where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.access_areas where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.adminUpdate("delete from cp.technical_grants where tenant_id in (?,?)",db.tenantA,db.tenantB);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static MapModels.CreateArea area(UUID id,String code,String kind,UUID parent) {
  return new MapModels.CreateArea(id,code,"Area "+code,kind,null,parent,null,null,null,"ACTIVE");
 }

 @Test void managerOwnsLogicalAreasAndMasterOwnsPhysicalMapping() throws Exception {
  UUID outside=UUID.randomUUID(),hall=UUID.randomUUID(),gym=UUID.randomUUID(),door=UUID.randomUUID();
  assertEquals("MFA_REQUIRED",code(()->map.createArea(MANAGER,db.tenantA,AAL1,area(outside,"EXTERIOR","EXTERIOR",null))));
  assertEquals("STAFF_ACCESS_DENIED",code(()->map.createArea(db.accountA,db.tenantA,AAL2,area(outside,"EXTERIOR","EXTERIOR",null))));
  var created=map.createArea(MANAGER,db.tenantA,AAL2,area(outside,"EXTERIOR","EXTERIOR",null));
  assertEquals(db.adminString("select time_zone from platform.tenants where id=?",db.tenantA),created.timeZone(),"Fuso inicial do condomínio");
  assertEquals(created,map.createArea(MANAGER,db.tenantA,AAL2,area(outside,"EXTERIOR","EXTERIOR",null)),"Mesmo pedido é repetição");
  assertEquals("ACCESS_AREA_ID_CONFLICT",code(()->map.createArea(MANAGER,db.tenantA,AAL2,area(outside,"EXTERIOR","COMMON_AREA",null))));
  map.createArea(MANAGER,db.tenantA,AAL2,area(hall,"HALL","RESIDENTIAL_ROUTE",outside));
  var gymArea=map.createArea(MANAGER,db.tenantA,AAL2,area(gym,"ACADEMIA","COMMON_AREA",hall));
  assertEquals("ACCESS_MAP_CODE_CONFLICT",code(()->map.createArea(MANAGER,db.tenantA,AAL2,area(UUID.randomUUID(),"ACADEMIA","COMMON_AREA",null))));
  assertEquals("ACCESS_MAP_INVALID",code(()->map.updateArea(MANAGER,db.tenantA,AAL2,outside,new MapModels.UpdateArea("Exterior","EXTERIOR",null,gym,null,
   null,null,"ACTIVE",0L))),"Hierarquia sem ciclo");
  var interdicted=map.updateArea(MANAGER,db.tenantA,AAL2,gym,new MapModels.UpdateArea("Academia","COMMON_AREA",null,hall,null,null,"GYM","INTERDICTED",
   gymArea.version()));
  assertEquals("INTERDICTED",interdicted.status());
  assertEquals("ACCESS_MAP_STALE",code(()->map.updateArea(MANAGER,db.tenantA,AAL2,gym,new MapModels.UpdateArea("Academia","COMMON_AREA",null,hall,null,
   null,"GYM","ACTIVE",gymArea.version()))));

  assertEquals("TENANT_ACCESS_DENIED",code(()->map.setPhysical(MANAGER,db.tenantA,AAL2,hall,new MapModels.AreaPhysical(true,true,1L))),
   "HAC-009: gestor não marca rota essencial");
  var physical=map.setPhysical(MASTER,db.tenantA,AAL2,hall,new MapModels.AreaPhysical(true,false,0L));
  assertTrue(physical.essentialRoute());
  assertEquals("MFA_REQUIRED",code(()->map.createPoint(MASTER,db.tenantA,AAL1,new MapModels.CreatePoint(door,"P-ACADEMIA","Porta",hall,gym,"DOOR",
   "BOTH","FREE_EGRESS",null,null,null))));
  var point=map.createPoint(MASTER,db.tenantA,AAL2,new MapModels.CreatePoint(door,"P-ACADEMIA","Porta da academia",hall,gym,"DOOR","BOTH","FREE_EGRESS",
   null,"projeto:emergencia-01","MAPPED"));
  assertEquals("MAPPED",point.status());
  assertEquals("ACCESS_POINT_STATUS_INVALID",code(()->map.createPoint(MASTER,db.tenantA,AAL2,new MapModels.CreatePoint(UUID.randomUUID(),"P2","Porta",hall,gym,
   "DOOR","BOTH",null,null,null,"COMMISSIONED"))));
  assertEquals("ACCESS_MAP_INVALID",code(()->map.updatePoint(MASTER,db.tenantA,AAL2,door,new MapModels.UpdatePoint("Porta da academia",hall,gym,"DOOR","BOTH",
   "FREE_EGRESS",null,null,"COMMISSIONED",0L))),"Comissionado só pelo aceite técnico");
  var summaries=map.pointSummaries(MANAGER,db.tenantA,AAL2).points();
  assertEquals(1,summaries.size()); assertEquals("Porta da academia",summaries.getFirst().name());
  assertEquals("TENANT_ACCESS_DENIED",code(()->map.createPoint(MANAGER,db.tenantA,AAL2,new MapModels.CreatePoint(UUID.randomUUID(),"P3","Porta",hall,gym,
   "DOOR","BOTH",null,null,null,null))),"HAC-027: gestor não cria ponto físico");
  assertEquals("TENANT_ACCESS_DENIED",code(()->map.technicalPoints(MASTER,db.tenantB,AAL2)),"HAC-007: concessão não atravessa condomínio");
  assertTrue(map.areas(MANAGER,db.tenantA,AAL2).areas().stream().allMatch(a->a.areaId()!=null));
  db.assertEmptyContext();
 }
}
