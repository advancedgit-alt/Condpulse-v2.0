package br.com.condpulse.reservations.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Fila de espera de reservas (CP-040): só as entradas da própria unidade, sem dados de outras unidades. */
public final class WaitlistModels {
 private WaitlistModels() {}
 public record Join(UUID unitId,UUID amenityId,Instant startsAt,Instant endsAt,int acceptedPolicyVersion) {}
 public record Change(UUID unitId,Long expectedVersion) {}
 /**
  * state: WAITING, OFFERED, ACCEPTED, LEFT, EXPIRED ou CLOSED. Na oferta, offerReservationId é a retenção HELD e
  * offerExpiresAt o prazo para aceitar; position é a posição atual na fila da área (só para WAITING).
  */
 public record Entry(UUID waitlistId,UUID unitId,UUID amenityId,String state,Instant startsAt,Instant endsAt,
     Instant occupiedFrom,Instant occupiedUntil,int acceptedPolicyVersion,boolean approvalRequired,long feeCents,
     UUID offerReservationId,Instant offeredAt,Instant offerExpiresAt,Instant closedAt,boolean createdByMe,
     Integer position,long version,Instant createdAt) {}
 public record Page(List<Entry> entries,boolean truncated) {
  public Page { entries=List.copyOf(entries); }
 }
 public record Mutation(Entry entry,boolean replayed,int responseStatus) {}
 /** Aceite: entrada atualizada e a reserva (confirmada ou pedido com aprovação) que nasceu da oferta. */
 public record Acceptance(Entry entry,ReservationModels.View reservation) {}
}
