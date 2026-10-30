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
   c.desired_version,c.revoke_reason,c.revoked_at,c.created_at,c.version
  from cp.access_credentials c
  join cp.units u on u.tenant_id=c.tenant_id and u.id=c.unit_id
  left join cp.memberships m on m.tenant_id=c.tenant_id and m.id=c.membership_id
  """;
 private final JdbcTemplate jdbc;
 public CredentialStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private record Row(UUID id,UUID connector,UUID membership,UUID unit,String unitLabel,String holder,String kind,String label,String desiredState,
   long desiredVersion,String reason,Instant revokedAt,Instant createdAt,long version) {}
 private static Row row(ResultSet r) throws SQLException {
  return new Row(r.getObject("id",UUID.class),r.getObject("connector_id",UUID.class),r.getObject("membership_id",UUID.class),
   r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("holder_name"),r.getString("kind"),r.getString("label"),
   r.getString("desired_state"),r.getLong("desired_version"),r.getString("revoke_reason"),instant(r.getTimestamp("revoked_at")),
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
  else if(failed) status="FAILED";
  else if(done==devices.size()) status="ACTIVE";
  else status=done==0?"SYNCING":"PARTIAL";
  return new CredentialModels.Credential(c.id(),c.connector(),c.membership(),c.unit(),c.unitLabel(),c.holder(),c.kind(),c.label(),c.desiredState(),
   c.desiredVersion(),status,c.reason(),c.revokedAt(),c.createdAt(),c.version(),devices);
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
