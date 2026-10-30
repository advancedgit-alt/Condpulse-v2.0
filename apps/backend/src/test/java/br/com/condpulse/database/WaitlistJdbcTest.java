package br.com.condpulse.database;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.application.WaitlistModels;
import br.com.condpulse.reservations.application.WaitlistOfferRunner;
import br.com.condpulse.reservations.application.WaitlistService;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.reservations.infrastructure.WaitlistStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V023 com PostgreSQL real: fila de espera, oferta com prazo depois do commit e aceite sem dupla confirmação. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class WaitlistJdbcTest {
 private static final String POLICY="""
  {"schemaVersion":1,"weekDays":[1,2,3,4,5,6,7],"opensAt":"06:00","closesAt":"23:59",
   "minimumLeadMinutes":0,"maximumAdvanceDays":366,"maximumDurationMinutes":600,
   "maximumActivePerUnit":3,"cancellationLeadMinutes":0}
  """;
 private static JdbcDisposableDatabase db;
 private static ReservationService reservations;
 /** Sem a oferta prévia: simula a criação que já passou da oferta quando o cancelamento concorrente confirma. */
 private static ReservationService withoutOffer;
 private static WaitlistService waitlist;
 private static UUID accountD,unitD;
 private UUID amenity;
 private Instant start;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new ReservationStore(db.jdbc);
  var waitlistStore=new WaitlistStore(db.jdbc);
  var offers=new WaitlistOfferRunner(transactions,waitlistStore);
  reservations=new ReservationService(transactions,store,offers);
  withoutOffer=new ReservationService(transactions,store);
  waitlist=new WaitlistService(transactions,store,waitlistStore,new AuditRecorder(db.jdbc),offers);
  accountD=UUID.randomUUID(); unitD=UUID.randomUUID(); db.extraAccounts.add(accountD);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",accountD);
  db.adminUpdate("insert into cp.units(id,tenant_id,label) values(?,?,'FILA JDBC D')",unitD,db.tenantA);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,'MORADOR FILA D','RESIDENT','ACTIVE')",
   db.tenantA,accountD,unitD);
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext();
  amenity=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,buffer_minutes,policy_version,policy)
   values(?,?,'SALAO FILA JDBC',10,false,0,1,?::jsonb)
   """,amenity,db.tenantA,POLICY);
  start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3).atTime(15,0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }
 private static String key() { return "waitlist-jdbc-"+UUID.randomUUID(); }
 private ReservationModels.View bookAsC() {
  return reservations.create(db.accountC,db.tenantA,key(),new ReservationModels.Create(db.otherUnitA,amenity,start,start.plusSeconds(7200),1)).reservation();
 }
 private WaitlistModels.Join joinBody(UUID unit) { return new WaitlistModels.Join(unit,amenity,start,start.plusSeconds(7200),1); }
 private void cancelAsC(ReservationModels.View booked) {
  reservations.cancel(db.accountC,db.tenantA,booked.reservationId(),key(),new ReservationModels.Cancel(db.otherUnitA,booked.version()));
 }
 private WaitlistModels.Entry current(UUID account,UUID unit,UUID id) {
  return waitlist.unitEntries(account,db.tenantA,unit).entries().stream().filter(e->e.waitlistId().equals(id)).findFirst().orElseThrow();
 }

 @Test void cancellationOffersThePeriodToTheFirstInLineAndAcceptingConfirmsOnce() throws Exception {
  var booked=bookAsC();
  String joinKey=key();
  var joined=waitlist.join(db.accountA,db.tenantA,joinKey,joinBody(db.unitA));
  assertEquals(201,joined.responseStatus()); assertFalse(joined.replayed());
  assertEquals("WAITING",joined.entry().state()); assertEquals(1,joined.entry().position());
  var replay=waitlist.join(db.accountA,db.tenantA,joinKey,joinBody(db.unitA));
  assertTrue(replay.replayed()); assertEquals(joined.entry().waitlistId(),replay.entry().waitlistId());
  assertEquals("WAITLIST_ALREADY_JOINED",assertThrows(BusinessProblem.class,()->waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA))).code());
  var second=waitlist.join(accountD,db.tenantA,key(),joinBody(unitD)).entry();
  assertEquals(2,second.position());

  cancelAsC(booked);
  var offered=current(db.accountA,db.unitA,joined.entry().waitlistId());
  assertEquals("OFFERED",offered.state());
  assertNotNull(offered.offerReservationId());
  assertFalse(offered.offerExpiresAt().isAfter(Instant.now().plusSeconds(15*60+5)),"Oferta vale até 15 minutos");
  assertEquals("WAITING",current(accountD,unitD,second.waitlistId()).state());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='WAITLIST_OFFERED' and aggregate_id=?",offered.waitlistId()));
  // Outra unidade não aceita a oferta; a criação direta do período retido conflita.
  assertEquals("WAITLIST_NOT_FOUND",assertThrows(BusinessProblem.class,()->waitlist.accept(accountD,db.tenantA,offered.waitlistId(),key(),
   new WaitlistModels.Change(unitD,offered.version()))).code());
  assertEquals("RESERVATION_CONFLICT",assertThrows(BusinessProblem.class,()->reservations.create(db.accountC,db.tenantA,key(),
   new ReservationModels.Create(db.otherUnitA,amenity,start,start.plusSeconds(7200),1))).code());

  String acceptKey=key();
  var accepted=waitlist.accept(db.accountA,db.tenantA,offered.waitlistId(),acceptKey,new WaitlistModels.Change(db.unitA,offered.version()));
  assertEquals("ACCEPTED",accepted.entry().state());
  assertEquals("CONFIRMED",accepted.reservation().status());
  assertEquals(offered.offerReservationId(),accepted.reservation().reservationId());
  var again=waitlist.accept(db.accountA,db.tenantA,offered.waitlistId(),acceptKey,new WaitlistModels.Change(db.unitA,offered.version()));
  assertEquals(accepted.reservation().reservationId(),again.reservation().reservationId(),"Repetição devolve a mesma reserva");
  assertEquals("WAITLIST_CLOSED",assertThrows(BusinessProblem.class,()->waitlist.accept(db.accountA,db.tenantA,offered.waitlistId(),key(),
   new WaitlistModels.Change(db.unitA,accepted.entry().version()))).code());
  assertEquals("1",db.adminString("select count(*) from cp.reservations where amenity_id=? and status in ('HELD','PENDING_APPROVAL','CONFIRMED')",amenity));
  assertEquals(db.accountA.toString(),db.adminString("select created_by from cp.reservations where id=?",accepted.reservation().reservationId()));
  db.assertEmptyContext();
 }

 @Test void freePeriodOwnReservationAndInvalidWindowAreRefused() throws Exception {
  assertEquals("WAITLIST_SLOT_AVAILABLE",assertThrows(BusinessProblem.class,()->waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA))).code());
  bookAsC();
  assertEquals("WAITLIST_OWN_RESERVATION",assertThrows(BusinessProblem.class,()->waitlist.join(db.accountC,db.tenantA,key(),joinBody(db.otherUnitA))).code());
  assertEquals("RESERVATION_WINDOW_INVALID",assertThrows(BusinessProblem.class,()->waitlist.join(db.accountA,db.tenantA,key(),
   new WaitlistModels.Join(db.unitA,amenity,start.plusSeconds(30),start.plusSeconds(7200),1))).code());
  assertEquals("AMENITY_POLICY_CHANGED",assertThrows(BusinessProblem.class,()->waitlist.join(db.accountA,db.tenantA,key(),
   new WaitlistModels.Join(db.unitA,amenity,start,start.plusSeconds(7200),2))).code());
  assertEquals("UNIT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->waitlist.join(db.accountA,db.tenantA,key(),joinBody(unitD))).code());
  assertEquals("0",db.adminString("select count(*) from cp.reservation_waitlist where tenant_id=?",db.tenantA));
  db.assertEmptyContext();
 }

 @Test void decliningPassesTheOfferToTheNextAndLeavingAWaitingEntryIsFinal() throws Exception {
  var booked=bookAsC();
  var first=waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA)).entry();
  var second=waitlist.join(accountD,db.tenantA,key(),joinBody(unitD)).entry();
  cancelAsC(booked);
  var offered=current(db.accountA,db.unitA,first.waitlistId());
  assertEquals("WAITLIST_CHANGED",assertThrows(BusinessProblem.class,()->waitlist.leave(db.accountA,db.tenantA,offered.waitlistId(),
   new WaitlistModels.Change(db.unitA,offered.version()-1))).code());
  var declined=waitlist.leave(db.accountA,db.tenantA,offered.waitlistId(),new WaitlistModels.Change(db.unitA,offered.version()));
  assertEquals("LEFT",declined.state());
  assertEquals("CANCELLED",db.adminString("select status from cp.reservations where id=?",offered.offerReservationId()));
  var next=current(accountD,unitD,second.waitlistId());
  assertEquals("OFFERED",next.state(),"Recusa passa a oferta ao próximo da fila");
  var left=waitlist.leave(db.accountA,db.tenantA,offered.waitlistId(),new WaitlistModels.Change(db.unitA,declined.version()));
  assertEquals("LEFT",left.state(),"Repetir a saída devolve o estado");
  db.assertEmptyContext();
 }

 @Test void staleHoldsOfOtherAreasDoNotCountAgainstTheAcceptedQuota() throws Exception {
  // Cota 3: duas reservas ativas da unidade em outros dias, uma retenção vencida em outra área e a oferta em vigor.
  reservations.create(db.accountA,db.tenantA,key(),new ReservationModels.Create(db.unitA,amenity,start.plusSeconds(86400),start.plusSeconds(86400+3600),1));
  reservations.create(db.accountA,db.tenantA,key(),new ReservationModels.Create(db.unitA,amenity,start.plusSeconds(2*86400),start.plusSeconds(2*86400+3600),1));
  UUID other=UUID.randomUUID();
  db.adminUpdate("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,buffer_minutes,policy_version,policy)
   values(?,?,'OUTRA AREA FILA JDBC',10,false,0,1,?::jsonb)
   """,other,db.tenantA,POLICY);
  db.adminUpdate("""
   insert into cp.reservations(tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,expires_at,rule_snapshot)
   values(?,?,?,?,'BOOKING','HELD',?,?,?,?,clock_timestamp()-interval '1 minute','{}'::jsonb)
   """,db.tenantA,other,db.unitA,db.accountA,java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)),
   java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)));
  var booked=bookAsC();
  var joined=waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA)).entry();
  cancelAsC(booked);
  var offered=current(db.accountA,db.unitA,joined.waitlistId());
  assertEquals("OFFERED",offered.state());
  var accepted=waitlist.accept(db.accountA,db.tenantA,offered.waitlistId(),key(),new WaitlistModels.Change(db.unitA,offered.version()));
  assertEquals("CONFIRMED",accepted.reservation().status(),"Retenção vencida de outra área não ocupa a cota");
  db.assertEmptyContext();
 }

 @Test void directBookingOfAFreedPeriodWaitsForTheQueue() throws Exception {
  var booked=bookAsC();
  var joined=waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA)).entry();
  // Cancelamento concorrente confirmado antes da oferta (aqui, direto no banco, sem a oferta pós-commit).
  db.adminUpdate("update cp.reservations set status='CANCELLED',version=version+1 where id=?",booked.reservationId());
  assertEquals("WAITLIST_PRIORITY",assertThrows(BusinessProblem.class,()->withoutOffer.create(accountD,db.tenantA,key(),
   new ReservationModels.Create(unitD,amenity,start,start.plusSeconds(7200),1))).code(),"Período livre com fila: a criação direta espera a fila");
  // Com a oferta prévia (caminho normal), o período já está retido para quem aguardava: conflito comum.
  assertEquals("RESERVATION_CONFLICT",assertThrows(BusinessProblem.class,()->reservations.create(accountD,db.tenantA,key(),
   new ReservationModels.Create(unitD,amenity,start,start.plusSeconds(7200),1))).code());
  assertEquals("OFFERED",current(db.accountA,db.unitA,joined.waitlistId()).state(),"A vaga foi oferecida a quem aguardava");
  assertEquals("0",db.adminString("select count(*) from cp.reservations where amenity_id=? and unit_id=?",amenity,unitD));
  db.assertEmptyContext();
 }
 @Test void expiredOffersOfOtherAreasDoNotFillTheOpenEntriesLimit() throws Exception {
  for(int i=0;i<5;i++) {
   UUID area=UUID.randomUUID();
   db.adminUpdate("""
    insert into cp.amenities(id,tenant_id,name,capacity,approval_required,buffer_minutes,policy_version,policy)
    values(?,?,?,10,false,0,1,?::jsonb)
    """,area,db.tenantA,"AREA LIMITE "+i,POLICY);
   UUID held=UUID.randomUUID();
   db.adminUpdate("""
    insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,expires_at,rule_snapshot)
    values(?,?,?,?,?,'BOOKING','HELD',?,?,?,?,clock_timestamp()-interval '1 minute','{}'::jsonb)
    """,held,db.tenantA,area,db.unitA,db.accountA,java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)),
    java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)));
   db.adminUpdate("""
    insert into cp.reservation_waitlist(tenant_id,amenity_id,unit_id,created_by,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot,
     state,offer_reservation_id,offered_at,offer_expires_at)
    values(?,?,?,?,?,?,?,?,'{}'::jsonb,'OFFERED',?,clock_timestamp()-interval '16 minutes',clock_timestamp()-interval '1 minute')
    """,db.tenantA,area,db.unitA,db.accountA,java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)),
    java.sql.Timestamp.from(start),java.sql.Timestamp.from(start.plusSeconds(3600)),held);
  }
  bookAsC();
  assertEquals("WAITING",waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA)).entry().state(),"Ofertas vencidas não contam no limite");
  db.assertEmptyContext();
 }

 @Test void offerOfADeactivatedAreaCannotBeAccepted() throws Exception {
  var booked=bookAsC();
  var joined=waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA)).entry();
  cancelAsC(booked);
  var offered=current(db.accountA,db.unitA,joined.waitlistId());
  assertEquals("OFFERED",offered.state());
  db.adminUpdate("update cp.amenities set active=false where id=?",amenity);
  assertEquals("AMENITY_INACTIVE",assertThrows(BusinessProblem.class,()->waitlist.accept(db.accountA,db.tenantA,offered.waitlistId(),key(),
   new WaitlistModels.Change(db.unitA,offered.version()))).code());
  assertEquals("HELD",db.adminString("select status from cp.reservations where id=?",offered.offerReservationId()));
  db.assertEmptyContext();
 }
 @Test void expiredOfferAdvancesTheLineAndCannotBeAccepted() throws Exception {
  var booked=bookAsC();
  var first=waitlist.join(db.accountA,db.tenantA,key(),joinBody(db.unitA)).entry();
  var second=waitlist.join(accountD,db.tenantA,key(),joinBody(unitD)).entry();
  cancelAsC(booked);
  var offered=current(db.accountA,db.unitA,first.waitlistId());
  db.adminUpdate("update cp.reservations set expires_at=clock_timestamp()-interval '1 second' where id=?",offered.offerReservationId());
  assertEquals("WAITLIST_OFFER_EXPIRED",assertThrows(BusinessProblem.class,()->waitlist.accept(db.accountA,db.tenantA,offered.waitlistId(),key(),
   new WaitlistModels.Change(db.unitA,offered.version()))).code());
  assertEquals("OFFERED",current(accountD,unitD,second.waitlistId()).state(),"A consulta avança a fila depois do prazo");
  assertEquals("EXPIRED",current(db.accountA,db.unitA,first.waitlistId()).state());
  assertEquals("1",db.adminString("select count(*) from cp.reservations where amenity_id=? and status in ('HELD','PENDING_APPROVAL','CONFIRMED')",amenity));
  db.assertEmptyContext();
 }
}
