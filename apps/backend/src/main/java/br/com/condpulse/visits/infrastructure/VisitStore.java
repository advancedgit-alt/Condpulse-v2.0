package br.com.condpulse.visits.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.visits.application.VisitModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL de visitas e chegadas (V013), sempre dentro do TenantTransactionExecutor: a visibilidade (unidade por vínculo,
 * portaria por capacidade), as transições e o prazo da chegada também são impostos pelo banco.
 */
@Repository @Profile("api")
public class VisitStore {
 public static final int PAGE=100;
 private final JdbcTemplate jdbc;
 public VisitStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Mesma regra das políticas: adulto da unidade ou dependente com a permissão indicada (null: só adulto). */
 public boolean member(UUID tenant,UUID unit,String dependentPermission) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select cp.visit_member(?,?,?)",Boolean.class,tenant,unit,dependentPermission));
 }
 public boolean activeUnit(UUID tenant,UUID unit) {
  Integer count=jdbc.queryForObject("select count(*) from cp.units where tenant_id=? and id=? and status='ACTIVE'",Integer.class,tenant,unit);
  return count!=null && count==1;
 }
 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }

 private static final String PERMIT_SELECT="""
  select p.id,p.unit_id,u.label as unit_label,b.name as block_name,v.full_name,v.document_last4,p.purpose,p.valid_from,p.valid_until,
    case when p.state='ACTIVE' and p.valid_until<=clock_timestamp() then 'EXPIRED'
     when p.state='ACTIVE' and p.valid_from>clock_timestamp() then 'SCHEDULED' else p.state end as effective_state,
    p.created_by=platform.current_actor_id() as created_by_me,p.revoked_at,p.revoke_reason,p.version,p.created_at
  from cp.visit_permits p
  join cp.visitors v on v.tenant_id=p.tenant_id and v.id=p.visitor_id
  join cp.units u on u.tenant_id=p.tenant_id and u.id=p.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  """;
 private static final RowMapper<VisitModels.Permit> PERMIT=(r,n)->new VisitModels.Permit(r.getObject("id",UUID.class),
  r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("block_name"),r.getString("full_name"),
  r.getString("document_last4"),r.getString("purpose"),instant(r,"valid_from"),instant(r,"valid_until"),r.getString("effective_state"),
  r.getBoolean("created_by_me"),instant(r,"revoked_at"),r.getString("revoke_reason"),r.getLong("version"),instant(r,"created_at"));
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }
 /** Convites da unidade visíveis a quem consulta (o banco limita o dependente aos que ele criou), do fim mais recente. */
 public VisitModels.PermitPage unitPermits(UUID tenant,UUID unit) {
  var rows=jdbc.query(PERMIT_SELECT+" where p.tenant_id=? and p.unit_id=? order by p.valid_until desc,p.id limit ?",PERMIT,tenant,unit,PAGE+1);
  return new VisitModels.PermitPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Visitas previstas para a portaria: convites ativos que valem agora ou começam nas próximas 24 horas. */
 public VisitModels.PermitPage expected(UUID tenant,UUID unit,String visitor) {
  var where=new ArrayList<String>(); var args=new ArrayList<Object>();
  where.add("p.tenant_id=?"); args.add(tenant);
  where.add("p.state='ACTIVE' and p.valid_until>clock_timestamp() and p.valid_from<clock_timestamp()+interval '24 hours'");
  if(unit!=null) { where.add("p.unit_id=?"); args.add(unit); }
  if(visitor!=null) { where.add("v.full_name ilike ? escape '\\'"); args.add("%"+literal(visitor)+"%"); }
  args.add(PAGE+1);
  var rows=jdbc.query(PERMIT_SELECT+" where "+String.join(" and ",where)+" order by p.valid_from,p.id limit ?",PERMIT,args.toArray());
  return new VisitModels.PermitPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public VisitModels.Permit permit(UUID tenant,UUID permit,boolean lock) {
  var rows=jdbc.query(PERMIT_SELECT+" where p.tenant_id=? and p.id=?"+(lock?" for update of p":""),PERMIT,tenant,permit);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertVisitor(UUID id,UUID tenant,String name,String document,UUID actor) {
  jdbc.update("insert into cp.visitors(id,tenant_id,full_name,document_last4,created_by) values(?,?,?,?,?)",id,tenant,name,document,actor);
 }
 public void insertPermit(UUID id,UUID tenant,UUID visitor,UUID unit,String purpose,UUID actor,Instant from,Instant until) {
  jdbc.update("""
   insert into cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until) values(?,?,?,?,?,?,?,?)
   """,id,tenant,visitor,unit,purpose,actor,Timestamp.from(from),Timestamp.from(until));
 }
 /** Revogação pela versão lida, de convite ainda ativo e em vigor; o horário vem do banco. */
 public boolean revoke(UUID tenant,UUID permit,long expectedVersion,UUID actor,String reason) {
  return jdbc.update("""
   update cp.visit_permits set state='REVOKED',revoked_by=?,revoke_reason=?,version=version+1
   where tenant_id=? and id=? and state='ACTIVE' and version=? and valid_until>clock_timestamp()
   """,actor,reason,tenant,permit,expectedVersion)==1;
 }

 private static final String ARRIVAL_SELECT="""
  select r.id,r.unit_id,u.label as unit_label,b.name as block_name,v.full_name,v.document_last4,r.permit_id,
    case when r.state='WAITING' and r.expires_at<=clock_timestamp() then 'EXPIRED' else r.state end as effective_state,
    r.expires_at,r.decided_at,r.decision_source,r.decision_note,coalesce(r.decided_by=platform.current_actor_id(),false) as decided_by_me,
    r.version,r.created_at
  from cp.arrival_requests r
  join cp.visitors v on v.tenant_id=r.tenant_id and v.id=r.visitor_id
  join cp.units u on u.tenant_id=r.tenant_id and u.id=r.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  """;
 private static final RowMapper<VisitModels.Arrival> ARRIVAL=(r,n)->new VisitModels.Arrival(r.getObject("id",UUID.class),
  r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("block_name"),r.getString("full_name"),
  r.getString("document_last4"),r.getObject("permit_id",UUID.class),r.getString("effective_state"),instant(r,"expires_at"),
  instant(r,"decided_at"),r.getString("decision_source"),r.getString("decision_note"),r.getBoolean("decided_by_me"),r.getLong("version"),
  instant(r,"created_at"));
 /** Chegadas da unidade nos últimos 7 dias, da mais recente. */
 public VisitModels.ArrivalPage unitArrivals(UUID tenant,UUID unit) {
  var rows=jdbc.query(ARRIVAL_SELECT+" where r.tenant_id=? and r.unit_id=? and r.created_at>clock_timestamp()-interval '7 days'"
   +" order by r.created_at desc,r.id limit ?",ARRIVAL,tenant,unit,PAGE+1);
  return new VisitModels.ArrivalPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Fila da portaria: chegadas das últimas 24 horas; WAITING considera só as que ainda estão no prazo. */
 public VisitModels.ArrivalPage queue(UUID tenant,String state) {
  var where=new ArrayList<String>(); var args=new ArrayList<Object>();
  where.add("r.tenant_id=?"); args.add(tenant);
  where.add("r.created_at>clock_timestamp()-interval '24 hours'");
  if("WAITING".equals(state)) where.add("r.state='WAITING' and r.expires_at>clock_timestamp()");
  else if("EXPIRED".equals(state)) where.add("r.state='WAITING' and r.expires_at<=clock_timestamp()");
  else if(state!=null) { where.add("r.state=?"); args.add(state); }
  args.add(PAGE+1);
  var rows=jdbc.query(ARRIVAL_SELECT+" where "+String.join(" and ",where)+" order by r.created_at desc,r.id limit ?",ARRIVAL,args.toArray());
  return new VisitModels.ArrivalPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public VisitModels.Arrival arrival(UUID tenant,UUID arrival) {
  var rows=jdbc.query(ARRIVAL_SELECT+" where r.tenant_id=? and r.id=?",ARRIVAL,tenant,arrival);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertArrival(UUID id,UUID tenant,UUID unit,UUID visitor,UUID permit,UUID actor) {
  jdbc.update("insert into cp.arrival_requests(id,tenant_id,unit_id,visitor_id,permit_id,created_by) values(?,?,?,?,?,?)",
   id,tenant,unit,visitor,permit,actor);
 }
 /**
  * Primeira decisão válida: a mudança condicional só acontece uma vez. Pelo aplicativo, só dentro do prazo; a
  * confirmação assistida da portaria vale também depois dele (o banco aplica a mesma regra).
  */
 public boolean decide(UUID tenant,UUID arrival,String state,UUID actor,String source,String note) {
  return jdbc.update("""
   update cp.arrival_requests set state=?,decided_by=?,decision_source=?,decision_note=?,version=version+1
   where tenant_id=? and id=? and state='WAITING' and (?::text<>'APP' or expires_at>clock_timestamp())
   """,state,actor,source,note,tenant,arrival,source)==1;
 }
 /** A portaria encerra a chegada ainda aguardando (registrada por engano, pessoa foi embora ou prazo vencido). */
 public boolean cancel(UUID tenant,UUID arrival,UUID actor,String reason) {
  return jdbc.update("""
   update cp.arrival_requests set state='CANCELLED',decided_by=?,decision_source='CONCIERGE',decision_note=?,version=version+1
   where tenant_id=? and id=? and state='WAITING'
   """,actor,reason,tenant,arrival)==1;
 }

 /** Intenção de aviso na mesma transação, com dados mínimos (sem nome do visitante): o worker consulta o resto. */
 public void event(UUID tenant,String eventType,UUID aggregate,long version,UUID unit,String state) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('id',?::uuid,'unitId',?::uuid,'state',?::text,'version',?::bigint))
   """,tenant,eventType,aggregate,version,aggregate,unit,state,version);
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
 /** Guarda o registro criado; a repetição devolve o estado atual dele. */
 public void finish(UUID tenant,UUID actor,String route,String key,UUID resource,int status) {
  int changed=jdbc.update("""
   update cp.idempotency_keys set response_status=?,response_body=jsonb_build_object('id',?::uuid)
   where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? and response_status is null
   """,status,resource,tenant,actor,route,key);
  if(changed!=1) throw new IllegalStateException("Resultado de idempotência não persistido.");
 }
 private static String literal(String value) {
  return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
 }
}
