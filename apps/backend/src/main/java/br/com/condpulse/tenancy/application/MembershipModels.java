package br.com.condpulse.tenancy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vínculos e convites vistos pela gestão. Não expõem e-mail, conta Auth nem token persistido. */
public final class MembershipModels {
 private MembershipModels() {}
 public record Membership(UUID membershipId,UUID unitId,String unitLabel,String displayName,String role,String status,
     Instant validFrom,Instant validUntil,long version) {}
 public record MembershipPage(List<Membership> memberships,boolean truncated) {
  public MembershipPage { memberships=List.copyOf(memberships); }
 }
 public record Decision(String decision,Long expectedVersion,String reason) {}
 public record Revocation(Long expectedVersion,String reason) {}
 public record CreateInvitation(UUID unitId,String role,String displayName,String email,Integer validDays) {
  @Override public String toString() { return "CreateInvitation[unitId="+unitId+",role="+role+",email=REDACTED]"; }
 }
 /** O token bruto aparece somente nesta resposta; o banco guarda apenas o hash. */
 public record InvitationCreated(UUID invitationId,String token,Instant expiresAt) {
  @Override public String toString() { return "InvitationCreated[invitationId="+invitationId+",token=REDACTED]"; }
 }
 public record Invitation(UUID invitationId,UUID unitId,String unitLabel,String role,String displayName,String status,
     Instant expiresAt,Instant createdAt,Instant acceptedAt) {}
 public record AcceptInvitation(String token) {
  @Override public String toString() { return "AcceptInvitation[token=REDACTED]"; }
 }
 public record InvitationAccepted(UUID tenantId,UUID membershipId,String status) {}
}
