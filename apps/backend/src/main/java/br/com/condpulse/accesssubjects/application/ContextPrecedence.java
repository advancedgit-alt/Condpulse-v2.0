package br.com.condpulse.accesssubjects.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolução determinística de contextos (AC-005). Regras:
 * <ul>
 * <li>Sujeito não ACTIVE (bloqueio transversal, exclusão, arquivo): nenhum contexto elegível.</li>
 * <li>Cada contexto é avaliado isoladamente: um convite vencido não cancela residência válida e a residência não amplia
 * o convite. Não há união de contextos; o motor de políticas avalia um contexto por vez nesta ordem.</li>
 * <li>Ordem fixa: RESIDENT, DEPENDENT, OWNER_NONRESIDENT, EMPLOYEE, SERVICE_PROVIDER, VISITOR, EMERGENCY_RESPONDER; depois
 * o fim mais distante (sem fim primeiro) e, por último, o id — o mesmo conjunto sempre produz a mesma ordem.</li>
 * </ul>
 */
public final class ContextPrecedence {
 private ContextPrecedence() {}
 public static final List<String> ORDER=List.of("RESIDENT","DEPENDENT","OWNER_NONRESIDENT","EMPLOYEE","SERVICE_PROVIDER","VISITOR",
  "EMERGENCY_RESPONDER");

 /** Contexto como lido do banco; sourceCurrent é cp.subject_context_current sem o estado do sujeito. */
 public record Candidate(UUID contextId,String kind,String status,Instant validFrom,Instant validUntil,boolean sourceCurrent) {
  public Candidate { Objects.requireNonNull(contextId); Objects.requireNonNull(kind); Objects.requireNonNull(status); Objects.requireNonNull(validFrom); }
 }

 public static List<UUID> eligible(String subjectStatus,List<Candidate> contexts,Instant now) {
  Objects.requireNonNull(now);
  if(!"ACTIVE".equals(subjectStatus) || contexts==null) return List.of();
  return contexts.stream()
   .filter(c->"ACTIVE".equals(c.status()) && c.sourceCurrent() && ORDER.contains(c.kind()) && !c.validFrom().isAfter(now)
    && (c.validUntil()==null || c.validUntil().isAfter(now)))
   .sorted(Comparator.comparingInt((Candidate c)->ORDER.indexOf(c.kind()))
    .thenComparing(Candidate::validUntil,Comparator.nullsFirst(Comparator.<Instant>reverseOrder()))
    .thenComparing(Candidate::contextId))
   .map(Candidate::contextId).toList();
 }
}
