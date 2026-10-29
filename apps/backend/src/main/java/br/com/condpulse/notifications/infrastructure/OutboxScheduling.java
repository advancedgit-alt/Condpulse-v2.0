package br.com.condpulse.notifications.infrastructure;

import br.com.condpulse.notifications.application.OutboxWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Laço do worker, desligado por padrão (CP_WORKER_OUTBOX_ENABLED=false): sem a flag, o perfil worker não processa
 * nada. Uma passada por vez (fixedDelay); falha de banco numa passada fica no log saneado e a próxima tenta de novo.
 */
@Configuration @Profile("worker") @EnableScheduling
@ConditionalOnProperty(name="condpulse.worker.outbox-enabled",havingValue="true")
public class OutboxScheduling {
 private static final Logger LOG=LoggerFactory.getLogger(OutboxScheduling.class);
 private final OutboxWorker worker;
 public OutboxScheduling(OutboxWorker worker) { this.worker=worker; }
 @Scheduled(initialDelayString="${condpulse.worker.poll-millis:2000}",fixedDelayString="${condpulse.worker.poll-millis:2000}")
 public void poll() {
  try {
   int processed=worker.runOnce();
   if(processed>0) LOG.debug("outbox passada processou {} tarefas",processed);
  } catch(RuntimeException error) {
   LOG.warn("outbox passada interrompida: {}",OutboxWorker.sanitize(error));
  }
 }
}
