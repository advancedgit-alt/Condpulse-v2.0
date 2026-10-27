package br.com.condpulse.reservations.application;

import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReservationRequestHashTest {
 private final UUID unit=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private final UUID amenity=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private final Instant start=Instant.parse("2030-01-01T12:00:00Z"),end=start.plusSeconds(3600);
 @Test void changingRulesOrUnitChangesSemanticRequestHash() {
  var base=new ReservationModels.Create(unit,amenity,start,end,1);
  String hash=ReservationRequestHash.create(base);
  assertTrue(hash.matches("[0-9a-f]{64}"));
  assertEquals(hash,ReservationRequestHash.create(new ReservationModels.Create(unit,amenity,start,end,1)));
  assertNotEquals(hash,ReservationRequestHash.create(new ReservationModels.Create(unit,amenity,start,end,2)));
  assertNotEquals(hash,ReservationRequestHash.create(new ReservationModels.Create(amenity,amenity,start,end,1)));
 }
 @Test void missingCreationFieldsAndCancellationVersionAreRejected() {
  assertThrows(BusinessProblem.class,()->ReservationRequestHash.create(new ReservationModels.Create(null,amenity,start,end,1)));
  assertThrows(BusinessProblem.class,()->ReservationRequestHash.cancel(amenity,new ReservationModels.Cancel(unit,-1)));
 }
 @Test void invalidOperationKeysAreRejectedBeforeStorage() {
  assertThrows(BusinessProblem.class,()->ReservationRequestHash.requireKey("short"));
  assertThrows(BusinessProblem.class,()->ReservationRequestHash.requireKey("synthetic-key\r\nInjected:true"));
  assertDoesNotThrow(()->ReservationRequestHash.requireKey("11111111-1111-4111-8111-111111111111"));
 }
}
