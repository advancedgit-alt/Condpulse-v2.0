package br.com.condpulse.accessgovernance.api;

import br.com.condpulse.accessgovernance.application.TechnicalGrantService;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessgovernance.infrastructure.TechnicalGrantStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Acesso facial (pacote v1.1, docs/integracoes/facial) atrás de CP_FACIAL_ACCESS_ENABLED=false. Desligado, nenhuma rota
 * nem bean deste módulo existe. A validade máxima da concessão técnica pelo painel é parâmetro (1 a 90 dias; padrão 30).
 */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessGovernanceConfiguration {
 @Bean TechnicalTransactionExecutor technicalTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  return new TechnicalTransactionExecutor(jdbc,manager);
 }
 @Bean TechnicalGrantStore technicalGrantStore(JdbcTemplate jdbc) { return new TechnicalGrantStore(jdbc); }
 @Bean TechnicalGrantService technicalGrantService(PlatformTransactionExecutor platform,TenantTransactionExecutor tenants,
   TechnicalTransactionExecutor technical,TechnicalGrantStore store,PlatformStore platformStore,
   @Value("${condpulse.facial-access.technical-grant-max-days:30}") int maxDays) {
  return new TechnicalGrantService(platform,tenants,technical,store,platformStore,maxDays);
 }
}
