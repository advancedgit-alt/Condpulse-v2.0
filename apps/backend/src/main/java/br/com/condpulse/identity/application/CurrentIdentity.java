package br.com.condpulse.identity.application;
import java.util.List;
import java.util.UUID;
/**
 * Conta, vínculos vigentes e, por condomínio dos vínculos, os módulos liberados (flag global ligada e condomínio
 * liberado pela central, V045) para o menu. {@code partnerContexts} (V049, ADR-0011) traz cada papel ativo de parceiro
 * com o painel de destino, as capacidades do papel e os condomínios que ele alcança agora, para a /entrada rotear. A API
 * de cada módulo confere de novo a cada requisição.
 */
public record CurrentIdentity(UUID accountId,List<Membership> memberships,List<TenantModules> tenantModules,
    List<PartnerContext> partnerContexts) {
 public CurrentIdentity {
  memberships=List.copyOf(memberships); tenantModules=List.copyOf(tenantModules);
  partnerContexts=partnerContexts==null?List.of():List.copyOf(partnerContexts);
 }
 public CurrentIdentity(UUID accountId,List<Membership> memberships,List<TenantModules> tenantModules) {
  this(accountId,memberships,tenantModules,List.of());
 }
 public CurrentIdentity(UUID accountId,List<Membership> memberships) { this(accountId,memberships,List.of()); }
 /** Condomínio alcançado pelo papel de parceiro (contrato ativo e, para operador/supervisor, escala vigente). */
 public record PartnerTenant(UUID tenantId,String tenantName,String timeZone) {}
 /** Papel ativo em parceiro ativo; panel é o segmento da rota /p/{partnerId}/{panel} (PRD §4.1). */
 public record PartnerContext(UUID memberId,UUID partnerId,String partnerName,String role,String panel,List<String> capabilities,
   List<PartnerTenant> tenants) {
  public PartnerContext { capabilities=List.copyOf(capabilities); tenants=List.copyOf(tenants); }
 }
 public record Membership(UUID membershipId,UUID tenantId,String tenantName,String timeZone,UUID unitId,String role) {}
 /** Módulos efetivamente liberados no condomínio, em ordem estável. */
 public record TenantModules(UUID tenantId,List<String> enabled) {
  public TenantModules { enabled=List.copyOf(enabled); }
 }
}
