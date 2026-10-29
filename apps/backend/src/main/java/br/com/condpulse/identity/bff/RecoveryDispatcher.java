package br.com.condpulse.identity.bff;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fila curta para o pedido de e-mail de recuperação: a resposta ao navegador sai no prazo fixo mesmo com provedor
 * lento. Fila cheia recusa o envio (a resposta continua neutra). Não é um Executor do Spring, para não substituir o
 * executor padrão da aplicação.
 */
final class RecoveryDispatcher implements RecoveryService.Dispatcher,AutoCloseable {
 private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),
  runnable->{
   Thread thread=new Thread(runnable,"cp-recovery-dispatch");
   thread.setDaemon(true);
   return thread;
  },new ThreadPoolExecutor.AbortPolicy());
 @Override public Future<?> submit(Runnable task) { return executor.submit(task); }
 @Override public void close() { executor.shutdownNow(); }
}
