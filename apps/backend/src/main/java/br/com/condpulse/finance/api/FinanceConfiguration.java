package br.com.condpulse.finance.api;

import java.net.http.HttpClient;
import java.time.Duration;
import br.com.condpulse.finance.application.AsaasGateway;
import br.com.condpulse.finance.application.FinanceService;
import br.com.condpulse.finance.application.FinanceWebhookService;
import br.com.condpulse.finance.infrastructure.AsaasHttpGateway;
import br.com.condpulse.finance.infrastructure.FinanceStore;
import br.com.condpulse.finance.infrastructure.FinanceWebhookStore;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Financeiro condominial (ADR-0008) atrás de CP_FINANCE_ENABLED=false. Ligado, exige CP_ASAAS_API_KEY do sandbox
 * ($aact_hmlg_) e CP_ASAAS_WEBHOOK_TOKEN (32+ caracteres); sem eles a API não sobe (falha fechada). Os valores ficam só
 * no servidor.
 */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.finance.enabled",havingValue="true")
public class FinanceConfiguration {
 @Bean AsaasGateway asaasGateway(@Value("${condpulse.finance.asaas-api-key:}") String apiKey) {
  var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  return new AsaasHttpGateway(http,apiKey);
 }
 @Bean FinanceStore financeStore(JdbcTemplate jdbc) { return new FinanceStore(jdbc); }
 @Bean FinanceService financeService(TenantTransactionExecutor transactions,FinanceStore store,AsaasGateway asaas,AuditRecorder audit) {
  return new FinanceService(transactions,store,asaas,audit);
 }
 @Bean FinanceWebhookService financeWebhookService(@Value("${condpulse.finance.asaas-webhook-token:}") String token,AsaasGateway asaas,
     JdbcTemplate jdbc,PlatformTransactionManager manager) {
  return new FinanceWebhookService(token,asaas,new FinanceWebhookStore(jdbc,manager));
 }
}
