package br.com.condpulse.accessgrants.api;

import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.accessgrants.application.GrantService;
import br.com.condpulse.accessgrants.application.PublicationService;
import br.com.condpulse.accessgrants.infrastructure.GrantStore;
import br.com.condpulse.accessgrants.infrastructure.PublicationStore;
import br.com.condpulse.accesspolicy.infrastructure.PolicyStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Direitos derivados, revogações e publicação por destino (AC-009/AC-012/AC-013/AC-015, V044/V045) atrás de CP_FACIAL_ACCESS_ENABLED=false. Também atende a simulação com sujeito real. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessGrantConfiguration {
 @Bean GrantStore grantStore(JdbcTemplate jdbc) { return new GrantStore(jdbc); }
 @Bean PublicationStore publicationStore(JdbcTemplate jdbc) { return new PublicationStore(jdbc); }
 @Bean PublicationService publicationService(TenantTransactionExecutor tenants,PublicationStore store,GrantStore grants,AuditRecorder audit) {
  return new PublicationService(tenants,store,grants,audit);
 }
 @Bean GrantService grantService(TenantTransactionExecutor tenants,GrantStore store,PolicyStore policies,AgeStore ages,BirthDateCipher cipher,AuditRecorder audit,
   PublicationService publications) {
  return new GrantService(tenants,store,policies,ages,cipher,audit,publications);
 }
}
