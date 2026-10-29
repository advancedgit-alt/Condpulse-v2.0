package br.com.condpulse.reservations.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.infrastructure.ReservationManagementStore;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationManagementServiceTest {
 private final UUID actor=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private final UUID tenant=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private final UUID unit=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private final UUID area=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private final UUID reservation=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private final UUID requester=UUID.fromString("66666666-6666-4666-8666-666666666666");
 private final Instant now=Instant.parse("2030-01-01T12:00:00Z");
 private final Instant start=Instant.parse("2030-01-03T14:00:00Z");
 private final TenantTransactionExecutor transactions=mock(TenantTransactionExecutor.class);
 private final ReservationStore reservations=mock(ReservationStore.class);
 private final ReservationManagementStore store=mock(ReservationManagementStore.class);
 private final AuditRecorder audit=mock(AuditRecorder.class);
 private final ReservationManagementService service=new ReservationManagementService(transactions,reservations,store,audit);
 private TenantContext context;
 private boolean committed;

 @SuppressWarnings("unchecked")
 @BeforeEach void setup() {
  context=manager(AssuranceLevel.AAL2);
  doAnswer(invocation->{
   Function<TenantContext,Object> action=invocation.getArgument(3);
   Object result=action.apply(context);
   committed=true;
   return result;
  }).when(transactions).execute(eq(actor),eq(tenant),any(AssuranceLevel.class),any());
  when(reservations.currentTime()).thenReturn(now);
 }
 private TenantContext manager(AssuranceLevel assurance) {
  return new TenantContext(actor,tenant,ZoneId.of("America/Sao_Paulo"),
   List.of(new TenantContext.Membership(UUID.randomUUID(),null,"MANAGER",false,false)),assurance);
 }
 private ReservationManagementStore.Target target(String kind,String status,long version,boolean deadlinePassed) {
  return new ReservationManagementStore.Target(reservation,kind,"BLOCK".equals(kind)?null:unit,requester,status,version,
   start.plusSeconds(3600),deadlinePassed,true,"ACTIVE");
 }
 private ReservationManagementModels.ManagedReservation managed(String status,long version) {
  return new ReservationManagementModels.ManagedReservation(reservation,"BOOKING",unit,"101",area,"Salao",status,start,
   start.plusSeconds(3600),start,start.plusSeconds(3600),null,1,0,version,null,"Morador",now);
 }
 private ReservationModels.View view(String status,long version) {
  return new ReservationModels.View(reservation,unit,area,status,start,start.plusSeconds(3600),start,start.plusSeconds(3600),
   1,0,version,null);
 }

 @Test void approvalWritesDecisionAuditAndEventOnce() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","PENDING_APPROVAL",0,false));
  when(store.requesterEligible(tenant,requester,unit)).thenReturn(true);
  when(store.decide(tenant,reservation,0,"CONFIRMED",null)).thenReturn(view("CONFIRMED",1));
  when(store.reservation(tenant,reservation)).thenReturn(managed("CONFIRMED",1));
  var result=service.decide(actor,tenant,AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Decision("APPROVE",0L,null));
  assertEquals("CONFIRMED",result.status());
  verify(audit).record(tenant,actor,"RESERVATION_APPROVED","RESERVATION",reservation,null);
  verify(reservations).event(tenant,"RESERVATION_CONFIRMED",view("CONFIRMED",1));
 }
 @Test void repeatedDecisionReturnsCurrentStateWithoutSideEffects() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","CONFIRMED",1,false));
  when(store.reservation(tenant,reservation)).thenReturn(managed("CONFIRMED",1));
  assertEquals(1,service.decide(actor,tenant,AssuranceLevel.AAL2,reservation,
   new ReservationManagementModels.Decision("APPROVE",0L,null)).version());
  verify(store,never()).decide(any(),any(),anyLong(),any(),any());
  verifyNoInteractions(audit); verify(reservations,never()).event(any(),any(),any());
 }
 @Test void oppositeDecisionAfterAnotherManagerDecidedConflicts() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","CONFIRMED",1,false));
  assertEquals("RESERVATION_CHANGED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,AssuranceLevel.AAL2,
   reservation,new ReservationManagementModels.Decision("REJECT",0L,"Agenda"))).code());
  verifyNoInteractions(audit);
 }
 @Test void lateDecisionCommitsExpiryBeforeRefusing() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","PENDING_APPROVAL",0,true));
  when(store.expire(tenant,reservation,0)).thenReturn(view("EXPIRED",1));
  var error=assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,AssuranceLevel.AAL2,reservation,
   new ReservationManagementModels.Decision("APPROVE",0L,null)));
  assertEquals("RESERVATION_DECISION_EXPIRED",error.code()); assertEquals(409,error.status());
  assertTrue(committed,"A expiração precisa ser confirmada antes da recusa");
  verify(reservations).event(tenant,"RESERVATION_EXPIRED",view("EXPIRED",1));
  verify(store,never()).decide(any(),any(),anyLong(),any(),any());
 }
 @Test void deadlineIsCheckedBeforeVersionSoStaleClientsStillPersistExpiry() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","PENDING_APPROVAL",2,true));
  when(store.expire(tenant,reservation,2)).thenReturn(view("EXPIRED",3));
  assertEquals("RESERVATION_DECISION_EXPIRED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,AssuranceLevel.AAL2,
   reservation,new ReservationManagementModels.Decision("APPROVE",0L,null))).code());
  assertTrue(committed);
  verify(reservations).event(tenant,"RESERVATION_EXPIRED",view("EXPIRED",3));
  verify(store,never()).decide(any(),any(),anyLong(),any(),any()); verifyNoInteractions(audit);
 }
 @Test void deadlineReachedBetweenLockAndUpdateExpiresInsteadOfReportingChange() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","PENDING_APPROVAL",0,false));
  when(store.requesterEligible(tenant,requester,unit)).thenReturn(true);
  when(store.decide(tenant,reservation,0,"CONFIRMED",null)).thenReturn(null);
  when(store.expire(tenant,reservation,0)).thenReturn(view("EXPIRED",1));
  assertEquals("RESERVATION_DECISION_EXPIRED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,AssuranceLevel.AAL2,
   reservation,new ReservationManagementModels.Decision("APPROVE",0L,null))).code());
  assertTrue(committed,"A expiração é confirmada antes da recusa");
  verify(reservations).event(tenant,"RESERVATION_EXPIRED",view("EXPIRED",1));
  verifyNoInteractions(audit);
 }
 @Test void alreadyExpiredRequestRefusesDecisionWithoutWriting() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","EXPIRED",1,false));
  assertEquals("RESERVATION_DECISION_EXPIRED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,AssuranceLevel.AAL2,
   reservation,new ReservationManagementModels.Decision("REJECT",1L,"Agenda"))).code());
  verify(store,never()).expire(any(),any(),anyLong()); verify(store,never()).decide(any(),any(),anyLong(),any(),any());
  verifyNoInteractions(audit); verify(reservations,never()).event(any(),any(),any());
 }
 @Test void managerCannotDecideOwnRequest() {
  when(store.lock(tenant,reservation)).thenReturn(new ReservationManagementStore.Target(reservation,"BOOKING",unit,actor,
   "PENDING_APPROVAL",0,start.plusSeconds(3600),false,true,"ACTIVE"));
  for(var decision:List.of(new ReservationManagementModels.Decision("APPROVE",0L,null),
    new ReservationManagementModels.Decision("REJECT",0L,"Desisti")))
   assertEquals("RESERVATION_SELF_DECISION_DENIED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,
    AssuranceLevel.AAL2,reservation,decision)).code());
  verify(store,never()).decide(any(),any(),anyLong(),any(),any()); verifyNoInteractions(audit);
 }
 @Test void approvalRequiresEligibleRequesterActiveUnitAndArea() {
  when(store.lock(tenant,reservation)).thenReturn(target("BOOKING","PENDING_APPROVAL",0,false));
  when(store.requesterEligible(tenant,requester,unit)).thenReturn(false);
  assertEquals("RESERVATION_REQUESTER_INACTIVE",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,
   AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Decision("APPROVE",0L,null))).code());
  when(store.lock(tenant,reservation)).thenReturn(new ReservationManagementStore.Target(reservation,"BOOKING",unit,requester,
   "PENDING_APPROVAL",0,start,false,false,"ACTIVE"));
  assertEquals("AMENITY_INACTIVE",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,
   AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Decision("APPROVE",0L,null))).code());
  verify(store,never()).decide(any(),any(),anyLong(),any(),any());
 }
 @Test void rejectionAndCancellationNeedReasonBeforeOpeningTransaction() {
  assertEquals("RESERVATION_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,
   AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Decision("REJECT",0L," "))).code());
  assertEquals("RESERVATION_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->service.cancel(actor,tenant,
   AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Cancellation(0L,null))).code());
  assertEquals("RESERVATION_REASON_INVALID",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,
   AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Decision("REJECT",0L,"x".repeat(501)))).code());
  assertEquals("RESERVATION_DECISION_INVALID",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,
   AssuranceLevel.AAL2,reservation,new ReservationManagementModels.Decision("MAYBE",0L,null))).code());
  verifyNoInteractions(transactions,store,audit);
 }
 @Test void aal1ManagerChangesNothing() {
  context=manager(AssuranceLevel.AAL1);
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->service.decide(actor,tenant,AssuranceLevel.AAL1,
   reservation,new ReservationManagementModels.Decision("APPROVE",0L,null))).code());
  verifyNoInteractions(store,audit);
 }
 @Test void blockConflictIsExplicitAndNothingIsInserted() {
  when(reservations.begin(eq(tenant),eq(actor),anyString(),anyString(),anyString()))
   .thenReturn(new ReservationStore.IdempotentReply(true,null,0));
  when(store.amenityActive(tenant,area)).thenReturn(true);
  when(store.occupied(tenant,area,start,start.plusSeconds(7200))).thenReturn(1L);
  assertEquals("AMENITY_BLOCK_CONFLICT",assertThrows(BusinessProblem.class,()->service.createBlock(actor,tenant,AssuranceLevel.AAL2,
   area,"synthetic-block-0001",new ReservationManagementModels.CreateBlock(start,start.plusSeconds(7200),"Pintura"))).code());
  var order=inOrder(reservations,store);
  order.verify(reservations).expireStale(tenant,null,area,start,start.plusSeconds(7200));
  order.verify(store).occupied(tenant,area,start,start.plusSeconds(7200));
  verify(store,never()).insertBlock(any(),any(),any(),any(),any(),any());
  verifyNoInteractions(audit);
 }
 @Test void blockWindowMustBeBoundedAndNotInThePast() {
  when(reservations.begin(eq(tenant),eq(actor),anyString(),anyString(),anyString()))
   .thenReturn(new ReservationStore.IdempotentReply(true,null,0));
  when(store.amenityActive(tenant,area)).thenReturn(true);
  for(var invalid:List.of(new ReservationManagementModels.CreateBlock(start,start,"Pintura"),
    new ReservationManagementModels.CreateBlock(start,start.plusSeconds(367L*86400),"Pintura"),
    new ReservationManagementModels.CreateBlock(start.plusNanos(1),start.plusSeconds(60),"Pintura"),
    new ReservationManagementModels.CreateBlock(now.minusSeconds(3600),now.plusSeconds(3600),"Pintura")))
   assertEquals("AMENITY_BLOCK_WINDOW_INVALID",assertThrows(BusinessProblem.class,()->service.createBlock(actor,tenant,
    AssuranceLevel.AAL2,area,"synthetic-block-0002",invalid)).code());
  verify(store,never()).insertBlock(any(),any(),any(),any(),any(),any());
 }
 @Test void listRejectsUnknownStatusAndPartialOrLongRanges() {
  assertEquals("RESERVATION_STATUS_INVALID",assertThrows(BusinessProblem.class,
   ()->service.reservations(actor,tenant,AssuranceLevel.AAL2,"PAID",null,null)).code());
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.reservations(actor,tenant,AssuranceLevel.AAL2,null,start,null)).code());
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.reservations(actor,tenant,AssuranceLevel.AAL2,null,start,start.plusSeconds(367L*86400))).code());
  verifyNoInteractions(transactions);
 }
}
