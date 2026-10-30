package br.com.condpulse.reservations.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.WaitlistModels;
import br.com.condpulse.reservations.domain.ReservationWindow;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fila de espera (V023). Somente dentro do contexto do TenantTransactionExecutor; o banco repete autoria e vínculo. */
@Repository @Profile("api")
public class WaitlistStore {
 public static final int PAGE=50;
 /** Entradas abertas por unidade, somando todas as áreas: evita fila infinita de uma unidade. */
 public static final int OPEN_LIMIT=5;
 public static final String DECLINED_REASON="Oferta da fila de espera recusada pela unidade.";
 private static final String ENTRY_SELECT="""
  select w.id,w.unit_id,w.amenity_id,
   case when w.state='WAITING' and w.starts_at<=clock_timestamp() then 'CLOSED'
    when w.state='OFFERED' and r.expires_at<=clock_timestamp() then 'EXPIRED' else w.state end as effective_state,
   w.starts_at,w.ends_at,w.occupied_from,w.occupied_until,coalesce((w.rule_snapshot->>'policyVersion')::integer,0) as policy_version,
   coalesce(w.rule_snapshot->>'approvalRequired','false')='true' as approval_required,w.fee_cents,w.offer_reservation_id,
   w.offered_at,w.offer_expires_at,w.closed_at,w.created_by=platform.current_actor_id() as created_by_me,
   case when w.state='WAITING' and w.starts_at>clock_timestamp() then cp.waitlist_position(w.tenant_id,w.id) end as position,
   w.version,w.created_at
  from cp.reservation_waitlist w
  left join cp.reservations r on r.tenant_id=w.tenant_id and r.id=w.offer_reservation_id
  """;
 private final JdbcTemplate jdbc;
 public WaitlistStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public int offer(UUID tenant,UUID amenity) {
  Integer created=jdbc.queryForObject("select cp.waitlist_offer(?,?)",Integer.class,tenant,amenity);
  return created==null?0:created;
 }
 public UUID amenityOf(UUID tenant,UUID reservation) {
  var rows=jdbc.query("select amenity_id from cp.reservations where tenant_id=? and id=?",
   (r,n)->r.getObject("amenity_id",UUID.class),tenant,reservation);
  return rows.size()==1?rows.getFirst():null;
 }

