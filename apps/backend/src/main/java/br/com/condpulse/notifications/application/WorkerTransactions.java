package br.com.condpulse.notifications.application;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transações do worker (cp_worker): sem conta, sem capacidades; o condomínio entra só por app.tenant_id local à
 * transação. O catálogo dos condomínios ativos é lido sem contexto de condomínio.
 */
@Component @Profile("worker")
public class WorkerTransactions {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public WorkerTransactions(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(10);
 }
 public <T> T catalog(Supplier<T> action) { return run(null,action); }
 public <T> T inTenant(UUID tenant,Supplier<T> action) { return run(Objects.requireNonNull(tenant),action); }
 private <T> T run(UUID tenant,Supplier<T> action) {
  return tx.execute(status->{
   for(String setting:new String[]{"app.actor_id","app.capabilities","app.platform_capabilities","app.session_hash","app.invitation_hash"})
    jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant==null?"":tenant.toString());
   return action.get();
  });
 }
}
