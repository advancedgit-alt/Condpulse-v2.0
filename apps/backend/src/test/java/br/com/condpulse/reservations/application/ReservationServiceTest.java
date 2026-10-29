package br.com.condpulse.reservations.application;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.reservations.domain.ReservationPolicy;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReservationServiceTest {
 private final UUID actor=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private final UUID tenant=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private final UUID unit=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private final UUID area=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private final UUID reservation=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private final String key="synthetic-operation-0001";
 private final Instant now=Instant.parse("2030-01-01T12:00:00Z");
 private final Instant start=Instant.parse("2030-01-02T14:00:00Z");
 private final Instant end=Instant.parse("2030-01-02T15:00:00Z");
 private final TenantTransactionExecutor transactions=mock(TenantTransactionExecutor.class);
 private final ReservationStore store=mock(ReservationStore.class);
 private final ReservationService service=new ReservationService(transactions,store);
 private TenantContext context;
 private final ReservationPolicy policy=new ReservationPolicy(1,EnumSet.allOf(DayOfWeek.class),
  LocalTime.of(6,0),LocalTime.of(23,59),0,30,240,1,0);

 @SuppressWarnings("unchecked")
 @BeforeEach void setup() {
  context=new TenantContext(actor,tenant,ZoneId.of("America/Sao_Paulo"),List.of(
   new TenantContext.Membership(UUID.randomUUID(),unit,"RESIDENT",false,false)));
  doAnswer(invocation->{
   Function<TenantContext,Object> action=invocation.getArgument(2);
   return action.apply(context);
  }).when(transactions).execute(eq(actor),eq(tenant),any());
  when(store.begin(eq(tenant),eq(actor),anyString(),eq(key),anyString()))
   .thenReturn(new ReservationStore.IdempotentReply(true,null,0));
  when(store.currentTime()).thenReturn(now);
 }
 private ReservationModels.Create request() { return new ReservationModels.Create(unit,area,start,end,1); }
 private ReservationStore.AmenityData amenity(boolean approval,ReservationPolicy rules) {
  return new ReservationStore.AmenityData(area,"Área sintética",10,approval,5000,10,1,"{}",0,rules);
 }
 private ReservationModels.View view(String status,long version) {
  return new ReservationModels.View(reservation,unit,area,status,start,end,start,end.plusSeconds(600),1,5000,version,null);
 }
 @Test void createWritesReservationAuditEventAndReplyWithinTheSameContext() {
  var areaData=amenity(false,policy); var value=view("CONFIRMED",0);
  when(store.amenity(tenant,area)).thenReturn(areaData);
  when(store.create(eq(tenant),eq(actor),eq(request()),eq(areaData),any(),eq("America/Sao_Paulo"))).thenReturn(value);
  var result=service.create(actor,tenant,key,request());
  assertEquals(201,result.responseStatus()); assertFalse(result.replayed()); assertEquals(value,result.reservation());
  var order=inOrder(store);
  order.verify(store).begin(eq(tenant),eq(actor),anyString(),eq(key),anyString());
  order.verify(store).lockUnit(tenant,unit);
  order.verify(store).amenity(tenant,area);
  order.verify(store).currentTime();
  order.verify(store).expireStale(tenant,unit,area,start,end.plusSeconds(600));
  order.verify(store).activeCount(tenant,unit,now);
  order.verify(store).create(eq(tenant),eq(actor),eq(request()),eq(areaData),any(),eq("America/Sao_Paulo"));
  order.verify(store).record(eq(tenant),eq(actor),eq(value),eq("RESERVATION_CREATED"),any(UUID.class));
  order.verify(store).finish(eq(tenant),eq(actor),anyString(),eq(key),eq(value),eq(201));
 }
 @Test void successfulReplayReturnsOriginalResponseWithoutRepeatingBusinessEffects() {
  var value=view("CONFIRMED",0);
  when(store.begin(eq(tenant),eq(actor),anyString(),eq(key),anyString()))
   .thenReturn(new ReservationStore.IdempotentReply(false,value,201));
  var result=service.create(actor,tenant,key,request());
  assertTrue(result.replayed()); assertEquals(value,result.reservation());
  verify(store).begin(eq(tenant),eq(actor),anyString(),eq(key),anyString());
  verifyNoMoreInteractions(store);
 }
 @Test void deniedUnitCannotReadEvenAnExistingIdempotentReply() {
  context=new TenantContext(actor,tenant,ZoneId.of("America/Sao_Paulo"),List.of(
   new TenantContext.Membership(UUID.randomUUID(),UUID.randomUUID(),"RESIDENT",false,false)));
  var error=assertThrows(BusinessProblem.class,()->service.create(actor,tenant,key,request()));
  assertEquals(404,error.status()); assertEquals("UNIT_ACCESS_DENIED",error.code());
  verifyNoInteractions(store);
 }
 @Test void ownerWithoutResidentMembershipCannotBookUnit() {
  context=new TenantContext(actor,tenant,ZoneId.of("America/Sao_Paulo"),List.of(
   new TenantContext.Membership(UUID.randomUUID(),unit,"OWNER",true,true)));
  assertEquals(404,assertThrows(BusinessProblem.class,()->service.create(actor,tenant,key,request())).status());
  verifyNoInteractions(store);
 }
 @Test void catalogAlsoRequiresResidentialReservationCapability() {
  context=new TenantContext(actor,tenant,ZoneId.of("America/Sao_Paulo"),List.of(
   new TenantContext.Membership(UUID.randomUUID(),null,"MANAGER",true,true)));
  assertEquals("RESERVATION_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->service.amenities(actor,tenant)).code());
  verifyNoInteractions(store);
 }
 @Test void incompletePolicyFailsClosed() {
  when(store.amenity(tenant,area)).thenReturn(amenity(false,null));
  assertEquals("AMENITY_POLICY_NOT_CONFIGURED",assertThrows(BusinessProblem.class,
   ()->service.create(actor,tenant,key,request())).code());
  verify(store,never()).create(any(),any(),any(),any(),any(),anyString());
  verify(store,never()).record(any(),any(),any(),anyString(),any());
 }
 @Test void approvalRequiredAreaCreatesPendingRequestWithRequestedEvent() {
  var areaData=amenity(true,policy); var pending=view("PENDING_APPROVAL",0);
  when(store.amenity(tenant,area)).thenReturn(areaData);
  when(store.create(eq(tenant),eq(actor),eq(request()),eq(areaData),any(),eq("America/Sao_Paulo"))).thenReturn(pending);
  var result=service.create(actor,tenant,key,request());
  assertEquals("PENDING_APPROVAL",result.reservation().status(),"Pedido pendente nunca é apresentado como confirmado");
  assertEquals(201,result.responseStatus());
  verify(store).record(eq(tenant),eq(actor),eq(pending),eq("RESERVATION_REQUESTED"),any(UUID.class));
  verify(store,never()).record(any(),any(),any(),eq("RESERVATION_CREATED"),any());
  verify(store).finish(eq(tenant),eq(actor),anyString(),eq(key),eq(pending),eq(201));
 }
 @Test void staleRequestsAreExpiredUnderUnitLockBeforeQuotaAndInsert() {
  var areaData=amenity(true,policy); var pending=view("PENDING_APPROVAL",0);
  when(store.amenity(tenant,area)).thenReturn(areaData);
  when(store.create(eq(tenant),eq(actor),eq(request()),eq(areaData),any(),eq("America/Sao_Paulo"))).thenReturn(pending);
  service.create(actor,tenant,key,request());
  var order=inOrder(store);
  order.verify(store).lockUnit(tenant,unit);
  order.verify(store).expireStale(tenant,unit,area,start,end.plusSeconds(600));
  order.verify(store).activeCount(tenant,unit,now);
  order.verify(store).create(eq(tenant),eq(actor),eq(request()),eq(areaData),any(),eq("America/Sao_Paulo"));
 }
 @Test void authorWithdrawsPendingRequestInsideCancellationLeadButNotAfterStart() {
  var pending=view("PENDING_APPROVAL",0); var withdrawn=view("CANCELLED",1);
  // Prazo de cancelamento de 2 dias já passou para a confirmada, mas pedido pendente pode ser retirado até o início.
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(pending,2880,actor));
  when(store.cancel(tenant,actor,pending)).thenReturn(withdrawn);
  assertEquals("CANCELLED",service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0)).reservation().status());
  verify(store).record(eq(tenant),eq(actor),eq(withdrawn),eq("RESERVATION_CANCELLED"),any());
  when(store.currentTime()).thenReturn(start);
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(pending,null,actor));
  assertEquals("RESERVATION_CANCELLATION_CLOSED",assertThrows(BusinessProblem.class,
   ()->service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0))).code());
 }
 @Test void confirmedReservationStillRespectsAcceptedCancellationLead() {
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(view("CONFIRMED",0),2880,actor));
  assertEquals("RESERVATION_CANCELLATION_CLOSED",assertThrows(BusinessProblem.class,
   ()->service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0))).code());
  verify(store,never()).cancel(any(),any(),any());
 }
 @Test void expiredOrDecidedRequestCannotBeWithdrawn() {
  for(String status:List.of("EXPIRED","REJECTED")) {
   when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(view(status,1),0,actor));
   assertEquals("RESERVATION_CHANGED",assertThrows(BusinessProblem.class,
    ()->service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,1))).code(),status);
  }
  verify(store,never()).cancel(any(),any(),any());
 }
 @Test void acceptedPolicyMustMatchTheCurrentVersion() {
  when(store.amenity(tenant,area)).thenReturn(amenity(false,policy));
  var changed=new ReservationModels.Create(unit,area,start,end,2);
  assertEquals("AMENITY_POLICY_CHANGED",assertThrows(BusinessProblem.class,
   ()->service.create(actor,tenant,key,changed)).code());
  verify(store,never()).create(any(),any(),any(),any(),any(),anyString());
 }
 @Test void quotaAcrossAmenitiesIsCheckedAfterUnitLock() {
  when(store.amenity(tenant,area)).thenReturn(amenity(false,policy));
  when(store.activeCount(tenant,unit,now)).thenReturn(1L);
  assertEquals("UNIT_RESERVATION_QUOTA_REACHED",assertThrows(BusinessProblem.class,
   ()->service.create(actor,tenant,key,request())).code());
  var order=inOrder(store); order.verify(store).lockUnit(tenant,unit); order.verify(store).activeCount(tenant,unit,now);
  verify(store,never()).create(any(),any(),any(),any(),any(),anyString());
 }
 @Test void exclusionConstraintIsTranslatedAfterExecutorThrowsWithoutLeakingSql() {
  doThrow(new DataIntegrityViolationException("synthetic SQL must not leak",new SQLException("conflict","23P01")))
   .when(transactions).execute(eq(actor),eq(tenant),any());
  var error=assertThrows(BusinessProblem.class,()->service.create(actor,tenant,key,request()));
  assertEquals(409,error.status()); assertEquals("RESERVATION_CONFLICT",error.code());
  assertFalse(error.getMessage().contains("SQL")); verifyNoInteractions(store);
 }
 @Test void repeatedCancellationWithNewKeyDoesNotWriteEventOrAdvanceVersion() {
  var cancelled=new ReservationModels.View(reservation,unit,area,"CANCELLED",start,end,start,end.plusSeconds(600),1,5000,1,
   ReservationStore.AUTHOR_CANCELLED_REASON);
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(cancelled,0,actor));
  var result=service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0));
  assertEquals(200,result.responseStatus()); assertEquals(1,result.reservation().version());
  verify(store,never()).cancel(any(),any(),any()); verify(store,never()).record(any(),any(),any(),anyString(),any());
  verify(store).finish(eq(tenant),eq(actor),anyString(),eq(key),eq(cancelled),eq(200));
 }
 @Test void managementCancellationIsNotReportedAsTheAuthorsOwnCancellation() {
  var cancelled=new ReservationModels.View(reservation,unit,area,"CANCELLED",start,end,start,end.plusSeconds(600),1,5000,1,
   "Vazamento no salão");
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(cancelled,0,actor));
  assertEquals("RESERVATION_CHANGED",assertThrows(BusinessProblem.class,
   ()->service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0))).code());
  verify(store,never()).finish(any(),any(),anyString(),anyString(),any(),anyInt());
 }
 @Test void anotherPersonOfTheUnitReceivesExplicitAuthorRequirementAndNothingChanges() {
  var other=UUID.fromString("77777777-7777-4777-8777-777777777777");
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(view("CONFIRMED",0),0,other));
  var denied=assertThrows(BusinessProblem.class,()->service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0)));
  assertEquals("RESERVATION_AUTHOR_REQUIRED",denied.code()); assertEquals(403,denied.status());
  verify(store,never()).cancel(any(),any(),any()); verify(store,never()).record(any(),any(),any(),anyString(),any());
  verify(store,never()).finish(any(),any(),anyString(),anyString(),any(),anyInt());
 }
 @Test void cancellationUsesAcceptedSnapshotRatherThanCurrentAmenityRules() {
  var current=view("CONFIRMED",0); var cancelled=view("CANCELLED",1);
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(current,0,actor));
  when(store.cancel(tenant,actor,current)).thenReturn(cancelled);
  var result=service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0));
  assertEquals(cancelled,result.reservation());
  verify(store,never()).amenity(any(),any());
  verify(store).record(eq(tenant),eq(actor),eq(cancelled),eq("RESERVATION_CANCELLED"),any());
 }
 @Test void staleVersionCannotCancelConfirmedReservation() {
  when(store.unitReservationForUpdate(tenant,unit,reservation)).thenReturn(new ReservationStore.StoredReservation(view("CONFIRMED",1),0,actor));
  assertEquals("RESERVATION_CHANGED",assertThrows(BusinessProblem.class,
   ()->service.cancel(actor,tenant,reservation,key,new ReservationModels.Cancel(unit,0))).code());
  verify(store,never()).cancel(any(),any(),any()); verify(store,never()).record(any(),any(),any(),anyString(),any());
 }
 @Test void readOnlyWindowIsBoundedBeforeQueryingDatabase() {
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.reservations(actor,tenant,unit,start,start.plusSeconds(32*86400L))).code());
  verifyNoInteractions(transactions,store);
 }
 @Test void extremeInstantsAreRejectedBeforeJdbcTimestampConversion() {
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.reservations(actor,tenant,unit,Instant.MAX.minusSeconds(3600),Instant.MAX)).code());
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.reservations(actor,tenant,unit,Instant.MIN,Instant.MIN.plusSeconds(3600))).code());
  verifyNoInteractions(transactions,store);
 }
 @Test void availabilityUsesVerifiedContextAndReturnsOnlyAnonymousOccupiedIntervals() {
  when(store.amenity(tenant,area)).thenReturn(amenity(false,policy));
  var blocked=List.of(new ReservationModels.BlockedInterval(start,end.plusSeconds(600)));
  when(store.blockedIntervals(tenant,area,start,end.plusSeconds(3600))).thenReturn(blocked);
  var result=service.availability(actor,tenant,unit,area,start,end.plusSeconds(3600));
  assertEquals(tenant,result.tenantId()); assertEquals(unit,result.unitId()); assertEquals(area,result.amenityId());
  assertEquals("America/Sao_Paulo",result.timeZone()); assertEquals(now,result.observedAt());
  assertEquals(start,result.from()); assertEquals(end.plusSeconds(3600),result.until());
  assertEquals(blocked,result.blockedIntervals());
  var order=inOrder(store); order.verify(store).amenity(tenant,area); order.verify(store).currentTime();
  order.verify(store).blockedIntervals(tenant,area,start,end.plusSeconds(3600));
  verifyNoMoreInteractions(store);
 }
 @Test void cancellationOnlyDependentCannotQueryAvailabilityForCreation() {
  context=new TenantContext(actor,tenant,ZoneId.of("America/Sao_Paulo"),List.of(
   new TenantContext.Membership(UUID.randomUUID(),unit,"DEPENDENT",false,true)));
  var denied=assertThrows(BusinessProblem.class,()->service.availability(actor,tenant,unit,area,start,end));
  assertEquals("UNIT_ACCESS_DENIED",denied.code()); assertEquals(404,denied.status());
  verifyNoInteractions(store);
 }
 @Test void unavailableAmenityRulesPreventAnApparentlyFreeCalendar() {
  when(store.amenity(tenant,area)).thenReturn(amenity(false,null));
  assertEquals("AMENITY_POLICY_NOT_CONFIGURED",assertThrows(BusinessProblem.class,
   ()->service.availability(actor,tenant,unit,area,start,end)).code());
  verify(store,never()).blockedIntervals(any(),any(),any(),any());
  verify(store,never()).currentTime();
 }
 @Test void approvalAreaServesTheSameAnonymousOccupancy() {
  when(store.amenity(tenant,area)).thenReturn(amenity(true,policy));
  when(store.blockedIntervals(tenant,area,start,end)).thenReturn(List.of());
  assertEquals(List.of(),service.availability(actor,tenant,unit,area,start,end).blockedIntervals());
  verify(store).blockedIntervals(tenant,area,start,end);
 }
 @Test void availabilityRejectsUnboundedReversedExtremeAndSubMicrosecondRangesBeforeJdbc() {
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.availability(actor,tenant,unit,area,start,start.plusSeconds(31*86400L+1))).code());
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.availability(actor,tenant,unit,area,end,start)).code());
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.availability(actor,tenant,unit,area,Instant.MIN,start)).code());
  assertEquals("RESERVATION_RANGE_INVALID",assertThrows(BusinessProblem.class,
   ()->service.availability(actor,tenant,unit,area,start.plusNanos(1),end)).code());
  verifyNoInteractions(transactions,store);
 }
}
