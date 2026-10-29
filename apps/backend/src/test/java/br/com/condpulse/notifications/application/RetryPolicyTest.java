package br.com.condpulse.notifications.application;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;

/** Backoff do PRD com variação limitada, fim em falha visível e nenhuma nova tentativa cega de efeito não idempotente. */
class RetryPolicyTest {
 @Test void followsThePrdDelaysWithBoundedJitter() {
  long[] seconds={10,30,120,600,1800};
  for(int attempt=1;attempt<=5;attempt++) {
   long base=seconds[attempt-1]*1000;
   var lowest=RetryPolicy.after(attempt,true,"Falha de banco",()->0.0);
   var highest=RetryPolicy.after(attempt,true,"Falha de banco",()->1.0);
   var middle=RetryPolicy.after(attempt,true,"Falha de banco",()->0.5);
   assertTrue(lowest.retry() && highest.retry() && middle.retry());
   assertEquals(Math.round(base*0.8),lowest.delay().toMillis());
   assertEquals(Math.round(base*1.2),highest.delay().toMillis());
   assertEquals(base,middle.delay().toMillis());
  }
  // Fonte aleatória fora do intervalo não escapa dos limites.
  assertEquals(12_000,RetryPolicy.after(1,true,"x",()->7.0).delay().toMillis());
  assertEquals(8_000,RetryPolicy.after(1,true,"x",()->-3.0).delay().toMillis());
 }
 @Test void lastAttemptEndsInVisibleFailure() {
  assertEquals(6,RetryPolicy.MAX_ATTEMPTS);
  var last=RetryPolicy.after(RetryPolicy.MAX_ATTEMPTS,true,"Falha de banco (08006)",()->0.5);
  assertFalse(last.retry()); assertEquals(Duration.ZERO,last.delay()); assertEquals("Falha de banco (08006)",last.error());
  assertFalse(RetryPolicy.after(0,true,"x",()->0.5).retry(),"Tentativa inválida não agenda nada");
 }
 @Test void nonIdempotentEffectNeverGetsABlindRetry() {
  var failure=RetryPolicy.after(1,false,"Erro inesperado (IllegalStateException)",()->0.5);
  assertFalse(failure.retry());
  assertTrue(failure.error().startsWith("Efeito não idempotente com falha"));
 }
 @Test void errorsAreLimitedAndNeverBlank() {
  assertEquals(300,RetryPolicy.after(1,true,"a".repeat(1000),()->0.5).error().length());
  assertEquals("Falha sem detalhe",RetryPolicy.after(1,true,"  ",()->0.5).error());
  assertEquals("Falha de banco",OutboxWorker.sanitize(new DataIntegrityViolationException("PESSOA FICTICIA 123.456")),
   "Mensagem do banco não vai para a tarefa");
  assertEquals("Erro inesperado (IllegalArgumentException)",OutboxWorker.sanitize(new IllegalArgumentException("dado pessoal")));
 }
}
