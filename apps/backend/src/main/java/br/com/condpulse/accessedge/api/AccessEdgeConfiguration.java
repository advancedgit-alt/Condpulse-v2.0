package br.com.condpulse.accessedge.api;

import br.com.condpulse.accessedge.application.EdgeAgentService;
import br.com.condpulse.accessedge.application.EdgeGatewayService;
import br.com.condpulse.accessedge.application.EdgeTransactionExecutor;
import br.com.condpulse.accessedge.infrastructure.EdgeStore;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Identidade do gateway e contrato de entrega/relatório (V048) atrás de CP_FACIAL_ACCESS_ENABLED=false. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessEdgeConfiguration {
 @Bean EdgeStore edgeStore(JdbcTemplate jdbc) { return new EdgeStore(jdbc); }
 @Bean EdgeTransactionExecutor edgeTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager,EdgeStore store) {
  return new EdgeTransactionExecutor(jdbc,manager,store);
 }
 @Bean EdgeAgentService edgeAgentService(TechnicalTransactionExecutor technical,EdgeStore store,AuditRecorder audit) {
  return new EdgeAgentService(technical,store,audit);
 }
 @Bean EdgeGatewayService edgeGatewayService(EdgeTransactionExecutor edge,EdgeStore store) { return new EdgeGatewayService(edge,store); }
}
