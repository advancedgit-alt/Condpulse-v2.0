package br.com.condpulse.reservations.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos da gestão local para pedidos, decisões e bloqueios de manutenção (CP-023). */
public final class ReservationManagementModels {
 private ReservationManagementModels() {}
 /** decision: APPROVE ou REJECT. Motivo obrigatório na rejeição, opcional na aprovação. */
 public record Decision(String decision,Long expectedVersion,String reason) {}
 public record Cancellation(Long expectedVersion,String reason) {}
 public record CreateBlock(Instant startsAt,Instant endsAt,String reason) {}
 /**
  * Visão da gestão. status considera prazo vencido como EXPIRED mesmo antes da transição física.
  * requesterName vem do vínculo do autor no próprio condomínio; não expõe e-mail nem conta Auth.
  */
 public record ManagedReservation(UUID reservationId,String kind,UUID unitId,String unitLabel,UUID amenityId,
     String amenityName,String status,Instant startsAt,Instant endsAt,Instant occupiedFrom,Instant occupiedUntil,
     Instant expiresAt,int acceptedPolicyVersion,long feeCents,long version,String decisionReason,
     String requesterName,Instant createdAt) {}
 public record ManagedReservationPage(List<ManagedReservation> reservations,boolean truncated) {
  public ManagedReservationPage { reservations=List.copyOf(reservations); }
 }
 /**
  * Fila de espera vista pela gestão (CP-040), só leitura. state efetivo: WAITING, OFFERED, ACCEPTED, LEFT, EXPIRED ou
  * CLOSED; position só para WAITING; offerExpiresAt só na oferta.
  */
 public record ManagedWaitlistEntry(UUID waitlistId,UUID unitId,String unitLabel,UUID amenityId,String amenityName,String state,
     Instant startsAt,Instant endsAt,Integer position,Instant offerExpiresAt,Instant closedAt,Instant createdAt) {}
 public record ManagedWaitlistPage(List<ManagedWaitlistEntry> entries,boolean truncated) {
  public ManagedWaitlistPage { entries=List.copyOf(entries); }
 }
 public record BlockMutation(ManagedReservation reservation,boolean replayed,int responseStatus) {}
}
