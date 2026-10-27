package br.com.condpulse.identity.application;
import java.util.List;
import java.util.UUID;
public record CurrentIdentity(UUID accountId,List<Membership> memberships) {
 public CurrentIdentity { memberships=List.copyOf(memberships); }
 public record Membership(UUID membershipId,UUID tenantId,String tenantName,String timeZone,UUID unitId,String role) {}
}
