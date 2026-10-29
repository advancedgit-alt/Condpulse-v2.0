package br.com.condpulse.announcements.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import br.com.condpulse.announcements.application.AnnouncementModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL dos avisos (V016), sempre dentro do TenantTransactionExecutor: visibilidade (gestão por capacidade, unidade pelo
 * público e vínculo vigente), transições, autoria e horários também são impostos pelo banco.
 */
@Repository @Profile("api")
public class AnnouncementStore {
 public static final int PAGE=100;
 private final JdbcTemplate jdbc;
 public AnnouncementStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static final String MANAGED_SELECT="""
  select a.id,a.title,a.body,a.audience,a.audience_ids,a.state,a.author_id=platform.current_actor_id() as by_me,a.created_at,
   a.updated_at,a.published_at,a.archived_at,a.version,
   (select count(*) from cp.announcement_reads r where r.tenant_id=a.tenant_id and r.announcement_id=a.id) as opens
  from cp.announcements a
  """;
 private static final RowMapper<AnnouncementModels.ManagedAnnouncement> MANAGED=(r,n)->new AnnouncementModels.ManagedAnnouncement(
  r.getObject("id",UUID.class),r.getString("title"),r.getString("body"),r.getString("audience"),ids(r.getArray("audience_ids")),
  r.getString("state"),r.getBoolean("by_me"),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"published_at"),
  instant(r,"archived_at"),r.getLong("version"),r.getLong("opens"));

 /** Rascunhos primeiro, depois publicados e arquivados do mais recente. */
 public AnnouncementModels.ManagedPage managed(UUID tenant) {
  var rows=jdbc.query(MANAGED_SELECT+"""
   where a.tenant_id=? order by (a.state='DRAFT') desc,(a.state='PUBLISHED') desc,coalesce(a.archived_at,a.published_at,a.updated_at) desc,a.id
   limit ?
   """,MANAGED,tenant,PAGE+1);
  return new AnnouncementModels.ManagedPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public AnnouncementModels.ManagedAnnouncement find(UUID tenant,UUID announcement) {
  var rows=jdbc.query(MANAGED_SELECT+" where a.tenant_id=? and a.id=?",MANAGED,tenant,announcement);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Alvos existentes no condomínio (o gatilho confere de novo): blocos ou unidades. */
 public int existingTargets(UUID tenant,String audience,List<UUID> ids) {
  if(ids.isEmpty()) return 0;
  String table="BLOCKS".equals(audience)?"cp.blocks":"cp.units";
  Integer count=jdbc.queryForObject("select count(*) from "+table+" where tenant_id=? and id=any(?::uuid[])",Integer.class,tenant,literal(ids));
  return count==null?0:count;
 }
 public void insert(UUID id,UUID tenant,String title,String body,String audience,List<UUID> ids,UUID actor) {
  jdbc.update("""
   insert into cp.announcements(id,tenant_id,title,body,audience,audience_ids,author_id) values(?,?,?,?,?,?::uuid[],?)
   """,id,tenant,title,body,audience,literal(ids),actor);
 }
 public boolean editDraft(UUID tenant,UUID announcement,long expectedVersion,String title,String body,String audience,List<UUID> ids) {
  return jdbc.update("""
   update cp.announcements set title=?,body=?,audience=?,audience_ids=?::uuid[],version=version+1
   where tenant_id=? and id=? and state='DRAFT' and version=?
   """,title,body,audience,literal(ids),tenant,announcement,expectedVersion)==1;
 }
 /** Publicação e arquivamento pela versão lida; autoria e horário pelo gatilho do banco. */
 public boolean transition(UUID tenant,UUID announcement,long expectedVersion,String from,String to) {
  return jdbc.update("""
   update cp.announcements set state=?,version=version+1 where tenant_id=? and id=? and state=? and version=?
   """,to,tenant,announcement,from,expectedVersion)==1;
 }

 /** Vínculo vigente ligado à unidade ativa (morador, responsável, dependente ou proprietário). */
 public boolean unitMember(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.memberships m join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id and u.status='ACTIVE'
    where m.tenant_id=? and m.unit_id=? and m.account_id=platform.current_actor_id() and m.status='ACTIVE'
     and m.role in('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER') and m.valid_from<=now() and(m.valid_until is null or m.valid_until>now()))
   """,Boolean.class,tenant,unit));
 }
 /** Publicados para a unidade (condomínio, a própria unidade ou o bloco dela), do mais recente, sem o texto. */
 public AnnouncementModels.UnitPage unitAnnouncements(UUID tenant,UUID unit) {
  var rows=jdbc.query("""
   select a.id,a.title,a.published_at,r.read_at from cp.announcements a
   join cp.units u on u.tenant_id=a.tenant_id and u.id=?
   left join cp.announcement_reads r on r.tenant_id=a.tenant_id and r.announcement_id=a.id and r.account_id=platform.current_actor_id()
   where a.tenant_id=? and a.state='PUBLISHED'
    and(a.audience='TENANT' or(a.audience='UNITS' and u.id=any(a.audience_ids)) or(a.audience='BLOCKS' and u.block_id=any(a.audience_ids)))
   order by a.published_at desc,a.id limit ?
   """,(r,n)->new AnnouncementModels.UnitAnnouncement(r.getObject("id",UUID.class),r.getString("title"),instant(r,"published_at"),
    instant(r,"read_at")),unit,tenant,PAGE+1);
  return new AnnouncementModels.UnitPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Aviso publicado visível a quem abre (a política de leitura aplica o público), ou null. */
 public boolean visiblePublished(UUID tenant,UUID announcement) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.announcements where tenant_id=? and id=? and state='PUBLISHED')
   """,Boolean.class,tenant,announcement));
 }
 /** Registra a abertura uma vez (horário do banco) e devolve o aviso com o horário da primeira abertura. */
 public AnnouncementModels.OpenedAnnouncement open(UUID tenant,UUID announcement) {
  jdbc.update("""
   insert into cp.announcement_reads(tenant_id,announcement_id,account_id) values(?,?,platform.current_actor_id())
   on conflict(tenant_id,announcement_id,account_id) do nothing
   """,tenant,announcement);
  var rows=jdbc.query("""
   select a.id,a.title,a.body,a.published_at,r.read_at from cp.announcements a
   join cp.announcement_reads r on r.tenant_id=a.tenant_id and r.announcement_id=a.id and r.account_id=platform.current_actor_id()
   where a.tenant_id=? and a.id=? and a.state='PUBLISHED'
   """,(r,n)->new AnnouncementModels.OpenedAnnouncement(r.getObject("id",UUID.class),r.getString("title"),r.getString("body"),
    instant(r,"published_at"),instant(r,"read_at")),tenant,announcement);
  return rows.isEmpty()?null:rows.getFirst();
 }

 public void event(UUID tenant,String type,UUID announcement,long version,String audience) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('id',?::uuid,'audience',?::text,'version',?::bigint))
   """,tenant,type,announcement,version,announcement,audience,version);
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

 /** Identificadores já validados como UUID: literal de array seguro para ?::uuid[]. */
 static String literal(List<UUID> ids) {
  return ids.stream().map(UUID::toString).collect(Collectors.joining(",","{","}"));
 }
 private static List<UUID> ids(Array array) throws SQLException {
  if(array==null) return List.of();
  Object raw=array.getArray();
  if(raw instanceof UUID[] values) return Arrays.asList(values);
  if(raw instanceof Object[] values) return Arrays.stream(values).map(value->value instanceof UUID uuid?uuid:UUID.fromString(value.toString())).toList();
  throw new SQLException("Público do aviso em formato inesperado.");
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }
}
