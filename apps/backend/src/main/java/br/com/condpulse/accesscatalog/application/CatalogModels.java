package br.com.condpulse.accesscatalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos do catálogo técnico e das homologações (AC-019, V040). Sem dados pessoais, fotos ou segredos. */
public final class CatalogModels {
 private CatalogModels() {}
 public static final List<String> CAPABILITIES=List.of("doorContactEvents","eventBackfill","faceDeletion","faceEnrollment","localValidity",
  "offlineRestriction","onlineDecisionBeforeRelay","passageEvents","perDoorRights","safeRecovery","scopedRemoteOpen","secureTransport");
 public static final List<String> LIMITS=List.of("cards","concurrentSessions","eventsStored","faces","groups","imagesPerPerson","maximumImageBytes",
  "plans","timeWindows","users");

 public record Profile(UUID id,String catalogCode,String manufacturer,String sku,String hardwareRevision,String region,String category,
   String sourceCategory,String lifecycle,String status,int revision,List<Object> sources,Map<String,Object> declaredCapabilities,
   Map<String,Object> declaredLimits,String notes,LocalDate consultedOn,String documentHash) {}
 public record ProfilePage(List<Profile> profiles) {}
 /** Observação do dossiê: documentada e observada (null = desconhecido). Aprovação nunca vem do cliente. */
 public record CapabilityObservation(Boolean documented,Boolean observed) {}
 public record CreateHomologation(UUID homologationId,UUID modelProfileId,String firmwareVersion,String adapterName,String adapterVersion,
   String apiContractVersion,String operatingMode,String topology,Map<String,CapabilityObservation> capabilities,Map<String,Long> limits,
   List<UUID> evidenceRefs,List<String> limitations,String reason) {}
 public record Decide(UUID decisionId,String decision,String reason,List<UUID> evidenceRefs,List<String> approvedCapabilityNames,
   Instant reviewDueAt,List<String> restrictions,Long expectedVersion) {}
 public record Decision(UUID decisionId,String decision,String fromStatus,String toStatus,String reason,List<UUID> evidenceRefs,
   List<String> approvedCapabilities,UUID decidedBy,Instant decidedAt) {}
 /** current: aprovado (bancada, piloto ou produção) e revisão ainda não vencida. */
 public record Homologation(UUID id,int revision,UUID modelProfileId,String firmwareVersion,String adapterName,String adapterVersion,
   String apiContractVersion,String operatingMode,String topology,Map<String,Object> capabilities,Map<String,Object> limits,List<Object> limitations,
   List<UUID> evidenceRefs,String evidenceHash,String status,List<String> approvedCapabilities,List<Object> restrictions,Instant approvedAt,
   Instant reviewDueAt,boolean current,long version) {}
 public record HomologationDetail(Homologation homologation,List<Decision> decisions) {}
 public record HomologationPage(List<Homologation> homologations) {}
 public record Created(Homologation homologation,boolean applied) {}
}
