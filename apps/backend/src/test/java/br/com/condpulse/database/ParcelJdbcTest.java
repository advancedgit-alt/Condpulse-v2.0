package br.com.condpulse.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
import br.com.condpulse.parcels.infrastructure.ParcelStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V012 com PostgreSQL real: recebimento idempotente, visibilidade por unidade e entrega única sob concorrência. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class ParcelJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static ParcelService parcels;
 private static UUID concierge;

 @BeforeAll static void setup() throws Exception {
  // Duas conexões: entregas simultâneas precisam de transações concorrentes reais.
  db=new JdbcDisposableDatabase(2);
  parcels=new ParcelService(new TenantTransactionExecutor(db.jdbc,db.manager),new ParcelStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID(); db.extraAccounts.add(concierge);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",concierge);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'PORTARIA JDBC','CONCIERGE','ACTIVE')",
   db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "parcel-jdbc-"+UUID.randomUUID(); }
 private ParcelModels.Parcel receive(UUID unit,String location) {
  return parcels.receive(concierge,db.tenantA,AAL2,key(),new ParcelModels.Receive(unit,1,"Caixa fictícia",location)).parcel();
 }

 @Test void repeatedClickWithTheSameKeyRecordsOneParcelAndOneNotificationIntent() throws Exception {
  String key=key();
  var request=new ParcelModels.Receive(db.unitA,2,"  Caixa   média ","  Prateleira B  ");
  var first=parcels.receive(concierge,db.tenantA,AAL2,key,request);
  var replay=parcels.receive(concierge,db.tenantA,AAL2,key,request);
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(201,replay.status());
  assertEquals(first.parcel(),replay.parcel(),"Repetição devolve a mesma resposta");
  assertEquals("RECEIVED",first.parcel().status()); assertEquals("Caixa média",first.parcel().description());
  assertEquals("Prateleira B",first.parcel().storageLocation()); assertEquals(0,first.parcel().version());
  assertNull(first.parcel().handoff());
  UUID id=first.parcel().parcelId();
  assertEquals("1",db.adminString("select count(*) from cp.parcels where tenant_id=?",db.tenantA));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='PARCEL_RECEIVED'",id));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='PARCEL_RECEIVED' and actor_id=?",id,concierge));
  assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,key,
   new ParcelModels.Receive(db.unitA,3,"Caixa média","Prateleira B"))).code());
  db.assertEmptyContext();
 }
 @Test void residentsSeeOnlyTheirOwnUnitsAndStaffNeedsTheCapabilityAndSecondFactor() throws Exception {
  var own=receive(db.unitA,"Armário 1");
  var other=receive(db.otherUnitA,"Armário 2");
  var page=parcels.unitParcels(db.accountA,db.tenantA,db.unitA,null);
  assertEquals(List.of(own.parcelId()),page.parcels().stream().map(ParcelModels.Parcel::parcelId).toList());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.unitParcels(db.accountA,db.tenantA,db.otherUnitA,null)).code());
  assertEquals("PARCEL_NOT_FOUND",assertThrows(BusinessProblem.class,()->parcels.parcel(db.accountA,db.tenantA,AssuranceLevel.AAL1,other.parcelId())).code(),
   "Encomenda de outra unidade do mesmo condomínio não é revelada");
  assertEquals(own.parcelId(),parcels.parcel(db.accountA,db.tenantA,AssuranceLevel.AAL1,own.parcelId()).parcelId());
  assertEquals(other.parcelId(),parcels.unitParcels(db.accountC,db.tenantA,db.otherUnitA,"RECEIVED").parcels().getFirst().parcelId());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.unitParcels(db.accountB,db.tenantA,db.unitA,null)).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.list(db.accountA,db.tenantA,AAL2,null,null,null)).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.list(concierge,db.tenantA,AssuranceLevel.AAL1,null,null,null)).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AssuranceLevel.AAL1,key(),
   new ParcelModels.Receive(db.unitA,1,"","Armário 3"))).code());
  var all=parcels.list(concierge,db.tenantA,AAL2,"RECEIVED",null,null).parcels();
  assertEquals(2,all.size());
  var filtered=parcels.list(concierge,db.tenantA,AAL2,null,db.otherUnitA,"armário_").parcels();
  assertTrue(filtered.isEmpty(),"Sublinhado no filtro de local é texto literal");
  assertEquals(List.of(other.parcelId()),parcels.list(concierge,db.tenantA,AAL2,null,db.otherUnitA,"ário 2").parcels().stream()
   .map(ParcelModels.Parcel::parcelId).toList());
  assertEquals(2,parcels.units(concierge,db.tenantA,AAL2,"jdbc a").size());
  db.assertEmptyContext();
 }
 @Test void deliveryNeedsIdentityCheckAndReadVersionAndIsRecordedOnce() throws Exception {
  var parcel=receive(db.unitA,"Armário 1");
  UUID id=parcel.parcelId();
  assertEquals("PARCEL_IDENTITY_CHECK_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver("Pessoa Fictícia",false,0L))).code());
  assertEquals("PARCEL_CHANGED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver("Pessoa Fictícia",true,5L))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.deliver(db.accountA,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver("Pessoa Fictícia",true,0L))).code());
  String key=key();
  var delivered=parcels.deliver(concierge,db.tenantA,AAL2,id,key,new ParcelModels.Deliver("  Pessoa   Fictícia ",true,0L));
  assertEquals("DELIVERED",delivered.parcel().status()); assertEquals(1,delivered.parcel().version());
  assertEquals("Pessoa Fictícia",delivered.parcel().handoff().recipientName()); assertEquals("ASSISTED",delivered.parcel().handoff().evidenceKind());
  var replay=parcels.deliver(concierge,db.tenantA,AAL2,id,key,new ParcelModels.Deliver("Pessoa Fictícia",true,0L));
  assertTrue(replay.replayed()); assertEquals(delivered.parcel(),replay.parcel());
  assertEquals("PARCEL_ALREADY_DELIVERED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver("Outra Pessoa",true,1L))).code());
  var seen=parcels.parcel(db.accountA,db.tenantA,AssuranceLevel.AAL1,id);
  assertEquals("Pessoa Fictícia",seen.handoff().recipientName(),"A unidade vê quem retirou");
  assertEquals("1",db.adminString("select count(*) from cp.parcel_handoffs where parcel_id=?",id));
  assertEquals("PARCEL_DELIVERED:1,PARCEL_RECEIVED:0",db.adminString(
   "select string_agg(event_type||':'||aggregate_version,',' order by event_type) from cp.outbox where aggregate_id=?",id));
  db.assertEmptyContext();
 }
 @Test void twoSimultaneousDeliveriesProduceOneSuccessAndOneConflict() throws Exception {
  var parcel=receive(db.unitA,"Armário 1");
  var start=new CountDownLatch(1);
  var pool=Executors.newFixedThreadPool(2);
  try {
   var tasks=new ArrayList<Callable<String>>();
   for(String name:new String[]{"Primeira Pessoa","Segunda Pessoa"})
    tasks.add(()->{
     start.await();
     try { parcels.deliver(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),new ParcelModels.Deliver(name,true,0L)); return "OK"; }
     catch(BusinessProblem problem) { return problem.code(); }
    });
   var futures=tasks.stream().map(pool::submit).toList();
   start.countDown();
   var results=new ArrayList<String>();
   for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
   assertEquals(1,results.stream().filter("OK"::equals).count(),results.toString());
   assertTrue(results.stream().filter(r->!"OK".equals(r)).allMatch(r->r.equals("PARCEL_ALREADY_DELIVERED") || r.equals("PARCEL_CHANGED")),
    results.toString());
  } finally { pool.shutdownNow(); }
  assertEquals("1",db.adminString("select count(*) from cp.parcel_handoffs where parcel_id=?",parcel.parcelId()));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='PARCEL_DELIVERED'",parcel.parcelId()));
  db.assertEmptyContext();
 }
 @Test void inactiveOrForeignUnitAndInvalidInputReceiveNothing() throws Exception {
  db.adminUpdate("update cp.units set status='INACTIVE' where id=?",db.otherUnitA);
  assertEquals("PARCEL_UNIT_UNAVAILABLE",assertThrows(BusinessProblem.class,()->receive(db.otherUnitA,"Armário 1")).code());
  assertEquals("PARCEL_UNIT_UNAVAILABLE",assertThrows(BusinessProblem.class,()->receive(db.unitB,"Armário 1")).code(),
   "Unidade de outro condomínio não recebe encomenda");
  assertEquals("PARCEL_QUANTITY_INVALID",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,key(),
   new ParcelModels.Receive(db.unitA,0,"","Armário 1"))).code());
  assertEquals("PARCEL_LOCATION_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,key(),
   new ParcelModels.Receive(db.unitA,1,"","​"))).code());
  assertEquals("IDEMPOTENCY_KEY_INVALID",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,"curta",
   new ParcelModels.Receive(db.unitA,1,"","Armário 1"))).code());
  assertEquals("0",db.adminString("select count(*) from cp.parcels where tenant_id in (?,?)",db.tenantA,db.tenantB));
  db.assertEmptyContext();
 }
}
