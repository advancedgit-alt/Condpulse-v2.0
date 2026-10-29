package br.com.condpulse.visits.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visitas (CP-025) e chegada sem convite (CP-026). Respostas sem contas da equipe nem de outros moradores: quem criou,
 * revogou ou decidiu fica na auditoria; a tela só sabe se foi a própria conta.
 */
public final class VisitModels {
 private VisitModels() {}
 /** Convite da unidade. state efetivo: ACTIVE, REVOKED ou EXPIRED (vigência encerrada, calculada na leitura). */
 public record Permit(UUID permitId,UUID unitId,String unitLabel,String blockName,String visitorName,String documentLast4,String purpose,
   Instant validFrom,Instant validUntil,String state,boolean createdByMe,Instant revokedAt,String revokeReason,long version,Instant createdAt) {}
 public record PermitPage(List<Permit> permits,boolean truncated) {
  public PermitPage { permits=List.copyOf(permits); }
 }
 public record CreatePermit(String visitorName,String documentLast4,String purpose,Instant validFrom,Instant validUntil) {}
 public record RevokePermit(String reason,Long expectedVersion) {}
 /**
  * Chegada sem convite. state efetivo: WAITING, APPROVED, DENIED, CANCELLED ou EXPIRED (prazo vencido sem decisão).
  * decisionSource: APP (unidade pelo aplicativo), INTERCOM/IN_PERSON (confirmação assistida pela portaria) ou
  * CONCIERGE (encerrada pela portaria).
  */
 public record Arrival(UUID arrivalId,UUID unitId,String unitLabel,String blockName,String visitorName,String documentLast4,UUID permitId,
   String state,Instant expiresAt,Instant decidedAt,String decisionSource,String decisionNote,boolean decidedByMe,long version,Instant createdAt) {}
 public record ArrivalPage(List<Arrival> arrivals,boolean truncated) {
  public ArrivalPage { arrivals=List.copyOf(arrivals); }
 }
 public record RegisterArrival(UUID unitId,String visitorName,String documentLast4,UUID permitId) {}
 /** Resposta da unidade pelo aplicativo: APPROVE ou DENY. */
 public record Decision(String decision) {}
 /** Confirmação assistida pela portaria (interfone ou presencial), com a origem anotada. */
 public record AssistedDecision(String decision,String source,String note) {}
 public record CancelArrival(String reason) {}
 public record PermitMutation(Permit permit,boolean replayed,int status) {}
 public record ArrivalMutation(Arrival arrival,boolean replayed,int status) {}
}
