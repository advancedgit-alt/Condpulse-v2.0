package br.com.condpulse.tenancy.application;

import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;

/**
 * Snapshot de vínculos próprios, resolvido novamente em cada transação de negócio. {@code partnerRoles} são os papéis de
 * parceiro que alcançam este condomínio agora (V049: contrato ativo, papel ativo e, para operador e supervisor, escala
 * vigente), usados só quando a conta não tem vínculo no condomínio.
 */
public record TenantContext(UUID accountId,UUID tenantId,ZoneId timeZone,List<Membership> memberships,
    AssuranceLevel assurance,Set<String> partnerRoles) {
 public TenantContext {
  Objects.requireNonNull(accountId); Objects.requireNonNull(tenantId); Objects.requireNonNull(timeZone);
  Objects.requireNonNull(assurance);
  memberships=List.copyOf(memberships);
  partnerRoles=partnerRoles==null?Set.of():Set.copyOf(partnerRoles);
 }
 public TenantContext(UUID accountId,UUID tenantId,ZoneId timeZone,List<Membership> memberships,AssuranceLevel assurance) {
  this(accountId,tenantId,timeZone,memberships,assurance,Set.of());
 }
 /** Fluxos residenciais existentes não dependem do segundo fator. */
 public TenantContext(UUID accountId,UUID tenantId,ZoneId timeZone,List<Membership> memberships) {
  this(accountId,tenantId,timeZone,memberships,AssuranceLevel.AAL1);
 }

 public void requireBookableUnit(UUID unitId) {
  if(unitId==null || memberships.stream().noneMatch(m->unitId.equals(m.unitId()) && m.canCreateReservation()))
   throw denied();
 }
 public void requireCancellableUnit(UUID unitId) {
  if(unitId==null || memberships.stream().noneMatch(m->unitId.equals(m.unitId()) && m.canCancelReservation()))
   throw denied();
 }
 /** Capacidades concedidas pelos papéis vigentes, antes de considerar o segundo fator. */
 public Set<StaffCapability> grantedCapabilities() {
  var granted=EnumSet.noneOf(StaffCapability.class);
  for(var membership:memberships) granted.addAll(StaffCapability.forRole(membership.role()));
  return Collections.unmodifiableSet(granted);
 }
 /** Capacidades utilizáveis nesta transação: exigem papel vigente e AAL2. */
 public Set<StaffCapability> effectiveCapabilities() {
  return assurance==AssuranceLevel.AAL2?grantedCapabilities():Set.of();
 }
 /** Capacidades de parceiro dos papéis que alcançam o condomínio (V049), antes do segundo fator. */
 public Set<br.com.condpulse.partners.application.PartnerCapability> grantedPartnerCapabilities() {
  return Collections.unmodifiableSet(br.com.condpulse.partners.application.PartnerCapability.forRoles(partnerRoles));
 }
 /** Utilizáveis nesta transação: papel de parceiro com alcance e AAL2 (o banco confere de novo). */
 public Set<br.com.condpulse.partners.application.PartnerCapability> effectivePartnerCapabilities() {
  return assurance==AssuranceLevel.AAL2?grantedPartnerCapabilities():Set.of();
 }
 public void requirePartnerCapability(br.com.condpulse.partners.application.PartnerCapability capability) {
  Objects.requireNonNull(capability);
  if(!grantedPartnerCapabilities().contains(capability))
   throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
  if(assurance!=AssuranceLevel.AAL2)
   throw new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator de autenticação para continuar.");
 }
 public void requireCapability(StaffCapability capability) {
  Objects.requireNonNull(capability);
  if(!grantedCapabilities().contains(capability))
   throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
  // Papel correto com sessão AAL1 recebe orientação explícita; nenhuma alteração ocorre.
  if(assurance!=AssuranceLevel.AAL2)
   throw new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator de autenticação para continuar.");
 }
 private BusinessProblem denied() {
  return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }

 public record Membership(UUID membershipId,UUID unitId,String role,
     boolean reservationCreateAllowed,boolean reservationCancelAllowed,boolean parcelViewAllowed) {
  public Membership { Objects.requireNonNull(membershipId); Objects.requireNonNull(role); }
  /** Vínculo sem permissão de encomendas concedida ao dependente. */
  public Membership(UUID membershipId,UUID unitId,String role,boolean reservationCreateAllowed,boolean reservationCancelAllowed) {
   this(membershipId,unitId,role,reservationCreateAllowed,reservationCancelAllowed,false);
  }
  public boolean canCreateReservation() {
   return unitId!=null && (isResident() || ("DEPENDENT".equals(role) && reservationCreateAllowed));
  }
  public boolean canCancelReservation() {
   return unitId!=null && (isResident() || ("DEPENDENT".equals(role) && reservationCancelAllowed));
  }
  /** Encomendas da unidade: morador e responsável; dependente só com permissions.parcels.view (o banco repete a regra). */
  public boolean canViewParcels() {
   return unitId!=null && (isResident() || ("DEPENDENT".equals(role) && parcelViewAllowed));
  }
  private boolean isResident() { return "RESIDENT".equals(role) || "RESPONSIBLE".equals(role); }
 }
}
