package br.com.condpulse.accessedge.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos do gateway (V048). Identidade: o MASTER cadastra só o hash SHA-256 da credencial gerada no gateway; nenhuma
 * resposta devolve credencial ou hash completo (só a impressão curta para conferência). Trabalho entregue ao gateway é
 * o documento da V047, exatamente como o banco o compilou (texto canônico do jsonb, o mesmo do hash).
 */
public final class EdgeModels {
 private EdgeModels() {}

 /** credentialFingerprint: 12 primeiros dígitos do hash, só para o MASTER conferir com o gateway. */
 public record Agent(UUID agentId,UUID connectorId,UUID instanceId,String label,String credentialFingerprint,Instant credentialExpiresAt,
   Instant credentialRotatedAt,String status,String agentVersion,String protocolVersion,Instant lastSeenAt,Instant revokedAt,String revokeReason,
   Instant createdAt,long version) {}
 public record AgentPage(List<Agent> agents) {}
 /** credentialHash: SHA-256 (hex minúsculo) da credencial gerada no gateway; validade de 2 a 180 dias. */
 public record RegisterAgent(UUID agentId,UUID connectorId,String label,String credentialHash,Integer credentialValidDays) {}
 public record RotateAgent(String credentialHash,Integer credentialValidDays,Long expectedVersion) {}
 public record RevokeAgent(String reason,Long expectedVersion) {}

 /** Identidade vista pelo próprio gateway, com o relógio do servidor para medir desvio. */
 public record Identity(UUID agentId,UUID connectorId,UUID instanceId,String status,Instant credentialExpiresAt,Instant serverTime) {}

 public record LeaseRequest(Integer max,Integer leaseSeconds) {}
 /**
  * Comando de sincronização: commandId e idempotencyKey = trabalho da V047 (a mesma entrega repetida é a mesma chave na
  * fila local). document: texto exato cujo SHA-256 é documentHash.
  */
 public record SyncCommand(UUID commandId,String idempotencyKey,UUID leaseId,int attempt,Instant leaseExpiresAt,UUID deviceId,String subjectRef,
   String type,long desiredVersion,int priority,String documentHash,String document,Instant issuedAt) {}
 public record LeasePage(List<SyncCommand> commands,Instant serverTime) {}

 /** Relatório por trabalho. CONFIRMED exige observedHash igual a documentHash (leitura de volta do mesmo documento). */
 public record ReportRequest(String state,String resultCode,String documentHash,String observedHash) {}
 public record ReportResult(UUID commandId,String outcome,String outcomeCode,String jobState,boolean duplicate) {}
}
