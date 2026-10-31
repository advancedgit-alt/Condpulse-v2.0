package br.com.condpulse.identity.application;
import java.util.List;
import java.util.UUID;
/**
 * Conta, vínculos vigentes e, por condomínio dos vínculos, os módulos liberados (flag global ligada e condomínio
 * liberado pela central, V045) para o menu. A API de cada módulo confere de novo a cada requisição.
 */
public record CurrentIdentity(UUID accountId,List<Membership> memberships,List<TenantModules> tenantModules) {
 public CurrentIdentity {
  memberships=List.copyOf(memberships); tenantModules=List.copyOf(tenantModules);
 }
 public CurrentIdentity(UUID accountId,List<Membership> memberships) { this(accountId,memberships,List.of()); }
 public record Membership(UUID membershipId,UUID tenantId,String tenantName,String timeZone,UUID unitId,String role) {}
 /** Módulos efetivamente liberados no condomínio, em ordem estável. */
 public record TenantModules(UUID tenantId,List<String> enabled) {
  public TenantModules { enabled=List.copyOf(enabled); }
 }
}
