package br.com.condpulse.partners.application;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;

/** Papéis ativos da própria conta no parceiro do contexto, resolvidos de novo em cada transação. */
public record PartnerContext(UUID accountId,UUID partnerId,Set<String> roles,AssuranceLevel assurance) {
 public PartnerContext {
  Objects.requireNonNull(accountId); Objects.requireNonNull(partnerId); Objects.requireNonNull(assurance);
  roles=Set.copyOf(roles);
 }
 /** Capacidades de nível parceiro concedidas pelos papéis (sem as de operação no condomínio). */
 public Set<PartnerCapability> grantedCapabilities() {
  var granted=EnumSet.noneOf(PartnerCapability.class);
  for(var capability:PartnerCapability.forRoles(roles)) if(!capability.tenantScoped()) granted.add(capability);
  return granted;
 }
 /** Utilizáveis nesta transação: papel vigente e AAL2. */
 public Set<PartnerCapability> effectiveCapabilities() {
  return assurance==AssuranceLevel.AAL2?grantedCapabilities():Set.of();
 }
 public void require(PartnerCapability capability) {
  Objects.requireNonNull(capability);
  if(!grantedCapabilities().contains(capability))
   throw new BusinessProblem("PARTNER_ACCESS_DENIED",403,"Esta ação não está disponível para o seu papel no parceiro.");
  if(assurance!=AssuranceLevel.AAL2)
   throw new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator de autenticação para continuar.");
 }
}
