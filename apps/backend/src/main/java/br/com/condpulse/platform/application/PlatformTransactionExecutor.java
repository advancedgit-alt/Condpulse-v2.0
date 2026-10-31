package br.com.condpulse.platform.application;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Única fronteira das operações de plataforma. Sem condomínio no contexto: as políticas tenant_boundary do schema
 * cp continuam fechadas. Capacidades entram no contexto local somente após conta ativa, papel vigente e AAL2.
 */
@Service @Profile("api")
public class PlatformTransactionExecutor {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public PlatformTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }
 public <T> T execute(UUID accountId,AssuranceLevel assurance,Function<PlatformContext,T> action) {
  if(accountId==null) throw denied();
  Objects.requireNonNull(assurance); Objects.requireNonNull(action);
  return tx.execute(status->{
   for(String setting:new String[]{"app.session_hash","app.capabilities","app.invitation_hash","app.tenant_id",
     "app.platform_capabilities","app.recovery_hash","app.recovery_purge","app.manager_invitation_hash","app.technical_scopes","app.subject_context_scan"})
    jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,accountId.toString());
   Integer active=jdbc.queryForObject("select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,accountId);
   if(active==null || active!=1)
    throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
   var roles=jdbc.queryForList("select role from platform.platform_roles where account_id=? and status='ACTIVE'",String.class,accountId);
   if(roles.size()!=1) throw denied();
   var context=new PlatformContext(accountId,roles.getFirst(),assurance);
   String capabilities=context.effectiveCapabilities().stream().map(PlatformCapability::key).sorted().collect(Collectors.joining(","));
   if(!capabilities.isEmpty())
    jdbc.queryForObject("select set_config('app.platform_capabilities',?,true)",String.class,capabilities);
   return action.apply(context);
  });
 }
 private static BusinessProblem denied() {
  return new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
 }
}
