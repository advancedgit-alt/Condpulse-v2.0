package br.com.condpulse.tenancy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Proprietário × inquilino (V034). Nunca expõe e-mail, conta Auth nem token persistido. */
public final class RentalModels {
 private RentalModels() {}
 /** Locação em vigor vista pela gestão: proprietários (nomes dos vínculos) que perderam a visão da unidade. */
 public record ManagedRental(UUID unitId,String unitLabel,Instant startedAt,List<String> owners) {
  public ManagedRental { owners=List.copyOf(owners); }
 }
 public record RentalChange(String reason) {}
 /** Ocupante (morador, responsável ou dependente) da unidade alugada, visto pelo proprietário. */
 public record Occupant(UUID membershipId,String displayName,String role,String status,Instant validFrom,long version) {}
 /** Visão do proprietário: sem locação em vigor, só a indicação (listas vazias). */
 public record OwnerView(UUID unitId,String unitLabel,boolean rented,Instant rentedSince,List<Occupant> occupants,
     List<MembershipModels.Invitation> invitations) {
  public OwnerView { occupants=List.copyOf(occupants); invitations=List.copyOf(invitations); }
 }
 public record TenantInvitation(String role,String displayName,String email,Integer validDays) {
  @Override public String toString() { return "TenantInvitation[role="+role+",email=REDACTED]"; }
 }
 public record OccupantRemoval(Long expectedVersion,String reason) {}
}
