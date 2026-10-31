package br.com.condpulse.partners.application;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fronteira das operações do gestor do parceiro (V049). Sem condomínio no contexto: publica app.partner_id e as
 * capacidades de nível parceiro somente depois de conta ativa, papel ativo em parceiro ativo e AAL2. O banco confere de
 * novo o papel (platform.has_capability) a cada instrução.
 */
public class PartnerTransactionExecutor {
 static final String[] RESET={"app.session_hash","app.capabilities","app.invitation_hash","app.tenant_id","app.platform_capabilities",
  "app.recovery_hash","app.recovery_purge","app.manager_invitation_hash","app.technical_scopes","app.subject_context_scan",
  "app.policy_destination_scan","app.permit_link_hash","app.occupancy_scan","app.finance_webhook_charge","app.partner_id"};
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public PartnerTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }
 public <T> T execute(UUID accountId,UUID partnerId,AssuranceLevel assurance,Function<PartnerContext,T> action) {
  if(accountId==null || partnerId==null) throw denied();
  Objects.requireNonNull(assurance); Objects.requireNonNull(action);
  return tx.execute(status->{
   for(String setting:RESET) jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,accountId.toString());
   Integer active=jdbc.queryForObject("select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,accountId);
   if(active==null || active!=1)
    throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
   // Só os próprios papéis são legíveis antes do contexto (política partner_members_self_read).
   var roles=new LinkedHashSet<>(jdbc.queryForList("""
    select m.role from platform.partner_members m join platform.partners p on p.id=m.partner_id and p.status='ACTIVE'
    where m.partner_id=? and m.account_id=? and m.status='ACTIVE' order by m.role
    """,String.class,partnerId,accountId));
   if(roles.isEmpty()) throw denied();
   jdbc.queryForObject("select set_config('app.partner_id',?,true)",String.class,partnerId.toString());
   var context=new PartnerContext(accountId,partnerId,roles,assurance);
   String capabilities=context.effectiveCapabilities().stream().map(PartnerCapability::key).sorted().collect(Collectors.joining(","));
   if(!capabilities.isEmpty()) jdbc.queryForObject("select set_config('app.capabilities',?,true)",String.class,capabilities);
   return action.apply(context);
  });
 }
 private static BusinessProblem denied() {
  return new BusinessProblem("PARTNER_NOT_FOUND",404,"Parceiro não disponível para este acesso.");
 }
}
