package br.com.condpulse.accesscredentials.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesscredentials.application.CredentialModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL das credenciais (V026), sempre dentro do TenantTransactionExecutor; o banco repete papel, vínculo e transições. */
@Repository @Profile("api")
public class CredentialStore {
 public static final int PAGE=100;
 private static final String SELECT="""
  select c.id,c.connector_id,c.membership_id,c.unit_id,u.label as unit_label,m.display_name as holder_name,c.kind,c.label,c.desired_state,
   c.desired_version,c.valid_until,c.valid_until is not null and c.valid_until<=clock_timestamp() as expired,c.revoke_reason,c.revoked_at,c.created_at,c.version
  from cp.access_credentials c
  join cp.units u on u.tenant_id=c.tenant_id and u.id=c.unit_id
  left join cp.memberships m on m.tenant_id=c.tenant_id and m.id=c.membership_id
  """;
 private final JdbcTemplate jdbc;
 public CredentialStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private record Row(UUID id,UUID connector,UUID membership,UUID unit,String unitLabel,String holder,String kind,String label,String desiredState,
   long desiredVersion,Instant validUntil,boolean expired,String reason,Instant revokedAt,Instant createdAt,long version) {}
 private static Row row(ResultSet r) throws SQLException {
  return new Row(r.getObject("id",UUID.class),r.getObject("connector_id",UUID.class),r.getObject("membership_id",UUID.class),
   r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("holder_name"),r.getString("kind"),r.getString("label"),
   r.getString("desired_state"),r.getLong("desired_version"),instant(r.getTimestamp("valid_until")),r.getBoolean("expired"),r.getString("revoke_reason"),instant(r.getTimestamp("revoked_at")),
   instant(r.getTimestamp("created_at")),r.getLong("version"));
 }

 /** Gestão: todas (filtro opcional por unidade); pendingOnly: só as que ainda aguardam algum equipamento. */
 public CredentialModels.Page managed(UUID tenant,UUID unit,boolean pendingOnly) {
  return page(tenant,(unit==null?"":" and c.unit_id=?"),unit,pendingOnly);
 }
 /** Credenciais de um vínculo da unidade (a política de leitura limita ao próprio vínculo). */
 public CredentialModels.Page unit(UUID tenant,UUID unit) { return page(tenant," and c.unit_id=?",unit,false); }
 /** Portaria: revogações não confirmadas em todos os equipamentos (as mais antigas primeiro). */
 public CredentialModels.Page pendingRevocations(UUID tenant) {
  var rows=jdbc.query(SELECT+" where c.tenant_id=? and c.desired_state='REVOKED' and "+PENDING+" order by c.revoked_at,c.id limit ?",
   (r,n)->row(r),tenant,PAGE+1);
  return new CredentialModels.Page(rows.stream().limit(PAGE).map(item->assemble(tenant,item)).toList(),rows.size()>PAGE);
 }
 private static final String PENDING="""
  exists(select 1 from cp.access_devices d where d.tenant_id=c.tenant_id and d.connector_id=c.connector_id and d.status='ACTIVE'
   and not exists(select 1 from cp.credential_syncs s where s.tenant_id=c.tenant_id and s.credential_id=c.id and s.device_id=d.id
    and s.observed_version=c.desired_version and s.observed_action=cp.credential_action(c.desired_state)))
  """;
 private CredentialModels.Page page(UUID tenant,String unitClause,UUID unit,boolean pendingOnly) {
  String sql=SELECT+" where c.tenant_id=?"+unitClause+(pendingOnly?" and "+PENDING:"")
   +" order by (c.desired_state='REVOKED' and "+PENDING+") desc,c.created_at desc,c.id limit ?";
  var rows=unit==null?jdbc.query(sql,(r,n)->row(r),tenant,PAGE+1):jdbc.query(sql,(r,n)->row(r),tenant,unit,PAGE+1);
  return new CredentialModels.Page(rows.stream().limit(PAGE).map(item->assemble(tenant,item)).toList(),rows.size()>PAGE);
 }
 public CredentialModels.Credential credential(UUID tenant,UUID id,boolean lock) {
  var rows=jdbc.query(SELECT+" where c.tenant_id=? and c.id=?"+(lock?" for update of c":""),(r,n)->row(r),tenant,id);
  return rows.isEmpty()?null:assemble(tenant,rows.getFirst());
 }

