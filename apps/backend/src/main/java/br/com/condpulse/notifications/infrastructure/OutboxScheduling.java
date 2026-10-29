package br.com.condpulse.notifications.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import br.com.condpulse.notifications.application.OutboxWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Laço do worker, desligado por padrão (CP_WORKER_OUTBOX_ENABLED=false): sem a flag, o perfil worker não processa
 * nada. Uma passada por vez (fixedDelay); falha de banco numa passada fica no log saneado e a próxima tenta de novo.
 * Nenhuma passada antes da aplicação pronta: o evento de pronto só vem depois da DatabaseRoleGuard, e role com
 * privilégio a mais derruba o processo sem que o worker tenha tocado em tarefa.
 */
@Configuration @Profile("worker") @EnableScheduling
@ConditionalOnProperty(name="condpulse.worker.outbox-enabled",havingValue="true")
public class OutboxScheduling {
 private static final Logger LOG=LoggerFactory.getLogger(OutboxScheduling.class);
 private final OutboxWorker worker;
 private final AtomicBoolean ready=new AtomicBoolean(false);
 public OutboxScheduling(OutboxWorker worker) { this.worker=worker; }
 @EventListener(ApplicationReadyEvent.class)
 public void ready() { ready.set(true); }
 @Scheduled(initialDelayString="${condpulse.worker.poll-millis:2000}",fixedDelayString="${condpulse.worker.poll-millis:2000}")
 public void poll() {
  if(!ready.get()) return;
  try {
   int processed=worker.runOnce();
   if(processed>0) LOG.debug("outbox passada processou {} tarefas",processed);
  } catch(RuntimeException error) {
   LOG.warn("outbox passada interrompida: {}",OutboxWorker.sanitize(error));
  }
 }
}
