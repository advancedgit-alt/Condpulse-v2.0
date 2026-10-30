package br.com.condpulse.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operação da fila de tarefas pela plataforma (CP-034): estado, tentativas, erro saneado e horários, nunca o conteúdo
 * da tarefa. stale: tarefa pronta esperando há mais de 60 s ou arrendamento vencido (alerta de fila antiga do PRD).
 */
public final class OutboxOperationsModels {
 private OutboxOperationsModels() {}
 public static final int STALE_SECONDS=60;
 public record TenantQueue(UUID tenantId,String tenantName,long pending,long ready,Long oldestReadySeconds,long expiredLeases,long failed,
   long skipped,boolean stale) {}
 public record Summary(List<TenantQueue> tenants,int staleAfterSeconds,Instant generatedAt) {
  public Summary { tenants=List.copyOf(tenants); }
 }
 public record Task(UUID taskId,UUID tenantId,String eventType,UUID aggregateId,String state,int attempts,String lastError,Instant createdAt,
   Instant availableAt,Instant processedAt) {}
 public record TaskPage(List<Task> tasks,boolean truncated) {
  public TaskPage { tasks=List.copyOf(tasks); }
 }
 /** Motivo obrigatório: vai para a auditoria da plataforma com o tipo do evento e o estado anterior. */
 public record Replay(String reason) {}
}
