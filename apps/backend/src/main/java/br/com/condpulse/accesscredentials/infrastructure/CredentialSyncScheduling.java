package br.com.condpulse.accesscredentials.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import br.com.condpulse.accesscredentials.application.CredentialReconciler;
import br.com.condpulse.accesscredentials.application.DeviceEventIngestor;
import br.com.condpulse.accesscredentials.application.DeviceHealthMonitor;
import br.com.condpulse.notifications.application.WorkerTransactions;
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
 * Passada de saúde (CP-048), de eventos dos equipamentos (CP-047) e de reconciliação das credenciais (CP-046) no worker, desligada por padrão (CP_WORKER_ACCESS_SYNC_ENABLED=false). Nenhum
 * parceiro físico está contratado: sem adaptador para o provedor do conector, nada é enviado e a pendência continua
 * visível para a gestão e a portaria. O adaptador simulado (CP-043) só existe nos testes.
 */
@Configuration @Profile("worker") @EnableScheduling
@ConditionalOnProperty(name="condpulse.worker.access-sync-enabled",havingValue="true")
public class CredentialSyncScheduling {
 private static final System.Logger LOG=System.getLogger(CredentialSyncScheduling.class.getName());
 private final AtomicBoolean ready=new AtomicBoolean(false);
 private CredentialReconciler reconciler;
 private DeviceEventIngestor ingestor;
 private DeviceHealthMonitor health;

 @Bean CredentialReconciler credentialReconciler(WorkerTransactions transactions,JdbcTemplate jdbc) {
  var syncs=new CredentialSyncStore(jdbc);
  reconciler=new CredentialReconciler(transactions,syncs,CredentialReconciler.NONE);
  ingestor=new DeviceEventIngestor(transactions,syncs,new DeviceEventStore(jdbc),CredentialReconciler.NONE);
  health=new DeviceHealthMonitor(transactions,syncs,new DeviceHealthStore(jdbc),CredentialReconciler.NONE);
  return reconciler;
 }
 @EventListener(ApplicationReadyEvent.class)
 public void ready() { ready.set(true); }
 @Scheduled(initialDelayString="${condpulse.worker.access-sync-millis:15000}",fixedDelayString="${condpulse.worker.access-sync-millis:15000}")
 public void poll() {
  if(!ready.get() || reconciler==null) return;
  try {
   // Saúde (CP-048), eventos (confirmações chegadas por evento evitam reenvio), depois a reconciliação.
   if(health!=null) health.runOnce();
   // Eventos primeiro (confirmações chegadas por evento evitam reenvio), depois a reconciliação.
   if(ingestor!=null) ingestor.runOnce();
   var pass=reconciler.runOnce();
   if(pass.skippedConnectors()>0) LOG.log(System.Logger.Level.WARNING,"Conectores sem adaptador disponível: {0}",pass.skippedConnectors());
  } catch(RuntimeException error) {
   LOG.log(System.Logger.Level.WARNING,"Passada de credenciais interrompida: {0}",error.getClass().getSimpleName());
  }
 }
}
