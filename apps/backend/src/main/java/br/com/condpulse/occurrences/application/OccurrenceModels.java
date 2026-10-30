package br.com.condpulse.occurrences.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ocorrências da portaria (CP-027): registro interno da equipe; nunca exibido a moradores. */
public final class OccurrenceModels {
 private OccurrenceModels() {}
 public static final Set<String> CATEGORIES=Set.of("EQUIPMENT","SECURITY","MAINTENANCE","CLEANING","NOISE","OTHER");
 public record Occurrence(UUID occurrenceId,String category,String title,String description,UUID unitId,String unitLabel,String state,
   String createdByName,boolean createdByMe,Instant createdAt,String resolution,String resolvedByName,Instant resolvedAt,long version) {}
 public record Page(List<Occurrence> occurrences,long open,boolean truncated) {
  public Page { occurrences=List.copyOf(occurrences); }
 }
 /** occurrenceId gerado pela tela: repetir o envio com o mesmo identificador devolve o registro, sem duplicar. */
 public record Create(UUID occurrenceId,String category,String title,String description,UUID unitId) {}
 public record Created(Occurrence occurrence,boolean replayed) {}
 public record Resolve(String resolution,Long expectedVersion) {}
}
