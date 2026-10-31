package br.com.condpulse.accesspolicy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos de política de ingresso (AC-007/AC-008, V043). O documento é fechado (schema 1.0): nenhum campo aceita SQL,
 * script, rota de fabricante ou segredo. Validação e simulação nunca criam comando físico.
 */
public final class PolicyModels {
 private PolicyModels() {}

 public record WeeklyWindow(List<String> weekdays,String startLocalTime,String endLocalTime,Boolean crossesMidnight) {}
 public record ReservationRule(String mode,Integer entryGraceBeforeSeconds,Integer entryGraceAfterSeconds) {}
 public record CompanionRule(String mode,Integer maximumPairingSeconds) {}
 public record OfflineRule(String mode,Integer maximumStalenessSeconds,Boolean requireLocalExpiry) {}
 public record FailureRule(String onMissingAttribute,String onServiceFailure) {}
 /** Documento de regra de ingresso (PolicyDocument do pacote v1.1). */
 public record PolicyDocument(String schemaVersion,String name,UUID areaId,String timezone,String ruleCombination,List<String> eligibleContexts,
   String visitorPolicy,Integer minimumAgeYears,Boolean requireVerifiedAgeEvidence,List<WeeklyWindow> weeklyWindows,List<UUID> holidayCalendarIds,
   String calendarPrecedence,ReservationRule reservation,CompanionRule companion,OfflineRule offline,FailureRule failure,String antiPassbackMode,
   Integer maximumOccupancy,Boolean emergencyEgressIndependent,Integer maximumAgeYears,String defaultEffect) {}

 public record Policy(UUID policyId,UUID areaId,String name,String status,UUID currentPublishedVersionId,Instant updatedAt,long version) {}
 public record PolicyPage(List<Policy> policies) {}
 public record CreatePolicy(UUID policyId,UUID areaId,String name) {}
 public record UpdatePolicy(String name,String status,Long expectedVersion) {}

 public record PolicyVersion(UUID versionId,UUID policyId,int revisionNumber,String state,PolicyDocument document,String canonicalHash,
   String changeReason,UUID validationId,UUID authoredBy,UUID approvedBy,Instant approvedAt,String rejectedReason,Instant createdAt,long version) {}
 public record PolicyVersionPage(List<PolicyVersion> versions) {}
 public record CreateVersion(UUID versionId,PolicyDocument document,String changeReason) {}
 public record VersionAction(Long expectedVersion) {}
 public record RejectVersion(String reason,Long expectedVersion) {}

 public record ValidateRequest(UUID policyVersionId) {}
 public record ValidationIssue(String code,String pointer,String message,String severity,UUID pointId,String capability) {}
 public record ValidationResult(UUID validationId,UUID policyVersionId,int installationRevision,Instant validatedAt,Instant expiresAt,String status,
   List<ValidationIssue> issues,List<UUID> affectedPointIds,List<String> requiredCapabilityNames) {}

 /** Somente cenário sintético; nunca reutilizado para abertura real. */
 public record SyntheticSubject(String context,String ageEvidence,Boolean permitActive,Boolean reservationActive,Boolean physicalCompanionVerified) {}
 public record SimulateRequest(String mode,UUID policyVersionId,UUID pointId,SyntheticSubject subject,Instant at) {}
 public record SimulationResult(String result,List<String> reasonCodes,String safeExplanation,UUID policyVersionId,Integer bindingRevision,
   List<String> missingCapabilities,boolean simulated,boolean hardwareCommandCreated) {}

 /** Fatos mínimos lidos para validar e simular (sem canal, relé ou evidência). */
 public record AreaFacts(UUID areaId,String status,String areaKind,UUID amenityId,String timeZone,boolean essentialRoute) {}
 public record Destination(UUID pointId,String pointStatus,String flowDirection,Integer bindingRevision,String homologationStatus,
   Instant homologationReviewDueAt,List<String> approvedCapabilities) {}
}
