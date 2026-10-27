package br.com.condpulse.reservations.domain;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
public record ReservationWindow(Instant startsAt,Instant endsAt,Instant occupiedFrom,Instant occupiedUntil) {
 public ReservationWindow {
  Objects.requireNonNull(startsAt); Objects.requireNonNull(endsAt);
  Objects.requireNonNull(occupiedFrom); Objects.requireNonNull(occupiedUntil);
  if(!startsAt.isBefore(endsAt) || occupiedFrom.isAfter(startsAt) || occupiedUntil.isBefore(endsAt))
   throw new IllegalArgumentException("Intervalo de reserva inválido.");
 }
 public static ReservationWindow withCleaning(Instant start,Instant end,Duration before,Duration after) {
  if(before.isNegative() || after.isNegative()) throw new IllegalArgumentException("Limpeza inválida.");
  return new ReservationWindow(start,end,start.minus(before),end.plus(after));
 }
 public boolean overlaps(ReservationWindow other) {
  return occupiedFrom.isBefore(other.occupiedUntil) && other.occupiedFrom.isBefore(occupiedUntil);
 }
}
