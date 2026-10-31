package br.com.condpulse.accessgovernance.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;

/**
 * Contexto técnico resolvido de novo em cada transação: conta ativa, condomínio ativo, concessões vigentes e AAL2. Os
 * escopos são a união das concessões vigentes da própria conta naquele condomínio; nunca valem em outro condomínio.
 */
public record TechnicalContext(UUID accountId,UUID tenantId,ZoneId timeZone,Set<TechnicalScope> scopes,Instant validUntil) {
 public TechnicalContext {
  Objects.requireNonNull(accountId); Objects.requireNonNull(tenantId); Objects.requireNonNull(timeZone);
  scopes=Set.copyOf(scopes); Objects.requireNonNull(validUntil);
 }
 public void require(TechnicalScope scope) {
  Objects.requireNonNull(scope);
  if(!scopes.contains(scope))
   throw new BusinessProblem("TECHNICAL_SCOPE_REQUIRED",403,"Sua concessão técnica neste condomínio não inclui esta operação.");
 }
}
