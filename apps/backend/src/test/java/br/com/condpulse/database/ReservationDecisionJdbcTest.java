package br.com.condpulse.database;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.reservations.application.AmenityManagementService;
import br.com.condpulse.reservations.application.ReservationManagementModels;
import br.com.condpulse.reservations.application.ReservationManagementService;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.infrastructure.AmenityManagementStore;
import br.com.condpulse.reservations.infrastructure.ReservationManagementStore;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CP-023 com PostgreSQL real: pedido em área com aprovação, decisão única por AAL2, expiração tardia persistida,
 * cancelamento pela gestão e bloqueio de manutenção. Nenhum store, serviço ou transação é mockado.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class ReservationDecisionJdbcTest {
 private static JdbcDisposableDatabase db;
 private static TenantTransactionExecutor transactions;
 private static ReservationService reservations;
 private static ReservationManagementService management;
 private static AmenityManagementService amenities;
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2,AAL1=AssuranceLevel.AAL1;
 private static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(3);
  transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var recorder=new AuditRecorder(db.jdbc);
  var store=new ReservationStore(db.jdbc);
  reservations=new ReservationService(transactions,store);
  management=new ReservationManagementService(transactions,store,new ReservationManagementStore(db.jdbc,store),recorder);
  amenities=new AmenityManagementService(transactions,new AmenityManagementStore(db.jdbc),recorder);
  // Conta C: moradora da outra unidade de A e gestora de A. Gestão nunca é concedida por conveniência.
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTOR FICTICIO DECISAO','MANAGER','ACTIVE')",
   db.tenantA,db.accountC);
 }
 @BeforeEach void reset() throws Exception { db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static AmenityManagementModels.ManagedAmenity approvalArea() {
  return amenities.create(db.accountC,db.tenantA,AAL2,new AmenityManagementModels.AmenityInput(
   "Salao "+UUID.randomUUID().toString().substring(0,8),20,true,5000L,0,true,
   new AmenityManagementModels.PolicyInput(1,List.of(1,2,3,4,5,6,7),"06:00","23:59",0,366,600,5,0),null,null));
 }
 private static Instant slot(int days,int hour) { return LocalDate.now(ZONE).plusDays(days).atTime(hour,0).atZone(ZONE).toInstant(); }
 private static ReservationModels.View request(UUID account,UUID unit,UUID amenity,Instant start) {
  return reservations.create(account,db.tenantA,"jdbc-decision:"+UUID.randomUUID(),
   new ReservationModels.Create(unit,amenity,start,start.plusSeconds(3600),1)).reservation();
 }
 private static long events(UUID reservation,String type) throws Exception {
  return Long.parseLong(db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type=?",reservation,type));
 }
 private static String status(UUID reservation) throws Exception {
  return db.adminString("select status from cp.reservations where id=?",reservation);
 }
 private static ReservationManagementModels.Decision approve(long version) {
  return new ReservationManagementModels.Decision("APPROVE",version,null);
 }
 private static String outcome(Supplier<ReservationManagementModels.ManagedReservation> action) {
  try { return "OK:"+action.get().status(); } catch(BusinessProblem problem) { return problem.code(); }
 }

 @Test void pendingRequestIsDecidedOnceAndRepeatedDecisionDoesNotDuplicateEvents() throws Exception {
  var area=approvalArea();
  assertEquals("AVAILABLE_FOR_APPROVAL_REQUEST",reservations.amenities(db.accountA,db.tenantA).stream()
   .filter(a->a.amenityId().equals(area.amenityId())).findFirst().orElseThrow().bookingState());
  var requested=request(db.accountA,db.unitA,area.amenityId(),slot(3,12));
  assertEquals("PENDING_APPROVAL",requested.status(),"Pedido pendente nunca é apresentado como confirmado");
  assertEquals(1,events(requested.reservationId(),"RESERVATION_REQUESTED"));
  assertEquals("true",db.adminString("select (expires_at<=starts_at and expires_at<=created_at+interval '24 hours')::text from cp.reservations where id=?",
   requested.reservationId()),"Prazo de decisão de até 24 h, nunca após o início");
  var item=management.reservations(db.accountC,db.tenantA,AAL2,"PENDING_APPROVAL",null,null).reservations().stream()
   .filter(r->r.reservationId().equals(requested.reservationId())).findFirst().orElseThrow();
  assertEquals("PESSOA FICTICIA JDBC A",item.requesterName()); assertEquals("JDBC A",item.unitLabel());
  assertEquals("BOOKING",item.kind()); assertEquals(area.name(),item.amenityName()); assertNotNull(item.expiresAt());
  var approved=management.decide(db.accountC,db.tenantA,AAL2,requested.reservationId(),approve(item.version()));
  assertEquals("CONFIRMED",approved.status()); assertNull(approved.expiresAt()); assertEquals(item.version()+1,approved.version());
  var repeated=management.decide(db.accountC,db.tenantA,AAL2,requested.reservationId(),approve(item.version()));
  assertEquals(approved.version(),repeated.version(),"Decisão repetida devolve o estado atual");
  assertEquals(1,events(requested.reservationId(),"RESERVATION_CONFIRMED"));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='RESERVATION_APPROVED'",
   requested.reservationId()));
  assertEquals("RESERVATION_CHANGED",assertThrows(BusinessProblem.class,()->management.decide(db.accountC,db.tenantA,AAL2,
   requested.reservationId(),new ReservationManagementModels.Decision("REJECT",item.version(),"Conflito de agenda"))).code());
  var own=reservations.reservations(db.accountA,db.tenantA,db.unitA,slot(3,0),slot(4,0)).reservations();
  assertEquals("CONFIRMED",own.stream().filter(r->r.reservationId().equals(requested.reservationId())).findFirst().orElseThrow().status());
  db.assertEmptyContext();
 }

 @Test void onlyAal2ManagerDecidesAndDatabaseRefusesConfirmationWithoutCapability() throws Exception {
  var area=approvalArea();
  var requested=request(db.accountA,db.unitA,area.amenityId(),slot(4,12));
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,
   ()->management.decide(db.accountC,db.tenantA,AAL1,requested.reservationId(),approve(0))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->management.decide(db.accountA,db.tenantA,AAL2,requested.reservationId(),approve(0))).code());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->management.decide(db.accountC,db.tenantB,AAL2,requested.reservationId(),approve(0))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->management.reservations(db.accountA,db.tenantA,AAL2,null,null,null)).code());
  // Segunda barreira: sem capacidade publicada (AAL1), o banco recusa confirmação direta e reserva já confirmada.
  var store=new ReservationManagementStore(db.jdbc,new ReservationStore(db.jdbc));
  var decided=assertThrows(DataAccessException.class,()->transactions.execute(db.accountC,db.tenantA,AAL1,
   context->store.decide(db.tenantA,requested.reservationId(),0,"CONFIRMED",null)));
  assertEquals("42501",SqlStates.of(decided));
  var direct=assertThrows(DataAccessException.class,()->transactions.execute(db.accountA,db.tenantA,context->db.jdbc.update("""
   insert into cp.reservations(tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot)
   values(?,?,?,?,'BOOKING','CONFIRMED',?,?,?,?,'{}'::jsonb)
   """,db.tenantA,area.amenityId(),db.unitA,db.accountA,Timestamp.from(slot(5,12)),Timestamp.from(slot(5,13)),
   Timestamp.from(slot(5,12)),Timestamp.from(slot(5,13)))));
  assertEquals("42501",SqlStates.of(direct));
  assertEquals("PENDING_APPROVAL",status(requested.reservationId()));
  assertEquals(0,events(requested.reservationId(),"RESERVATION_CONFIRMED"));
  db.assertEmptyContext();
 }

 @Test void concurrentApproveAndRejectProduceExactlyOneDecision() throws Exception {
  var area=approvalArea();
  var requested=request(db.accountA,db.unitA,area.amenityId(),slot(5,15));
  var start=new CountDownLatch(1);
  List<String> results;
  try(var pool=Executors.newFixedThreadPool(2)) {
   var approval=pool.submit(()->{ start.await(); return outcome(()->management.decide(db.accountC,db.tenantA,AAL2,
    requested.reservationId(),approve(0))); });
   var rejection=pool.submit(()->{ start.await(); return outcome(()->management.decide(db.accountC,db.tenantA,AAL2,
    requested.reservationId(),new ReservationManagementModels.Decision("REJECT",0L,"Area reservada pela administracao"))); });
   start.countDown();
   results=List.of(approval.get(30,TimeUnit.SECONDS),rejection.get(30,TimeUnit.SECONDS));
  }
  assertEquals(1,results.stream().filter(r->r.startsWith("OK:")).count(),results.toString());
  assertEquals(1,results.stream().filter("RESERVATION_CHANGED"::equals).count(),results.toString());
  assertEquals(1,events(requested.reservationId(),"RESERVATION_CONFIRMED")+events(requested.reservationId(),"RESERVATION_REJECTED"));
  assertEquals(1L,Long.parseLong(db.adminString("select count(*) from cp.audit_log where resource_id=? and action in ('RESERVATION_APPROVED','RESERVATION_REJECTED')",
   requested.reservationId())));
  db.assertEmptyContext();
 }

 @Test void rejectionAndManagementCancellationRequireReasonAndFreeThePeriod() throws Exception {
  var area=approvalArea();
  Instant start=slot(6,10);
  var first=request(db.accountA,db.unitA,area.amenityId(),start);
  assertEquals("RESERVATION_CONFLICT",assertThrows(BusinessProblem.class,
   ()->request(db.accountC,db.otherUnitA,area.amenityId(),start)).code(),"Pedido pendente ocupa o período");
  assertEquals("RESERVATION_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->management.decide(db.accountC,db.tenantA,AAL2,
   first.reservationId(),new ReservationManagementModels.Decision("REJECT",0L,"  "))).code());
  var rejected=management.decide(db.accountC,db.tenantA,AAL2,first.reservationId(),
   new ReservationManagementModels.Decision("REJECT",0L,"Manutencao da area"));
  assertEquals("REJECTED",rejected.status()); assertEquals("Manutencao da area",rejected.decisionReason());
  var own=reservations.reservations(db.accountA,db.tenantA,db.unitA,start.minusSeconds(3600),start.plusSeconds(7200)).reservations();
  assertEquals("Manutencao da area",own.stream().filter(r->r.reservationId().equals(first.reservationId())).findFirst()
   .orElseThrow().decisionReason(),"Motivo da decisão é visível à unidade");
  var second=request(db.accountA,db.unitA,area.amenityId(),start);
  assertEquals("PENDING_APPROVAL",second.status(),"Rejeição libera o período");
  var confirmed=management.decide(db.accountC,db.tenantA,AAL2,second.reservationId(),
   new ReservationManagementModels.Decision("APPROVE",0L,"Liberado"));
  assertEquals("RESERVATION_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->management.cancel(db.accountC,db.tenantA,AAL2,
   second.reservationId(),new ReservationManagementModels.Cancellation(confirmed.version(),null))).code());
  var cancelled=management.cancel(db.accountC,db.tenantA,AAL2,second.reservationId(),
   new ReservationManagementModels.Cancellation(confirmed.version(),"Vazamento no salao"));
  assertEquals("CANCELLED",cancelled.status());
  assertEquals(cancelled.version(),management.cancel(db.accountC,db.tenantA,AAL2,second.reservationId(),
   new ReservationManagementModels.Cancellation(confirmed.version(),"Repetido")).version(),"Cancelamento repetido sem nova versão");
  assertEquals(1,events(second.reservationId(),"RESERVATION_CANCELLED"));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='RESERVATION_CANCELLED_BY_MANAGEMENT' and reason='Vazamento no salao'",
   second.reservationId()));
  assertEquals("RESERVATION_CHANGED",assertThrows(BusinessProblem.class,()->reservations.cancel(db.accountA,db.tenantA,
   second.reservationId(),"jdbc-decision:"+UUID.randomUUID(),new ReservationModels.Cancel(db.unitA,cancelled.version()))).code(),
   "Cancelamento da gestão não é devolvido ao autor como cancelamento próprio");
  assertEquals(1,events(second.reservationId(),"RESERVATION_CANCELLED"));
  var own=request(db.accountC,db.otherUnitA,area.amenityId(),start);
  assertEquals("PENDING_APPROVAL",own.status(),"Cancelamento libera o período");
  assertEquals("RESERVATION_SELF_DECISION_DENIED",assertThrows(BusinessProblem.class,()->management.decide(db.accountC,db.tenantA,AAL2,
   own.reservationId(),approve(0))).code(),"Gestor não decide o próprio pedido");
  assertEquals("RESERVATION_SELF_DECISION_DENIED",assertThrows(BusinessProblem.class,()->management.decide(db.accountC,db.tenantA,AAL2,
   own.reservationId(),new ReservationManagementModels.Decision("REJECT",0L,"Desisti"))).code());
  assertEquals("PENDING_APPROVAL",status(own.reservationId()));
  assertEquals(0,events(own.reservationId(),"RESERVATION_CONFIRMED")+events(own.reservationId(),"RESERVATION_REJECTED"));
  db.assertEmptyContext();
 }

 @Test void lateDecisionExpiresRequestAndNextRequestExpiresStaleOccupationUnderLock() throws Exception {
  var area=approvalArea();
  Instant start=slot(7,9);
  var stale=request(db.accountA,db.unitA,area.amenityId(),start);
  db.adminUpdate("update cp.reservations set expires_at=clock_timestamp()-interval '1 minute' where id=?",stale.reservationId());
  var own=reservations.reservations(db.accountA,db.tenantA,db.unitA,start.minusSeconds(60),start.plusSeconds(3600)).reservations();
  assertEquals("EXPIRED",own.getFirst().status(),"Prazo vencido não aparece como pendente");
  assertTrue(reservations.availability(db.accountA,db.tenantA,db.unitA,area.amenityId(),start.minusSeconds(3600),
   start.plusSeconds(7200)).blockedIntervals().isEmpty(),"Pedido vencido não é ocupação fantasma");
  // Versão antiga no cliente: o prazo é conferido antes da versão e a expiração é gravada mesmo assim.
  var late=assertThrows(BusinessProblem.class,()->management.decide(db.accountC,db.tenantA,AAL2,stale.reservationId(),approve(3)));
  assertEquals("RESERVATION_DECISION_EXPIRED",late.code()); assertEquals(409,late.status());
  assertEquals("EXPIRED",status(stale.reservationId()),"Expiração persiste mesmo com a decisão recusada");
  assertEquals("RESERVATION_DECISION_EXPIRED",assertThrows(BusinessProblem.class,()->management.decide(db.accountC,db.tenantA,AAL2,
   stale.reservationId(),approve(1))).code(),"Pedido já expirado continua recusando decisão");
  assertEquals(1,events(stale.reservationId(),"RESERVATION_EXPIRED"));
  assertEquals(0,events(stale.reservationId(),"RESERVATION_CONFIRMED"));
  var second=request(db.accountA,db.unitA,area.amenityId(),start);
  db.adminUpdate("update cp.reservations set expires_at=clock_timestamp()-interval '1 minute' where id=?",second.reservationId());
  var third=request(db.accountC,db.otherUnitA,area.amenityId(),start);
  assertEquals("PENDING_APPROVAL",third.status());
  assertEquals("EXPIRED",status(second.reservationId()));
  assertEquals(1,events(second.reservationId(),"RESERVATION_EXPIRED"));
  // Cancelamento pela gestão de pedido vencido também grava a expiração antes de recusar.
  var fourth=request(db.accountA,db.unitA,area.amenityId(),slot(7,15));
  db.adminUpdate("update cp.reservations set expires_at=clock_timestamp()-interval '1 minute' where id=?",fourth.reservationId());
  assertEquals("RESERVATION_DECISION_EXPIRED",assertThrows(BusinessProblem.class,()->management.cancel(db.accountC,db.tenantA,AAL2,
   fourth.reservationId(),new ReservationManagementModels.Cancellation(0L,"Agenda"))).code());
  assertEquals("EXPIRED",status(fourth.reservationId()));
  assertEquals(1,events(fourth.reservationId(),"RESERVATION_EXPIRED"));
  assertEquals("0",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='RESERVATION_CANCELLED_BY_MANAGEMENT'",
   fourth.reservationId()));
  db.assertEmptyContext();
 }

 @Test void maintenanceBlockIsIdempotentConflictsExplicitlyAndCanBeLifted() throws Exception {
  var area=approvalArea();
  Instant from=slot(8,8),until=slot(8,18);
  var booked=request(db.accountA,db.unitA,area.amenityId(),slot(8,12));
  String key="jdbc-block:"+UUID.randomUUID();
  var block=new ReservationManagementModels.CreateBlock(from,until,"Pintura");
  assertEquals("AMENITY_BLOCK_CONFLICT",assertThrows(BusinessProblem.class,
   ()->management.createBlock(db.accountC,db.tenantA,AAL2,area.amenityId(),key,block)).code());
  management.decide(db.accountC,db.tenantA,AAL2,booked.reservationId(),
   new ReservationManagementModels.Decision("REJECT",0L,"Pintura programada"));
  var created=management.createBlock(db.accountC,db.tenantA,AAL2,area.amenityId(),key,block);
  assertEquals(201,created.responseStatus()); assertFalse(created.replayed());
  assertEquals("BLOCK",created.reservation().kind()); assertNull(created.reservation().unitId());
  assertEquals("CONFIRMED",created.reservation().status()); assertEquals("Pintura",created.reservation().decisionReason());
  var replay=management.createBlock(db.accountC,db.tenantA,AAL2,area.amenityId(),key,block);
  assertTrue(replay.replayed()); assertEquals(created.reservation().reservationId(),replay.reservation().reservationId());
  assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(BusinessProblem.class,()->management.createBlock(db.accountC,db.tenantA,AAL2,
   area.amenityId(),key,new ReservationManagementModels.CreateBlock(from,until,"Outro motivo"))).code());
  assertEquals(1,events(created.reservation().reservationId(),"AMENITY_BLOCK_CREATED"));
  assertEquals(List.of(new ReservationModels.BlockedInterval(from,until)),
   reservations.availability(db.accountA,db.tenantA,db.unitA,area.amenityId(),from,until).blockedIntervals());
  assertEquals("RESERVATION_CONFLICT",assertThrows(BusinessProblem.class,
   ()->request(db.accountA,db.unitA,area.amenityId(),slot(8,14))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->management.createBlock(db.accountA,db.tenantA,AAL2,
   area.amenityId(),"jdbc-block:"+UUID.randomUUID(),new ReservationManagementModels.CreateBlock(slot(9,8),slot(9,9),"Invasao"))).code());
  assertEquals("AMENITY_BLOCK_WINDOW_INVALID",assertThrows(BusinessProblem.class,()->management.createBlock(db.accountC,db.tenantA,AAL2,
   area.amenityId(),"jdbc-block:"+UUID.randomUUID(),new ReservationManagementModels.CreateBlock(slot(-2,8),slot(-2,9),"Passado"))).code());
  var lifted=management.cancel(db.accountC,db.tenantA,AAL2,created.reservation().reservationId(),
   new ReservationManagementModels.Cancellation(created.reservation().version(),"Pintura concluida"));
  assertEquals("CANCELLED",lifted.status());
  assertEquals(1,events(created.reservation().reservationId(),"AMENITY_BLOCK_CANCELLED"));
  assertEquals("PENDING_APPROVAL",request(db.accountA,db.unitA,area.amenityId(),slot(8,14)).status());
  db.assertEmptyContext();
 }

 @Test void managementNeverReadsOrDecidesAnotherTenantReservation() throws Exception {
  UUID foreignArea=UUID.randomUUID(),foreign=UUID.randomUUID();
  db.adminUpdate("insert into cp.amenities(id,tenant_id,name,capacity,approval_required) values(?,?,'AREA B DECISAO',5,true)",
   foreignArea,db.tenantB);
  db.adminUpdate("""
   insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,expires_at,rule_snapshot)
   values(?,?,?,?,?,'BOOKING','PENDING_APPROVAL',?,?,?,?,?,'{}'::jsonb)
   """,foreign,db.tenantB,foreignArea,db.unitB,db.accountB,Timestamp.from(slot(10,12)),Timestamp.from(slot(10,13)),
   Timestamp.from(slot(10,12)),Timestamp.from(slot(10,13)),Timestamp.from(slot(9,12)));
  assertTrue(management.reservations(db.accountC,db.tenantA,AAL2,null,null,null).reservations().stream()
   .noneMatch(r->r.reservationId().equals(foreign)));
  assertEquals("RESERVATION_NOT_FOUND",assertThrows(BusinessProblem.class,
   ()->management.decide(db.accountC,db.tenantA,AAL2,foreign,approve(0))).code());
  assertEquals("RESERVATION_NOT_FOUND",assertThrows(BusinessProblem.class,()->management.cancel(db.accountC,db.tenantA,AAL2,
   foreign,new ReservationManagementModels.Cancellation(0L,"Invasao"))).code());
  assertEquals("PENDING_APPROVAL",status(foreign));
  db.assertEmptyContext();
 }
}
