package br.com.condpulse.accesssubjects.api;

import br.com.condpulse.accesssubjects.application.SubjectService;
import br.com.condpulse.accesssubjects.infrastructure.SubjectStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Sujeitos e contextos de acesso (AC-005, V038) atrás de CP_FACIAL_ACCESS_ENABLED=false. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessSubjectConfiguration {
 @Bean SubjectStore subjectStore(JdbcTemplate jdbc) { return new SubjectStore(jdbc); }
 @Bean SubjectService subjectService(TenantTransactionExecutor transactions,SubjectStore store,AuditRecorder audit) {
  return new SubjectService(transactions,store,audit);
 }
}
