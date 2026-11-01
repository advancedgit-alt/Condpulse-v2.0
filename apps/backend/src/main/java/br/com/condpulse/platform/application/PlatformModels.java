package br.com.condpulse.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos do painel master: somente dados da plataforma, sem conteúdo residencial dos condomínios. */
public final class PlatformModels {
 private PlatformModels() {}

 /** Papel de plataforma da própria conta; sem AAL2 as capacidades ficam vazias e mfaRequired indica o próximo passo. */
 public record PlatformSession(UUID accountId,String role,List<String> capabilities,boolean mfaRequired) {
  public PlatformSession { capabilities=List.copyOf(capabilities); }
 }
 /** Recibo agregado do onboarding operacional (contagens), quando o condomínio veio desse procedimento. */
 public record OnboardingReceipt(UUID operationId,Integer accounts,Integer units,Integer memberships,Integer amenities,
   Instant completedAt) {}
 /**
  * Uso agregado do condomínio, sem conteúdo residencial: unidades ativas, vínculos vigentes, gestores vigentes e a
  * última atividade conhecida (maior data entre o próprio cadastro, vínculos, unidades e a trilha da plataforma).
  */
 public record TenantUsage(long activeUnits,long activeMemberships,long activeManagers,Instant lastActivityAt) {}
 /** Situação da assinatura CondPulse (fase 5), somente com o financeiro ligado; null quando desligado. */
 public record TenantBilling(boolean activeSubscription,long openCharges,long overdueCharges) {}
 /** origin: DEMO (condomínio demo sintético, V055), ONBOARDING (manifesto operacional), PLATFORM (criado no painel) ou null (sem recibo). */
 public record Tenant(UUID tenantId,String name,String timeZone,boolean active,long version,Instant createdAt,
   Instant updatedAt,OnboardingReceipt onboarding,String origin,TenantUsage usage,TenantBilling billing) {
  public Tenant withBilling(TenantBilling value) {
   return new Tenant(tenantId,name,timeZone,active,version,createdAt,updatedAt,onboarding,origin,usage,value);
  }
 }
 public record TenantPage(List<Tenant> tenants,boolean truncated) {
  public TenantPage { tenants=List.copyOf(tenants); }
 }
 /** Renomear, suspender ou reativar pela versão lida; o motivo é obrigatório e vai para a auditoria da plataforma. */
 public record UpdateTenant(String name,Boolean active,Long expectedVersion,String reason) {}
 /** applied=false: o condomínio já estava no estado pedido; nada foi gravado e o motivo não foi registrado. */
 public record TenantChange(Tenant tenant,boolean applied) {}
 public record AuditEvent(UUID eventId,UUID actorId,String action,UUID tenantId,String reason,Map<String,String> details,
   Instant createdAt) {
  public AuditEvent { details=Map.copyOf(details); }
 }
 public record AuditPage(List<AuditEvent> events,String nextCursor) {
  public AuditPage { events=List.copyOf(events); }
 }
 /** Gestor vigente ou pendente do condomínio (somente nome, situação e datas; sem conta ou contato). */
 public record Manager(UUID membershipId,String displayName,String status,Instant since) {}
 /** Convite de gestor emitido pela plataforma; o e-mail e o token nunca voltam do banco. */
 public record ManagerInvitation(UUID invitationId,String displayName,String status,Instant createdAt,Instant expiresAt,
   Instant acceptedAt,Instant revokedAt) {}
 public record TenantDetail(Tenant tenant,List<Manager> managers,List<ManagerInvitation> managerInvitations,
   List<AuditEvent> recentEvents) {
  public TenantDetail { managers=List.copyOf(managers); managerInvitations=List.copyOf(managerInvitations); recentEvents=List.copyOf(recentEvents); }
 }
 public record ManagerInput(String displayName,String email,Integer validDays) {}
 /** Área inicial opcional: mesmas regras e validação da gestão local (AmenityManagementModels). */
 public record CreateTenant(String name,String timeZone,List<String> units,ManagerInput manager,
   br.com.condpulse.reservations.application.AmenityManagementModels.AmenityInput amenity,String reason) {}
 /**
  * Resultado da criação. applied=false: repetição da mesma chave com o mesmo pedido; o condomínio já existia e o token
  * do convite não é mostrado de novo (emitir outro convite revoga o anterior). invitationToken só existe na criação.
  */
 public record TenantCreated(Tenant tenant,boolean applied,UUID invitationId,String invitationToken,Instant invitationExpiresAt) {}
 public record IssueManagerInvitation(String displayName,String email,Integer validDays,String reason) {}
 public record ManagerInvitationIssued(UUID invitationId,String token,Instant expiresAt) {}
 public record RevokeManagerInvitation(String reason) {}
 public record AcceptManagerInvitation(String token) {}
}
