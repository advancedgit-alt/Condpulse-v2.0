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
    case when jsonb_typeof(r.counts->'amenities')='number' then (r.counts->>'amenities')::integer end as amenities
  from platform.tenants t
  left join platform.onboarding_runs r on r.tenant_id=t.id
  """;
 private static final RowMapper<PlatformModels.Tenant> TENANT=(r,n)->{
  UUID operation=r.getObject("operation_id",UUID.class);
  var receipt=operation==null?null:new PlatformModels.OnboardingReceipt(operation,integer(r,"accounts"),integer(r,"units"),
   integer(r,"memberships"),integer(r,"amenities"),r.getTimestamp("onboarded_at").toInstant());
  return new PlatformModels.Tenant(r.getObject("id",UUID.class),r.getString("name"),r.getString("time_zone"),
   r.getBoolean("active"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),
   r.getTimestamp("updated_at").toInstant(),receipt);
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
