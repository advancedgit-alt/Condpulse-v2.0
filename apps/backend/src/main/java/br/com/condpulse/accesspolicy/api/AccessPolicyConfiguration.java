package br.com.condpulse.accesspolicy.api;

import br.com.condpulse.accesspolicy.application.PolicyService;
import br.com.condpulse.accesspolicy.infrastructure.PolicyStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Políticas de ingresso (AC-007/AC-008, V043) atrás de CP_FACIAL_ACCESS_ENABLED=false. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessPolicyConfiguration {
 @Bean PolicyStore policyStore(JdbcTemplate jdbc) { return new PolicyStore(jdbc); }
 @Bean PolicyService policyService(TenantTransactionExecutor tenants,PolicyStore store,AuditRecorder audit) { return new PolicyService(tenants,store,audit); }
}
