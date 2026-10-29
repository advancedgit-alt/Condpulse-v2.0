package br.com.condpulse.tenancy.application;

import java.time.DateTimeException;
import java.time.ZoneId;
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

/** Única fronteira pública para executar a fatia de negócio com contexto tenant validado. */
@Service @Profile("api")
public class TenantTransactionExecutor {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public TenantTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }

 public <T> T execute(UUID accountId,UUID tenantId,Function<TenantContext,T> action) {
  return execute(accountId,tenantId,AssuranceLevel.AAL1,action);
 }

 /** Mesma validação, com o nível de garantia do token já validado para liberar capacidades de equipe. */
 public <T> T execute(UUID accountId,UUID tenantId,AssuranceLevel assurance,Function<TenantContext,T> action) {
  if(accountId==null || tenantId==null)
   throw new BusinessProblem("TENANT_ACCESS_DENIED",404,"Condomínio não disponível para este acesso.");
  Objects.requireNonNull(action); Objects.requireNonNull(assurance);
  return tx.execute(status->{
   // Nunca herdar contexto de outra requisição ou de uma sessão BFF nesta conexão.
   jdbc.queryForObject("select set_config('app.session_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.capabilities','',true)",String.class);
   jdbc.queryForObject("select set_config('app.invitation_hash','',true)",String.class);
   jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
   jdbc.queryForObject("select set_config('app.platform_capabilities','',true)",String.class);
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,accountId.toString());
   Integer active=jdbc.queryForObject(
    "select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,accountId);
   if(active==null || active!=1)
    throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
   var zones=jdbc.query("select time_zone from platform.tenants where id=? and active",
    (r,n)->r.getString("time_zone"),tenantId);
   if(zones.size()!=1) throw denied();
   ZoneId timeZone;
   try { timeZone=ZoneId.of(zones.getFirst()); }
   catch(DateTimeException | NullPointerException ex) {
    throw new BusinessProblem("TENANT_CONFIGURATION_INVALID",503,"A configuração do condomínio precisa ser revisada.");
   }
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
   var memberships=jdbc.query("""
    select m.id,m.unit_id,m.role,
      coalesce(m.permissions#>'{reservations,create}'='true'::jsonb,false) as can_create,
      coalesce(m.permissions#>'{reservations,cancel}'='true'::jsonb,false) as can_cancel,
      coalesce(m.permissions#>'{parcels,view}'='true'::jsonb,false) as can_view_parcels
    from cp.memberships m
    left join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id
    where m.tenant_id=? and m.account_id=? and m.status='ACTIVE'
      and m.valid_from<=now() and(m.valid_until is null or m.valid_until>now())
      and(m.unit_id is null or u.status='ACTIVE')
    order by m.id
    """,(r,n)->new TenantContext.Membership(r.getObject("id",UUID.class),
      r.getObject("unit_id",UUID.class),r.getString("role"),r.getBoolean("can_create"),r.getBoolean("can_cancel"),
      r.getBoolean("can_view_parcels")),
      tenantId,accountId);
   if(memberships.isEmpty()) throw denied();
   var context=new TenantContext(accountId,tenantId,timeZone,memberships,assurance);
   // Capacidades de equipe entram no contexto local somente após vínculo vigente e AAL2.
   String capabilities=context.effectiveCapabilities().stream().map(StaffCapability::key).sorted()
    .collect(Collectors.joining(","));
   if(!capabilities.isEmpty())
    jdbc.queryForObject("select set_config('app.capabilities',?,true)",String.class,capabilities);
   return action.apply(context);
  });
 }
 private BusinessProblem denied() {
  return new BusinessProblem("TENANT_ACCESS_DENIED",404,"Condomínio não disponível para este acesso.");
 }
}
