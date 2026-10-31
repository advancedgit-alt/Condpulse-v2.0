package br.com.condpulse.accessgovernance.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos HTTP das concessões técnicas (AC-003). Nenhum campo carrega segredo, e-mail ou dado residencial. */
public final class TechnicalGrantModels {
 private TechnicalGrantModels() {}
 /** grantId gerado pelo cliente: repetir o mesmo pedido devolve a concessão já gravada (applied=false). */
 public record IssueTechnicalGrant(UUID grantId,UUID accountId,String roleCode,List<String> scopes,Integer validDays,String reason) {}
 public record RevokeTechnicalGrant(Long expectedVersion,String reason) {}
 public record TechnicalGrant(UUID grantId,UUID tenantId,UUID accountId,String roleCode,List<String> scopes,Instant validFrom,
   Instant validUntil,String grantedVia,String status,boolean current,Instant revokedAt,long version) {}
 public record TechnicalGrantIssued(TechnicalGrant grant,boolean applied) {}
 public record TechnicalGrantPage(List<TechnicalGrant> grants) {}
 /** Sessão técnica da própria conta no condomínio: escopos vigentes e fim da concessão mais longa. */
 public record TechnicalSession(UUID tenantId,List<String> scopes,Instant validUntil) {}
}
