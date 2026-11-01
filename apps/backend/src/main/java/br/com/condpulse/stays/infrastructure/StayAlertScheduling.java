package br.com.condpulse.stays.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.stays.application.StayAlertJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Passada de alertas de permanência no worker (CPX-016), desligada por padrão (CP_WORKER_STAYS_ENABLED=false). Intervalo
 * CP_WORKER_STAYS_MILLIS (padrão 60 s): é a tolerância máxima entre o horário do nível e a gravação. Nenhuma passada
 * antes da aplicação pronta (depois da DatabaseRoleGuard).
 */
@Configuration @Profile("worker") @EnableScheduling
@ConditionalOnProperty(name="condpulse.worker.stays-enabled",havingValue="true")
public class StayAlertScheduling {
 private static final System.Logger LOG=System.getLogger(StayAlertScheduling.class.getName());
 private final AtomicBoolean ready=new AtomicBoolean(false);
 private StayAlertJob job;

 @Bean StayAlertJob stayAlertJob(WorkerTransactions transactions,JdbcTemplate jdbc) {
  job=new StayAlertJob(transactions,jdbc);
  return job;
 }
 @EventListener(ApplicationReadyEvent.class)
 public void ready() { ready.set(true); }
 @Scheduled(initialDelayString="${condpulse.worker.stays-millis:60000}",fixedDelayString="${condpulse.worker.stays-millis:60000}")
 public void alert() {
  if(!ready.get() || job==null) return;
  try {
   var pass=job.runOnce();
   if(pass.failures()>0) LOG.log(System.Logger.Level.WARNING,"Alertas de permanência com falha: {0} de {1} condomínios",pass.failures(),pass.tenants());
  } catch(RuntimeException error) {
   LOG.log(System.Logger.Level.WARNING,"Passada de alertas de permanência interrompida: {0}",error.getClass().getSimpleName());
  }
 }
}
