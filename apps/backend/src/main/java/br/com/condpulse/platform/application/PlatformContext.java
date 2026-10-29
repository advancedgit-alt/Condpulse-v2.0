package br.com.condpulse.platform.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;

/** Papel de plataforma vigente da própria conta, resolvido novamente em cada transação. */
public record PlatformContext(UUID accountId,String role,AssuranceLevel assurance) {
 public PlatformContext {
  Objects.requireNonNull(accountId); Objects.requireNonNull(role); Objects.requireNonNull(assurance);
 }
 /** Capacidades utilizáveis nesta transação: exigem papel vigente e AAL2. */
 public Set<PlatformCapability> effectiveCapabilities() {
  return assurance==AssuranceLevel.AAL2 ? PlatformCapability.forRole(role) : Set.of();
 }
 public void require(PlatformCapability capability) {
  Objects.requireNonNull(capability);
  if(!PlatformCapability.forRole(role).contains(capability))
   throw new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
  if(assurance!=AssuranceLevel.AAL2)
   throw new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator de autenticação para continuar.");
 }
}
