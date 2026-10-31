package br.com.condpulse.accessage.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Contratos da evidência de idade e dos parâmetros (AC-006, V039). A data de nascimento só entra; nunca sai. */
public final class AgeModels {
 private AgeModels() {}
 public record Settings(String leapDayRule,boolean minorFaceEnrollmentEnabled,int ageEvidenceValidityDays,long version) {}
 public record UpdateSettings(String leapDayRule,Boolean minorFaceEnrollmentEnabled,Integer ageEvidenceValidityDays,Long expectedVersion,String reason) {}
 /**
  * evidenceId do cliente para repetição. ENCRYPTED_BIRTH_DATE usa birthDate; AGE_PREDICATE_ATTESTATION usa minimumAge e/ou
  * maximumAge e predicateResult. A evidência válida anterior do sujeito é substituída (correção cadastral).
  */
 public record RecordEvidence(UUID evidenceId,String method,LocalDate birthDate,Integer minimumAge,Integer maximumAge,Boolean predicateResult,
   String verificationMethod,String sourceEvidenceRef) {}
 /** Projeção para a gestão: método, situação e validade, sem data nem documento. */
 public record Evidence(UUID evidenceId,UUID subjectId,String method,String verificationMethod,String status,Instant verifiedAt,Instant validUntil,
   UUID supersedesEvidenceId,long version) {}
 public record RevokeEvidence(String reason,Long expectedVersion) {}
 /** Resposta do predicado "N anos ou mais" na data civil do condomínio. */
 public record AgeCheck(UUID subjectId,int minimumAge,String outcome,LocalDate assessedOn,String timeZone,UUID evidenceId,Instant validUntil) {}
}
