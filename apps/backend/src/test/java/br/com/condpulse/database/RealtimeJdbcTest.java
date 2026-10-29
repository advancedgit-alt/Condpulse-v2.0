package br.com.condpulse.database;

import java.util.Map;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
import br.com.condpulse.parcels.infrastructure.ParcelStore;
import br.com.condpulse.realtime.application.RealtimeService;
import br.com.condpulse.realtime.infrastructure.RealtimeStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** CP-036 com PostgreSQL real e login da API: o estado resumido segue as políticas de leitura e a revogação do vínculo. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class RealtimeJdbcTest {
 private static JdbcDisposableDatabase db;
 private static RealtimeService realtime;
 private static ParcelService parcels;
 private static UUID concierge;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  realtime=new RealtimeService(transactions,new RealtimeStore(db.jdbc));
  parcels=new ParcelService(transactions,new ParcelStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID(); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA JDBC TEMPO REAL','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static void receive(UUID unit) {
  parcels.receive(concierge,db.tenantA,AssuranceLevel.AAL2,"realtime-jdbc-"+UUID.randomUUID(),
   new ParcelModels.Receive(unit,1,"Caixa fictícia","Armário tempo real",null));
 }

 @Test void everyTopicIsReadAndOnlyChangesVisibleToTheReaderMoveTheFingerprint() throws Exception {
  Map<String,String> before=realtime.snapshot(db.accountA,db.tenantA,db.unitA);
  assertEquals(RealtimeStore.TOPICS,before.keySet().stream().toList(),"Todos os assuntos, na ordem fixa");
  assertTrue(before.values().stream().allMatch(value->value!=null && !value.isBlank()));
  receive(db.otherUnitA);
  assertEquals(before,realtime.snapshot(db.accountA,db.tenantA,db.unitA),"Encomenda de outra unidade não muda nada para A");
  receive(db.unitA);
  Map<String,String> after=realtime.snapshot(db.accountA,db.tenantA,db.unitA);
  assertNotEquals(before.get("parcels"),after.get("parcels"));
  for(String topic:RealtimeStore.TOPICS) if(!topic.equals("parcels")) assertEquals(before.get(topic),after.get(topic),topic);
  assertFalse(String.join(",",after.values()).contains(db.unitA.toString()),"Sem identificadores no estado resumido");
  db.assertEmptyContext();
 }

 @Test void otherUnitOtherTenantAndRevokedMembershipAreRefused() throws Exception {
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->realtime.snapshot(db.accountA,db.tenantA,db.otherUnitA)).code());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->realtime.snapshot(concierge,db.tenantA,db.unitA)).code(),
   "Equipe sem unidade não abre o canal residencial");
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->realtime.snapshot(db.accountB,db.tenantA,db.unitA)).code());
  assertNotNull(realtime.snapshot(db.accountA,db.tenantA,db.unitA));
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=? and unit_id=?",db.tenantA,db.accountA,db.unitA);
  var revoked=assertThrows(BusinessProblem.class,()->realtime.snapshot(db.accountA,db.tenantA,db.unitA));
  assertTrue(revoked.status()==403 || revoked.status()==404,revoked.code());
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountC);
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,()->realtime.snapshot(db.accountC,db.tenantA,db.otherUnitA)).code());
  db.restoreActiveFixtures();
  assertNotNull(realtime.snapshot(db.accountA,db.tenantA,db.unitA));
  db.assertEmptyContext();
 }
}
