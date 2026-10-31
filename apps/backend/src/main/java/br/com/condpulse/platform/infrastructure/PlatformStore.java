package br.com.condpulse.platform.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import br.com.condpulse.audit.application.AuditQuery;
import br.com.condpulse.platform.application.PlatformModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL do painel master. Executado somente dentro do PlatformTransactionExecutor: as políticas de platform.tenants,
 * onboarding_runs e platform_audit_log exigem a capacidade publicada no contexto local da transação.
 */
@Repository @Profile("api")
public class PlatformStore {
 public static final int TENANT_PAGE=200;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;
 public PlatformStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static final String TENANT_SELECT="""
  select t.id,t.name,t.time_zone,t.active,t.version,t.created_at,t.updated_at,
    r.operation_id,r.created_at as onboarded_at,
    case when jsonb_typeof(r.counts->'accounts')='number' then (r.counts->>'accounts')::integer end as accounts,
    case when jsonb_typeof(r.counts->'units')='number' then (r.counts->>'units')::integer end as units,
    case when jsonb_typeof(r.counts->'memberships')='number' then (r.counts->>'memberships')::integer end as memberships,
    case when jsonb_typeof(r.counts->'amenities')='number' then (r.counts->>'amenities')::integer end as amenities,
    case when r.operation_id is not null then 'ONBOARDING'
     when exists(select 1 from platform.tenant_creations c where c.tenant_id=t.id) then 'PLATFORM' end as origin,
    (select count(*) from cp.units u where u.tenant_id=t.id and u.status='ACTIVE') as active_units,
    (select count(*) from cp.memberships m where m.tenant_id=t.id and m.status='ACTIVE'
      and m.valid_from<=now() and (m.valid_until is null or m.valid_until>now())) as active_memberships,
    (select count(*) from cp.memberships m where m.tenant_id=t.id and m.status='ACTIVE' and m.role='MANAGER'
      and m.valid_from<=now() and (m.valid_until is null or m.valid_until>now())) as active_managers,
    greatest(t.updated_at,(select max(m.updated_at) from cp.memberships m where m.tenant_id=t.id),
     (select max(u.updated_at) from cp.units u where u.tenant_id=t.id),
     (select max(a.created_at) from platform.platform_audit_log a where a.tenant_id=t.id)) as last_activity_at
  from platform.tenants t
  left join platform.onboarding_runs r on r.tenant_id=t.id
  """;
 private static final RowMapper<PlatformModels.Tenant> TENANT=(r,n)->{
  UUID operation=r.getObject("operation_id",UUID.class);
  var receipt=operation==null?null:new PlatformModels.OnboardingReceipt(operation,integer(r,"accounts"),integer(r,"units"),
   integer(r,"memberships"),integer(r,"amenities"),r.getTimestamp("onboarded_at").toInstant());
  var usage=new PlatformModels.TenantUsage(r.getLong("active_units"),r.getLong("active_memberships"),r.getLong("active_managers"),
   r.getTimestamp("last_activity_at").toInstant());
  return new PlatformModels.Tenant(r.getObject("id",UUID.class),r.getString("name"),r.getString("time_zone"),
   r.getBoolean("active"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),
   r.getTimestamp("updated_at").toInstant(),receipt,r.getString("origin"),usage,null);
 };
 private static Integer integer(ResultSet r,String column) throws SQLException {
  int value=r.getInt(column);
  return r.wasNull()?null:value;
 }

