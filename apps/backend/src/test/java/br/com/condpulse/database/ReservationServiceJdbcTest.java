package br.com.condpulse.database;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Comandos reais da fatia: nenhum store/serviço/transaction manager é mockado. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class ReservationServiceJdbcTest {
 private static JdbcDisposableDatabase db;
 private static ReservationService service;
 private UUID amenityA,amenityB;
 private Instant start,end;
 private static final String POLICY="""
  {"schemaVersion":1,"weekDays":[1,2,3,4,5,6,7],"opensAt":"06:00","closesAt":"23:59",
   "minimumLeadMinutes":0,"maximumAdvanceDays":366,"maximumDurationMinutes":600,
   "maximumActivePerUnit":1,"cancellationLeadMinutes":0}
  """;

 @BeforeAll static void setup() throws Exception {
  // Os ensaios anteriores mantêm max1; somente esta classe precisa de concorrência real.
  db=new JdbcDisposableDatabase(4);
  // Deixar duas conexões prontas evita serializar a disputa durante a abertura inicial da pool.
  try(var first=db.pool.getConnection();var second=db.pool.getConnection()) {
   assertFalse(first.isClosed()); assertFalse(second.isClosed());
  }
  service=new ReservationService(new TenantTransactionExecutor(db.jdbc,db.manager),new ReservationStore(db.jdbc));
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext();
  amenityA=UUID.randomUUID(); amenityB=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,buffer_minutes,policy_version,policy)
   values(?,?,'AREA FICTICIA JDBC A',10,false,15,1,?::jsonb),
    (?,?,'AREA FICTICIA JDBC B',10,false,15,1,?::jsonb)
   """,amenityA,db.tenantA,POLICY,amenityB,db.tenantA,POLICY);
  start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).atTime(12,0)
   .atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
  end=start.plusSeconds(3600);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private String key() { return "jdbc-operation:"+UUID.randomUUID(); }
 private ReservationModels.Create request(UUID unit,UUID amenity) {
  return new ReservationModels.Create(unit,amenity,start,end,1);
 }
 private ReservationModels.Mutation create(String key) {
  return service.create(db.accountA,db.tenantA,key,request(db.unitA,amenityA));
 }
 private void assertCommittedCounts(long reservations,long audits,long events,long keys) throws Exception {
  assertEquals(reservations,db.businessCount("reservations"));
  assertEquals(audits,db.businessCount("audit_log"));
  assertEquals(events,db.businessCount("outbox"));
  assertEquals(keys,db.businessCount("idempotency_keys"));
  db.assertEmptyContext();
 }

 @Test void creationAndSameKeyReplayPersistOneReservationAndOneAtomicEvent() throws Exception {
  String key=key(); var created=create(key); var replay=create(key);
  assertEquals(201,created.responseStatus()); assertFalse(created.replayed()); assertTrue(replay.replayed());
  assertEquals(created.reservation(),replay.reservation());
  assertEquals("CONFIRMED",created.reservation().status());
  assertEquals(end.plusSeconds(15*60L),created.reservation().occupiedUntil());
  var mine=service.reservations(db.accountA,db.tenantA,db.unitA,start.minusSeconds(60),end.plusSeconds(60));
  assertEquals(1,mine.reservations().size());
  assertEquals(created.reservation().reservationId(),mine.reservations().getFirst().reservationId());
  assertCommittedCounts(1,1,1,1);
 }
 @Test void reusedKeyWithDifferentBodyReturns409AndDoesNotChangeCommittedOperation() throws Exception {
  String key=key(); var created=create(key);
  var changed=new ReservationModels.Create(db.unitA,amenityA,start,end.plusSeconds(60),1);
  var rejected=assertThrows(BusinessProblem.class,()->service.create(db.accountA,db.tenantA,key,changed));
  assertEquals(409,rejected.status()); assertEquals("IDEMPOTENCY_KEY_REUSED",rejected.code());
  assertEquals(created.reservation(),create(key).reservation()); assertCommittedCounts(1,1,1,1);
 }
 @Test void concurrentDifferentUnitsCompeteForOneSlotWithOnlyOneCommittedWinner() throws Exception {
  var results=race(
   ()->service.create(db.accountA,db.tenantA,key(),request(db.unitA,amenityA)),
   ()->service.create(db.accountC,db.tenantA,key(),request(db.otherUnitA,amenityA)));
  assertEquals(1,results.stream().filter(Outcome::succeeded).count());
  assertEquals(1,results.stream().filter(r->r.problem()!=null && "RESERVATION_CONFLICT".equals(r.problem().code())).count());
  assertCommittedCounts(1,1,1,1);
 }
 @Test void concurrentSameKeyRequestsReturnTheSameCommittedReservationWithoutDuplicateEffects() throws Exception {
  String shared=key();
  var results=race(()->create(shared),()->create(shared));
  assertTrue(results.stream().allMatch(Outcome::succeeded));
  assertEquals(results.getFirst().mutation().reservation(),results.getLast().mutation().reservation());
  assertEquals(1,results.stream().filter(r->r.mutation().replayed()).count());
  assertCommittedCounts(1,1,1,1);
 }
 @Test void concurrentBookingsInDifferentAreasStillRespectQuotaOneForTheSameUnit() throws Exception {
  var results=race(
   ()->service.create(db.accountA,db.tenantA,key(),request(db.unitA,amenityA)),
   ()->service.create(db.accountA,db.tenantA,key(),request(db.unitA,amenityB)));
  assertEquals(1,results.stream().filter(Outcome::succeeded).count());
  assertEquals(1,results.stream().filter(r->r.problem()!=null && "UNIT_RESERVATION_QUOTA_REACHED".equals(r.problem().code())).count());
  // GiST não conflita entre áreas diferentes: esta disputa precisa da trava/cota da unidade.
  assertCommittedCounts(1,1,1,1);
 }
 @Test void cancellationChecksVersionAndBothFormsOfRetryAvoidDuplicateEvents() throws Exception {
  var original=create(key()).reservation();
  var stale=assertThrows(BusinessProblem.class,()->service.cancel(db.accountA,db.tenantA,
   original.reservationId(),key(),new ReservationModels.Cancel(db.unitA,original.version()+1)));
  assertEquals(409,stale.status()); assertCommittedCounts(1,1,1,1);
  String cancelKey=key(); var body=new ReservationModels.Cancel(db.unitA,original.version());
  var cancelled=service.cancel(db.accountA,db.tenantA,original.reservationId(),cancelKey,body);
  assertEquals("CANCELLED",cancelled.reservation().status()); assertEquals(original.version()+1,cancelled.reservation().version());
  var sameKey=service.cancel(db.accountA,db.tenantA,original.reservationId(),cancelKey,body);
  assertTrue(sameKey.replayed()); assertEquals(cancelled.reservation(),sameKey.reservation());
  var newKey=service.cancel(db.accountA,db.tenantA,original.reservationId(),key(),
   new ReservationModels.Cancel(db.unitA,cancelled.reservation().version()));
  assertEquals(cancelled.reservation(),newKey.reservation());
  assertEquals(1,db.businessCount("reservations")); assertEquals(2,db.businessCount("audit_log"));
  assertEquals(2,db.businessCount("outbox")); db.assertEmptyContext();
 }
 @Test void revokedMembershipBlocksReplayBeforeReturningPreviouslyAuthorizedResult() throws Exception {
  String key=key(); create(key);
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=?",db.tenantA,db.accountA);
  var denied=assertThrows(BusinessProblem.class,()->create(key));
  assertEquals("TENANT_ACCESS_DENIED",denied.code());
  assertThrows(BusinessProblem.class,()->service.reservations(db.accountA,db.tenantA,db.unitA,start,end));
  assertCommittedCounts(1,1,1,1);
 }
 @Test void cancellationUsesAcceptedRuleSnapshotWhenTheCurrentAmenityPolicyChanges() throws Exception {
  var original=create(key()).reservation();
  db.adminUpdate("""
   update cp.amenities set policy=jsonb_set(policy,'{cancellationLeadMinutes}','525600'::jsonb),
    policy_version=policy_version+1,version=version+1 where id=? and tenant_id=?
   """,amenityA,db.tenantA);
  var cancelled=service.cancel(db.accountA,db.tenantA,original.reservationId(),key(),
   new ReservationModels.Cancel(db.unitA,original.version()));
  assertEquals("CANCELLED",cancelled.reservation().status());
  assertEquals(1,cancelled.reservation().acceptedPolicyVersion()); assertCommittedCounts(1,2,2,2);
 }
 @Test void otherUnitCannotBeBookedListedOrUsedToCancelAnotherResidentsReservation() throws Exception {
  var original=create(key()).reservation();
  assertEquals(404,assertThrows(BusinessProblem.class,()->service.create(db.accountA,db.tenantA,
   key(),request(db.otherUnitA,amenityB))).status());
  assertEquals(404,assertThrows(BusinessProblem.class,()->service.reservations(db.accountA,db.tenantA,
   db.otherUnitA,start,end)).status());
  assertEquals(404,assertThrows(BusinessProblem.class,()->service.cancel(db.accountC,db.tenantA,
   original.reservationId(),key(),new ReservationModels.Cancel(db.otherUnitA,original.version()))).status());
  assertTrue(service.reservations(db.accountC,db.tenantA,db.otherUnitA,start,end).reservations().isEmpty());
  assertCommittedCounts(1,1,1,1);
 }
 @Test void amenityUuidFromAnotherTenantDoesNotLeakOrPersistPartialIdempotency() throws Exception {
  var rejected=assertThrows(BusinessProblem.class,()->service.create(db.accountB,db.tenantB,key(),request(db.unitB,amenityA)));
  assertEquals(404,rejected.status()); assertCommittedCounts(0,0,0,0);
 }
 @Test void anotherResidentInTheSameUnitCannotCancelTheAuthorsReservation() throws Exception {
  UUID membership=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status)
   values(?,?,?,?,'OUTRO MORADOR FICTICIO DA MESMA UNIDADE','RESIDENT','ACTIVE')
   """,membership,db.tenantA,db.accountC,db.unitA);
  try {
   var original=create(key()).reservation();
   var denied=assertThrows(BusinessProblem.class,()->service.cancel(db.accountC,db.tenantA,
    original.reservationId(),key(),new ReservationModels.Cancel(db.unitA,original.version())));
   assertEquals(404,denied.status()); assertCommittedCounts(1,1,1,1);
  } finally {
   db.adminUpdate("delete from cp.memberships where id=? and tenant_id=? and account_id=?",membership,db.tenantA,db.accountC);
  }
 }

 private record Outcome(ReservationModels.Mutation mutation,BusinessProblem problem) {
  boolean succeeded() { return mutation!=null; }
 }
 private static Outcome invoke(Callable<ReservationModels.Mutation> action,CountDownLatch ready,CountDownLatch start) throws Exception {
  ready.countDown();
  if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Barreira do ensaio concorrente excedida.");
  try { return new Outcome(action.call(),null); }
  catch(BusinessProblem problem) { return new Outcome(null,problem); }
 }
 private static List<Outcome> race(Callable<ReservationModels.Mutation> first,
     Callable<ReservationModels.Mutation> second) throws Exception {
  try(var executor=Executors.newFixedThreadPool(2)) {
   var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
   var a=executor.submit(()->invoke(first,ready,start));
   var b=executor.submit(()->invoke(second,ready,start));
   if(!ready.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Workers JDBC não ficaram prontos.");
   start.countDown();
   return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
  }
 }
}
