package br.com.condpulse.reservations.domain;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReservationWindowTest {
 private final Instant noon=Instant.parse("2030-01-01T12:00:00Z");
 @Test void halfOpenAdjacentIntervalsDoNotConflict() {
  var a=ReservationWindow.withCleaning(noon,noon.plusSeconds(3600),Duration.ZERO,Duration.ZERO);
  var b=ReservationWindow.withCleaning(noon.plusSeconds(3600),noon.plusSeconds(7200),Duration.ZERO,Duration.ZERO);
  assertFalse(a.overlaps(b));
 }
 @Test void cleaningIntervalBlocksNextBooking() {
  var a=ReservationWindow.withCleaning(noon,noon.plusSeconds(3600),Duration.ZERO,Duration.ofMinutes(30));
  var b=ReservationWindow.withCleaning(noon.plusSeconds(3600),noon.plusSeconds(7200),Duration.ZERO,Duration.ZERO);
  assertTrue(a.overlaps(b));
 }
 @Test void invalidPeriodAndNegativeCleaningAreRejected() {
  assertThrows(IllegalArgumentException.class,()->ReservationWindow.withCleaning(noon,noon,Duration.ZERO,Duration.ZERO));
  assertThrows(IllegalArgumentException.class,()->ReservationWindow.withCleaning(noon,noon.plusSeconds(60),Duration.ofSeconds(-1),Duration.ZERO));
 }
}
