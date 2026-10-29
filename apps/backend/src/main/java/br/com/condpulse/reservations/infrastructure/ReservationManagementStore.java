package br.com.condpulse.reservations.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.reservations.application.ReservationManagementModels;
import br.com.condpulse.reservations.application.ReservationModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consultas e transições da gestão local. Somente dentro do contexto do TenantTransactionExecutor com a capacidade
 * publicada: as políticas restritivas da V008 recusam confirmação, rejeição e bloqueio sem ela.
 */
@Repository @Profile("api")
public class ReservationManagementStore {
 public static final int PAGE_LIMIT=200;
 private static final String EXPIRED_REASON_SQL="'"+ReservationStore.EXPIRED_REASON.replace("'","''")+"'";
 private static final String MANAGED="""
  select * from (
   select r.tenant_id,r.id as reservation_id,r.kind,r.unit_id,u.label as unit_label,r.amenity_id,a.name as amenity_name,
    case when r.status in ('HELD','PENDING_APPROVAL') and r.expires_at<=clock_timestamp() then 'EXPIRED' else r.status end as status,
    r.starts_at,r.ends_at,r.occupied_from,r.occupied_until,r.expires_at,
    coalesce((r.rule_snapshot->>'policyVersion')::integer,0) as accepted_policy_version,r.fee_cents,r.version,
    case when r.status in ('HELD','PENDING_APPROVAL') and r.expires_at<=clock_timestamp() then %s
     else r.decision_reason end as decision_reason,
    (select m.display_name from cp.memberships m
      where m.tenant_id=r.tenant_id and m.account_id=r.created_by and m.unit_id is not distinct from r.unit_id
      order by (m.status='ACTIVE') desc,m.updated_at desc,m.id limit 1) as requester_name,
    r.created_at
   from cp.reservations r
   join cp.amenities a on a.tenant_id=r.tenant_id and a.id=r.amenity_id
   left join cp.units u on u.tenant_id=r.tenant_id and u.id=r.unit_id
  ) x
  """.formatted(EXPIRED_REASON_SQL);
 private final JdbcTemplate jdbc;
 private final ReservationStore reservations;
 public ReservationManagementStore(JdbcTemplate jdbc,ReservationStore reservations) {
  this.jdbc=jdbc; this.reservations=reservations;
 }

 /** Sem período: reservas e bloqueios ainda não encerrados. Com período: interseção com [from, until). */
 public ReservationManagementModels.ManagedReservationPage reservations(UUID tenant,String status,Instant from,Instant until) {
  var sql=new StringBuilder(MANAGED).append(" where x.tenant_id=?");
  var args=new ArrayList<Object>(); args.add(tenant);
  if(status!=null) { sql.append(" and x.status=?"); args.add(status); }
  if(from!=null) { sql.append(" and x.starts_at<? and x.ends_at>?"); args.add(Timestamp.from(until)); args.add(Timestamp.from(from)); }
  else sql.append(" and x.ends_at>clock_timestamp()");
  sql.append(" order by x.starts_at,x.reservation_id limit ?"); args.add(PAGE_LIMIT+1);
  var rows=jdbc.query(sql.toString(),(r,n)->managed(r),args.toArray());
  return new ReservationManagementModels.ManagedReservationPage(rows.stream().limit(PAGE_LIMIT).toList(),rows.size()>PAGE_LIMIT);
 }
 public ReservationManagementModels.ManagedReservation reservation(UUID tenant,UUID id) {
  var rows=jdbc.query(MANAGED+" where x.tenant_id=? and x.reservation_id=?",(r,n)->managed(r),tenant,id);
  return rows.size()==1?rows.getFirst():null;
 }

