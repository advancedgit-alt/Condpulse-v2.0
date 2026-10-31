package br.com.condpulse.accessgrants.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direitos derivados e marcas de revogação (AC-009/AC-015, V044). ACTIVE é desejo lógico; current diz se o direito vale
 * agora (origem, contexto, sujeito, revisão e revogações conferidos pelo banco). Nada aqui confirma porta (V045).
 */
public final class GrantModels {
 private GrantModels() {}

 public record Grant(UUID grantId,UUID subjectId,UUID contextId,UUID areaId,UUID policyId,UUID policyVersionId,String originKind,UUID originId,
   long originVersion,Instant validFrom,Instant validUntil,long generation,long subjectRestrictionEpoch,String status,String statusReasonCode,
   UUID revocationMarkerId,boolean current,long version) {}
 public record GrantPage(List<Grant> grants,boolean truncated) {}
 public record DeriveRequest(UUID policyVersionId) {}
 public record Skip(UUID contextId,String reason) {}
 /**
 * Resumo da derivação: contagens por motivo e amostra (até 50) de contextos não atendidos; nada truncado em silêncio.
 * publication: trabalhos por destino criados na mesma transação (V045), nunca sucesso físico.
 */
 public record Derivation(UUID policyVersionId,int created,int unchanged,int superseded,int expired,int revoked,Map<String,Integer> skippedByReason,
   List<Skip> skippedSample,PublicationModels.Plan publication) {}
 public record RevokeGrant(String reason,Long expectedVersion) {}
 public record Marker(UUID markerId,String targetKind,UUID subjectId,UUID contextId,UUID grantId,List<String> coveredMethods,long revokedThroughGeneration,
   long subjectRestrictionEpoch,Instant effectiveAt,String reasonCode,long journalSequence,String externalDurabilityStatus) {}
 public record MarkerPage(List<Marker> markers,boolean truncated) {}
}
