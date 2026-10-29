package br.com.condpulse.database;

import java.sql.Timestamp;
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
   assertEquals(403,denied.status()); assertEquals("RESERVATION_AUTHOR_REQUIRED",denied.code());
   assertCommittedCounts(1,1,1,1);
   assertEquals("CONFIRMED",db.adminString("select status from cp.reservations where id=?",original.reservationId()));
  } finally {
   db.adminUpdate("delete from cp.memberships where id=? and tenant_id=? and account_id=?",membership,db.tenantA,db.accountC);
  }
 }

 @Test void availabilityIncludesAnotherUnitsOccupancyAndBufferWithoutExposingTheirReservation() throws Exception {
  var theirs=service.create(db.accountC,db.tenantA,key(),request(db.otherUnitA,amenityA));
  assertTrue(service.reservations(db.accountA,db.tenantA,db.unitA,start,end).reservations().isEmpty());
  var calendar=service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start.minusSeconds(60),end.plusSeconds(3600));
  assertEquals(db.tenantA,calendar.tenantId()); assertEquals(db.unitA,calendar.unitId());
  assertEquals(amenityA,calendar.amenityId()); assertEquals("America/Sao_Paulo",calendar.timeZone());
  assertNotNull(calendar.observedAt());
  assertEquals(List.of(new ReservationModels.BlockedInterval(start,end.plusSeconds(900))),calendar.blockedIntervals());
  assertEquals(end.plusSeconds(900),theirs.reservation().occupiedUntil());
  assertCommittedCounts(1,1,1,1);
 }
 @Test void availabilityClipsAndMergesAdjacentBookingsAndMaintenanceBlocks() throws Exception {
  occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING","CONFIRMED",start.minusSeconds(3600),start,
   start.minusSeconds(3600),start,null);
  occupied(db.tenantA,amenityA,null,db.accountA,"BLOCK","CONFIRMED",start,start.plusSeconds(3600),
   start,start.plusSeconds(4500),null);
  occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING","CONFIRMED",start.plusSeconds(4500),start.plusSeconds(7200),
   start.plusSeconds(4500),start.plusSeconds(8100),null);
  var from=start.minusSeconds(1800); var until=start.plusSeconds(7500);
  var result=service.availability(db.accountA,db.tenantA,db.unitA,amenityA,from,until);
  assertEquals(List.of(new ReservationModels.BlockedInterval(from,until)),result.blockedIntervals());
  assertCommittedCounts(3,0,0,0);
 }
 @Test void expiredHoldStaysBlockedUntilTransitionButExpiredApprovalRequestDoesNot() throws Exception {
  Instant expired=Instant.now().minusSeconds(86400);
  UUID held=occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING","HELD",start,end,start,end,expired);
  UUID pending=occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING","PENDING_APPROVAL",end,end.plusSeconds(3600),
   end,end.plusSeconds(3600),expired);
  for(String status:List.of("CANCELLED","EXPIRED","REJECTED"))
   occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING",status,start.minusSeconds(3600),end.plusSeconds(7200),
    start.minusSeconds(3600),end.plusSeconds(7200),null);
  // Retenção HELD de fila só sai com a transição real (fila ainda não implementada). Pedido de aprovação vencido
  // não pode mais ser confirmado e a próxima criação o expira sob trava: não aparece como ocupação.
  assertEquals(List.of(new ReservationModels.BlockedInterval(start,end)),
   service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start.minusSeconds(3600),end.plusSeconds(7200)).blockedIntervals());
  db.adminUpdate("update cp.reservations set status='EXPIRED' where tenant_id=? and id in (?,?)",db.tenantA,held,pending);
  assertTrue(service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start.minusSeconds(3600),end.plusSeconds(7200))
   .blockedIntervals().isEmpty());
  assertCommittedCounts(5,0,0,0);
 }
 @Test void availabilityDoesNotLeakAcrossTenantForeignUnitOrInactiveArea() throws Exception {
  create(key());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantB,db.unitB,amenityA,start,end)).code());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantA,db.otherUnitA,amenityA,start,end)).code());
  assertEquals("RESERVATION_RESOURCE_UNAVAILABLE",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountB,db.tenantB,db.unitB,amenityA,start,end)).code());
  db.adminUpdate("update cp.amenities set active=false where tenant_id=? and id=?",db.tenantA,amenityA);
  assertEquals("RESERVATION_RESOURCE_UNAVAILABLE",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end)).code());
  assertCommittedCounts(1,1,1,1);
 }
 @Test void availabilityRevalidatesAccountMembershipAndUnitOnEveryRequest() throws Exception {
  assertTrue(service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end).blockedIntervals().isEmpty());
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=?",db.tenantA,db.accountA);
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end)).code());
  db.restoreActiveFixtures();
  db.adminUpdate("update cp.units set status='INACTIVE' where tenant_id=? and id=?",db.tenantA,db.unitA);
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end)).code());
  db.restoreActiveFixtures();
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountA);
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end)).code());
  assertCommittedCounts(0,0,0,0);
 }
 @Test void apparentlyFreeReadDoesNotReserveAndGistRemainsTheFinalAuthority() throws Exception {
  assertTrue(service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end).blockedIntervals().isEmpty());
  var winner=service.create(db.accountC,db.tenantA,key(),request(db.otherUnitA,amenityA)).reservation();
  var conflict=assertThrows(BusinessProblem.class,()->create(key()));
  assertEquals("RESERVATION_CONFLICT",conflict.code());
  assertEquals(List.of(new ReservationModels.BlockedInterval(start,end)),
   service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end).blockedIntervals());
  service.cancel(db.accountC,db.tenantA,winner.reservationId(),key(),new ReservationModels.Cancel(db.otherUnitA,winner.version()));
  assertTrue(service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end).blockedIntervals().isEmpty());
  assertCommittedCounts(1,2,2,2);
 }
 @Test void intervalsTouchingTheQueriesEndpointsDoNotBlockTheOpenGap() throws Exception {
  occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING","CONFIRMED",start.minusSeconds(3600),start,
   start.minusSeconds(3600),start,null);
  occupied(db.tenantA,amenityA,null,db.accountA,"BLOCK","CONFIRMED",end,end.plusSeconds(3600),end,end.plusSeconds(3600),null);
  assertTrue(service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,end).blockedIntervals().isEmpty());
  assertCommittedCounts(2,0,0,0);
 }
 @Test void availabilityReturnsAtMostOneThousandIntervalsAndFailsClosedInsteadOfTruncating() throws Exception {
  db.adminUpdate("""
   insert into cp.reservations(tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot)
   select ?,?,?,?,'BOOKING','CONFIRMED',base+g*interval '2 seconds',base+g*interval '2 seconds'+interval '1 second',
    base+g*interval '2 seconds',base+g*interval '2 seconds'+interval '1 second','{}'::jsonb
   from (select ?::timestamptz as base) b cross join generate_series(0,999) g
   """,db.tenantA,amenityA,db.otherUnitA,db.accountC,Timestamp.from(start));
  Instant until=start.plusSeconds(2002);
  assertEquals(1000,service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,until).blockedIntervals().size());
  occupied(db.tenantA,amenityA,db.otherUnitA,db.accountC,"BOOKING","CONFIRMED",start.plusSeconds(2000),start.plusSeconds(2001),
   start.plusSeconds(2000),start.plusSeconds(2001),null);
  var unavailable=assertThrows(BusinessProblem.class,
   ()->service.availability(db.accountA,db.tenantA,db.unitA,amenityA,start,until));
  assertEquals(503,unavailable.status()); assertEquals("AMENITY_AVAILABILITY_UNAVAILABLE",unavailable.code());
  assertCommittedCounts(1001,0,0,0);
 }
 private UUID occupied(UUID tenant,UUID area,UUID unit,UUID actor,String kind,String status,Instant starts,Instant ends,
     Instant occupiedFrom,Instant occupiedUntil,Instant expiresAt) throws Exception {
  UUID id=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,expires_at,rule_snapshot)
   values(?,?,?,?,?,?,?,?,?,?,?,?,'{}'::jsonb)
   """,id,tenant,area,unit,actor,kind,status,Timestamp.from(starts),Timestamp.from(ends),Timestamp.from(occupiedFrom),
   Timestamp.from(occupiedUntil),expiresAt==null?null:Timestamp.from(expiresAt));
  return id;
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
