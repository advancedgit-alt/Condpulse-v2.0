package br.com.condpulse.moves.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.moves.application.MoveModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL das mudanças (V025), sempre dentro do TenantTransactionExecutor; o banco repete vínculo, papel e regras. */
@Repository @Profile("api")
public class MoveStore {
 public static final int PAGE=100;
 private static final String MOVE_SELECT="""
  select m.id,m.unit_id,u.label as unit_label,b.name as block_name,m.kind,m.starts_at,m.ends_at,m.provider_name,m.vehicle_plate,m.items,
   m.rules_version,m.state,m.decision_note,m.decided_at,m.requester_id=platform.current_actor_id() as created_by_me,m.created_at,m.version
  from cp.move_requests m
  join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  """;
 private final JdbcTemplate jdbc;
 public MoveStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public MoveModels.Rules rules(UUID tenant) {
  var rows=jdbc.query("""
   select rules_text,week_days,to_char(starts_local,'HH24:MI') as starts_local,to_char(ends_local,'HH24:MI') as ends_local,
    min_notice_hours,max_duration_minutes,version,updated_at from cp.move_rules where tenant_id=?
   """,(r,n)->new MoveModels.Rules(r.getString("rules_text"),days(r),r.getString("starts_local"),r.getString("ends_local"),
    r.getInt("min_notice_hours"),r.getInt("max_duration_minutes"),r.getInt("version"),r.getTimestamp("updated_at").toInstant()),tenant);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean insertRules(UUID tenant,UUID actor,String text,List<Integer> days,String start,String end,int notice,int duration) {
  return jdbc.update("""
   insert into cp.move_rules(tenant_id,rules_text,week_days,starts_local,ends_local,min_notice_hours,max_duration_minutes,updated_by)
   values(?,?,?::smallint[],?::time,?::time,?,?,?) on conflict(tenant_id) do nothing
   """,tenant,text,array(days),start,end,notice,duration,actor)==1;
 }
 public boolean updateRules(UUID tenant,UUID actor,int expectedVersion,String text,List<Integer> days,String start,String end,int notice,int duration) {
  return jdbc.update("""
   update cp.move_rules set rules_text=?,week_days=?::smallint[],starts_local=?::time,ends_local=?::time,min_notice_hours=?,
    max_duration_minutes=?,version=version+1,updated_by=? where tenant_id=? and version=?
   """,text,array(days),start,end,notice,duration,actor,tenant,expectedVersion)==1;
 }
 public MoveModels.Page unitMoves(UUID tenant,UUID unit) {
  var rows=jdbc.query(MOVE_SELECT+" where m.tenant_id=? and m.unit_id=? order by m.starts_at desc,m.id limit ?",(r,n)->move(r),tenant,unit,PAGE+1);
  return new MoveModels.Page(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public MoveModels.Page managed(UUID tenant,String state) {
  var rows=state==null
   ?jdbc.query(MOVE_SELECT+" where m.tenant_id=? and m.ends_at>clock_timestamp()-interval '30 days' order by m.starts_at,m.id limit ?",(r,n)->move(r),tenant,PAGE+1)
   :jdbc.query(MOVE_SELECT+" where m.tenant_id=? and m.state=? order by m.starts_at,m.id limit ?",(r,n)->move(r),tenant,state,PAGE+1);
  return new MoveModels.Page(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Aprovadas que se sobrepõem ao intervalo (a política de leitura da portaria só deixa ver aprovadas). */
 public MoveModels.Page approvedBetween(UUID tenant,Instant from,Instant until) {
  var rows=jdbc.query(MOVE_SELECT+" where m.tenant_id=? and m.state='APPROVED' and m.starts_at<? and m.ends_at>? order by m.starts_at,m.id limit ?",
   (r,n)->move(r),tenant,Timestamp.from(until),Timestamp.from(from),PAGE+1);
  return new MoveModels.Page(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public MoveModels.Move move(UUID tenant,UUID id,boolean lock) {
  var rows=jdbc.query(MOVE_SELECT+" where m.tenant_id=? and m.id=?"+(lock?" for update of m":""),(r,n)->move(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insert(UUID id,UUID tenant,UUID unit,UUID actor,MoveModels.Create request,String provider,String plate,String items) {
  jdbc.update("""
   insert into cp.move_requests(id,tenant_id,unit_id,requester_id,kind,starts_at,ends_at,provider_name,vehicle_plate,items,rules_version)
   values(?,?,?,?,?,?,?,?,?,?,?)
   """,id,tenant,unit,actor,request.kind(),Timestamp.from(request.startsAt()),Timestamp.from(request.endsAt()),provider,plate,items,
   request.acceptedRulesVersion());
 }
 public boolean decide(UUID tenant,UUID id,long expectedVersion,String state,UUID actor,String note) {
  return jdbc.update("""
   update cp.move_requests set state=?,decided_by=?,decision_note=?,version=version+1 where tenant_id=? and id=? and state='REQUESTED' and version=?
   """,state,actor,note,tenant,id,expectedVersion)==1;
 }
 public boolean cancel(UUID tenant,UUID id,long expectedVersion,UUID actor,String note) {
  return jdbc.update("""
   update cp.move_requests set state='CANCELLED',cancelled_by=?,decision_note=coalesce(?,decision_note),version=version+1
   where tenant_id=? and id=? and state in ('REQUESTED','APPROVED') and version=?
   """,actor,note,tenant,id,expectedVersion)==1;
 }
 public void event(UUID tenant,String type,MoveModels.Move move) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('moveId',?::uuid,'unitId',?::uuid,'state',?::text,'startsAt',?::text,'endsAt',?::text,'version',?::bigint))
   """,tenant,type,move.moveId(),move.version(),move.moveId(),move.unitId(),move.state(),move.startsAt().toString(),move.endsAt().toString(),move.version());
 }
 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }

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

 private static String array(List<Integer> days) {
  return "{"+String.join(",",days.stream().map(String::valueOf).toList())+"}";
 }
 private static List<Integer> days(ResultSet r) throws SQLException {
  return Arrays.stream((Object[])r.getArray("week_days").getArray()).map(d->((Number)d).intValue()).toList();
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  var value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }
 private static MoveModels.Move move(ResultSet r) throws SQLException {
  return new MoveModels.Move(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("block_name"),
   r.getString("kind"),instant(r,"starts_at"),instant(r,"ends_at"),r.getString("provider_name"),r.getString("vehicle_plate"),r.getString("items"),
   r.getInt("rules_version"),r.getString("state"),r.getString("decision_note"),instant(r,"decided_at"),r.getBoolean("created_by_me"),
   instant(r,"created_at"),r.getLong("version"));
 }
}
