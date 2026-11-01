package br.com.condpulse.partners.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import br.com.condpulse.partners.application.SecurityCommandModels;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comando de Segurança (V054) na transação do parceiro (app.partner_id e capacidades do papel publicadas). Consultas de
 * condomínio rodam depois de {@link #enterTenant}; o banco confere o alcance de líder (platform.partner_reach) em cada
 * política e função, não só a capacidade publicada.
 */
public class SecurityCommandStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;
 public SecurityCommandStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public record ReachedTenant(UUID tenantId,String name,String timeZone) {}

 /** Condomínios ativos que a conta alcança como líder (security.command) neste parceiro, por nome. */
 public List<ReachedTenant> commandTenants(UUID partner,int limit) {
  return jdbc.query("""
   select distinct t.id,t.name,t.time_zone from platform.partner_reach() r join platform.tenants t on t.id=r.tenant_id and t.active
   where r.partner_id=? and 'security.command'=any(platform.partner_role_capabilities(r.role))
   order by t.name,t.id limit ?
   """,(r,n)->new ReachedTenant(r.getObject("id",UUID.class),r.getString("name"),r.getString("time_zone")),partner,limit);
 }
 /**
  * Operadores e supervisores com papel e conta ativos e escala vigente agora, por condomínio (leitura da V054, só o próprio
  * parceiro): mesma regra de platform.partner_reach, sem contar conta suspensa ou encerrada.
  */
 public Map<UUID,Integer> operatorsOnDuty(UUID partner) {
  var counts=new TreeMap<UUID,Integer>();
  jdbc.query("""
   select s.tenant_id,count(distinct s.account_id) as n from cp.operator_schedules s
   join platform.partner_members m on m.id=s.member_id and m.partner_id=s.partner_id and m.status='ACTIVE'
    and m.role in ('REMOTE_OPERATOR','SECURITY_SUPERVISOR')
   join platform.accounts a on a.id=s.account_id and a.status='ACTIVE'
   where s.partner_id=? and s.status='ACTIVE' and s.starts_at<=now() and s.ends_at>now() group by s.tenant_id
   """,r->{ counts.put(r.getObject("tenant_id",UUID.class),r.getInt("n")); },partner);
  return counts;
 }
 public List<SecurityCommandModels.TeamMember> team(UUID partner) {
  return jdbc.query("""
   select id,account_id,role,display_name from platform.partner_members
   where partner_id=? and status='ACTIVE' and role in ('SECURITY_LEAD','SECURITY_SUPERVISOR','REMOTE_OPERATOR')
   order by role,display_name,id
   """,(r,n)->new SecurityCommandModels.TeamMember(r.getObject("id",UUID.class),r.getObject("account_id",UUID.class),
    r.getString("role"),r.getString("display_name")),partner);
 }

 /** Condomínio do contexto (política de gravação do log e RLS). Na mesma transação, troca a cada condomínio. */
 public void enterTenant(UUID tenant) {
  jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
 }
 public void leaveTenant() { jdbc.queryForObject("select set_config('app.tenant_id','',true)",String.class); }

 public SecurityCommandModels.Indicators summary(long healthStaleSeconds,long heartbeatStaleSeconds,int operatorsOnDuty) {
  return jdbc.queryForObject("select * from cp.security_command_summary(?,?)",(r,n)->new SecurityCommandModels.Indicators(
   r.getInt("devices_total"),r.getInt("devices_offline"),r.getInt("devices_unknown"),r.getInt("security_occurrences_open"),
   r.getInt("critical_alerts_unlinked"),r.getInt("incidents_open"),r.getInt("incidents_critical_open"),
   r.getInt("remote_opens_pending_review"),operatorsOnDuty),(int)healthStaleSeconds,(int)heartbeatStaleSeconds);
 }

 private static final String INCIDENT="""
  select i.id,i.tenant_id,t.name as tenant_name,i.partner_id,i.title,i.severity,i.state,i.opened_by,i.opened_at,i.assignee_member_id,
   i.assigned_at,i.outcome,i.report,i.closed_by,i.closed_at,i.version,
   (select count(*) from cp.incident_links l where l.tenant_id=i.tenant_id and l.incident_id=i.id) as link_count
  from cp.incidents i left join platform.tenants t on t.id=i.tenant_id
  """;
 private static SecurityCommandModels.Incident incident(ResultSet r,int n) throws SQLException {
  return new SecurityCommandModels.Incident(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("tenant_name"),
   r.getObject("partner_id",UUID.class),r.getString("title"),r.getString("severity"),r.getString("state"),r.getObject("opened_by",UUID.class),
   instant(r,"opened_at"),r.getObject("assignee_member_id",UUID.class),instant(r,"assigned_at"),r.getString("outcome"),r.getString("report"),
   r.getObject("closed_by",UUID.class),instant(r,"closed_at"),r.getLong("version"),r.getInt("link_count"));
 }
 /** Incidentes do condomínio do contexto (RLS: só do parceiro com alcance de líder). */
 public List<SecurityCommandModels.Incident> incidents(UUID tenant,String state,int limit) {
  return jdbc.query(INCIDENT+" where i.tenant_id=? and i.partner_id=platform.current_partner_id() and i.state=? order by i.opened_at desc,i.id limit ?",
   SecurityCommandStore::incident,tenant,state,limit);
 }
 public SecurityCommandModels.Incident incident(UUID tenant,UUID id) {
  var rows=jdbc.query(INCIDENT+" where i.tenant_id=? and i.partner_id=platform.current_partner_id() and i.id=?",SecurityCommandStore::incident,tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertIncident(UUID id,UUID tenant,String title,String severity) {
  jdbc.update("insert into cp.incidents(id,tenant_id,title,severity) values(?,?,?,?)",id,tenant,title,severity);
 }
 /** Anexa só o que ainda não está anexado; devolve quantos entraram. */
 public int link(UUID tenant,UUID incident,List<UUID> events) {
  int added=0;
  for(UUID event:events)
   added+=jdbc.update("""
    insert into cp.incident_links(tenant_id,incident_id,log_event_id)
    select ?,?,? where not exists(select 1 from cp.incident_links where incident_id=? and log_event_id=?)
    """,tenant,incident,event,incident,event);
  return added;
 }
 public List<SecurityCommandModels.IncidentLink> links(UUID tenant,UUID incident) {
  return jdbc.query("select log_event_id,linked_by,linked_at from cp.incident_links where tenant_id=? and incident_id=? order by linked_at,log_event_id",
   (r,n)->new SecurityCommandModels.IncidentLink(r.getObject("log_event_id",UUID.class),r.getObject("linked_by",UUID.class),
    instant(r,"linked_at")),tenant,incident);
 }
 public boolean assign(UUID tenant,UUID incident,UUID member,long expectedVersion) {
  return jdbc.update("update cp.incidents set assignee_member_id=?,version=version+1 where tenant_id=? and id=? and state='OPEN' and version=?",
   member,tenant,incident,expectedVersion)==1;
 }
 public boolean close(UUID tenant,UUID incident,String outcome,String report,long expectedVersion) {
  return jdbc.update("""
   update cp.incidents set state='CLOSED',outcome=?,report=?,version=version+1 where tenant_id=? and id=? and state='OPEN' and version=?
   """,outcome,report,tenant,incident,expectedVersion)==1;
 }
 /** Todos os eventos da cadeia conferidos no banco: digest recalculado e elo com o evento anterior do condomínio. */
 public boolean eventsIntact(UUID tenant,List<UUID> events) {
  if(events.isEmpty()) return true;
  Integer broken=jdbc.queryForObject("""
   select count(*) from cp.security_log s where s.tenant_id=? and s.id=any(?::uuid[])
    and (cp.security_log_digest(s.prev_hash,s)<>s.hash
     or not ((s.seq=1 and s.prev_hash=repeat('0',64))
      or exists(select 1 from cp.security_log b where b.tenant_id=s.tenant_id and b.seq=s.seq-1 and b.hash=s.prev_hash)))
   """,Integer.class,tenant,array(events));
  return broken!=null && broken==0;
 }
 /** EVIDENCE_EXPORTED (motivo obrigatório no banco), correlacionado ao incidente; devolve o horário do banco. */
 public Instant recordDossier(UUID tenant,UUID eventId,UUID incident,String reason,Map<String,Object> details) {
  jdbc.update("""
   insert into cp.security_log(id,tenant_id,event_type,severity,source,subject_type,subject_id,correlation_id,reason,details)
   values(?,?,'EVIDENCE_EXPORTED','INFO','REMOTE_CENTRAL','INCIDENT',?,?,?,?::jsonb)
   """,eventId,tenant,incident,incident,reason,JSON.writeValueAsString(new TreeMap<>(details)));
  return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
 }

 /** Autor de um REMOTE_OPEN do condomínio do contexto; null quando não existe (ou não é legível). */
 public record OpenRef(boolean exists,UUID actor) {}
 public OpenRef remoteOpen(UUID tenant,UUID event) {
  var rows=jdbc.query("select actor_account_id from cp.security_log where tenant_id=? and id=? and event_type='REMOTE_OPEN'",
   (r,n)->r.getObject("actor_account_id",UUID.class),tenant,event);
  return rows.isEmpty()?new OpenRef(false,null):new OpenRef(true,rows.getFirst());
 }
 public void insertReview(UUID tenant,UUID id,UUID subject,String verdict,String note,UUID corrects) {
  jdbc.update("""
   insert into cp.security_log(id,tenant_id,event_type,severity,source,subject_type,subject_id,reason,corrects_id,details)
   values(?,?,'REMOTE_OPEN_REVIEWED',?,'REMOTE_CENTRAL','SECURITY_LOG_EVENT',?,?,?,?::jsonb)
   """,id,tenant,"IRREGULAR".equals(verdict)?"ATTENTION":"INFO",subject,note,corrects,JSON.writeValueAsString(Map.of("verdict",verdict)));
 }
 /**
  * Aberturas remotas do condomínio (mais recentes primeiro) com a última revisão; até limit+1 linhas para saber se há
  * próxima página.
  */
 public List<SecurityCommandModels.RemoteOpen> remoteOpens(UUID tenant,String filter,UUID operator,Long beforeSeq,int limit) {
  return remoteOpens(tenant,filter,operator,beforeSeq,limit,null);
 }
 /** Uma abertura com a revisão mais recente (depois de revisar). */
 public SecurityCommandModels.RemoteOpen remoteOpenItem(UUID tenant,UUID event) {
  var rows=remoteOpens(tenant,"ALL",null,null,1,event);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private List<SecurityCommandModels.RemoteOpen> remoteOpens(UUID tenant,String filter,UUID operator,Long beforeSeq,int limit,UUID only) {
  var sql=new StringBuilder("""
   select o.id,o.seq,o.occurred_at,o.recorded_at,o.actor_account_id,o.actor_role,o.reason,o.unit_id,o.point_id,o.device_id,o.evidence_ref,
    v.id as review_id,v.details->>'verdict' as verdict,v.reason as note,v.actor_account_id as reviewed_by,v.recorded_at as reviewed_at
   from cp.security_log o
   left join lateral (select r.id,r.details,r.reason,r.actor_account_id,r.recorded_at from cp.security_log r
    where r.tenant_id=o.tenant_id and r.event_type='REMOTE_OPEN_REVIEWED' and r.subject_id=o.id order by r.seq desc limit 1) v on true
   where o.tenant_id=? and o.event_type='REMOTE_OPEN'
   """);
  var args=new java.util.ArrayList<Object>(); args.add(tenant);
  if("PENDING".equals(filter)) sql.append(" and v.id is null");
  if("REVIEWED".equals(filter)) sql.append(" and v.id is not null");
  if(only!=null) { sql.append(" and o.id=?"); args.add(only); }
  if(operator!=null) { sql.append(" and o.actor_account_id=?"); args.add(operator); }
  if(beforeSeq!=null) { sql.append(" and o.seq<?"); args.add(beforeSeq); }
  sql.append(" order by o.seq desc limit ?"); args.add(limit+1);
  return jdbc.query(sql.toString(),(r,n)->{
   UUID review=r.getObject("review_id",UUID.class);
   return new SecurityCommandModels.RemoteOpen(r.getObject("id",UUID.class),r.getLong("seq"),instant(r,"occurred_at"),instant(r,"recorded_at"),
    r.getObject("actor_account_id",UUID.class),r.getString("actor_role"),r.getString("reason"),r.getObject("unit_id",UUID.class),
    r.getObject("point_id",UUID.class),r.getObject("device_id",UUID.class),r.getString("evidence_ref"),
    review==null?null:new SecurityCommandModels.Review(review,r.getString("verdict"),r.getString("note"),r.getObject("reviewed_by",UUID.class),
     instant(r,"reviewed_at")));
  },args.toArray());
 }

 static String array(List<UUID> values) {
  return values.stream().map(UUID::toString).collect(Collectors.joining(",","{","}"));
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
 }
}
