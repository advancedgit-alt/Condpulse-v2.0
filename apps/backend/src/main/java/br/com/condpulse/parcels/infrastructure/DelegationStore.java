package br.com.condpulse.parcels.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.parcels.application.DelegationModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Delegações de retirada (V024). Hash e sal nunca saem desta classe; a conferência do código é feita pelo banco. */
@Repository @Profile("api")
public class DelegationStore {
 private static final String SELECT="""
  select d.id,d.parcel_id,d.unit_id,d.delegate_name,d.document_last4,d.valid_until,
   case when d.state='ACTIVE' and d.valid_until<=clock_timestamp() then 'EXPIRED' else d.state end as effective_state,
   d.attempts,d.created_by=platform.current_actor_id() as created_by_me,d.created_at,d.used_at,d.closed_at,d.version
  from cp.parcel_delegations d
  """;
 private final JdbcTemplate jdbc;
 public DelegationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public DelegationModels.Delegation latest(UUID tenant,UUID parcel) {
  var rows=jdbc.query(SELECT+" where d.tenant_id=? and d.parcel_id=? order by d.created_at desc,d.id desc limit 1",(r,n)->delegation(r),tenant,parcel);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public DelegationModels.Delegation delegation(UUID tenant,UUID id) {
  var rows=jdbc.query(SELECT+" where d.tenant_id=? and d.id=?",(r,n)->delegation(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Uma criação por encomenda de cada vez (a delegação anterior é encerrada pela nova). */
 public void lockParcel(UUID tenant,UUID parcel) {
  jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"cp.parcel_delegations:"+tenant+":"+parcel);
 }
 public int closeActive(UUID tenant,UUID parcel) {
  return jdbc.update("update cp.parcel_delegations set state='CLOSED',version=version+1 where tenant_id=? and parcel_id=? and state='ACTIVE'",tenant,parcel);
 }
 public void insert(UUID id,UUID tenant,UUID parcel,UUID unit,String name,String document,String hash,String salt,Instant validUntil,UUID actor) {
  jdbc.update("""
   insert into cp.parcel_delegations(id,tenant_id,parcel_id,unit_id,delegate_name,document_last4,code_hash,code_salt,valid_until,created_by)
   values(?,?,?,?,?,?,?,?,?,?)
   """,id,tenant,parcel,unit,name,document,hash,salt,Timestamp.from(validUntil),actor);
 }
 public boolean revoke(UUID tenant,UUID id,long expectedVersion,UUID actor) {
  return jdbc.update("""
   update cp.parcel_delegations set state='REVOKED',revoked_by=?,version=version+1
   where tenant_id=? and id=? and state='ACTIVE' and version=?
   """,actor,tenant,id,expectedVersion)==1;
 }
 /** OK, WRONG, LOCKED, EXPIRED ou NONE (V024). */
 public String check(UUID tenant,UUID parcel,String code) {
  return jdbc.queryForObject("select cp.parcel_delegation_check(?,?,?)",String.class,tenant,parcel,code);
 }
 /** Delegação usada nesta transação pela portaria (para o nome gravado na retirada). */
 public DelegationModels.Delegation usedByMe(UUID tenant,UUID parcel) {
  var rows=jdbc.query(SELECT+" where d.tenant_id=? and d.parcel_id=? and d.state='USED' and d.used_by=platform.current_actor_id()",
   (r,n)->delegation(r),tenant,parcel);
  return rows.size()==1?rows.getFirst():null;
 }
 public boolean deliver(UUID tenant,UUID parcel,long expectedVersion,UUID actor,String delegateName) {
  int changed=jdbc.update("update cp.parcels set status='DELIVERED',version=version+1 where tenant_id=? and id=? and status='RECEIVED' and version=?",
   tenant,parcel,expectedVersion);
  if(changed!=1) return false;
  jdbc.update("""
   insert into cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,evidence_kind) values(?,?,?,?,'DELEGATION')
   """,tenant,parcel,actor,delegateName);
  return true;
 }
 public boolean keyExists(UUID tenant,UUID actor,String route,String key) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.idempotency_keys where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=?)
   """,Boolean.class,tenant,actor,route,key));
 }
 public void event(UUID tenant,String type,DelegationModels.Delegation delegation) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('delegationId',?::uuid,'parcelId',?::uuid,'unitId',?::uuid,'state',?::text,'version',?::bigint))
   """,tenant,type,delegation.delegationId(),delegation.version(),delegation.delegationId(),delegation.parcelId(),delegation.unitId(),
   delegation.state(),delegation.version());
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
 /** Guarda só o identificador: o código nunca é gravado na resposta idempotente. */
 public void finish(UUID tenant,UUID actor,String route,String key,UUID resource,int status) {
  int changed=jdbc.update("""
   update cp.idempotency_keys set response_status=?,response_body=jsonb_build_object('id',?::uuid)
   where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? and response_status is null
   """,status,resource,tenant,actor,route,key);
  if(changed!=1) throw new IllegalStateException("Resultado de idempotência não persistido.");
 }
 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }

 private static Instant instant(ResultSet r,String column) throws SQLException {
  var value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }
 private static DelegationModels.Delegation delegation(ResultSet r) throws SQLException {
  int attempts=r.getInt("attempts");
  String state=r.getString("effective_state");
  return new DelegationModels.Delegation(r.getObject("id",UUID.class),r.getObject("parcel_id",UUID.class),r.getObject("unit_id",UUID.class),
   r.getString("delegate_name"),r.getString("document_last4"),instant(r,"valid_until"),state,attempts,
   "ACTIVE".equals(state)?5-attempts:0,r.getBoolean("created_by_me"),instant(r,"created_at"),instant(r,"used_at"),instant(r,"closed_at"),
   r.getLong("version"));
 }
}
