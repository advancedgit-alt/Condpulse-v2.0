package br.com.condpulse.reservations.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import br.com.condpulse.reservations.application.WaitlistSweep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Passada periódica da fila de espera no worker, desligada por padrão (CP_WORKER_WAITLIST_ENABLED=false). Sem worker,
 * a fila anda nas requisições da API (reserva, cancelamento, decisão, consulta da fila). Nenhuma passada antes da
 * aplicação pronta (depois da DatabaseRoleGuard).
 */
@Configuration @Profile("worker") @EnableScheduling
@ConditionalOnProperty(name="condpulse.worker.waitlist-enabled",havingValue="true")
public class WaitlistScheduling {
 private static final System.Logger LOG=System.getLogger(WaitlistScheduling.class.getName());
 private final WaitlistSweep sweep;
 private final AtomicBoolean ready=new AtomicBoolean(false);
 public WaitlistScheduling(WaitlistSweep sweep) { this.sweep=sweep; }
 @EventListener(ApplicationReadyEvent.class)
 public void ready() { ready.set(true); }
 @Scheduled(initialDelayString="${condpulse.worker.waitlist-millis:60000}",fixedDelayString="${condpulse.worker.waitlist-millis:60000}")
 public void poll() {
  if(!ready.get()) return;
  try { sweep.runOnce(); }
  catch(RuntimeException error) {
   LOG.log(System.Logger.Level.WARNING,"Passada da fila interrompida: {0}",error.getClass().getSimpleName());
  }
 }
}
