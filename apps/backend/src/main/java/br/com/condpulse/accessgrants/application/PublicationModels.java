package br.com.condpulse.accessgrants.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publicação dos direitos por destino (AC-012/AC-013, V046). Cada trabalho é o documento compilado pelo banco para um
 * sujeito num equipamento: desejo a aplicar, nunca sucesso físico. Só o executor (worker/gateway) relata ACK,
 * APPLIED_UNVERIFIED, CONFIRMED (leitura de volta igual), PARTIAL, FAILED ou UNKNOWN; o lote só está concluído quando
 * todos os destinos estão CONFIRMED.
 */
public final class PublicationModels {
 private PublicationModels() {}

 public static final List<String> STATES=List.of("PENDING","ACK","APPLIED_UNVERIFIED","CONFIRMED","PARTIAL","FAILED","UNKNOWN","SUPERSEDED");

 public record Publication(UUID publicationId,UUID operationId,UUID subjectId,UUID deviceId,String origin,UUID revocationMarkerId,String action,
   int priority,long desiredVersion,long rightsGeneration,long subjectRestrictionEpoch,int installationRevision,int entries,String documentHash,
   String state,String confirmationLevel,String resultCode,int attempts,Instant reportedAt,Instant confirmedAt,UUID supersededBy,Instant createdAt,
   long version) {}
 /**
  * Resumo sobre todas as linhas do filtro (nunca só a página). byState conta as linhas; destinations e confirmedDestinations
  * seguem cada destino até o trabalho mais novo (sucessor de superado, em qualquer operação). allConfirmed só quando o
  * trabalho atual de todos os destinos tocados está CONFIRMED.
  */
 public record Summary(Map<String,Integer> byState,int total,int destinations,int confirmedDestinations,boolean allConfirmed) {}
 public record PublicationPage(List<Publication> publications,Summary summary,boolean truncated) {}
 public record PlanRequest(List<UUID> subjectIds) {}
 public record Failure(UUID subjectId,String reason) {}
 /** Resultado do planejamento: trabalhos criados e sujeitos recusados pelo banco (registrados por item, sem abortar o lote). */
 public record Plan(UUID operationId,int subjects,int created,Map<String,Integer> failedByReason,List<Failure> failedSample) {}
}
