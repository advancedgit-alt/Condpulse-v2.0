package br.com.condpulse.notifications.application;

import java.time.Duration;
import java.util.UUID;

/** Tarefa do outbox arrendada pelo worker e o resultado do efeito. Payload só com IDs e dados mínimos. */
public final class OutboxModels {
 private OutboxModels() {}
 /**
  * attempts já conta esta tentativa; a conclusão é cercada por ela (nunca volta atrás). attemptBase: tentativas anteriores à
  * última devolução pela operação (V032); o limite vale para attempts-attemptBase. unitId vem do payload, quando há unidade.
  */
 public record LeasedEvent(UUID id,String type,UUID aggregateId,long aggregateVersion,UUID unitId,int attempts,int attemptBase) {
  public LeasedEvent(UUID id,String type,UUID aggregateId,long aggregateVersion,UUID unitId,int attempts) {
   this(id,type,aggregateId,aggregateVersion,unitId,attempts,0);
  }
  /** Tentativas desde a última devolução (1 na primeira), contadas contra o limite. */
  public int budgetUsed() { return attempts-attemptBase; }
 }
 /** DELIVERED: efeito interno aplicado. SKIPPED: sem consumidor, sem destinatário apto ou registro já encerrado. */
 public enum Outcome { DELIVERED,SKIPPED }
 /** Próximo passo depois de uma falha: nova tentativa no horário indicado ou falha definitiva visível. */
 public record Failure(boolean retry,Duration delay,String error) {}
 /**
  * Falha que nova tentativa não resolve (evento sem unidade, registro inexistente): vai direto para FAILED. A mensagem é
  * escrita pelo próprio código, sem dado de pessoa, e vira o erro saneado da tarefa.
  */
 public static final class PermanentFailure extends RuntimeException {
  public PermanentFailure(String message) { super(message,null,false,false); }
 }
}
