package br.com.condpulse.requests.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL dos pedidos (V017), sempre dentro do TenantTransactionExecutor: visibilidade (gestão por capacidade, unidade pelo
 * vínculo vigente), conteúdo, autoria, nomes, horários e transições também são impostos pelo banco.
 */
@Repository @Profile("api")
public class RequestStore {
 public static final int PAGE=100;
 public static final int MESSAGES=200;
 private final JdbcTemplate jdbc;
 public RequestStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Morador ou responsável com vínculo vigente na unidade ativa e conta ativa (a mesma regra da política do banco). */
 public boolean unitMember(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select cp.request_unit_member(?,?)",Boolean.class,tenant,unit));
 }

 // Unidade.
 public RequestModels.UnitPage unitRequests(UUID tenant,UUID unit) {
  var rows=jdbc.query("""
   select r.id,r.unit_id,r.category,r.title,r.state,r.assignee_membership_id is not null as assigned,r.due_on,r.created_at,r.updated_at
   from cp.service_requests r where r.tenant_id=? and r.unit_id=?
   order by (r.state='CLOSED'),r.updated_at desc,r.id limit ?
   """,(r,n)->new RequestModels.UnitSummary(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("category"),
    r.getString("title"),r.getString("state"),r.getBoolean("assigned"),date(r,"due_on"),instant(r,"created_at"),instant(r,"updated_at")),
   tenant,unit,PAGE+1);
  return new RequestModels.UnitPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Pedido visível para a unidade (a política do banco exige o vínculo vigente), com as mensagens visíveis. */
 public RequestModels.UnitRequest unitRequest(UUID tenant,UUID request) {
  var rows=jdbc.query("""
   select r.id,r.unit_id,r.category,r.title,r.description,r.state,r.assignee_membership_id is not null as assigned,r.due_on,
    r.resolution,r.requester_name,r.requester_id=platform.current_actor_id() as mine,r.created_at,r.updated_at,r.resolved_at,r.closed_at
   from cp.service_requests r where r.tenant_id=? and r.id=? and cp.request_unit_member(r.tenant_id,r.unit_id)
   """,(r,n)->new RequestModels.UnitRequest(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("category"),
    r.getString("title"),r.getString("description"),r.getString("state"),r.getBoolean("assigned"),date(r,"due_on"),r.getString("resolution"),
    r.getString("requester_name"),r.getBoolean("mine"),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"resolved_at"),
    instant(r,"closed_at"),List.of(),false),tenant,request);
  if(rows.isEmpty()) return null;
  var found=rows.getFirst();
  var messages=messages(tenant,request,false);
  return new RequestModels.UnitRequest(found.requestId(),found.unitId(),found.category(),found.title(),found.description(),found.state(),
   found.assigned(),found.dueOn(),found.resolution(),found.requesterName(),found.openedByMe(),found.createdAt(),found.updatedAt(),
   found.resolvedAt(),found.closedAt(),messages.items(),messages.truncated());
 }
 /** Unidade e andamento do pedido, se visível a quem consulta (unidade pelo vínculo, gestão pela capacidade). */
 public record Head(UUID unitId,String state,long version,UUID assigneeMembershipId,LocalDate dueOn,String resolution) {}
 public Head head(UUID tenant,UUID request) {
  var rows=jdbc.query("""
   select unit_id,state,version,assignee_membership_id,due_on,resolution from cp.service_requests where tenant_id=? and id=?
   """,(r,n)->new Head(r.getObject("unit_id",UUID.class),r.getString("state"),r.getLong("version"),
    r.getObject("assignee_membership_id",UUID.class),date(r,"due_on"),r.getString("resolution")),tenant,request);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insert(UUID id,UUID tenant,UUID unit,UUID actor,String category,String title,String description) {
  jdbc.update("""
   insert into cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) values(?,?,?,?,?,?,?)
   """,id,tenant,unit,actor,category,title,description);
 }
 public RequestModels.Message insertMessage(UUID id,UUID tenant,UUID request,UUID actor,String role,String visibility,String body,
     boolean staffView) {
  jdbc.update("""
   insert into cp.request_messages(id,tenant_id,request_id,author_id,author_role,body,visibility) values(?,?,?,?,?,?,?)
   """,id,tenant,request,actor,role,body,visibility);
  return message(tenant,id,staffView);
 }
 public RequestModels.Message message(UUID tenant,UUID id,boolean staffView) {
  var rows=jdbc.query(MESSAGE_SELECT+" where m.tenant_id=? and m.id=?",(r,n)->message(r,staffView),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }

 // Gestão.
 public RequestModels.ManagedPage managed(UUID tenant,String filter) {
  String where=switch(filter) {
   case "ACTIVE" -> " and r.state<>'CLOSED'";
   case "ALL" -> "";
   default -> " and r.state=?";
  };
  List<Object> args=new ArrayList<>(List.of(tenant));
  if(!"ACTIVE".equals(filter) && !"ALL".equals(filter)) args.add(filter);
  args.add(PAGE+1);
  var rows=jdbc.query(MANAGED_SELECT+" where r.tenant_id=?"+where+" order by r.updated_at desc,r.id limit ?",
   (r,n)->new RequestModels.ManagedSummary(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("unit_label"),
    r.getString("category"),r.getString("title"),r.getString("state"),r.getObject("assignee_membership_id",UUID.class),
    r.getString("assignee_name"),date(r,"due_on"),instant(r,"created_at"),instant(r,"updated_at"),r.getLong("version")),args.toArray());
  return new RequestModels.ManagedPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public RequestModels.ManagedRequest managedRequest(UUID tenant,UUID request) {
  var rows=jdbc.query(MANAGED_SELECT+" where r.tenant_id=? and r.id=?",(r,n)->new RequestModels.ManagedRequest(r.getObject("id",UUID.class),
   r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("category"),r.getString("title"),r.getString("description"),
   r.getString("state"),r.getObject("assignee_membership_id",UUID.class),r.getString("assignee_name"),date(r,"due_on"),r.getString("resolution"),
   r.getString("requester_name"),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"resolved_at"),instant(r,"closed_at"),
   r.getLong("version"),List.of(),false,List.of()),tenant,request);
  if(rows.isEmpty()) return null;
  var found=rows.getFirst();
  var messages=messages(tenant,request,true);
  return new RequestModels.ManagedRequest(found.requestId(),found.unitId(),found.unitLabel(),found.category(),found.title(),
   found.description(),found.state(),found.assigneeMembershipId(),found.assigneeName(),found.dueOn(),found.resolution(),
   found.requesterName(),found.createdAt(),found.updatedAt(),found.resolvedAt(),found.closedAt(),found.version(),messages.items(),
   messages.truncated(),team(tenant));
 }
 /** Equipe vigente do condomínio (gestão e portaria), que pode ser responsável por um pedido. */
 public List<RequestModels.TeamMember> team(UUID tenant) {
  return jdbc.query("""
   select m.id,m.display_name,m.role from cp.memberships m
   where m.tenant_id=? and m.role in('MANAGER','CONCIERGE') and m.status='ACTIVE' and m.valid_from<=now()
    and(m.valid_until is null or m.valid_until>now())
   order by m.display_name,m.id limit 200
   """,(r,n)->new RequestModels.TeamMember(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("role")),tenant);
 }
 /** Andamento, responsável, prazo e resolução pela versão lida; horários e resolução vigente pelo gatilho do banco. */
 public boolean update(UUID tenant,UUID request,long expectedVersion,String state,UUID assignee,LocalDate dueOn,String resolution) {
  return jdbc.update("""
   update cp.service_requests set state=?,assignee_membership_id=?,due_on=?,resolution=?,version=version+1
   where tenant_id=? and id=? and version=?
   """,state,assignee,dueOn==null?null:Date.valueOf(dueOn),resolution,tenant,request,expectedVersion)==1;
 }

 /**
  * Quantas mensagens visíveis o pedido tem, contando a recém-gravada. O gatilho da mensagem trava o pedido até o fim da
  * transação e, em READ COMMITTED, este comando já enxerga as mensagens confirmadas antes da trava: duas mensagens do
  * mesmo pedido nunca recebem o mesmo número. Unidade e gestão enxergam as mesmas mensagens visíveis, e mensagem não
  * é apagada nem muda de visibilidade.
  */
 public long visibleMessages(UUID tenant,UUID request) {
  Long count=jdbc.queryForObject("""
   select count(*) from cp.request_messages where tenant_id=? and request_id=? and visibility='RESIDENT'
   """,Long.class,tenant,request);
  return count==null?0:count;
 }
 /**
  * Evento do pedido. A identidade no outbox (tipo, pedido, número) precisa ser única: o número é a versão do pedido na
  * abertura e no andamento, e o número da mensagem visível em mensagem nova (mensagem não muda a versão do pedido).
  * O payload sempre leva a versão do pedido.
  */
 public void event(UUID tenant,String type,UUID request,long number,long version,UUID unit,UUID message) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_strip_nulls(jsonb_build_object('id',?::uuid,'unitId',?::uuid,'version',?::bigint,'messageId',?::uuid)))
   """,tenant,type,request,number,request,unit,version,message);
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

 private static final String MANAGED_SELECT="""
  select r.id,r.unit_id,u.label as unit_label,r.category,r.title,r.description,r.state,r.assignee_membership_id,m.display_name as assignee_name,
   r.due_on,r.resolution,r.requester_name,r.created_at,r.updated_at,r.resolved_at,r.closed_at,r.version
  from cp.service_requests r
  join cp.units u on u.tenant_id=r.tenant_id and u.id=r.unit_id
  left join cp.memberships m on m.tenant_id=r.tenant_id and m.id=r.assignee_membership_id
  """;
 private static final String MESSAGE_SELECT="""
  select m.id,m.author_role,m.author_name,m.author_id=platform.current_actor_id() as mine,m.visibility,m.body,m.created_at
  from cp.request_messages m
  """;
 private record Messages(List<RequestModels.Message> items,boolean truncated) {}
 /** O pedido é visível pela unidade de quem consulta (mesma regra do detalhe da unidade). */
 public boolean unitVisible(UUID tenant,UUID request) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.service_requests r where r.tenant_id=? and r.id=? and cp.request_unit_member(r.tenant_id,r.unit_id))
   """,Boolean.class,tenant,request));
 }
 /**
  * Até 200 mensagens anteriores à mensagem de referência, na mesma ordem do detalhe (horário e identificador). A
  * referência precisa ser visível a quem consulta no mesmo pedido; senão, null. A política do banco continua escondendo
  * o comentário interno da unidade.
  */
 public RequestModels.MessagePage olderMessages(UUID tenant,UUID request,UUID before,boolean staffView) {
  var cursor=jdbc.query("select created_at from cp.request_messages where tenant_id=? and request_id=? and id=?",
   (r,n)->r.getTimestamp("created_at"),tenant,request,before);
  if(cursor.isEmpty()) return null;
  var rows=new ArrayList<>(jdbc.query(MESSAGE_SELECT+" where m.tenant_id=? and m.request_id=? and (m.created_at,m.id)<(?,?)"
   +" order by m.created_at desc,m.id desc limit ?",(r,n)->message(r,staffView),tenant,request,cursor.getFirst(),before,MESSAGES+1));
  boolean truncated=rows.size()>MESSAGES;
  var page=new ArrayList<>(rows.subList(0,Math.min(rows.size(),MESSAGES)));
  Collections.reverse(page);
  return new RequestModels.MessagePage(page,truncated);
 }
 /** As 200 mais recentes, em ordem de conversa; a política do banco esconde o comentário interno da unidade. */
 private Messages messages(UUID tenant,UUID request,boolean staffView) {
  var rows=new ArrayList<>(jdbc.query(MESSAGE_SELECT+" where m.tenant_id=? and m.request_id=? order by m.created_at desc,m.id desc limit ?",
   (r,n)->message(r,staffView),tenant,request,MESSAGES+1));
  boolean truncated=rows.size()>MESSAGES;
  var page=new ArrayList<>(rows.subList(0,Math.min(rows.size(),MESSAGES)));
  Collections.reverse(page);
  return new Messages(page,truncated);
 }
 private static RequestModels.Message message(ResultSet r,boolean staffView) throws SQLException {
  String role=r.getString("author_role");
  // A unidade vê "Equipe do condomínio" (nome da pessoa da equipe só para a gestão).
  String name=staffView || "UNIT".equals(role)?r.getString("author_name"):null;
  return new RequestModels.Message(r.getObject("id",UUID.class),role,name,r.getBoolean("mine"),r.getString("visibility"),
   r.getString("body"),instant(r,"created_at"));
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }
 private static LocalDate date(ResultSet r,String column) throws SQLException {
  Date value=r.getDate(column);
  return value==null?null:value.toLocalDate();
 }
}
