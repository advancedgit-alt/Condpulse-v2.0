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
 /**
  * Convite da unidade. state efetivo, pelo relógio do banco na leitura: SCHEDULED (ainda não começou), ACTIVE (vale
  * agora), REVOKED ou EXPIRED (vigência encerrada). Fora do período o convite não autoriza a entrada.
  */
 public record Permit(UUID permitId,UUID unitId,String unitLabel,String blockName,String visitorName,String documentLast4,String purpose,
   Instant validFrom,Instant validUntil,String state,boolean createdByMe,Instant revokedAt,String revokeReason,long version,Instant createdAt,
   boolean shareLinkActive,UUID reservationId,UUID ruleId) {}
 public record PermitPage(List<Permit> permits,boolean truncated) {
  public PermitPage { permits=List.copyOf(permits); }
 }
 public record CreatePermit(String visitorName,String documentLast4,String purpose,Instant validFrom,Instant validUntil) {}
 /**
  * Prestador recorrente (CP-039). A regra não autoriza sozinha: gera janelas (convites) no fuso do condomínio; state
  * efetivo ACTIVE, REVOKED ou EXPIRED (período terminado). nextWindow*: próxima janela em vigor ou agendada, se houver.
  */
 public record VisitRule(UUID ruleId,UUID unitId,String visitorName,String documentLast4,String purpose,List<Integer> weekDays,
   String startTime,String endTime,java.time.LocalDate validFrom,java.time.LocalDate validUntil,String state,boolean createdByMe,
   Instant revokedAt,String revokeReason,long version,Instant createdAt,Instant nextWindowFrom,Instant nextWindowUntil) {
  public VisitRule { weekDays=List.copyOf(weekDays); }
 }
 public record RulePage(List<VisitRule> rules,boolean truncated) {
  public RulePage { rules=List.copyOf(rules); }
 }
 public record CreateRule(String visitorName,String documentLast4,String purpose,List<Integer> weekDays,String startTime,String endTime,
   java.time.LocalDate validFrom,java.time.LocalDate validUntil) {}
 public record RevokeRule(String reason,Long expectedVersion) {}
 public record RuleMutation(VisitRule rule,boolean replayed,int status) {}
 /** Convidado de reserva (CP-038): só a pessoa; a janela e a finalidade vêm do evento. */
 public record AddGuest(String visitorName,String documentLast4) {}
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
 /**
  * Link compartilhável (CP-037): o token bruto sai somente nesta resposta e nunca é guardado; o banco tem só o hash.
  * Vale até a revogação do link ou do convite, ou até o fim da vigência do convite.
  */
 public record PermitLink(String token,Instant validUntil) {
  @Override public String toString() { return "PermitLink[token=REDACTED]"; }
 }
 public record PermitLinkView(String token) {
  @Override public String toString() { return "PermitLinkView[token=REDACTED]"; }
 }
 /** O que quem abre o link vê: sem unidade, sem identificadores e só o primeiro nome da pessoa convidada. */
 public record PublicPermit(String tenantName,String visitorFirstName,String purpose,Instant validFrom,Instant validUntil,String timeZone,
   String state) {}
 public record ArrivalMutation(Arrival arrival,boolean replayed,int status) {}
}
