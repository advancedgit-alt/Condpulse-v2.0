package br.com.condpulse.accessgrants.api;

import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.accessgrants.application.GrantService;
import br.com.condpulse.accessgrants.infrastructure.GrantStore;
import br.com.condpulse.accesspolicy.infrastructure.PolicyStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Direitos derivados e revogações (AC-009/AC-015, V044) atrás de CP_FACIAL_ACCESS_ENABLED=false. Também atende a simulação com sujeito real. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessGrantConfiguration {
 @Bean GrantStore grantStore(JdbcTemplate jdbc) { return new GrantStore(jdbc); }
 @Bean GrantService grantService(TenantTransactionExecutor tenants,GrantStore store,PolicyStore policies,AgeStore ages,BirthDateCipher cipher,AuditRecorder audit) {
  return new GrantService(tenants,store,policies,ages,cipher,audit);
 }
}
