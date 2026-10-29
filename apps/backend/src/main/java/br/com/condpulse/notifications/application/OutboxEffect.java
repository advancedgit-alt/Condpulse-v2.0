package br.com.condpulse.notifications.application;

import java.util.UUID;

/**
 * Efeito de um tipo de evento. Somente efeito interno ao banco: roda na transação do condomínio em que a tarefa foi
 * arrendada, com a linha da tarefa travada, e a conclusão é gravada na mesma transação; se ela não puder ser gravada, a
 * transação inteira volta e o efeito some junto. Chamada externa (push, e-mail, SMS, webhook) não pode rodar aqui
 * (AGENTS.md: nada externo dentro de transação): canal externo exige desenho próprio — marcar o envio em andamento e
 * confirmar, chamar fora da transação com chave de idempotência do provedor e registrar o resultado —, ainda não
 * implementado. Efeito declarado não idempotente nunca recebe nova tentativa automática depois de falha: vai para FAILED
 * e depende de conferência.
 */
public interface OutboxEffect {
 boolean handles(String eventType);
 boolean idempotent();
 OutboxModels.Outcome apply(UUID tenant,OutboxModels.LeasedEvent event);
}
