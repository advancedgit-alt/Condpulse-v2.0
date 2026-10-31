package br.com.condpulse.accessedge.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.accessedge.infrastructure.EdgeStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantModuleGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transação dedicada do gateway (V048): sem conta, capacidade, escopo técnico ou papel de plataforma. A credencial
 * apresentada vira hash (nunca o valor) em app.edge_credential_hash; o condomínio vem da identidade cadastrada, nunca
 * do corpo. Credencial ausente, desconhecida, revogada ou vencida respondem igual (401), sem distinguir o motivo.
 */
public class EdgeTransactionExecutor {
 /** Contexto autenticado do gateway; vale só dentro da transação. */
 public record EdgeContext(UUID agentId,UUID tenantId,UUID connectorId,UUID instanceId,String status,Instant credentialExpiresAt) {}

 private static final String[] RESET={"app.session_hash","app.capabilities","app.invitation_hash","app.tenant_id","app.platform_capabilities",
  "app.permit_link_hash","app.occupancy_scan","app.finance_webhook_charge","app.technical_scopes","app.subject_context_scan",
  "app.policy_destination_scan","app.recovery_hash","app.recovery_purge","app.manager_invitation_hash","app.actor_id"};
 private final JdbcTemplate jdbc;
 private final EdgeStore store;
 private final TransactionTemplate tx;
 public EdgeTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager,EdgeStore store) {
  this.jdbc=jdbc; this.store=store;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }

 public <T> T execute(String presentedCredential,Function<EdgeContext,T> action) {
  Objects.requireNonNull(action);
  String hash=EdgeCredentials.hashOf(presentedCredential).orElseThrow(EdgeTransactionExecutor::unauthenticated);
  return tx.execute(status->{
   for(String setting:RESET) jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.edge_credential_hash',?,true)",String.class,hash);
   var presented=store.presented(hash);
   if(presented==null || "REVOKED".equals(presented.status())) throw unauthenticated();
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,presented.tenantId().toString());
   if(!store.authenticated(presented.tenantId(),presented.agentId())) throw unauthenticated();
   // Módulo liberado pela central para este condomínio (V045), depois da identidade conferida.
   TenantModuleGate.check(jdbc,presented.tenantId());
   store.touch(presented.tenantId(),presented.agentId());
   return action.apply(new EdgeContext(presented.agentId(),presented.tenantId(),presented.connectorId(),presented.instanceId(),
    "PROVISIONED".equals(presented.status())?"ONLINE":presented.status(),presented.credentialExpiresAt()));
  });
 }
 static BusinessProblem unauthenticated() {
  return new BusinessProblem("EDGE_UNAUTHENTICATED",401,"Gateway não autenticado.");
 }
}
