package br.com.condpulse.reservations.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReservationPolicyTest {
 private static final ZoneId SAO_PAULO=ZoneId.of("America/Sao_Paulo");
 private static final Instant NOW=Instant.parse("2030-01-01T10:00:00Z");
 private static final Instant START=Instant.parse("2030-01-01T12:00:00Z");
 private static final Instant END=Instant.parse("2030-01-01T13:00:00Z");
 private ReservationPolicy policy() {
  return new ReservationPolicy(1,EnumSet.allOf(DayOfWeek.class),LocalTime.of(8,0),LocalTime.of(18,0),120,30,60,2,120);
 }

 @Test void appliesSaoPauloHoursAndCleaningToTheEffectiveWindow() {
  var window=policy().book(START,END,NOW,SAO_PAULO,30);
  assertEquals(START,window.occupiedFrom());
  assertEquals(Instant.parse("2030-01-01T13:30:00Z"),window.occupiedUntil());
 }
 @Test void minimumLeadAllowsExactBoundaryAndRejectsOneMinuteEarlier() {
  assertDoesNotThrow(()->policy().book(START,END,NOW,SAO_PAULO,0));
  assertThrows(IllegalArgumentException.class,()->policy().book(START.minusSeconds(60),END.minusSeconds(60),NOW,SAO_PAULO,0));
 }
 @Test void requiresStrictlyFutureStartEvenWithZeroMinimumLead() {
  var p=new ReservationPolicy(1,EnumSet.allOf(DayOfWeek.class),LocalTime.MIN,LocalTime.of(23,59),0,30,60,2,0);
  assertThrows(IllegalArgumentException.class,()->p.book(NOW,NOW.plusSeconds(60),NOW,SAO_PAULO,0));
 }
 @Test void maximumAdvanceUsesCondominiumLocalDate() {
  var lastStart=Instant.parse("2030-01-31T12:00:00Z");
  assertDoesNotThrow(()->policy().book(lastStart,lastStart.plusSeconds(3600),NOW,SAO_PAULO,0));
  var after=Instant.parse("2030-02-01T12:00:00Z");
  assertThrows(IllegalArgumentException.class,()->policy().book(after,after.plusSeconds(3600),NOW,SAO_PAULO,0));
 }
 @Test void cleaningMustFinishBeforeClosingAndCannotOverflowToAnotherDay() {
  var start=Instant.parse("2030-01-01T20:00:00Z");
  var end=Instant.parse("2030-01-01T21:00:00Z");
  assertDoesNotThrow(()->policy().book(start,end,NOW,SAO_PAULO,0));
  assertThrows(IllegalArgumentException.class,()->policy().book(start,end,NOW,SAO_PAULO,1));
  assertThrows(IllegalArgumentException.class,()->policy().book(START,END,NOW,SAO_PAULO,1440));
 }
 @Test void validatesWeekdayAndOpeningTimeInTheConfiguredZone() {
  var mondayOnly=new ReservationPolicy(1,Set.of(DayOfWeek.MONDAY),LocalTime.of(8,0),LocalTime.of(18,0),0,30,60,2,0);
  assertThrows(IllegalArgumentException.class,()->mondayOnly.book(START,END,NOW,SAO_PAULO,0));
  var early=Instant.parse("2030-01-02T10:00:00Z");
  assertThrows(IllegalArgumentException.class,()->policy().book(early,early.plusSeconds(3600),NOW,SAO_PAULO,0));
 }
 @Test void enforcesMaximumDurationAndDoesNotRoundSecondsOrNanos() {
  assertThrows(IllegalArgumentException.class,()->policy().book(START,END.plusSeconds(60),NOW,SAO_PAULO,0));
  assertThrows(IllegalArgumentException.class,()->policy().book(START.plusSeconds(1),END,NOW,SAO_PAULO,0));
  assertThrows(IllegalArgumentException.class,()->policy().book(START,END.plusNanos(1),NOW,SAO_PAULO,0));
 }
 @Test void rejectsOvernightBookingsInsteadOfAssumingTheFollowingDaysSchedule() {
  var p=new ReservationPolicy(1,EnumSet.allOf(DayOfWeek.class),LocalTime.MIN,LocalTime.of(23,59),0,30,1440,2,0);
  assertThrows(IllegalArgumentException.class,()->p.book(Instant.parse("2030-01-01T23:00:00Z"),
   Instant.parse("2030-01-02T04:00:00Z"),NOW,SAO_PAULO,0));
 }
 @Test void rejectsFallBackAndCleaningAcrossSpringForwardTransitions() {
  var p=new ReservationPolicy(1,EnumSet.allOf(DayOfWeek.class),LocalTime.MIN,LocalTime.of(23,59),0,366,1440,2,0);
  var zone=ZoneId.of("America/New_York");
  assertThrows(IllegalArgumentException.class,()->p.book(Instant.parse("2026-11-01T05:30:00Z"),
   Instant.parse("2026-11-01T06:30:00Z"),Instant.parse("2026-10-31T00:00:00Z"),zone,0));
  assertThrows(IllegalArgumentException.class,()->p.book(Instant.parse("2026-03-08T06:00:00Z"),
   Instant.parse("2026-03-08T06:30:00Z"),Instant.parse("2026-03-07T00:00:00Z"),zone,60));
 }
 @Test void cancellationAllowsExactDeadlineButNeverAfterStart() {
  assertDoesNotThrow(()->policy().requireCancellation(START,NOW));
  assertThrows(IllegalArgumentException.class,()->policy().requireCancellation(START,NOW.plusSeconds(1)));
  assertThrows(IllegalArgumentException.class,()->policy().requireCancellation(START,START));
 }
 @Test void invalidConfigurationAndMissingInputsFailClosed() {
  assertAll(
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(2,Set.of(DayOfWeek.TUESDAY),LocalTime.MIN,LocalTime.NOON,0,30,60,2,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(),LocalTime.MIN,LocalTime.NOON,0,30,60,2,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(DayOfWeek.TUESDAY),LocalTime.NOON,LocalTime.NOON,0,30,60,2,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(DayOfWeek.TUESDAY),LocalTime.MIN,LocalTime.NOON,-1,30,60,2,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(DayOfWeek.TUESDAY),LocalTime.MIN,LocalTime.NOON,0,0,60,2,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(DayOfWeek.TUESDAY),LocalTime.MIN,LocalTime.NOON,0,30,0,2,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(DayOfWeek.TUESDAY),LocalTime.MIN,LocalTime.NOON,0,30,60,0,0)),
   ()->assertThrows(IllegalArgumentException.class,()->new ReservationPolicy(1,Set.of(DayOfWeek.TUESDAY),LocalTime.MIN,LocalTime.NOON,0,30,60,2,-1)),
   ()->assertThrows(IllegalArgumentException.class,()->policy().book(null,END,NOW,SAO_PAULO,0)),
   ()->assertThrows(IllegalArgumentException.class,()->policy().book(START,END,NOW,SAO_PAULO,-1)));
 }
 @Test void snapshotsWeekdaysInsteadOfKeepingTheCallersMutableSet() {
  var days=EnumSet.allOf(DayOfWeek.class);
  var p=new ReservationPolicy(1,days,LocalTime.of(8,0),LocalTime.of(18,0),0,30,60,2,0);
  days.clear();
  assertEquals(7,p.weekDays().size());
  assertThrows(UnsupportedOperationException.class,()->p.weekDays().clear());
 }
}
