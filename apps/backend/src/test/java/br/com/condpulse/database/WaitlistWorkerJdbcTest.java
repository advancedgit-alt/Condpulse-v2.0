package br.com.condpulse.database;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.application.WaitlistModels;
import br.com.condpulse.reservations.application.WaitlistService;
import br.com.condpulse.reservations.application.WaitlistSweep;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.reservations.infrastructure.WaitlistStore;
import br.com.condpulse.reservations.infrastructure.WaitlistSweepStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V023 com o login do worker (cp_ci_worker, só cp_worker): a passada periódica oferece período liberado e vence oferta
 * não aceita sem nenhuma requisição da API. A API aqui não oferece (sem WaitlistOffers), só o worker.
 */
@EnabledIfEnvironmentVariable(named="CP_TEST_WORKER_DB_USER",matches="cp_ci_worker")
class WaitlistWorkerJdbcTest {
 private static final String POLICY="""
  {"schemaVersion":1,"weekDays":[1,2,3,4,5,6,7],"opensAt":"06:00","closesAt":"23:59",
   "minimumLeadMinutes":0,"maximumAdvanceDays":366,"maximumDurationMinutes":600,
   "maximumActivePerUnit":3,"cancellationLeadMinutes":0}
  """;
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;
 private static ReservationService reservations;
 private static WaitlistService waitlist;
 private static WaitlistSweep sweep;
 private static UUID accountD,unitD;
 private UUID amenity;
 private Instant start;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  workerDb=new JdbcWorkerPool();
  var api=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new ReservationStore(db.jdbc);
  reservations=new ReservationService(api,store);
  waitlist=new WaitlistService(api,store,new WaitlistStore(db.jdbc),new AuditRecorder(db.jdbc),
   br.com.condpulse.reservations.application.WaitlistOffers.NONE);
  sweep=new WaitlistSweep(new WorkerTransactions(workerDb.jdbc,workerDb.manager),new WaitlistSweepStore(workerDb.jdbc));
  accountD=UUID.randomUUID(); unitD=UUID.randomUUID(); db.extraAccounts.add(accountD);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",accountD);
  db.adminUpdate("insert into cp.units(id,tenant_id,label) values(?,?,'FILA WORKER D')",unitD,db.tenantA);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,'MORADOR FILA WORKER','RESIDENT','ACTIVE')",
   db.tenantA,accountD,unitD);
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext(); workerDb.assertEmptyContext();
  amenity=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,buffer_minutes,policy_version,policy)
   values(?,?,'SALAO FILA WORKER',10,false,0,1,?::jsonb)
   """,amenity,db.tenantA,POLICY);
  start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(4).atTime(15,0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
 }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }
 private static String key() { return "waitlist-worker-"+UUID.randomUUID(); }
 private String state(UUID entry) { return db.adminString("select state from cp.reservation_waitlist where id=?",entry); }
 private String offerOf(UUID entry) { return db.adminString("select offer_reservation_id from cp.reservation_waitlist where id=?",entry); }

 @Test void workerOffersFreedPeriodsAndExpiresUnansweredOffersWithoutRequests() throws Exception {
  var booked=reservations.create(db.accountC,db.tenantA,key(),new ReservationModels.Create(db.otherUnitA,amenity,start,start.plusSeconds(7200),1)).reservation();
  var first=waitlist.join(db.accountA,db.tenantA,key(),new WaitlistModels.Join(db.unitA,amenity,start,start.plusSeconds(7200),1)).entry();
  var second=waitlist.join(accountD,db.tenantA,key(),new WaitlistModels.Join(unitD,amenity,start,start.plusSeconds(7200),1)).entry();
  reservations.cancel(db.accountC,db.tenantA,booked.reservationId(),key(),new ReservationModels.Cancel(db.otherUnitA,booked.version()));
  assertEquals("WAITING",state(first.waitlistId()),"Sem oferta pela API");

  assertTrue(sweep.runOnce()>=1);
  assertEquals("OFFERED",state(first.waitlistId()));
  String held=offerOf(first.waitlistId());
  assertEquals("HELD:"+db.accountA,db.adminString("select status||':'||created_by from cp.reservations where id=?::uuid",held));
  assertEquals("WAITING",state(second.waitlistId()));
  sweep.runOnce();
  assertEquals("WAITING",state(second.waitlistId()),"Período retido não gera segunda oferta");
  assertEquals("1",db.adminString("select count(*) from cp.reservations where amenity_id=? and status='HELD'",amenity));

  db.adminUpdate("update cp.reservations set expires_at=clock_timestamp()-interval '1 second' where id=?::uuid",held);
  sweep.runOnce();
  assertEquals("EXPIRED",state(first.waitlistId()));
  assertEquals("EXPIRED",db.adminString("select status from cp.reservations where id=?::uuid",held));
  assertEquals("OFFERED",state(second.waitlistId()),"Oferta vencida avança a fila sem requisição");
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='RESERVATION_EXPIRED' and aggregate_id=?::uuid",held));
  assertEquals("2",db.adminString("select count(*) from cp.outbox where event_type='WAITLIST_OFFERED' and aggregate_id in (?,?)",
   first.waitlistId(),second.waitlistId()));
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }
}
