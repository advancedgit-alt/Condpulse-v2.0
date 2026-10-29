package br.com.condpulse.tenancy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos da importação de unidades em staging (CP-016). Sem pessoas, vínculos ou dados de outro condomínio. */
public final class UnitImportModels {
 private UnitImportModels() {}
 public record Open(String fileName,String content) {}
 public record Batch(UUID batchId,String source,String fileHash,int mappingVersion,String state,int totalRows,int validRows,
   int invalidRows,int duplicateRows,boolean partial,Integer createdUnits,Integer existingUnits,Integer createdBlocks,
   Instant createdAt,Instant decidedAt,long version) {}
 /**
  * Linha do lote. {@code alreadyExists} é calculado na leitura (prévia): a unidade já existe no condomínio e não será
  * criada de novo. {@code applied} e {@code unitId} só existem depois da aprovação.
  */
 public record Row(int line,String block,String unit,String externalId,String outcome,String errorCode,boolean alreadyExists,
   String applied,UUID unitId) {}
 public record Detail(Batch batch,List<Row> rows) {
  public Detail { rows=List.copyOf(rows); }
 }
 public record BatchPage(List<Batch> batches) {
  public BatchPage { batches=List.copyOf(batches); }
 }
 public record Opened(Detail detail,boolean created) {}
 public record Approve(Long expectedVersion,Boolean acceptPartial) {}
 public record Discard(Long expectedVersion) {}
 public record Decided(Detail detail,boolean applied) {}
}
