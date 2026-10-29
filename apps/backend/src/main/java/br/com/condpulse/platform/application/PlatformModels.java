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
 public record Tenant(UUID tenantId,String name,String timeZone,boolean active,long version,Instant createdAt,
   Instant updatedAt,OnboardingReceipt onboarding) {}
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
}
