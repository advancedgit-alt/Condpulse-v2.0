package br.com.condpulse.accesspolicy.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Leitura dos fatos reais de um sujeito para a simulação (V044). Implementada pelo módulo de direitos; chamada dentro da
 * transação da gestão, que já conferiu vínculos (memberships.manage) e AAL2.
 */
public interface SubjectFactsPort {
 PolicyModels.SubjectFacts facts(UUID tenant,UUID subject,UUID context,UUID area,UUID policyVersion,PolicyModels.PolicyDocument document,
   ZoneId zone,Instant at);
}
