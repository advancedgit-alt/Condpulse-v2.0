package br.com.condpulse.accessage.api;

import br.com.condpulse.accessage.application.AgeService;
import br.com.condpulse.accessage.application.BirthDateCipher;
import br.com.condpulse.accessage.infrastructure.AgeStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Evidência de idade (AC-006, V039) atrás de CP_FACIAL_ACCESS_ENABLED=false. CP_AGE_EVIDENCE_KEY (base64 de 32 bytes) é
 * opcional: sem ela só a atestação funciona e a data protegida responde 503 (falha fechada). A chave fica só no servidor.
 */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessAgeConfiguration {
 @Bean AgeStore ageStore(JdbcTemplate jdbc) { return new AgeStore(jdbc); }
 @Bean BirthDateCipher birthDateCipher(@Value("${condpulse.facial-access.age-evidence-key:}") String key,
   @Value("${condpulse.facial-access.age-evidence-key-version:v1}") String version) {
  return BirthDateCipher.fromBase64(key,version);
 }
 @Bean AgeService ageService(TenantTransactionExecutor transactions,AgeStore store,BirthDateCipher cipher,AuditRecorder audit,
   SensitiveViewRecorder views) {
  return new AgeService(transactions,store,cipher,audit,views);
 }
}
