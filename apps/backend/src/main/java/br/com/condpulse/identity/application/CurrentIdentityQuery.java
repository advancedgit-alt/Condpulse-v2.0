package br.com.condpulse.identity.application;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("api")
public class CurrentIdentityQuery {
 private final JdbcTemplate jdbc;
 public CurrentIdentityQuery(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @Transactional(readOnly=true)
 public CurrentIdentity execute(UUID accountId) {
  jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,accountId.toString());
  jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class);
  Integer active=jdbc.queryForObject(
    "select count(*) from platform.accounts where id=? and status='ACTIVE'",Integer.class,accountId);
  if(active==null || active!=1)
   throw new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão.");
  var memberships=jdbc.query("""
    select m.id,m.tenant_id,t.name,t.time_zone,m.unit_id,m.role
    from cp.memberships m join platform.tenants t on t.id=m.tenant_id
    where m.account_id=? and m.status='ACTIVE' and m.valid_from<=now()
     and(m.valid_until is null or m.valid_until>now()) and t.active
    order by t.name,m.id
    """,(row,index)->new CurrentIdentity.Membership(
      row.getObject("id",UUID.class),row.getObject("tenant_id",UUID.class),
      row.getString("name"),row.getString("time_zone"),row.getObject("unit_id",UUID.class),
      row.getString("role")),accountId);
  return new CurrentIdentity(accountId,memberships);
 }
}
