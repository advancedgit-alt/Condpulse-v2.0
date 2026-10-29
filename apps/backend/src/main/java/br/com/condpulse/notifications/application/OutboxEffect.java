package br.com.condpulse.notifications.application;

import java.util.UUID;

/**
 * Efeito de um tipo de evento. Roda na transação do condomínio em que a tarefa foi arrendada; se a conclusão não puder
 * ser gravada (arrendamento perdido), a transação inteira volta e o efeito some junto. Efeito não idempotente nunca
 * recebe nova tentativa automática depois de falha: vai para FAILED e depende de conferência.
 */
public interface OutboxEffect {
 boolean handles(String eventType);
 boolean idempotent();
 OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event);
}
