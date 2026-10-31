package br.com.condpulse.accessgovernance.application;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
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
 * Fronteira do MASTER de implantação (AC-003, V037). Não usa vínculo residencial nem capacidades de equipe: exige conta
 * ativa, condomínio ativo, concessão técnica vigente e AAL2 (administração técnica sempre pede segundo fator). Os escopos
 * entram em app.technical_scopes só depois disso; o banco confere de novo a concessão vigente (platform.has_technical_scope).
 */
public class TechnicalTransactionExecutor {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public TechnicalTransactionExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }
 private record Grant(String roleCode,String[] scopes,Instant validUntil) {}

 public <T> T execute(UUID accountId,UUID tenantId,AssuranceLevel assurance,Function<TechnicalContext,T> action) {
  if(accountId==null || tenantId==null) throw denied();
  Objects.requireNonNull(assurance); Objects.requireNonNull(action);
  return tx.execute(status->{
   for(String setting:new String[]{"app.session_hash","app.capabilities","app.invitation_hash","app.tenant_id",
     "app.platform_capabilities","app.permit_link_hash","app.occupancy_scan","app.finance_webhook_charge","app.technical_scopes","app.subject_context_scan",
     "app.recovery_hash","app.recovery_purge","app.manager_invitation_hash"})
    jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,accountId.toString());
   Integer active=jdbc.queryForObject("select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,accountId);
   if(active==null || active!=1)
    throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
   var grants=jdbc.query("""
    select role_code,scopes,valid_until from cp.technical_grants
    where tenant_id=? and account_id=? and status='ACTIVE' and valid_from<=now() and valid_until>now()
    order by valid_until,id
    """,(r,n)->new Grant(r.getString("role_code"),(String[])r.getArray("scopes").getArray(),
     r.getTimestamp("valid_until").toInstant()),tenantId,accountId);
   if(grants.isEmpty()) throw denied();
   var zones=jdbc.query("select time_zone from platform.tenants where id=? and active",(r,n)->r.getString("time_zone"),tenantId);
   if(zones.size()!=1) throw denied();
   ZoneId timeZone;
   try { timeZone=ZoneId.of(zones.getFirst()); }
   catch(DateTimeException | NullPointerException ex) {
    throw new BusinessProblem("TENANT_CONFIGURATION_INVALID",503,"A configuração do condomínio precisa ser revisada.");
   }
   if(assurance!=AssuranceLevel.AAL2)
    throw new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator de autenticação para continuar.");
   var scopes=EnumSet.noneOf(TechnicalScope.class);
   Instant until=null;
   for(var grant:grants) {
    // O banco já recusa escopo fora do papel; aqui só a interseção conhecida entra no contexto.
    var allowed=TechnicalScope.allowedFor(grant.roleCode());
    for(String key:grant.scopes()) TechnicalScope.fromKey(key).filter(allowed::contains).ifPresent(scopes::add);
    if(until==null || grant.validUntil().isAfter(until)) until=grant.validUntil();
   }
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
   String published=scopes.stream().map(TechnicalScope::key).sorted().collect(Collectors.joining(","));
   if(!published.isEmpty())
    jdbc.queryForObject("select set_config('app.technical_scopes',?,true)",String.class,published);
   return action.apply(new TechnicalContext(accountId,tenantId,timeZone,scopes,until));
  });
 }
 private static BusinessProblem denied() {
  return new BusinessProblem("TENANT_ACCESS_DENIED",404,"Condomínio não disponível para este acesso.");
 }
}