 /** Linha travada para decisão ou cancelamento pela gestão. */
 public record Target(UUID id,String kind,UUID unitId,UUID createdBy,String status,long version,Instant endsAt,
     boolean deadlinePassed,boolean amenityActive,String unitStatus) {}
 /**
  * Mesma ordem global dos fluxos residenciais: trava da unidade (quando houver) antes da linha da reserva.
  * A unidade de uma reserva nunca muda, então a leitura prévia sem trava é segura.
  */
 public Target lock(UUID tenant,UUID id) {
  var located=jdbc.query("select unit_id from cp.reservations where tenant_id=? and id=?",
   (r,n)->new UUID[]{r.getObject("unit_id",UUID.class)},tenant,id);
  if(located.size()!=1) return null;
  UUID unit=located.getFirst()[0];
  if(unit!=null) reservations.lockUnit(tenant,unit);
  var rows=jdbc.query("""
   select r.id,r.kind,r.unit_id,r.created_by,r.status,r.version,r.ends_at,
    r.status in ('HELD','PENDING_APPROVAL') and r.expires_at<=clock_timestamp() as deadline_passed,
    a.active as amenity_active,u.status as unit_status
   from cp.reservations r
   join cp.amenities a on a.tenant_id=r.tenant_id and a.id=r.amenity_id
   left join cp.units u on u.tenant_id=r.tenant_id and u.id=r.unit_id
   where r.tenant_id=? and r.id=? for update of r
   """,(r,n)->new Target(r.getObject("id",UUID.class),r.getString("kind"),r.getObject("unit_id",UUID.class),
    r.getObject("created_by",UUID.class),r.getString("status"),r.getLong("version"),r.getTimestamp("ends_at").toInstant(),
    r.getBoolean("deadline_passed"),r.getBoolean("amenity_active"),r.getString("unit_status")),tenant,id);
  return rows.size()==1?rows.getFirst():null;
 }
 /** Autor ainda apto a reservar pela unidade: mesmo critério do contexto residencial. */
 public boolean requesterEligible(UUID tenant,UUID account,UUID unit) {
  Integer count=jdbc.queryForObject("""
   select count(*) from cp.memberships m
   where m.tenant_id=? and m.account_id=? and m.unit_id=? and m.status='ACTIVE'
    and m.valid_from<=now() and (m.valid_until is null or m.valid_until>now())
    and (m.role in ('RESIDENT','RESPONSIBLE')
     or (m.role='DEPENDENT' and coalesce(m.permissions#>'{reservations,create}'='true'::jsonb,false)))
   """,Integer.class,tenant,account,unit);
  return count!=null && count>0;
 }
 public ReservationModels.View decide(UUID tenant,UUID id,long expectedVersion,String status,String reason) {
  var rows=jdbc.query("""
   update cp.reservations set status=?,expires_at=null,decision_reason=?,updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and kind='BOOKING' and status='PENDING_APPROVAL' and version=? and expires_at>clock_timestamp()
   returning"""+ReservationStore.VIEW_RETURNING,(r,n)->ReservationStore.view(r),status,reason,tenant,id,expectedVersion);
  return rows.size()==1?rows.getFirst():null;
 }
 public ReservationModels.View expire(UUID tenant,UUID id,long expectedVersion) {
  var rows=jdbc.query("""
   update cp.reservations set status='EXPIRED',decision_reason=?,updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and status in ('HELD','PENDING_APPROVAL') and version=? and expires_at<=clock_timestamp()
   returning"""+ReservationStore.VIEW_RETURNING,(r,n)->ReservationStore.view(r),
   ReservationStore.EXPIRED_REASON,tenant,id,expectedVersion);
  return rows.size()==1?rows.getFirst():null;
 }
 public ReservationModels.View cancel(UUID tenant,UUID id,long expectedVersion,String reason) {
  var rows=jdbc.query("""
   update cp.reservations set status='CANCELLED',decision_reason=?,updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and version=? and ends_at>clock_timestamp()
    and (status='CONFIRMED' or (status='PENDING_APPROVAL' and expires_at>clock_timestamp()))
   returning"""+ReservationStore.VIEW_RETURNING,(r,n)->ReservationStore.view(r),reason,tenant,id,expectedVersion);
  return rows.size()==1?rows.getFirst():null;
 }

 public Boolean amenityActive(UUID tenant,UUID amenity) {
  var rows=jdbc.query("select active from cp.amenities where tenant_id=? and id=?",(r,n)->r.getBoolean("active"),tenant,amenity);
  return rows.size()==1?rows.getFirst():null;
 }
 /** Ocupações físicas no período; chamar depois de expirar prazos vencidos sob a mesma transação. */
 public long occupied(UUID tenant,UUID amenity,Instant from,Instant until) {
  Long count=jdbc.queryForObject("""
   select count(*) from cp.reservations where tenant_id=? and amenity_id=? and status in ('HELD','PENDING_APPROVAL','CONFIRMED')
    and tstzrange(occupied_from,occupied_until,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)')
   """,Long.class,tenant,amenity,Timestamp.from(from),Timestamp.from(until));
  return count==null?0:count;
 }
 public ReservationModels.View insertBlock(UUID tenant,UUID actor,UUID amenity,Instant startsAt,Instant endsAt,String reason) {
  return jdbc.queryForObject("""
   insert into cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,
     occupied_from,occupied_until,rule_snapshot,fee_cents,decision_reason)
   values(?,?,?,null,?,'BLOCK','CONFIRMED',?,?,?,?,jsonb_build_object('kind','BLOCK'),0,?)
   returning"""+ReservationStore.VIEW_RETURNING,(r,n)->ReservationStore.view(r),UUID.randomUUID(),tenant,amenity,actor,
   Timestamp.from(startsAt),Timestamp.from(endsAt),Timestamp.from(startsAt),Timestamp.from(endsAt),reason);
 }

 private static ReservationManagementModels.ManagedReservation managed(ResultSet r) throws SQLException {
  return new ReservationManagementModels.ManagedReservation(r.getObject("reservation_id",UUID.class),r.getString("kind"),
   r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getObject("amenity_id",UUID.class),r.getString("amenity_name"),
   r.getString("status"),instant(r,"starts_at"),instant(r,"ends_at"),instant(r,"occupied_from"),instant(r,"occupied_until"),
   instant(r,"expires_at"),r.getInt("accepted_policy_version"),r.getLong("fee_cents"),r.getLong("version"),
   r.getString("decision_reason"),r.getString("requester_name"),instant(r,"created_at"));
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
 }
}