 public WaitlistModels.Page unitEntries(UUID tenant,UUID unit) {
  var rows=jdbc.query(ENTRY_SELECT+"""
    where w.tenant_id=? and w.unit_id=?
   order by (w.state not in ('WAITING','OFFERED')),w.starts_at,w.id limit ?
   """,(r,n)->entry(r),tenant,unit,PAGE+1);
  return new WaitlistModels.Page(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public WaitlistModels.Entry entry(UUID tenant,UUID id,boolean lock) {
  var rows=jdbc.query(ENTRY_SELECT+" where w.tenant_id=? and w.id=?"+(lock?" for update of w":""),(r,n)->entry(r),tenant,id);
  return rows.size()==1?rows.getFirst():null;
 }
 public int openEntries(UUID tenant,UUID unit) {
  Integer count=jdbc.queryForObject("""
   select count(*) from cp.reservation_waitlist w
   left join cp.reservations r on r.tenant_id=w.tenant_id and r.id=w.offer_reservation_id
   where w.tenant_id=? and w.unit_id=? and w.starts_at>clock_timestamp()
    and (w.state='WAITING' or (w.state='OFFERED' and r.status='HELD' and r.expires_at>clock_timestamp()))
   """,Integer.class,tenant,unit);
  return count==null?0:count;
 }
 /** Período ainda ocupado por reserva, pedido ou retenção em vigor (de qualquer unidade, inclusive bloqueio). */
 public boolean occupied(UUID tenant,UUID amenity,ReservationWindow window) {
  Boolean value=jdbc.queryForObject("""
   select exists(select 1 from cp.reservations where tenant_id=? and amenity_id=?
    and status in ('HELD','PENDING_APPROVAL','CONFIRMED') and not (status in ('HELD','PENDING_APPROVAL') and expires_at<=clock_timestamp())
    and tstzrange(occupied_from,occupied_until,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)'))
   """,Boolean.class,tenant,amenity,Timestamp.from(window.occupiedFrom()),Timestamp.from(window.occupiedUntil()));
  return Boolean.TRUE.equals(value);
 }
 /** A própria unidade já ocupa o período nesta área: entrar na fila não faz sentido. */
 public boolean ownOccupation(UUID tenant,UUID unit,UUID amenity,ReservationWindow window) {
  Boolean value=jdbc.queryForObject("""
   select exists(select 1 from cp.reservations where tenant_id=? and unit_id=? and amenity_id=?
    and status in ('HELD','PENDING_APPROVAL','CONFIRMED') and not (status in ('HELD','PENDING_APPROVAL') and expires_at<=clock_timestamp())
    and tstzrange(occupied_from,occupied_until,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)'))
   """,Boolean.class,tenant,unit,amenity,Timestamp.from(window.occupiedFrom()),Timestamp.from(window.occupiedUntil()));
  return Boolean.TRUE.equals(value);
 }
 /**
  * Entrada com o retrato das regras vigentes, conferido contra a área lida (mesma versão, política, limpeza e taxa).
  * Devolve false quando a área mudou entre a leitura e a gravação.
  */
 public boolean insert(UUID id,UUID tenant,UUID actor,UUID unit,ReservationStore.AmenityData amenity,ReservationWindow window,String timeZone) {
  return jdbc.update("""
   insert into cp.reservation_waitlist(id,tenant_id,amenity_id,unit_id,created_by,starts_at,ends_at,occupied_from,occupied_until,
     rule_snapshot,fee_cents)
   select ?,a.tenant_id,a.id,?,?,?,?,?,?,
    jsonb_build_object('policyVersion',a.policy_version,'policy',a.policy,'bufferMinutes',a.buffer_minutes,
      'capacity',a.capacity,'approvalRequired',a.approval_required,'feeCents',a.fee_cents,'timeZone',?::text),a.fee_cents
   from cp.amenities a where a.tenant_id=? and a.id=? and a.active and a.approval_required=?
    and a.policy_version=? and a.version=? and a.policy=?::jsonb and a.buffer_minutes=? and a.fee_cents=?
   """,id,unit,actor,Timestamp.from(window.startsAt()),Timestamp.from(window.endsAt()),Timestamp.from(window.occupiedFrom()),
   Timestamp.from(window.occupiedUntil()),timeZone,tenant,amenity.id(),amenity.approvalRequired(),amenity.policyVersion(),
   amenity.version(),amenity.rawPolicy(),amenity.bufferMinutes(),amenity.feeCents())==1;
 }
 public boolean leave(UUID tenant,UUID id,long expectedVersion) {
  return jdbc.update("update cp.reservation_waitlist set state='LEFT',version=version+1 where tenant_id=? and id=? and state='WAITING' and version=?",
   tenant,id,expectedVersion)==1;
 }
 /** Recusa da oferta: a retenção é cancelada e o gatilho encerra a entrada como LEFT na mesma transação. */
 public boolean declineOffer(UUID tenant,UUID reservation) {
  return jdbc.update("""
   update cp.reservations set status='CANCELLED',updated_at=clock_timestamp(),version=version+1,decision_reason=?
   where tenant_id=? and id=? and status='HELD' and expires_at>clock_timestamp()
   """,DECLINED_REASON,tenant,reservation)==1;
 }
 /** Retenção em vigor da própria oferta, travada (depois da trava da unidade). */
 public boolean heldOfferActive(UUID tenant,UUID reservation) {
  var rows=jdbc.query("""
   select status='HELD' and expires_at>clock_timestamp() as active from cp.reservations where tenant_id=? and id=? for update
   """,(r,n)->r.getBoolean("active"),tenant,reservation);
  return rows.size()==1 && rows.getFirst();
 }
 public boolean amenityActive(UUID tenant,UUID amenity) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from cp.amenities where tenant_id=? and id=? and active)",Boolean.class,tenant,amenity));
 }
 public String accept(UUID tenant,UUID entry,long expectedVersion) {
  return jdbc.queryForObject("select cp.waitlist_accept(?,?,?)",String.class,tenant,entry,expectedVersion);
 }
 /** Cota da unidade com as regras aceitas na entrada (a retenção da própria oferta já conta como ativa). */
 public Integer acceptedQuota(UUID tenant,UUID entry) {
  var rows=jdbc.query("""
   select case when rule_snapshot->'policy'->>'maximumActivePerUnit' ~ '^[1-9][0-9]{0,2}$'
    then (rule_snapshot->'policy'->>'maximumActivePerUnit')::integer end as quota
   from cp.reservation_waitlist where tenant_id=? and id=?
   """,(r,n)->r.getObject("quota",Integer.class),tenant,entry);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public ReservationModels.View reservation(UUID tenant,UUID id) {
  var rows=jdbc.query("select"+ReservationStore.VIEW_RETURNING+" from cp.reservations where tenant_id=? and id=?",
   (r,n)->ReservationStore.view(r),tenant,id);
  if(rows.size()!=1) throw new IllegalStateException("Reserva da oferta não encontrada na mesma transação.");
  return rows.getFirst();
 }
 public void event(UUID tenant,String type,WaitlistModels.Entry entry) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('waitlistId',?::uuid,'unitId',?::uuid,'amenityId',?::uuid,'state',?::text,'version',?::bigint))
   """,tenant,type,entry.waitlistId(),entry.version(),entry.waitlistId(),entry.unitId(),entry.amenityId(),entry.state(),entry.version());
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

 private static Instant instant(ResultSet r,String column) throws SQLException {
  var value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }
 private static WaitlistModels.Entry entry(ResultSet r) throws SQLException {
  return new WaitlistModels.Entry(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getObject("amenity_id",UUID.class),
   r.getString("effective_state"),instant(r,"starts_at"),instant(r,"ends_at"),instant(r,"occupied_from"),instant(r,"occupied_until"),
   r.getInt("policy_version"),r.getBoolean("approval_required"),r.getLong("fee_cents"),r.getObject("offer_reservation_id",UUID.class),
   instant(r,"offered_at"),instant(r,"offer_expires_at"),instant(r,"closed_at"),r.getBoolean("created_by_me"),
   r.getObject("position",Integer.class),r.getLong("version"),instant(r,"created_at"));
 }
}
