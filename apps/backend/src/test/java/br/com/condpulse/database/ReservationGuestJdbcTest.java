package br.com.condpulse.database;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V021 com PostgreSQL real: convidados com a janela do evento e revogação junto com o cancelamento da reserva. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class ReservationGuestJdbcTest {
 private static final String POLICY="""
  {"schemaVersion":1,"weekDays":[1,2,3,4,5,6,7],"opensAt":"06:00","closesAt":"23:59",
   "minimumLeadMinutes":0,"maximumAdvanceDays":366,"maximumDurationMinutes":600,
   "maximumActivePerUnit":3,"cancellationLeadMinutes":0}
  """;
 private static JdbcDisposableDatabase db;
 private static ReservationService reservations;
 private static VisitService visits;
 private UUID amenity;
 private Instant start;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  reservations=new ReservationService(transactions,new ReservationStore(db.jdbc));
  visits=new VisitService(transactions,new VisitStore(db.jdbc),new AuditRecorder(db.jdbc));
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext();
  amenity=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,buffer_minutes,policy_version,policy)
   values(?,?,'SALAO CONVIDADOS JDBC',2,false,0,1,?::jsonb)
   """,amenity,db.tenantA,POLICY);
  start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).atTime(15,0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String key() { return "guest-jdbc-"+UUID.randomUUID(); }
 private ReservationModels.View book() {
  return reservations.create(db.accountA,db.tenantA,key(),new ReservationModels.Create(db.unitA,amenity,start,start.plusSeconds(7200),1)).reservation();
 }

 @Test void guestsGetTheEventWindowUpToTheAreaCapacityAndRepeatingTheKeyDoesNotDuplicate() throws Exception {
  var reservation=book();
  String key=key();
  var first=visits.addGuest(db.accountA,db.tenantA,reservation.reservationId(),key,new VisitModels.AddGuest("Convidada Fictícia","12ab"));
  var replay=visits.addGuest(db.accountA,db.tenantA,reservation.reservationId(),key,new VisitModels.AddGuest("Convidada Fictícia","12ab"));
  assertFalse(first.replayed()); assertTrue(replay.replayed()); assertEquals(first.permit(),replay.permit());
  var guest=first.permit();
  assertEquals(reservation.reservationId(),guest.reservationId());
  assertEquals(start.minusSeconds(3600),guest.validFrom(),"Até 1 hora antes do início");
  assertEquals(start.plusSeconds(7200),guest.validUntil(),"Até o fim do uso");
  assertEquals("Convidado: SALAO CONVIDADOS JDBC",guest.purpose());
  assertEquals("SCHEDULED",guest.state());
  visits.addGuest(db.accountA,db.tenantA,reservation.reservationId(),key(),new VisitModels.AddGuest("Outra Pessoa",null));
  assertEquals("RESERVATION_GUEST_LIMIT",assertThrows(BusinessProblem.class,()->visits.addGuest(db.accountA,db.tenantA,
   reservation.reservationId(),key(),new VisitModels.AddGuest("Terceira Pessoa",null))).code());
  assertEquals(2,visits.reservationGuests(db.accountA,db.tenantA,reservation.reservationId()).permits().size());
  assertEquals(2,visits.unitPermits(db.accountA,db.tenantA,db.unitA).permits().size(),"Convidados aparecem nos convites da unidade");
  db.assertEmptyContext();
 }

 @Test void otherUnitAndOtherTenantCannotSeeOrAddGuests() throws Exception {
  var reservation=book();
  assertEquals("RESERVATION_GUEST_UNAVAILABLE",assertThrows(BusinessProblem.class,()->visits.addGuest(db.accountC,db.tenantA,
   reservation.reservationId(),key(),new VisitModels.AddGuest("Pessoa Fictícia",null))).code());
  assertEquals("RESERVATION_GUEST_UNAVAILABLE",assertThrows(BusinessProblem.class,()->visits.reservationGuests(db.accountC,db.tenantA,
   reservation.reservationId())).code());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.addGuest(db.accountB,db.tenantA,
   reservation.reservationId(),key(),new VisitModels.AddGuest("Pessoa Fictícia",null))).code());
  assertEquals("0",db.adminString("select count(*) from cp.visit_permits where reservation_id=?",reservation.reservationId()));
  db.assertEmptyContext();
 }

 @Test void cancellingTheReservationRevokesEveryGuestInTheSameTransactionAndRetryKeepsThemRevoked() throws Exception {
  var reservation=book();
  var first=visits.addGuest(db.accountA,db.tenantA,reservation.reservationId(),key(),new VisitModels.AddGuest("Convidada Fictícia",null)).permit();
  var second=visits.addGuest(db.accountA,db.tenantA,reservation.reservationId(),key(),new VisitModels.AddGuest("Outra Pessoa",null)).permit();
  String cancelKey=key();
  var cancelled=reservations.cancel(db.accountA,db.tenantA,reservation.reservationId(),cancelKey,
   new ReservationModels.Cancel(db.unitA,reservation.version())).reservation();
  assertEquals("CANCELLED",cancelled.status());
  for(var guest:List.of(first,second)) {
   var after=visits.unitPermits(db.accountA,db.tenantA,db.unitA).permits().stream().filter(p->p.permitId().equals(guest.permitId())).findFirst().orElseThrow();
   assertEquals("REVOKED",after.state()); assertEquals("Reserva cancelada",after.revokeReason());
  }
  assertEquals("2",db.adminString("select count(*) from cp.outbox where event_type='VISIT_PERMIT_REVOKED' and payload->>'reservationId'=?",
   reservation.reservationId().toString()));
  // Repetição do cancelamento (mesma chave e chave nova) não reativa nem duplica nada.
  reservations.cancel(db.accountA,db.tenantA,reservation.reservationId(),cancelKey,new ReservationModels.Cancel(db.unitA,reservation.version()));
  reservations.cancel(db.accountA,db.tenantA,reservation.reservationId(),key(),new ReservationModels.Cancel(db.unitA,cancelled.version()));
  assertEquals("0",db.adminString("select count(*) from cp.visit_permits where reservation_id=? and state='ACTIVE'",reservation.reservationId()));
  assertEquals("2",db.adminString("select count(*) from cp.outbox where event_type='VISIT_PERMIT_REVOKED' and payload->>'reservationId'=?",
   reservation.reservationId().toString()));
  assertEquals("RESERVATION_NOT_CONFIRMED",assertThrows(BusinessProblem.class,()->visits.addGuest(db.accountA,db.tenantA,
   reservation.reservationId(),key(),new VisitModels.AddGuest("Atrasada",null))).code());
  db.assertEmptyContext();
 }
}