 /** Catálogo em ordem alfabética; com busca, filtra por trecho do nome (curingas escapados) ou pelo id exato. */
 public PlatformModels.TenantPage tenants(String search) {
  var rows=search==null
   ?jdbc.query(TENANT_SELECT+" order by t.name,t.id limit ?",TENANT,TENANT_PAGE+1)
   :jdbc.query(TENANT_SELECT+" where t.name ilike ? escape '\\' or t.id::text=? order by t.name,t.id limit ?",TENANT,
     "%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%",search.toLowerCase(java.util.Locale.ROOT),TENANT_PAGE+1);
  return new PlatformModels.TenantPage(rows.stream().limit(TENANT_PAGE).toList(),rows.size()>TENANT_PAGE);
 }
 /** Leitura com bloqueio da linha: a decisão de idempotência e a gravação veem o mesmo estado. */
 public PlatformModels.Tenant lockTenant(UUID tenant) {
  // NO KEY UPDATE: não conflita com as travas das chaves estrangeiras de cp.* durante a alteração.
  var rows=jdbc.query(TENANT_SELECT+" where t.id=? for no key update of t",TENANT,tenant);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PlatformModels.Tenant tenant(UUID tenant) {
  var rows=jdbc.query(TENANT_SELECT+" where t.id=?",TENANT,tenant);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean updateTenant(UUID tenant,String name,boolean active,long expectedVersion) {
  return jdbc.update("""
   update platform.tenants set name=?,active=?,version=version+1,updated_at=now()
   where id=? and version=?
   """,name,active,tenant,expectedVersion)==1;
 }
 public void audit(UUID actor,String action,UUID tenant,String reason,Map<String,String> details) {
  if(action==null || !action.matches("[A-Z][A-Z_]{2,63}")) throw new IllegalArgumentException("Ação de auditoria inválida.");
  jdbc.update("""
   insert into platform.platform_audit_log(actor_id,action,tenant_id,reason,details) values(?,?,?,?,?::jsonb)
   """,actor,action,tenant,reason,JSON.writeValueAsString(new TreeMap<>(details)));
 }
 /** Mesmo cursor opaco da auditoria do condomínio; duas formas fixas para o plano manter o índice. */
 public PlatformModels.AuditPage auditPage(AuditQuery.Cursor after,int limit) {
  RowMapper<PlatformModels.AuditEvent> mapper=(r,n)->new PlatformModels.AuditEvent(r.getObject("id",UUID.class),
   r.getObject("actor_id",UUID.class),r.getString("action"),r.getObject("tenant_id",UUID.class),r.getString("reason"),
   details(r.getString("details")),r.getTimestamp("created_at").toInstant());
  var rows=after==null
   ?jdbc.query("""
     select id,actor_id,action,tenant_id,reason,details::text as details,created_at from platform.platform_audit_log
     order by created_at desc,id desc limit ?
     """,mapper,limit+1)
   :jdbc.query("""
     select id,actor_id,action,tenant_id,reason,details::text as details,created_at from platform.platform_audit_log
     where (created_at,id)<(?::timestamptz,?::uuid) order by created_at desc,id desc limit ?
     """,mapper,after.timestamp(),after.id(),limit+1);
  boolean more=rows.size()>limit;
  var events=rows.stream().limit(limit).toList();
  String next=more?new AuditQuery.Cursor(events.getLast().createdAt(),events.getLast().eventId()).encode():null;
  return new PlatformModels.AuditPage(events,next);
 }

 /** Situação da assinatura por condomínio (financeiro ligado): assinatura ativa, cobranças em aberto e vencidas. */
 public Map<UUID,PlatformModels.TenantBilling> billing(java.util.Collection<UUID> tenants) {
  var result=new java.util.HashMap<UUID,PlatformModels.TenantBilling>();
  if(tenants.isEmpty()) return result;
  String ids=tenants.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
  jdbc.query("""
   select t.id,
    exists(select 1 from platform.billing_subscriptions s where s.tenant_id=t.id and s.status='ACTIVE') as active_subscription,
    (select count(*) from platform.billing_charges c where c.tenant_id=t.id and c.status='OPEN') as open_charges,
    (select count(*) from platform.billing_charges c where c.tenant_id=t.id and c.status='OPEN'
      and c.due_on<(clock_timestamp() at time zone 'America/Sao_Paulo')::date) as overdue_charges
   from platform.tenants t where t.id=any(string_to_array(?,',')::uuid[])
   """,(org.springframework.jdbc.core.RowCallbackHandler)r->result.put(r.getObject("id",UUID.class),
    new PlatformModels.TenantBilling(r.getBoolean("active_subscription"),r.getLong("open_charges"),r.getLong("overdue_charges"))),ids);
  return result;
 }
 /** Gestores (vínculos MANAGER não revogados) do condomínio: nome, situação e início; nunca conta ou contato. */
 public java.util.List<PlatformModels.Manager> managers(UUID tenant) {
  return jdbc.query("""
   select id,display_name,status,valid_from from cp.memberships
   where tenant_id=? and role='MANAGER' and status<>'REVOKED' order by status,display_name,id limit 50
   """,(r,n)->new PlatformModels.Manager(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("status"),
    r.getTimestamp("valid_from").toInstant()),tenant);
 }
 public java.util.List<PlatformModels.ManagerInvitation> managerInvitations(UUID tenant) {
  return jdbc.query("""
   select id,display_name,case when status='PENDING' and expires_at<=now() then 'EXPIRED' else status end as status,
    created_at,expires_at,accepted_at,revoked_at
   from platform.manager_invitations where tenant_id=? order by created_at desc,id desc limit 20
   """,(r,n)->new PlatformModels.ManagerInvitation(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("status"),
    r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),instant(r,"accepted_at"),
    instant(r,"revoked_at")),tenant);
 }
 public java.util.List<PlatformModels.AuditEvent> recentEvents(UUID tenant,int limit) {
  return jdbc.query("""
   select id,actor_id,action,tenant_id,reason,details::text as details,created_at from platform.platform_audit_log
   where tenant_id=? order by created_at desc,id desc limit ?
   """,(r,n)->new PlatformModels.AuditEvent(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("action"),
    r.getObject("tenant_id",UUID.class),r.getString("reason"),details(r.getString("details")),r.getTimestamp("created_at").toInstant()),
   tenant,limit);
 }
 private static java.time.Instant instant(ResultSet r,String column) throws SQLException {
  var value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }

 /** Criação registrada pela mesma conta e chave (idempotência), quando existir. */
 public record Creation(UUID tenantId,String requestHash) {}
 public Creation creation(UUID actor,String key) {
  var rows=jdbc.query("select tenant_id,request_hash from platform.tenant_creations where created_by=? and idempotency_key=?",
   (r,n)->new Creation(r.getObject("tenant_id",UUID.class),r.getString("request_hash")),actor,key);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean timeZoneKnown(String zone) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_timezone_names where name=?)",Boolean.class,zone));
 }
 /**
  * Registro de criação, condomínio e unidades na transação da plataforma; o banco só aceita condomínio e unidades da
  * criação registrada nesta mesma transação pela mesma conta (V035). O contexto local passa a ser o condomínio novo.
  */
 public void insertTenant(UUID actor,String key,String requestHash,UUID tenant,String name,String zone,java.util.List<String> units,
   int amenities) {
  jdbc.update("""
   insert into platform.tenant_creations(tenant_id,created_by,idempotency_key,request_hash,counts) values(?,?,?,?,?::jsonb)
   """,tenant,actor,key,requestHash,"{\"amenities\":"+amenities+",\"units\":"+units.size()+"}");
  jdbc.update("insert into platform.tenants(id,name,time_zone,active) values(?,?,?,true)",tenant,name,zone);
  jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  jdbc.batchUpdate("insert into cp.units(tenant_id,label) values(?,?)",units.stream().map(label->new Object[]{tenant,label}).toList());
 }
 /** Área inicial com a regra já validada pelas mesmas regras da gestão local; versão de regra 1. */
 public UUID insertAmenity(UUID tenant,br.com.condpulse.reservations.application.AmenityManagementModels.ValidAmenity value) {
  UUID id=UUID.randomUUID();
  jdbc.update("""
   insert into cp.amenities(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active)
   values(?,?,?,?,?,?,?,1,?::jsonb,true)
   """,id,tenant,value.name(),value.capacity(),value.approvalRequired(),value.feeCents(),value.bufferMinutes(),value.policyJson());
  return id;
 }
 public boolean tenantHasActiveManager(UUID tenant) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.memberships where tenant_id=? and role='MANAGER' and status='ACTIVE'
    and (valid_until is null or valid_until>now()))
   """,Boolean.class,tenant));
 }
 /** Revoga o convite pendente (um por condomínio); devolve o id revogado ou null. */
 public UUID revokePendingInvitation(UUID tenant,UUID actor,String reason) {
  var rows=jdbc.query("""
   update platform.manager_invitations set status='REVOKED',revoked_by=?,revoked_at=now(),revoke_reason=?,version=version+1
   where tenant_id=? and status='PENDING' returning id
   """,(r,n)->r.getObject("id",UUID.class),actor,reason,tenant);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean revokeInvitation(UUID tenant,UUID invitation,UUID actor,String reason) {
  return jdbc.update("""
   update platform.manager_invitations set status='REVOKED',revoked_by=?,revoked_at=now(),revoke_reason=?,version=version+1
   where tenant_id=? and id=? and status='PENDING'
   """,actor,reason,tenant,invitation)==1;
 }
 public java.time.Instant insertInvitation(UUID id,UUID tenant,String name,String emailHash,String tokenHash,int days,UUID actor) {
  return jdbc.queryForObject("""
   insert into platform.manager_invitations(id,tenant_id,display_name,email_hash,token_hash,expires_at,created_by)
   values(?,?,?,?,?,now()+make_interval(days=>?),?) returning expires_at
   """,(r,n)->r.getTimestamp(1).toInstant(),id,tenant,name,emailHash,tokenHash,days,actor);
 }

 /** Detalhes gravados por este módulo são objetos planos de textos; qualquer outra forma é omitida da leitura. */
 static Map<String,String> details(String json) {
  if(json==null) return Map.of();
  try {
   Map<?,?> raw=JSON.readValue(json,Map.class);
   var result=new LinkedHashMap<String,String>();
   for(var entry:raw.entrySet()) {
    if(!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) return Map.of();
    result.put(key,value);
   }
   return result;
  } catch(RuntimeException invalid) {
   return Map.of();
  }
 }
}
