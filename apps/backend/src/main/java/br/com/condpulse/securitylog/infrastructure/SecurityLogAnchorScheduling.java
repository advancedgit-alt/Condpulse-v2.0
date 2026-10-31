package br.com.condpulse.securitylog.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.securitylog.application.SecurityLogAnchorJob;
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
 * Âncora diária do Log de Segurança no worker (CPX-008), atrás de CP_SECURITY_LOG_ENABLED=false. A passada roda de hora em
 * hora e só grava dias fechados ainda sem âncora (idempotente).
 */
@Configuration @Profile("worker") @EnableScheduling
@ConditionalOnProperty(name="condpulse.security-log.enabled",havingValue="true")
public class SecurityLogAnchorScheduling {
 private static final System.Logger LOG=System.getLogger(SecurityLogAnchorScheduling.class.getName());
 private final AtomicBoolean ready=new AtomicBoolean(false);
 private SecurityLogAnchorJob job;

 @Bean SecurityLogAnchorJob securityLogAnchorJob(WorkerTransactions transactions,JdbcTemplate jdbc) {
  job=new SecurityLogAnchorJob(transactions,jdbc);
  return job;
 }
 @EventListener(ApplicationReadyEvent.class)
 public void ready() { ready.set(true); }
 @Scheduled(initialDelayString="${condpulse.security-log.anchor-millis:60000}",fixedDelayString="${condpulse.security-log.anchor-millis:3600000}")
 public void anchor() {
  if(!ready.get() || job==null) return;
  try {
   var pass=job.runOnce();
   if(pass.failures()>0) LOG.log(System.Logger.Level.WARNING,"Âncoras com falha: {0} de {1} condomínios",pass.failures(),pass.tenants());
  } catch(RuntimeException error) {
   LOG.log(System.Logger.Level.WARNING,"Passada de âncora interrompida: {0}",error.getClass().getSimpleName());
  }
 }
}
