package br.com.condpulse.visits.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registro manual de entrada e saída de visitantes (CP-028). Cada registro parte de uma entrada (MANUAL_IN, origem
 * CONCIERGE): nunca é passagem detectada por equipamento. Saída e correção são eventos adicionais; nada é alterado nem
 * apagado. Respostas sem contas da equipe: a tela só sabe se foi a própria conta.
 */
public final class AccessModels {
 private AccessModels() {}
 /** Correção humana de uma entrada ou saída, com motivo. */
 public record Correction(UUID eventId,Instant correctedAt,String reason,boolean recordedByMe) {}
 /** Saída registrada pela portaria (MANUAL_OUT), possivelmente corrigida. */
 public record Exit(UUID eventId,Instant exitedAt,String note,boolean recordedByMe,Correction correction) {}
 /**
  * Entrada registrada pela portaria e o que veio depois. eventType é sempre MANUAL_IN e source CONCIERGE; evidence:
  * DOCUMENT_CHECKED ou RECOGNIZED_BY_UNIT. Base: arrivalId (chegada autorizada) ou permitId (convite em vigor).
  */
 public record AccessRecord(UUID entryId,UUID unitId,String unitLabel,String blockName,String visitorName,String documentLast4,
   UUID arrivalId,UUID permitId,String eventType,String source,String evidence,String note,Instant enteredAt,boolean recordedByMe,
   Correction correction,Exit exit) {}
 public record RecordPage(List<AccessRecord> records,boolean truncated) {
  public RecordPage { records=List.copyOf(records); }
 }
 /** Entrada: exatamente uma base (chegada autorizada ou convite em vigor) e a conferência feita. */
 public record RecordEntry(UUID arrivalId,UUID permitId,String evidence,String note) {}
 public record RecordExit(String note) {}
 public record Correct(String reason) {}
 public record RecordMutation(AccessRecord record,boolean replayed,int status) {}
}
