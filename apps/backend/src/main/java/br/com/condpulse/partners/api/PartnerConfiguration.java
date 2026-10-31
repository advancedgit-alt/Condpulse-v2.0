package br.com.condpulse.partners.api;

import br.com.condpulse.partners.application.ContextSwitchService;
import br.com.condpulse.partners.application.PartnerService;
import br.com.condpulse.partners.application.PartnerTransactionExecutor;
import br.com.condpulse.partners.infrastructure.PartnerStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Hierarquia de parceiro (V049, ADR-0011) atrás de CP_PARTNERS_ENABLED=false. Desligada, nenhuma rota nem bean deste
 * módulo existe; /v1/me devolve partnerContexts vazio e o executor do condomínio não encontra alcance (tabelas vazias).
 */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.partners.enabled",havingValue="true")
public class PartnerConfiguration {
 @Bean PartnerTransactionExecutor partnerTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  return new PartnerTransactionExecutor(jdbc,manager);
 }
 @Bean PartnerStore partnerStore(JdbcTemplate jdbc) { return new PartnerStore(jdbc); }
 @Bean PartnerService partnerService(PlatformTransactionExecutor platform,PartnerTransactionExecutor partners,PartnerStore store,
   PlatformStore audit) {
  return new PartnerService(platform,partners,store,audit);
 }
 /** Troca de contexto (R4.4): grava no Log de Segurança só com CP_SECURITY_LOG_ENABLED=true. */
 @Bean ContextSwitchService contextSwitchService(PartnerTransactionExecutor partners,PartnerStore store,
   @Value("${condpulse.security-log.enabled:false}") boolean securityLog) {
  return new ContextSwitchService(partners,store,securityLog);
 }
}
