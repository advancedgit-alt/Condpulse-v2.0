package br.com.condpulse.identity.application;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.partners.application.PartnerCapability;
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
 private final boolean partnersEnabled;
 /** Sem flags conhecidas nenhum módulo aparece como liberado (falha fechada no menu). */
 public CurrentIdentityQuery(JdbcTemplate jdbc) { this(jdbc,new TenantModuleFlags(java.util.EnumSet.noneOf(TenantModule.class))); }
 public CurrentIdentityQuery(JdbcTemplate jdbc,TenantModuleFlags flags) { this(jdbc,flags,false); }
 /** Com a hierarquia de parceiro desligada (CP_PARTNERS_ENABLED=false) partnerContexts volta vazio. */
 public CurrentIdentityQuery(JdbcTemplate jdbc,TenantModuleFlags flags,boolean partnersEnabled) {
  this.jdbc=jdbc; this.flags=flags; this.partnersEnabled=partnersEnabled;
 }
 /** Flags lidas do próprio ambiente: não depende da configuração web (contextos parciais continuam válidos). */
 @Autowired
 public CurrentIdentityQuery(JdbcTemplate jdbc,org.springframework.core.env.Environment environment) {
  this(jdbc,TenantModuleFlags.from(environment),environment.getProperty("condpulse.partners.enabled",Boolean.class,false));
 }
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
  var partners=partnerContexts();
  var reached=new LinkedHashSet<UUID>();
  for(var membership:memberships) reached.add(membership.tenantId());
  for(var context:partners) for(var tenant:context.tenants()) reached.add(tenant.tenantId());
  return new CurrentIdentity(accountId,memberships,modules(reached),partners,demoTenants(reached));
 }

 /** Condomínios alcançados marcados como DEMO (V055); a linha é visível pelas mesmas políticas de vínculo e alcance. */
 private List<UUID> demoTenants(java.util.Set<UUID> tenants) {
  var result=new ArrayList<UUID>();
  for(UUID tenant:tenants)
   if(Boolean.TRUE.equals(jdbc.queryForObject("select coalesce(bool_or(demo),false) from platform.tenants where id=?",Boolean.class,tenant)))
    result.add(tenant);
  return result;
 }

 /**
  * Papéis ativos de parceiro (leitura própria, V049) e os condomínios alcançados agora por platform.partner_reach(), que
  * aplica contrato ativo, parceiro ativo e escala vigente no banco.
  */
 private List<CurrentIdentity.PartnerContext> partnerContexts() {
  if(!partnersEnabled) return List.of();
  record Row(UUID memberId,UUID partnerId,String partnerName,String role) {}
  var roles=jdbc.query("""
    select m.id,m.partner_id,p.name,m.role from platform.partner_members m
    join platform.partners p on p.id=m.partner_id and p.status='ACTIVE'
    where m.account_id=platform.current_actor_id() and m.status='ACTIVE'
    order by p.name,m.partner_id,m.role
    """,(r,n)->new Row(r.getObject("id",UUID.class),r.getObject("partner_id",UUID.class),r.getString("name"),r.getString("role")));
  if(roles.isEmpty()) return List.of();
  var tenants=new HashMap<UUID,List<CurrentIdentity.PartnerTenant>>();
  jdbc.query("""
    select r.member_id,t.id,t.name,t.time_zone from platform.partner_reach() r
    join platform.tenants t on t.id=r.tenant_id and t.active
    order by t.name,t.id
    """,(org.springframework.jdbc.core.RowCallbackHandler)r->tenants.computeIfAbsent(r.getObject("member_id",UUID.class),k->new ArrayList<>())
     .add(new CurrentIdentity.PartnerTenant(r.getObject("id",UUID.class),r.getString("name"),r.getString("time_zone"))));
  var result=new ArrayList<CurrentIdentity.PartnerContext>();
  for(var row:roles)
   result.add(new CurrentIdentity.PartnerContext(row.memberId(),row.partnerId(),row.partnerName(),row.role(),
    PartnerCapability.panel(row.role()),PartnerCapability.forRole(row.role()).stream().map(PartnerCapability::key).sorted().toList(),
    tenants.getOrDefault(row.memberId(),List.of())));
  return result;
 }

 /**
  * Estado por condomínio dos vínculos e dos alcançados por parceiro; a linha é visível pela política de vínculo ativo da
  * própria conta (V045) ou de alcance de parceiro (V049).
  */
 private List<CurrentIdentity.TenantModules> modules(java.util.Set<UUID> tenants) {
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
