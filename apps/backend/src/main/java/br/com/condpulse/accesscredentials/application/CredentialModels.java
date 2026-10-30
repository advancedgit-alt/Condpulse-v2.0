package br.com.condpulse.accesscredentials.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Credenciais de acesso com estado desejado versionado (CP-046). A situação por equipamento é derivada da comparação
 * entre o que deve valer (versão e ação desejadas) e o que o equipamento confirmou: confirmação de outra versão ou de
 * outra ação nunca conta.
 */
public final class CredentialModels {
 private CredentialModels() {}

 /** Por equipamento: CONFIRMED (versão e ação atuais confirmadas), PENDING (aguarda) ou FAILED (recusa definitiva desta versão). */
 public record DeviceSync(UUID deviceId,String deviceLabel,String direction,String state,String lastOutcome,Instant confirmedAt,
     String lastReason,Instant lastAttemptAt) {}
 /**
  * status: ACTIVE (todos confirmaram), SYNCING (nenhum confirmou), PARTIAL (parte confirmou), FAILED (algum equipamento
  * recusou esta versão), REVOKING (revogação ainda não confirmada em todos), REVOKED (revogação confirmada em todos),
  * EXPIRED (vínculo com validade vencida: nenhuma ativação nova; o equipamento recebeu o mesmo limite), NO_DEVICES
  * (conector sem equipamento ativo).
  */
 public record Credential(UUID credentialId,UUID connectorId,UUID membershipId,UUID unitId,String unitLabel,String holderName,String kind,
     String label,String desiredState,long desiredVersion,Instant validUntil,String status,String revokeReason,Instant revokedAt,Instant createdAt,long version,
     List<DeviceSync> devices) {
  public Credential { devices=List.copyOf(devices); }
 }
 public record Page(List<Credential> credentials,boolean truncated) {
  public Page { credentials=List.copyOf(credentials); }
 }
 public record Device(UUID deviceId,String label,String direction,String status) {}
 public record Connector(UUID connectorId,String provider,String label,String status,List<Device> devices) {
  public Connector { devices=List.copyOf(devices); }
 }
 public record Connectors(List<Connector> connectors) {
  public Connectors { connectors=List.copyOf(connectors); }
 }
 /**
  * Evento do equipamento como o parceiro informou (CP-049): autorização, recusa, abertura (relé/porta) e passagem são
  * tipos diferentes; direção e evidência ausentes continuam null. exitRecorded só existe para passagem de entrada: diz
  * se há passagem de saída posterior da mesma credencial — nunca presença.
  */
 public record DeviceEvent(UUID eventId,String kind,String direction,String evidence,Instant occurredAt,String deviceLabel,String credentialLabel,
     String unitLabel,String state,String quarantineReason,Boolean exitRecorded) {}
 public record EventPage(List<DeviceEvent> events,boolean truncated) {
  public EventPage { events=List.copyOf(events); }
 }
 /**
  * Saúde de um equipamento (CP-048). status: ONLINE (informou estar no ar, com sinal recente), OFFLINE (informou estar
  * fora do ar, ou sinal antigo: reason HEARTBEAT_STALE), UNKNOWN (sem informação recente: reason NOT_REPORTED, STALE,
  * HEARTBEAT_MISSING (no ar sem nenhum sinal informado) ou
  * o motivo do conector) ou RETIRED. pending*: credenciais cujo estado desejado atual ainda não foi confirmado neste
  * equipamento; rejected: recusa definitiva da versão atual.
  */
 public record DeviceHealth(UUID deviceId,String label,String direction,String status,String reason,Instant observedAt,Instant stateSince,
     Instant lastHeartbeat,Long clockSkewSeconds,boolean clockDrift,int pendingApply,int pendingRevoke,int rejected,Instant oldestPendingRevocation) {}
 /** status: OK, UNREACHABLE (última verificação falhou; reason), UNKNOWN (NEVER_CHECKED ou STALE) ou DISABLED. */
 public record ConnectorHealth(UUID connectorId,String provider,String label,String status,String reason,Instant checkedAt,Instant lastReachableAt,
     Integer pendingCommands,Long observedDelaySeconds,List<DeviceHealth> devices) {
  public ConnectorHealth { devices=List.copyOf(devices); }
 }
 public record Health(Instant generatedAt,long staleAfterSeconds,List<ConnectorHealth> connectors) {
  public Health { connectors=List.copyOf(connectors); }
 }
 public record Create(UUID connectorId,UUID membershipId,String kind,String label) {}
 public record Revoke(String reason,Long expectedVersion) {}
 public record Mutation(Credential credential,boolean replayed,int status) {}
}
