package br.com.condpulse.accessinstall.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos da instalação física (AC-004 parte 2, V042): equipamento provisionado, canal nativo, vínculo versionado do ponto e
 * aceite de instalação. Só o MASTER de implantação recebe equipamento, canal e vínculo; a gestão vê o resumo do aceite e
 * assina as regras.
 */
public final class InstallModels {
 private InstallModels() {}
 /** connectionMode null usa o padrão do condomínio (LOCAL_GATEWAY ou CLOUD_SERVICE). */
 public record Device(UUID deviceId,UUID connectorId,String externalId,UUID provisionedInstanceId,String label,String direction,String status,
   String hardwareRevision,UUID homologationId,String deviceRole,String connectionMode,long version) {}
 public record DevicePage(List<Device> devices) {}
 public record CreateDevice(UUID deviceId,UUID connectorId,String externalId,String label,String direction,String hardwareRevision,
   UUID homologationId,String deviceRole,String connectionMode) {}
 public record UpdateDevice(String label,String direction,String status,String hardwareRevision,UUID homologationId,String deviceRole,
   String connectionMode,Long expectedVersion) {}

 public record Channel(UUID channelId,UUID deviceId,String nativeNamespace,String externalChannel,String channelKind,String status,long version) {}
 public record ChannelPage(List<Channel> channels) {}
 public record CreateChannel(UUID channelId,UUID deviceId,String nativeNamespace,String externalChannel,String channelKind,String status) {}
 public record UpdateChannel(String channelKind,String status,Long expectedVersion) {}

 /** Sentido de cada leitor em relação ao ponto: FROM_TO (origem → destino) ou TO_FROM. */
 public record Directions(String entryReader,String exitReader) {}
 public record Binding(UUID bindingId,UUID pointId,int bindingRevision,UUID actuatorChannelId,UUID controllerDeviceId,UUID entryReaderDeviceId,
   UUID exitReaderDeviceId,UUID doorContactChannelId,UUID exitRequestChannelId,UUID passageSensorChannelId,UUID homologationId,
   UUID installationAcceptanceId,Directions directions,String status,Instant validFrom,Instant validUntil,String wiringEvidenceRef,
   String configurationHash,long version) {}
 public record BindingPage(List<Binding> bindings) {}
 public record CreateBinding(UUID bindingId,UUID pointId,UUID actuatorChannelId,UUID controllerDeviceId,UUID entryReaderDeviceId,
   UUID exitReaderDeviceId,UUID doorContactChannelId,UUID exitRequestChannelId,UUID passageSensorChannelId,UUID homologationId,
   Directions directions,String wiringEvidenceRef) {}
 public record SubmitBinding(UUID installationAcceptanceId,Long expectedVersion) {}
 public record VersionOnly(Long expectedVersion) {}

 public record Acceptance(UUID acceptanceId,int installationRevision,String status,String plantRef,String plantHash,String topologyManifestRef,
   String topologyHash,String capabilityEnvelopeHash,String testSuiteVersion,String testReportRef,int criticalTestsPending,int criticalTestsFailed,
   String emergencyTestRef,String rollbackPlanRef,String revocationCheckpointRef,UUID technicalApproverAccountId,Instant technicalSignedAt,
   UUID rulesApproverAccountId,Instant rulesSignedAt,Instant effectiveAt,Instant reviewDueAt,String statusReason,long version) {}
 public record AcceptancePage(List<Acceptance> acceptances) {}
 public record CreateAcceptance(UUID acceptanceId,String plantRef,String plantHash,String topologyManifestRef,String topologyHash,
   String capabilityEnvelopeHash,String testSuiteVersion,String testReportRef,Integer criticalTestsPending,Integer criticalTestsFailed,
   String emergencyTestRef,String rollbackPlanRef,String revocationCheckpointRef,Instant reviewDueAt) {}
 /** Resultado dos testes antes da assinatura técnica. */
 public record AcceptanceResults(Integer criticalTestsPending,Integer criticalTestsFailed,String testReportRef,String revocationCheckpointRef,
   Long expectedVersion) {}
 /** TESTING, APPROVED, REJECTED, SUSPENDED ou SUPERSEDED; motivo obrigatório para encerrar. */
 public record AcceptanceTransition(String status,String reason,Long expectedVersion) {}
 /** Visão da gestão: revisão, estado, testes críticos e assinaturas, sem planta, topologia nem relatório. */
 public record AcceptanceSummary(UUID acceptanceId,int installationRevision,String status,int criticalTestsPending,int criticalTestsFailed,
   Instant technicalSignedAt,Instant rulesSignedAt,UUID rulesApproverAccountId,Instant effectiveAt,Instant reviewDueAt,long version) {}
 public record AcceptanceSummaryPage(List<AcceptanceSummary> acceptances) {}
}
