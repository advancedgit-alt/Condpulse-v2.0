package br.com.condpulse.identity.application;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantModule;
import br.com.condpulse.tenancy.application.TenantModuleFlags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("api")
public class CurrentIdentityQuery {
 private final JdbcTemplate jdbc;
 private final TenantModuleFlags flags;
 /** Sem flags conhecidas nenhum módulo aparece como liberado (falha fechada no menu). */
 public CurrentIdentityQuery(JdbcTemplate jdbc) { this(jdbc,new TenantModuleFlags(java.util.EnumSet.noneOf(TenantModule.class))); }
 @Autowired
 public CurrentIdentityQuery(JdbcTemplate jdbc,TenantModuleFlags flags) { this.jdbc=jdbc; this.flags=flags; }
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
  return new CurrentIdentity(accountId,memberships,modules(memberships));
 }

 /** Estado por condomínio dos vínculos; a linha é visível pela política de vínculo ativo da própria conta (V044). */
 private List<CurrentIdentity.TenantModules> modules(List<CurrentIdentity.Membership> memberships) {
  var tenants=new LinkedHashSet<UUID>();
  for(var membership:memberships) tenants.add(membership.tenantId());
  if(tenants.isEmpty()) return List.of();
  var disabled=new HashMap<UUID,java.util.Set<String>>();
  for(UUID tenant:tenants)
   disabled.put(tenant,new java.util.HashSet<>(jdbc.queryForList(
    "select module from platform.tenant_modules where tenant_id=? and not enabled",String.class,tenant)));
  var result=new ArrayList<CurrentIdentity.TenantModules>();
  for(UUID tenant:tenants) {
   var off=disabled.getOrDefault(tenant,java.util.Set.of());
   var enabled=new ArrayList<String>();
   for(TenantModule module:TenantModule.values())
    if(flags.globallyEnabled(module) && !off.contains(module.key())) enabled.add(module.key());
   result.add(new CurrentIdentity.TenantModules(tenant,enabled));
  }
  return result;
 }
}
