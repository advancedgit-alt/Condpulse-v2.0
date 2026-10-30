package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.occurrences.application.OccurrenceModels;
import br.com.condpulse.occurrences.application.OccurrenceService;
import br.com.condpulse.occurrences.infrastructure.OccurrenceStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V033 com PostgreSQL real e o login da API: ocorrências da portaria, repetição sem duplicar, encerramento pela versão e isolamento. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class OccurrenceJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static OccurrenceService occurrences;
 private static UUID concierge,manager;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  occurrences=new OccurrenceService(new TenantTransactionExecutor(db.jdbc,db.manager),new OccurrenceStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID(); manager=UUID.randomUUID();
  for(UUID account:List.of(concierge,manager)) db.extraAccounts.add(account);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",concierge,manager);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
   values(?,?,null,'PORTARIA OCORRENCIA JDBC','CONCIERGE','ACTIVE'),(?,?,null,'GESTAO OCORRENCIA JDBC','MANAGER','ACTIVE')
   """,db.tenantA,concierge,db.tenantA,manager);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.adminUpdateWithoutTriggers("delete from cp.concierge_occurrences where tenant_id in (?,?)",db.tenantA,db.tenantB);
  db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception {
  if(db!=null) {
   db.adminUpdateWithoutTriggers("delete from cp.concierge_occurrences where tenant_id in (?,?)",db.tenantA,db.tenantB);
   db.close();
  }
 }
 private static OccurrenceModels.Create create(UUID id,String title) {
  return new OccurrenceModels.Create(id,"EQUIPMENT",title,"  Não fecha sozinho.\n\n\nDesde a manhã. ",db.unitA);
 }

 @Test void conciergeRegistersReplaysWithoutDuplicateAndManagerResolvesByVersion() throws Exception {
  UUID id=UUID.randomUUID();
  var created=occurrences.create(concierge,db.tenantA,AAL2,create(id,"  Portão   da garagem "));
  assertFalse(created.replayed());
  var occurrence=created.occurrence();
  assertEquals("Portão da garagem",occurrence.title()); assertEquals("Não fecha sozinho.\n\nDesde a manhã.",occurrence.description());
  assertEquals("OPEN",occurrence.state()); assertEquals("PORTARIA OCORRENCIA JDBC",occurrence.createdByName()); assertTrue(occurrence.createdByMe());
  assertNotNull(occurrence.unitLabel());
  assertTrue(occurrences.create(concierge,db.tenantA,AAL2,create(id,"Portão da garagem")).replayed(),"Repetição devolve o mesmo registro");
  assertEquals("OCCURRENCE_ID_CONFLICT",assertThrows(BusinessProblem.class,()->occurrences.create(concierge,db.tenantA,AAL2,create(id,"Outro título"))).code());
  assertEquals("1",db.adminString("select count(*) from cp.concierge_occurrences where tenant_id=?",db.tenantA));
  var page=occurrences.page(manager,db.tenantA,AAL2,false);
  assertEquals(1,page.open()); assertFalse(page.occurrences().getFirst().createdByMe());
  assertEquals("OCCURRENCE_CHANGED",assertThrows(BusinessProblem.class,
   ()->occurrences.resolve(manager,db.tenantA,AAL2,id,new OccurrenceModels.Resolve("Ajustado",5L))).code());
  var resolved=occurrences.resolve(manager,db.tenantA,AAL2,id,new OccurrenceModels.Resolve(" Motor ajustado ",0L));
  assertEquals("RESOLVED",resolved.state()); assertEquals("Motor ajustado",resolved.resolution()); assertEquals("GESTAO OCORRENCIA JDBC",resolved.resolvedByName());
  assertEquals(resolved,occurrences.resolve(manager,db.tenantA,AAL2,id,new OccurrenceModels.Resolve("Motor ajustado",0L)),"Repetição natural");
  assertEquals("OCCURRENCE_RESOLVED",assertThrows(BusinessProblem.class,
   ()->occurrences.resolve(concierge,db.tenantA,AAL2,id,new OccurrenceModels.Resolve("Outra",1L))).code());
  assertTrue(occurrences.page(concierge,db.tenantA,AAL2,false).occurrences().isEmpty(),"Lista padrão só com abertas");
  assertEquals(1,occurrences.page(concierge,db.tenantA,AAL2,true).occurrences().size());
  assertEquals("2",db.adminString("select count(*) from cp.audit_log where tenant_id=? and resource_id=? and action like 'OCCURRENCE\\_%'",db.tenantA,id));
  db.assertEmptyContext();
 }

 @Test void residentsOtherTenantsAndAal1AreRefused() throws Exception {
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->occurrences.page(db.accountA,db.tenantA,AAL2,false)).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->occurrences.page(concierge,db.tenantA,AssuranceLevel.AAL1,false)).code());
  assertEquals("OCCURRENCE_UNIT_INVALID",assertThrows(BusinessProblem.class,()->occurrences.create(concierge,db.tenantA,AAL2,
   new OccurrenceModels.Create(UUID.randomUUID(),"SECURITY","Porta aberta","Porta do hall",db.unitB))).code());
  assertEquals("OCCURRENCE_CATEGORY_INVALID",assertThrows(BusinessProblem.class,()->occurrences.create(concierge,db.tenantA,AAL2,
   new OccurrenceModels.Create(UUID.randomUUID(),"FACIAL","Porta aberta","Porta do hall",null))).code());
  var created=occurrences.create(concierge,db.tenantA,AAL2,new OccurrenceModels.Create(UUID.randomUUID(),"SECURITY","Porta aberta","Porta do hall",null)).occurrence();
  assertNull(created.unitId());
  assertThrows(BusinessProblem.class,()->occurrences.page(concierge,db.tenantB,AAL2,false),"Outro condomínio");
  assertEquals("OCCURRENCE_NOT_FOUND",assertThrows(BusinessProblem.class,
   ()->occurrences.resolve(concierge,db.tenantA,AAL2,UUID.randomUUID(),new OccurrenceModels.Resolve("x",0L))).code());
  db.assertEmptyContext();
 }
}
