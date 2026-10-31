package br.com.condpulse.database;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesscatalog.application.CatalogModels;
import br.com.condpulse.accesscatalog.application.CatalogService;
import br.com.condpulse.accesscatalog.infrastructure.CatalogStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V040 com PostgreSQL real e o login da API: catálogo documental e homologação por combinação (AC-019). Casos HAC-001,
 * HAC-002, HAC-092 e HAC-096.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AccessCatalogJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static CatalogService catalog;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  catalog=new CatalogService(new PlatformTransactionExecutor(db.jdbc,db.manager),new TenantTransactionExecutor(db.jdbc,db.manager),
   new CatalogStore(db.jdbc),new PlatformStore(db.jdbc));
  db.extraAccounts.add(MANAGER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",MANAGER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO CATALOGO JDBC','MANAGER','ACTIVE')",db.tenantA,MANAGER);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.grantPlatformAdmin(db.accountA); db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) { db.clearPlatformFixtures(); db.close(); } }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static Map<String,CatalogModels.CapabilityObservation> caps(String... observed) {
  var result=new HashMap<String,CatalogModels.CapabilityObservation>();
  for(String name:CatalogModels.CAPABILITIES) result.put(name,new CatalogModels.CapabilityObservation(true,List.of(observed).contains(name)?true:null));
  return result;
 }
 private static Map<String,Long> limits() {
  var result=new HashMap<String,Long>(); for(String name:CatalogModels.LIMITS) result.put(name,null); result.put("faces",3000L); return result;
 }
 private static UUID profile(String code) throws Exception {
  return UUID.fromString(db.adminString("select id from platform.access_model_profiles where catalog_code=?",code));
 }
 private static CatalogModels.CreateHomologation dossier(UUID id,UUID profile,String firmware,String topology) {
  return new CatalogModels.CreateHomologation(id,profile,firmware,"control-id-rest","0.1.0","v1","EDGE_ONLINE",topology,caps("faceEnrollment"),
   limits(),List.of(UUID.fromString("41000000-0000-4000-8000-000000000001")),List.of("Ensaio de bancada ficticio"),"Dossie de ensaio ficticio JDBC");
 }
 private static CatalogModels.Decide decision(String kind,List<String> approved,long version) {
  return new CatalogModels.Decide(UUID.randomUUID(),kind,"Decisao de ensaio ficticia JDBC",
   kind.startsWith("APPROVE_")?List.of(UUID.fromString("41000000-0000-4000-8000-000000000002")):List.of(),approved,null,List.of(),version);
 }

 @Test void catalogIsDocumentalAndReadOnly() throws Exception {
  assertEquals(27,catalog.profiles(db.accountA,AAL2,null).profiles().size(),"Pacote v1.1: 27 modelos candidatos");
  var control=catalog.profiles(db.accountA,AAL2,"CONTROL_ID").profiles();
  assertTrue(control.stream().allMatch(p->"CANDIDATE".equals(p.status()) && "DOCUMENTED_CANDIDATE".equals(p.lifecycle())
   && "UNKNOWN".equals(p.hardwareRevision()) && p.documentHash().length()==64 && !p.sources().isEmpty()),"HAC-002");
  assertEquals("MFA_REQUIRED",code(()->catalog.profiles(db.accountA,AAL1,null)));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->catalog.profiles(MANAGER,AAL2,null)));
  assertEquals("ACCESS_MODEL_FILTER_INVALID",code(()->catalog.profiles(db.accountA,AAL2,"OUTRA")));
  db.assertEmptyContext();
 }

 @Test void homologationFollowsDecisionsOnlyForTheExactCombination() throws Exception {
  UUID ci=profile("CI-M01"),h1=UUID.randomUUID(),h2=UUID.randomUUID(),h3=UUID.randomUUID();
  var created=catalog.create(db.accountA,AAL2,dossier(h1,ci,"1.2.3","LOCAL_GATEWAY"));
  assertTrue(created.applied()); assertEquals("CANDIDATO_DOCUMENTADO",created.homologation().status()); assertEquals(1,created.homologation().revision());
  assertFalse(catalog.create(db.accountA,AAL2,dossier(h1,ci,"1.2.3","LOCAL_GATEWAY")).applied(),"Mesmo dossiê é repetição");
  assertEquals("HOMOLOGATION_ID_CONFLICT",code(()->catalog.create(db.accountA,AAL2,dossier(h1,ci,"1.2.4","LOCAL_GATEWAY"))));
  assertEquals("HOMOLOGATION_FIRMWARE_INVALID",code(()->catalog.create(db.accountA,AAL2,dossier(UUID.randomUUID(),ci,"1.*","LOCAL_GATEWAY"))),"HAC-001");
  assertEquals("HOMOLOGATION_TOPOLOGY_INVALID",code(()->catalog.create(db.accountA,AAL2,dossier(UUID.randomUUID(),ci,"1.2.3","QUALQUER"))));
  assertEquals(2,catalog.create(db.accountA,AAL2,dossier(h2,ci,"1.2.3","LOCAL_GATEWAY")).homologation().revision(),"HAC-096: nova revisão");
  catalog.create(db.accountA,AAL2,dossier(h3,profile("CI-M02"),"1.2.3","CLOUD_SERVICE"));

  assertEquals("HOMOLOGATION_TRANSITION_INVALID",code(()->catalog.decide(db.accountA,AAL2,h1,decision("APPROVE_LAB",List.of(),0))));
  var trial=catalog.decide(db.accountA,AAL2,h1,decision("START_TRIAL",List.of(),0));
  assertEquals("EM_ENSAIO",trial.homologation().status());
  assertEquals("HOMOLOGATION_TRANSITION_INVALID",code(()->catalog.decide(db.accountA,AAL2,h1,decision("APPROVE_LAB",List.of("perDoorRights"),1))),
   "HAC-002: só capacidade observada");
  assertEquals("HOMOLOGATION_STALE",code(()->catalog.decide(db.accountA,AAL2,h1,decision("APPROVE_LAB",List.of("faceEnrollment"),0))));
  var review=Instant.now().plus(180,ChronoUnit.DAYS);
  var request=new CatalogModels.Decide(UUID.randomUUID(),"APPROVE_LAB","Bancada aprovada ficticia",
   List.of(UUID.fromString("41000000-0000-4000-8000-000000000002")),List.of("faceEnrollment"),review,List.of("Somente modo online"),1L);
  var lab=catalog.decide(db.accountA,AAL2,h1,request);
  assertEquals("APROVADO_BANCADA",lab.homologation().status()); assertTrue(lab.homologation().current());
  assertEquals(List.of("faceEnrollment"),lab.homologation().approvedCapabilities()); assertEquals(2,lab.decisions().size());
  assertEquals(lab.homologation(),catalog.decide(db.accountA,AAL2,h1,request).homologation(),"Mesma decisão é repetição");
  assertEquals("HOMOLOGATION_DECISION_ID_CONFLICT",code(()->catalog.decide(db.accountA,AAL2,h1,new CatalogModels.Decide(request.decisionId(),"APPROVE_LAB",
   "Outro motivo ficticio",request.evidenceRefs(),request.approvedCapabilityNames(),review,request.restrictions(),1L))));
  assertEquals("CANDIDATO_DOCUMENTADO",catalog.homologation(db.accountA,AAL2,h3).homologation().status(),"HAC-092: outra combinação não herda");
  assertEquals("CANDIDATO_DOCUMENTADO",catalog.homologation(db.accountA,AAL2,h2).homologation().status(),"Revisões são independentes");
  var listed=catalog.managementHomologations(MANAGER,db.tenantA,AAL2).homologations();
  assertTrue(listed.stream().anyMatch(h->h.id().equals(h1) && "APROVADO_BANCADA".equals(h.status())),"Gestão consulta o estado");
  assertEquals("STAFF_ACCESS_DENIED",code(()->catalog.managementHomologations(db.accountC,db.tenantA,AAL2)));
  assertEquals("PLATFORM_ACCESS_DENIED",code(()->catalog.decide(MANAGER,AAL2,h1,decision("SUSPEND",List.of(),2))));
  assertEquals("SUSPENSO",catalog.decide(db.accountA,AAL2,h1,decision("SUSPEND",List.of(),2)).homologation().status());
  assertFalse(catalog.homologation(db.accountA,AAL2,h1).homologation().current());
  db.assertEmptyContext();
 }
}
