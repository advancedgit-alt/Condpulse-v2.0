package br.com.condpulse.notifications.application;

import java.time.Duration;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Backoff do PRD (10 s, 30 s, 2 min, 10 min, 30 min) com variação de até 20% para os workers não baterem juntos; depois
 * da última espera a tarefa vai para FAILED. Efeito não idempotente não recebe nova tentativa automática.
 */
public final class RetryPolicy {
 public static final List<Duration> DELAYS=List.of(Duration.ofSeconds(10),Duration.ofSeconds(30),Duration.ofMinutes(2),
  Duration.ofMinutes(10),Duration.ofMinutes(30));
 /** Tentativas no total: a primeira e uma para cada espera. */
 public static final int MAX_ATTEMPTS=DELAYS.size()+1;
 private RetryPolicy() {}
 public static OutboxModels.Failure after(int attempts,boolean idempotent,String error,DoubleSupplier random) {
  if(!idempotent) return new OutboxModels.Failure(false,Duration.ZERO,limit("Efeito não idempotente com falha: conferir antes de reprocessar. "+error));
  if(attempts<1 || attempts>=MAX_ATTEMPTS) return new OutboxModels.Failure(false,Duration.ZERO,limit(error));
  Duration base=DELAYS.get(attempts-1);
  double factor=0.8+0.4*Math.max(0,Math.min(1,random.getAsDouble()));
  return new OutboxModels.Failure(true,Duration.ofMillis(Math.max(1000,Math.round(base.toMillis()*factor))),limit(error));
 }
 static String limit(String error) {
  String value=error==null || error.isBlank()?"Falha sem detalhe":error.strip();
  return value.length()<=300?value:value.substring(0,300);
 }
}