 private CredentialModels.Credential assemble(UUID tenant,Row c) {
  String action="REVOKED".equals(c.desiredState())?"REVOKE":"APPLY";
  var devices=jdbc.query("""
   select d.id,d.label,d.direction,s.outcome,s.attempted_version,s.attempted_action,s.observed_version,s.observed_action,s.confirmed_at,
    s.last_reason,s.updated_at
   from cp.access_devices d
   left join cp.credential_syncs s on s.tenant_id=d.tenant_id and s.device_id=d.id and s.credential_id=?
   where d.tenant_id=? and d.connector_id=? and d.status='ACTIVE' order by d.label,d.id
   """,(r,n)->{
    Long observed=r.getObject("observed_version",Long.class);
    Long attempted=r.getObject("attempted_version",Long.class);
    boolean confirmed=observed!=null && observed==c.desiredVersion() && action.equals(r.getString("observed_action"));
    boolean current=attempted!=null && attempted==c.desiredVersion() && action.equals(r.getString("attempted_action"));
    String outcome=current?r.getString("outcome"):null;
    String state=confirmed?"CONFIRMED":"REJECTED".equals(outcome)?"FAILED":"PENDING";
    return new CredentialModels.DeviceSync(r.getObject("id",UUID.class),r.getString("label"),r.getString("direction"),state,outcome,
     confirmed?instant(r.getTimestamp("confirmed_at")):null,current?r.getString("last_reason"):null,current?instant(r.getTimestamp("updated_at")):null);
   },c.id(),tenant,c.connector());
  long done=devices.stream().filter(d->"CONFIRMED".equals(d.state())).count();
  boolean failed=devices.stream().anyMatch(d->"FAILED".equals(d.state()));
  String status;
  if(devices.isEmpty()) status="NO_DEVICES";
  else if("REVOKED".equals(c.desiredState())) status=done==devices.size()?"REVOKED":"REVOKING";
  else if(c.expired()) status="EXPIRED";
  else if(failed) status="FAILED";
  else if(done==devices.size()) status="ACTIVE";
  else status=done==0?"SYNCING":"PARTIAL";
  return new CredentialModels.Credential(c.id(),c.connector(),c.membership(),c.unit(),c.unitLabel(),c.holder(),c.kind(),c.label(),c.desiredState(),
   c.desiredVersion(),c.validUntil(),status,c.reason(),c.revokedAt(),c.createdAt(),c.version(),devices);
 }

