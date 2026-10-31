package br.com.condpulse.accesssubjects.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos HTTP de sujeitos e contextos (AC-005, V038). Sem CPF, documento, biometria ou e-mail. */
public final class SubjectModels {
 private SubjectModels() {}
 public record SubjectContext(UUID contextId,String contextKind,String sourceKind,UUID membershipId,UUID permitId,UUID unitId,
   UUID guardianSubjectId,Instant validFrom,Instant validUntil,String status,boolean current,long version) {}
 /** eligibleContextIds: contextos vigentes na ordem determinística de avaliação (nenhuma união permissiva). */
 public record Subject(UUID subjectId,String originKind,UUID accountId,UUID visitorId,String status,long restrictionEpoch,
   String externalRef,List<SubjectContext> contexts,List<UUID> eligibleContextIds,long version) {}
 public record SubjectPage(List<Subject> subjects,boolean truncated) {}
 /** revokedKept: origens com contexto revogado que a materialização não recria (exige reautorização explícita). */
 public record Materialized(int subjectsCreated,int contextsCreated,int contextsSuperseded,int revokedKept) {}
 public record Reauthorize(String reason) {}
 public record Block(String reason,Long expectedVersion) {}
 public record Unblock(Long expectedVersion) {}
 public record CloseContext(String reason,Long expectedVersion) {}
 public record FunctionalAuthorization(UUID contextId,String contextKind,String reference,Instant validUntil) {}
}
