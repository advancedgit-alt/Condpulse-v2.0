package br.com.condpulse.accessmap.api;

import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessmap.application.MapService;
import br.com.condpulse.accessmap.infrastructure.MapStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Áreas e pontos (AC-004, V041) atrás de CP_FACIAL_ACCESS_ENABLED=false. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessMapConfiguration {
 @Bean MapStore mapStore(JdbcTemplate jdbc) { return new MapStore(jdbc); }
 @Bean MapService mapService(TenantTransactionExecutor tenants,TechnicalTransactionExecutor technical,MapStore store,AuditRecorder audit) {
  return new MapService(tenants,technical,store,audit);
 }
}
