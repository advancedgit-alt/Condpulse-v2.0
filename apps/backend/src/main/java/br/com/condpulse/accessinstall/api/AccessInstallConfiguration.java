package br.com.condpulse.accessinstall.api;

import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessinstall.application.InstallService;
import br.com.condpulse.accessinstall.infrastructure.InstallStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Instalação física (AC-004 parte 2, V042) atrás de CP_FACIAL_ACCESS_ENABLED=false. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessInstallConfiguration {
 @Bean InstallStore installStore(JdbcTemplate jdbc) { return new InstallStore(jdbc); }
 @Bean InstallService installService(TenantTransactionExecutor tenants,TechnicalTransactionExecutor technical,InstallStore store,AuditRecorder audit) {
  return new InstallService(tenants,technical,store,audit);
 }
}
