package br.com.condpulse.reservations.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.reservations.domain.ReservationPolicy;

/** Contratos da fatia residencial; não incluem identidade de outros moradores. */
public final class ReservationModels {
 private ReservationModels() {}
 public record Create(UUID unitId,UUID amenityId,Instant startsAt,Instant endsAt,int acceptedPolicyVersion) {}
 public record Cancel(UUID unitId,long expectedVersion) {}
 /** decisionReason: motivo de decisão, cancelamento ou expiração, visível à unidade solicitante. */
 public record View(UUID reservationId,UUID unitId,UUID amenityId,String status,Instant startsAt,
     Instant endsAt,Instant occupiedFrom,Instant occupiedUntil,int acceptedPolicyVersion,long feeCents,long version,
     String decisionReason) {}
 public record Page(List<View> reservations,boolean truncated) {
  public Page { reservations=List.copyOf(reservations); }
 }
 /** Uniao anonima de ocupacoes reais, incluindo limpeza; nao garante vaga para uma nova reserva. */
 public record BlockedInterval(Instant occupiedFrom,Instant occupiedUntil) {}
 public record Availability(UUID tenantId,UUID unitId,UUID amenityId,String timeZone,
     Instant from,Instant until,Instant observedAt,List<BlockedInterval> blockedIntervals) {
  public Availability { blockedIntervals=List.copyOf(blockedIntervals); }
 }
 public record Policy(int schemaVersion,List<Integer> weekDays,String opensAt,String closesAt,
     int minimumLeadMinutes,int maximumAdvanceDays,int maximumDurationMinutes,
     int maximumActivePerUnit,int cancellationLeadMinutes) {
  public Policy { weekDays=List.copyOf(weekDays); }
  public static Policy from(ReservationPolicy value) {
   return new Policy(value.schemaVersion(),value.weekDays().stream().map(d->d.getValue()).sorted().toList(),
    value.opensAt().toString(),value.closesAt().toString(),value.minimumLeadMinutes(),value.maximumAdvanceDays(),
    value.maximumDurationMinutes(),value.maximumActivePerUnit(),value.cancellationLeadMinutes());
  }
 }
 public record Amenity(UUID amenityId,String name,int capacity,int policyVersion,long feeCents,
     int bufferMinutes,boolean approvalRequired,String bookingState,Policy policy) {}
 public record Mutation(View reservation,boolean replayed,int responseStatus) {}
}