 private static final String EVENT_SELECT="""
  select e.id,e.kind,e.direction,e.evidence,e.occurred_at,d.label as device_label,c.label as credential_label,u.label as unit_label,e.state,
   e.quarantine_reason,
   case when e.kind='PASSAGE_DETECTED' and e.direction='ENTRY' and e.credential_id is not null then exists(select 1 from cp.device_events x
    where x.tenant_id=e.tenant_id and x.credential_id=e.credential_id and x.kind='PASSAGE_DETECTED' and x.direction='EXIT'
     and x.occurred_at>e.occurred_at) end as exit_recorded
  from cp.device_events e
  left join cp.access_devices d on d.tenant_id=e.tenant_id and d.id=e.device_id
  left join cp.access_credentials c on c.tenant_id=e.tenant_id and c.id=e.credential_id
  left join cp.units u on u.tenant_id=e.tenant_id and u.id=e.unit_id
  """;
 private static CredentialModels.DeviceEvent event(ResultSet r) throws SQLException {
  return new CredentialModels.DeviceEvent(r.getObject("id",UUID.class),r.getString("kind"),r.getString("direction"),r.getString("evidence"),
   instant(r.getTimestamp("occurred_at")),r.getString("device_label"),r.getString("credential_label"),r.getString("unit_label"),r.getString("state"),
   r.getString("quarantine_reason"),r.getObject("exit_recorded",Boolean.class));
 }
 /** Unidade: últimos 7 dias, só autorização, recusa, abertura e passagem (a política do banco limita às próprias credenciais). */
 public CredentialModels.EventPage unitEvents(UUID tenant,UUID unit) {
  var rows=jdbc.query(EVENT_SELECT+"""
   where e.tenant_id=? and e.unit_id=? and e.state='ACCEPTED' and e.occurred_at>clock_timestamp()-interval '7 days'
    and e.kind in ('AUTHORIZED','DENIED','RELAY_TRIGGERED','DOOR_OPENED','PASSAGE_DETECTED')
   order by e.occurred_at desc,e.id limit ?
   """,(r,n)->event(r),tenant,unit,PAGE+1);
  return new CredentialModels.EventPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Portaria e gestão: últimas 24 horas do condomínio, inclusive quarentena e eventos de equipamento e credencial. */
 public CredentialModels.EventPage recentEvents(UUID tenant) {
  var rows=jdbc.query(EVENT_SELECT+" where e.tenant_id=? and e.occurred_at>clock_timestamp()-interval '24 hours' order by e.occurred_at desc,e.id limit ?",
   (r,n)->event(r),tenant,PAGE+1);
  return new CredentialModels.EventPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }

 /**
  * Saúde dos conectores e equipamentos (CP-048) pelo relógio do banco. Observação mais antiga que {@code stale} vira
  * UNKNOWN; equipamento só aparece ONLINE com conector respondendo, observação recente e sinal recente. A pendência por
  * equipamento compara o estado desejado ATUAL com o confirmado (mesma regra da reconciliação).
  */
 public CredentialModels.Health health(UUID tenant,java.time.Duration stale,java.time.Duration heartbeat,java.time.Duration drift) {
  record Pending(int apply,int revoke,int rejected,Instant oldestRevocation) {}
  Map<UUID,Pending> pending=new LinkedHashMap<>();
  jdbc.query("""
   select d.id,
    count(*) filter (where c.desired_state='ACTIVE' and coalesce(s.outcome,'')<>'REJECTED') as apply,
    count(*) filter (where c.desired_state='REVOKED' and coalesce(s.outcome,'')<>'REJECTED') as revoke,
    count(*) filter (where s.outcome='REJECTED') as rejected,
    min(c.revoked_at) filter (where c.desired_state='REVOKED' and coalesce(s.outcome,'')<>'REJECTED') as oldest
   from cp.access_devices d
   join cp.access_credentials c on c.tenant_id=d.tenant_id and c.connector_id=d.connector_id
   left join cp.credential_syncs s on s.tenant_id=c.tenant_id and s.credential_id=c.id and s.device_id=d.id
    and s.attempted_version=c.desired_version and s.attempted_action=cp.credential_action(c.desired_state)
   where d.tenant_id=? and d.status='ACTIVE'
    and not (c.desired_state='ACTIVE' and c.valid_until is not null and c.valid_until<=clock_timestamp())
    and not (s.observed_version is not distinct from c.desired_version and s.observed_action is not distinct from cp.credential_action(c.desired_state))
   group by d.id
   """,r->{ pending.put(r.getObject("id",UUID.class),new Pending(r.getInt("apply"),r.getInt("revoke"),r.getInt("rejected"),instant(r.getTimestamp("oldest")))); },tenant);
  long staleSeconds=stale.toSeconds(),heartbeatSeconds=heartbeat.toSeconds(),driftMillis=drift.toMillis();
  Map<UUID,List<CredentialModels.DeviceHealth>> devices=new LinkedHashMap<>();
  jdbc.query("""
   select d.id,d.connector_id,d.label,d.direction,d.status as device_status,h.online,h.observed_at,h.state_since,h.last_heartbeat,h.clock_skew_ms,
    k.status as connector_status,ch.reachable,ch.checked_at,ch.failure_reason,
    h.observed_at is not null and h.observed_at>clock_timestamp()-make_interval(secs=>?) as fresh,
    ch.checked_at is not null and ch.checked_at>clock_timestamp()-make_interval(secs=>?) as connector_fresh,
    -- Sinal envelhece pelo relógio atual do banco, mesmo sem nova consulta.
    h.last_heartbeat is not null and h.last_heartbeat<clock_timestamp()-make_interval(secs=>?) as heartbeat_old
   from cp.access_devices d
   join cp.access_connectors k on k.tenant_id=d.tenant_id and k.id=d.connector_id
   left join cp.access_device_health h on h.tenant_id=d.tenant_id and h.device_id=d.id
   left join cp.access_connector_health ch on ch.tenant_id=d.tenant_id and ch.connector_id=d.connector_id
   where d.tenant_id=? order by d.label,d.id
   """,r->{
    UUID id=r.getObject("id",UUID.class);
    String status,reason=null;
    Boolean reachable=(Boolean)r.getObject("reachable");
    if("RETIRED".equals(r.getString("device_status"))) status="RETIRED";
    else if(!"ACTIVE".equals(r.getString("connector_status"))) { status="UNKNOWN"; reason="CONNECTOR_DISABLED"; }
    else if(reachable==null) { status="UNKNOWN"; reason="NEVER_CHECKED"; }
    else if(!reachable) { status="UNKNOWN"; reason="CONNECTOR_UNREACHABLE"; }
    else if(!r.getBoolean("connector_fresh")) { status="UNKNOWN"; reason="STALE"; }
    else if(r.getObject("online")==null) { status="UNKNOWN"; reason="NOT_REPORTED"; }
    else if(!r.getBoolean("fresh")) { status="UNKNOWN"; reason="STALE"; }
    else if(!r.getBoolean("online")) status="OFFLINE";
    else if(r.getTimestamp("last_heartbeat")==null) { status="UNKNOWN"; reason="HEARTBEAT_MISSING"; }
    else if(r.getBoolean("heartbeat_old")) { status="OFFLINE"; reason="HEARTBEAT_STALE"; }
    else status="ONLINE";
    Long skew=r.getObject("clock_skew_ms",Long.class);
    var p=pending.getOrDefault(id,new Pending(0,0,0,null));
    devices.computeIfAbsent(r.getObject("connector_id",UUID.class),k->new ArrayList<>()).add(new CredentialModels.DeviceHealth(id,r.getString("label"),
     r.getString("direction"),status,reason,instant(r.getTimestamp("observed_at")),instant(r.getTimestamp("state_since")),instant(r.getTimestamp("last_heartbeat")),
     skew==null?null:skew/1000,skew!=null && Math.abs(skew)>driftMillis,p.apply(),p.revoke(),p.rejected(),p.oldestRevocation()));
   },staleSeconds,staleSeconds,heartbeatSeconds,tenant);
  var connectors=jdbc.query("""
   select k.id,k.provider,k.label,k.status,ch.reachable,ch.failure_reason,ch.checked_at,ch.last_reachable_at,ch.pending_commands,ch.observed_delay_ms,
    ch.checked_at is not null and ch.checked_at>clock_timestamp()-make_interval(secs=>?) as fresh
   from cp.access_connectors k left join cp.access_connector_health ch on ch.tenant_id=k.tenant_id and ch.connector_id=k.id
   where k.tenant_id=? order by k.label,k.id
   """,(r,n)->{
    Boolean reachable=(Boolean)r.getObject("reachable");
    String status,reason=null;
    if(!"ACTIVE".equals(r.getString("status"))) status="DISABLED";
    else if(reachable==null) { status="UNKNOWN"; reason="NEVER_CHECKED"; }
    else if(!reachable) { status="UNREACHABLE"; reason=r.getString("failure_reason"); }
    else if(!r.getBoolean("fresh")) { status="UNKNOWN"; reason="STALE"; }
    else status="OK";
    Long delay=r.getObject("observed_delay_ms",Long.class);
    UUID id=r.getObject("id",UUID.class);
    return new CredentialModels.ConnectorHealth(id,r.getString("provider"),r.getString("label"),status,reason,instant(r.getTimestamp("checked_at")),
     instant(r.getTimestamp("last_reachable_at")),r.getObject("pending_commands",Integer.class),delay==null?null:delay/1000,devices.getOrDefault(id,List.of()));
   },staleSeconds,tenant);
  Instant now=jdbc.queryForObject("select clock_timestamp()",(r,n)->instant(r.getTimestamp(1)));
  return new CredentialModels.Health(now,staleSeconds,connectors);
 }

 public CredentialModels.Connectors connectors(UUID tenant) {
  Map<UUID,List<CredentialModels.Device>> devices=new LinkedHashMap<>();
  jdbc.query("select id,connector_id,label,direction,status from cp.access_devices where tenant_id=? order by label,id",r->{
   devices.computeIfAbsent(r.getObject("connector_id",UUID.class),k->new ArrayList<>()).add(new CredentialModels.Device(r.getObject("id",UUID.class),
    r.getString("label"),r.getString("direction"),r.getString("status")));
  },tenant);
  return new CredentialModels.Connectors(jdbc.query("select id,provider,label,status from cp.access_connectors where tenant_id=? order by label,id",
   (r,n)->new CredentialModels.Connector(r.getObject("id",UUID.class),r.getString("provider"),r.getString("label"),r.getString("status"),
    devices.getOrDefault(r.getObject("id",UUID.class),List.of())),tenant));
 }
 /**
  * Vínculo residencial vigente (unidade ativa) visível para a gestão: unidade do vínculo ou null. Trava o vínculo
  * (FOR SHARE) até o fim da transação: o encerramento concorrente espera este cadastro e revoga a credencial nova, ou
  * este cadastro espera o encerramento e vê o vínculo já revogado.
  */
 public UUID holderUnit(UUID tenant,UUID membership) {
  var rows=jdbc.query("""
   select m.unit_id from cp.memberships m join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id and u.status='ACTIVE'
   where m.tenant_id=? and m.id=? and m.status='ACTIVE' and m.role in ('RESIDENT','RESPONSIBLE','DEPENDENT')
    and m.valid_from<=now() and (m.valid_until is null or m.valid_until>now())
   for share of m
   """,(r,n)->r.getObject("unit_id",UUID.class),tenant,membership);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean connectorActive(UUID tenant,UUID connector) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from cp.access_connectors where tenant_id=? and id=? and status='ACTIVE')",
   Boolean.class,tenant,connector));
 }
 public void insert(UUID id,UUID tenant,UUID connector,UUID membership,UUID unit,String kind,String label,UUID actor) {
  jdbc.update("""
   insert into cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) values(?,?,?,?,?,?,?,?)
   """,id,tenant,connector,membership,unit,kind,label,actor);
 }
 public boolean revoke(UUID tenant,UUID id,long expectedVersion,UUID actor,String reason) {
  return jdbc.update("""
   update cp.access_credentials set desired_state='REVOKED',desired_version=desired_version+1,revoke_reason=?,revoked_by=?,version=version+1
   where tenant_id=? and id=? and desired_state='ACTIVE' and version=?
   """,reason,actor,tenant,id,expectedVersion)==1;
 }
 public void event(UUID tenant,String type,CredentialModels.Credential credential) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('credentialId',?::uuid,'unitId',?::uuid,'desiredState',?::text,'desiredVersion',?::bigint))
   """,tenant,type,credential.credentialId(),credential.version(),credential.credentialId(),credential.unitId(),credential.desiredState(),
   credential.desiredVersion());
 }

 public record IdempotentReply(boolean fresh,UUID resource,int responseStatus) {}
 public IdempotentReply begin(UUID tenant,UUID actor,String route,String key,String hash) {
  int inserted=jdbc.update("""
   insert into cp.idempotency_keys(tenant_id,actor_id,request_key,method,route,body_hash,expires_at)
   values(?,?,?,'POST',?,?,clock_timestamp()+interval '24 hours')
   on conflict(tenant_id,actor_id,method,route,request_key) do nothing
   """,tenant,actor,key,route,hash);
  if(inserted==1) return new IdempotentReply(true,null,0);
  var rows=jdbc.query("""
   select body_hash,response_status,expires_at<=clock_timestamp() as expired,(response_body->>'id')::uuid as resource
   from cp.idempotency_keys where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? for update
   """,(r,n)->{
    if(r.getBoolean("expired")) throw new BusinessProblem("IDEMPOTENCY_KEY_EXPIRED",409,"Use uma nova chave para esta operação.");
    if(!hash.equals(r.getString("body_hash")))
     throw new BusinessProblem("IDEMPOTENCY_KEY_REUSED",409,"Esta chave já foi usada com outros dados.");
    Integer status=r.getObject("response_status",Integer.class);
    if(status==null) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
    return new IdempotentReply(false,r.getObject("resource",UUID.class),status);
   },tenant,actor,route,key);
  if(rows.size()!=1) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
  return rows.getFirst();
 }
 public void finish(UUID tenant,UUID actor,String route,String key,UUID resource,int status) {
  int changed=jdbc.update("""
   update cp.idempotency_keys set response_status=?,response_body=jsonb_build_object('id',?::uuid)
   where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? and response_status is null
   """,status,resource,tenant,actor,route,key);
  if(changed!=1) throw new IllegalStateException("Resultado de idempotência não persistido.");
 }
 private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
