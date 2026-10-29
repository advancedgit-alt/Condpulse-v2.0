package br.com.condpulse.visits.infrastructure;

import br.com.condpulse.visits.application.VisitModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leitura de quem abre o link de convite (CP-037), sem conta: transação própria, contexto limpo, sem condomínio nem
 * ator, só o hash do token definido localmente. As políticas do banco (V020) mostram apenas o link daquele hash, o
 * convite dele, a pessoa e o nome do condomínio, e só enquanto o link estiver em vigor.
 */
@Repository @Profile("api")
public class PermitLinkPublicStore {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public PermitLinkPublicStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setReadOnly(true); tx.setTimeout(5);
 }
 public VisitModels.PublicPermit view(String tokenHash) {
  return tx.execute(status->{
   for(String setting:new String[]{"app.actor_id","app.tenant_id","app.session_hash","app.capabilities","app.invitation_hash",
     "app.platform_capabilities","app.auth_rate_key"})
    jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.permit_link_hash',?,true)",String.class,tokenHash);
   var rows=jdbc.query("""
    select t.name as tenant_name,t.time_zone,v.full_name,p.purpose,p.valid_from,p.valid_until,
     case when p.state<>'ACTIVE' then 'REVOKED' when p.valid_until<=clock_timestamp() then 'EXPIRED'
      when p.valid_from>clock_timestamp() then 'SCHEDULED' else 'ACTIVE' end as effective_state
    from cp.permit_links l
    join cp.visit_permits p on p.tenant_id=l.tenant_id and p.id=l.permit_id
    join cp.visitors v on v.tenant_id=p.tenant_id and v.id=p.visitor_id
    join platform.tenants t on t.id=l.tenant_id
    where l.token_hash=? and l.revoked_at is null
    """,(r,n)->new VisitModels.PublicPermit(r.getString("tenant_name"),firstName(r.getString("full_name")),r.getString("purpose"),
      r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until").toInstant(),r.getString("time_zone"),
      r.getString("effective_state")),tokenHash);
   return rows.isEmpty()?null:rows.getFirst();
  });
 }
 /** Só o primeiro nome: quem tem o link é a própria pessoa convidada, mas o link pode ser repassado. */
 static String firstName(String full) {
  if(full==null) return null;
  String trimmed=full.strip();
  int space=trimmed.indexOf(' ');
  return space<0?trimmed:trimmed.substring(0,space);
 }
}
