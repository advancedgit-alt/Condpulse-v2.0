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

/** V012 com PostgreSQL real: recebimento idempotente, visibilidade por unidade, retirada identificada e única, anulação. */
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
 /** Vínculo residencial fixo da conta A na unidade A ("PESSOA FICTICIA JDBC A"). */
 private static UUID memberA() throws Exception {
  return UUID.fromString(db.adminString("select id from cp.memberships where tenant_id=? and account_id=? and role='RESIDENT'",
   db.tenantA,db.accountA));
 }
 private ParcelModels.Parcel receive(UUID unit,String location) {
  return parcels.receive(concierge,db.tenantA,AAL2,key(),new ParcelModels.Receive(unit,1,"Caixa fictícia",location,null)).parcel();
 }

 @Test void repeatedClickWithTheSameKeyRecordsOneParcelAndOneNotificationIntent() throws Exception {
  String key=key();
  var request=new ParcelModels.Receive(db.unitA,2,"  Caixa   média ","  Prateleira B  ",null);
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
   new ParcelModels.Receive(db.unitA,3,"Caixa média","Prateleira B",null))).code());
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
   new ParcelModels.Receive(db.unitA,1,"","Armário 3",null))).code());
  var all=parcels.list(concierge,db.tenantA,AAL2,"RECEIVED",null,null).parcels();
  assertEquals(2,all.size());
  var filtered=parcels.list(concierge,db.tenantA,AAL2,null,db.otherUnitA,"armário_").parcels();
  assertTrue(filtered.isEmpty(),"Sublinhado no filtro de local é texto literal");
  assertEquals(List.of(other.parcelId()),parcels.list(concierge,db.tenantA,AAL2,null,db.otherUnitA,"ário 2").parcels().stream()
   .map(ParcelModels.Parcel::parcelId).toList());
  assertEquals(2,parcels.units(concierge,db.tenantA,AAL2,"jdbc a").size());
  db.assertEmptyContext();
 }
 @Test void deliveryNeedsALinkedPersonIdentityCheckAndReadVersionAndIsRecordedOnce() throws Exception {
  var parcel=receive(db.unitA,"Armário 1");
  UUID id=parcel.parcelId();
  UUID person=memberA();
  var recipients=parcels.recipients(concierge,db.tenantA,AAL2,id);
  assertEquals(List.of(new ParcelModels.Recipient(person,"PESSOA FICTICIA JDBC A","RESIDENT")),recipients,
   "Somente vínculos residenciais vigentes da unidade da encomenda");
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.recipients(db.accountA,db.tenantA,AAL2,id)).code());
  assertEquals("PARCEL_IDENTITY_CHECK_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(person,false,0L))).code());
  assertEquals("PARCEL_RECIPIENT_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(null,true,0L))).code());
  assertEquals("PARCEL_CHANGED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(person,true,5L))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.deliver(db.accountA,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(person,true,0L))).code());
  UUID otherUnitPerson=UUID.fromString(db.adminString("select id from cp.memberships where tenant_id=? and account_id=?",db.tenantA,db.accountC));
  assertEquals("PARCEL_RECIPIENT_NOT_ALLOWED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(otherUnitPerson,true,0L))).code(),"Morador de outra unidade não retira");
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountA);
  assertTrue(parcels.recipients(concierge,db.tenantA,AAL2,id).isEmpty(),"Conta suspensa não aparece para retirada");
  assertEquals("PARCEL_RECIPIENT_NOT_ALLOWED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(person,true,0L))).code());
  db.restoreActiveFixtures();
  String key=key();
  var delivered=parcels.deliver(concierge,db.tenantA,AAL2,id,key,new ParcelModels.Deliver(person,true,0L));
  assertEquals("DELIVERED",delivered.parcel().status()); assertEquals(1,delivered.parcel().version());
  assertEquals("PESSOA FICTICIA JDBC A",delivered.parcel().handoff().recipientName()); assertEquals("ASSISTED",delivered.parcel().handoff().evidenceKind());
  assertEquals(person.toString(),db.adminString("select recipient_membership_id from cp.parcel_handoffs where parcel_id=?",id));
  var replay=parcels.deliver(concierge,db.tenantA,AAL2,id,key,new ParcelModels.Deliver(person,true,0L));
  assertTrue(replay.replayed()); assertEquals(delivered.parcel(),replay.parcel());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='PARCEL_DELIVERED'",id),
   "Repetição não grava nova auditoria");
  assertEquals("PARCEL_ALREADY_DELIVERED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(person,true,1L))).code());
  var seen=parcels.parcel(db.accountA,db.tenantA,AssuranceLevel.AAL1,id);
  assertEquals("PESSOA FICTICIA JDBC A",seen.handoff().recipientName(),"A unidade vê quem retirou");
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
   UUID person=memberA();
   for(int attempt=0;attempt<2;attempt++)
    tasks.add(()->{
     start.await();
     try { parcels.deliver(concierge,db.tenantA,AAL2,parcel.parcelId(),key(),new ParcelModels.Deliver(person,true,0L)); return "OK"; }
     catch(BusinessProblem problem) { return problem.code(); }
    });
   var futures=tasks.stream().map(pool::submit).toList();
   start.countDown();
   var results=new ArrayList<String>();
   for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
   assertEquals(1,results.stream().filter("OK"::equals).count(),results.toString());
   // A perdedora espera o bloqueio da encomenda e lê o estado final: "já retirada", nunca "alterada".
   assertEquals(List.of("PARCEL_ALREADY_DELIVERED"),results.stream().filter(r->!"OK".equals(r)).toList(),results.toString());
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
   new ParcelModels.Receive(db.unitA,0,"","Armário 1",null))).code());
  assertEquals("PARCEL_LOCATION_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,key(),
   new ParcelModels.Receive(db.unitA,1,"","​",null))).code());
  assertEquals("IDEMPOTENCY_KEY_INVALID",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,"curta",
   new ParcelModels.Receive(db.unitA,1,"","Armário 1",null))).code());
  assertEquals("0",db.adminString("select count(*) from cp.parcels where tenant_id in (?,?)",db.tenantA,db.tenantB));
  db.assertEmptyContext();
 }
 @Test void suspectedDuplicateNeedsExplicitConfirmationAndWrongRecordIsVoidedNotDeleted() throws Exception {
  var first=receive(db.unitA,"Armário 1");
  var again=new ParcelModels.Receive(db.unitA,1,"CAIXA fictícia","armário 1",null);
  assertEquals("PARCEL_DUPLICATE_SUSPECTED",assertThrows(BusinessProblem.class,()->parcels.receive(concierge,db.tenantA,AAL2,key(),again)).code());
  assertEquals("1",db.adminString("select count(*) from cp.parcels where tenant_id=?",db.tenantA),"Aviso de duplicidade não grava nada");
  var confirmed=parcels.receive(concierge,db.tenantA,AAL2,key(),new ParcelModels.Receive(db.unitA,1,"CAIXA fictícia","armário 1",true));
  assertNotEquals(first.parcelId(),confirmed.parcel().parcelId());
  assertEquals("PARCEL_VOID_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->parcels.voidParcel(concierge,db.tenantA,AAL2,
   confirmed.parcel().parcelId(),key(),new ParcelModels.VoidRequest(" ",0L))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.voidParcel(db.accountA,db.tenantA,AAL2,
   confirmed.parcel().parcelId(),key(),new ParcelModels.VoidRequest("Registro duplicado",0L))).code());
  assertEquals("PARCEL_CHANGED",assertThrows(BusinessProblem.class,()->parcels.voidParcel(concierge,db.tenantA,AAL2,
   confirmed.parcel().parcelId(),key(),new ParcelModels.VoidRequest("Registro duplicado",3L))).code());
  String voidKey=key();
  var voided=parcels.voidParcel(concierge,db.tenantA,AAL2,confirmed.parcel().parcelId(),voidKey,
   new ParcelModels.VoidRequest("  Registro   duplicado ",0L)).parcel();
  var again=parcels.voidParcel(concierge,db.tenantA,AAL2,confirmed.parcel().parcelId(),voidKey,new ParcelModels.VoidRequest("Registro duplicado",0L));
  assertTrue(again.replayed()); assertEquals(voided,again.parcel(),"Repetir a anulação devolve o mesmo resultado");
  assertEquals("VOIDED",voided.status()); assertEquals(1,voided.version()); assertEquals("Registro duplicado",voided.voidReason());
  assertNotNull(voided.voidedAt()); assertNull(voided.handoff());
  UUID id=voided.parcelId();
  assertEquals("PARCEL_NOT_FOUND",assertThrows(BusinessProblem.class,()->parcels.parcel(db.accountA,db.tenantA,AssuranceLevel.AAL1,id)).code(),
   "A unidade não vê o registro anulado");
  assertEquals(List.of(first.parcelId()),parcels.unitParcels(db.accountA,db.tenantA,db.unitA,null).parcels().stream()
   .map(ParcelModels.Parcel::parcelId).toList());
  assertEquals("PARCEL_STATUS_INVALID",assertThrows(BusinessProblem.class,()->parcels.unitParcels(db.accountA,db.tenantA,db.unitA,"VOIDED")).code());
  assertEquals(List.of(id),parcels.list(concierge,db.tenantA,AAL2,"VOIDED",null,null).parcels().stream().map(ParcelModels.Parcel::parcelId).toList());
  UUID person=memberA();
  assertEquals("PARCEL_VOIDED",assertThrows(BusinessProblem.class,()->parcels.deliver(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.Deliver(person,true,1L))).code());
  assertEquals("PARCEL_VOIDED",assertThrows(BusinessProblem.class,()->parcels.voidParcel(concierge,db.tenantA,AAL2,id,key(),
   new ParcelModels.VoidRequest("De novo",1L))).code());
  assertEquals("Registro duplicado",db.adminString("select reason from cp.audit_log where resource_id=? and action='PARCEL_VOIDED' and actor_id=?",
   id,concierge));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='PARCEL_VOIDED'",id));
  db.assertEmptyContext();
 }
 @Test void dependentSeesParcelsOnlyWithExplicitPermissionAndPendingQueueStartsWithTheOldest() throws Exception {
  UUID dependent=UUID.randomUUID(); db.extraAccounts.add(dependent);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",dependent);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,'DEPENDENTE JDBC','DEPENDENT','ACTIVE')",
   db.tenantA,dependent,db.unitA);
  try {
   var older=receive(db.unitA,"Armário 1");
   var newer=receive(db.unitA,"Armário 2");
   assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->parcels.unitParcels(dependent,db.tenantA,db.unitA,null)).code());
   assertFalse(parcels.recipients(concierge,db.tenantA,AAL2,older.parcelId()).stream().anyMatch(r->"DEPENDENT".equals(r.role())),
    "Dependente sem permissão não retira");
   db.adminUpdate("update cp.memberships set permissions='{\"parcels\":{\"view\":true,\"pickup\":true}}'::jsonb where account_id=?",dependent);
   assertEquals(2,parcels.unitParcels(dependent,db.tenantA,db.unitA,null).parcels().size());
   assertTrue(parcels.recipients(concierge,db.tenantA,AAL2,older.parcelId()).stream().anyMatch(r->"DEPENDENTE JDBC".equals(r.displayName())));
   assertEquals(List.of(older.parcelId(),newer.parcelId()),parcels.list(concierge,db.tenantA,AAL2,"RECEIVED",null,null).parcels().stream()
    .map(ParcelModels.Parcel::parcelId).toList(),"Pendentes da mais antiga para a mais nova");
  } finally {
   db.clearReservationFixtures();
   db.adminUpdate("delete from cp.memberships where account_id=?",dependent);
  }
  db.assertEmptyContext();
 }
}
