package br.com.condpulse.tenancy.application;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;

/** Snapshot de vínculos próprios, resolvido novamente em cada transação de negócio. */
public record TenantContext(UUID accountId,UUID tenantId,ZoneId timeZone,List<Membership> memberships) {
 public TenantContext {
  Objects.requireNonNull(accountId); Objects.requireNonNull(tenantId); Objects.requireNonNull(timeZone);
  memberships=List.copyOf(memberships);
 }

 public void requireBookableUnit(UUID unitId) {
  if(unitId==null || memberships.stream().noneMatch(m->unitId.equals(m.unitId()) && m.canCreateReservation()))
   throw denied();
 }
 public void requireCancellableUnit(UUID unitId) {
  if(unitId==null || memberships.stream().noneMatch(m->unitId.equals(m.unitId()) && m.canCancelReservation()))
   throw denied();
 }
 private BusinessProblem denied() {
  return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }

 public record Membership(UUID membershipId,UUID unitId,String role,
     boolean reservationCreateAllowed,boolean reservationCancelAllowed) {
  public Membership { Objects.requireNonNull(membershipId); Objects.requireNonNull(role); }
  public boolean canCreateReservation() {
   return unitId!=null && (isResident() || ("DEPENDENT".equals(role) && reservationCreateAllowed));
  }
  public boolean canCancelReservation() {
   return unitId!=null && (isResident() || ("DEPENDENT".equals(role) && reservationCancelAllowed));
  }
  private boolean isResident() { return "RESIDENT".equals(role) || "RESPONSIBLE".equals(role); }
 }
}
