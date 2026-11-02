package br.com.condpulse.stays.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.stays.application.StayModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL das permanências (V055), sempre dentro do TenantTransactionExecutor. Visibilidade (portaria, parceiro com alcance,
 * unidade), transições e horários são impostos pelo banco; aqui só se consulta e se pede a transição.
 */
@Repository @Profile("api")
public class StayStore {
 private final JdbcTemplate jdbc;
 public StayStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static final String SELECT="""
  select s.reservation_id,a.name as amenity_name,coalesce(cr.enabled,true) as control_enabled,s.id,s.unit_id,u.label as unit_label,b.name as block_name,v.full_name,v.document_last4,s.kind,s.origin,s.basis,s.entered_at,
    s.expected_exit_at,s.expected_source,s.state,s.closure,s.closed_at,s.entry_event_id,s.exit_event_id,s.justification,s.justified_at,
    case when s.state<>'OPEN' then 'NONE' when s.l2_at<=clock_timestamp() then 'L2' when s.l1_at<=clock_timestamp() then 'L1'
     when s.expected_exit_at<=clock_timestamp() then 'DUE' else 'NONE' end as level,
    case when s.l2_alerted_at is not null then 'L2' when s.l1_alerted_at is not null then 'L1' when s.due_alerted_at is not null then 'DUE'
     else 'NONE' end as recorded_level,
    (s.state='OPEN' and s.expected_exit_at<=clock_timestamp()) as overdue
  from cp.stays s
  join cp.units u on u.tenant_id=s.tenant_id and u.id=s.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  left join cp.visitors v on v.tenant_id=s.tenant_id and v.id=s.visitor_id
  left join cp.reservations rv on rv.tenant_id=s.tenant_id and rv.id=s.reservation_id
  left join cp.amenities a on a.tenant_id=rv.tenant_id and a.id=rv.amenity_id
  left join cp.stay_rules cr on cr.tenant_id=s.tenant_id and cr.kind=s.kind
  """;
 private static final RowMapper<StayModels.Stay> STAY=(r,n)->new StayModels.Stay(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),
  r.getString("unit_label"),r.getString("block_name"),r.getString("full_name"),r.getString("document_last4"),r.getString("kind"),
  r.getString("origin"),r.getString("basis"),instant(r,"entered_at"),instant(r,"expected_exit_at"),r.getString("expected_source"),
  r.getString("state"),r.getString("closure"),instant(r,"closed_at"),r.getString("level"),r.getString("recorded_level"),r.getBoolean("overdue"),
  r.getString("justification")!=null,r.getString("justification"),instant(r,"justified_at"),r.getObject("entry_event_id",UUID.class),
  r.getObject("exit_event_id",UUID.class),r.getObject("reservation_id",UUID.class),r.getString("amenity_name"),r.getBoolean("control_enabled"));
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }

 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }

 /** Permanências abertas (vencidas primeiro, depois pela saída prevista); opcionalmente só as vencidas. */
 public List<StayModels.Stay> open(UUID tenant,boolean overdueOnly,int limit) {
  return jdbc.query(SELECT+" where s.tenant_id=? and s.state='OPEN'"+(overdueOnly?" and s.expected_exit_at<=clock_timestamp()":"")
   +" order by (s.expected_exit_at<=clock_timestamp()) desc,s.expected_exit_at,s.id limit ?",STAY,tenant,limit);
 }
 /** Abertas da unidade, da entrada mais recente. */
 public List<StayModels.Stay> unitOpen(UUID tenant,UUID unit,int limit) {
  return jdbc.query(SELECT+" where s.tenant_id=? and s.unit_id=? and s.state='OPEN' order by s.entered_at desc,s.id limit ?",STAY,tenant,unit,limit);
 }
 /** Entradas no intervalo [from,until) sem saída registrada até until (abertas agora ou fechadas depois); anuladas ficam fora. */
 public List<StayModels.Stay> withoutExit(UUID tenant,Instant from,Instant until,int limit) {
  return jdbc.query(SELECT+" where s.tenant_id=? and s.entered_at>=? and s.entered_at<? and s.state<>'VOIDED'"
   +" and (s.state='OPEN' or s.closed_at>=?) order by s.entered_at,s.id limit ?",STAY,tenant,Timestamp.from(from),Timestamp.from(until),
   Timestamp.from(until),limit);
 }
 public StayModels.Stay find(UUID tenant,UUID stay) {
  var rows=jdbc.query(SELECT+" where s.tenant_id=? and s.id=?",STAY,tenant,stay);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Quem fechou ou justificou é a própria conta (para repetição idempotente). */
 public boolean closedBy(UUID tenant,UUID stay,UUID actor) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from cp.stays where tenant_id=? and id=? and closed_by=?)",
   Boolean.class,tenant,stay,actor));
 }
 public boolean justifiedBy(UUID tenant,UUID stay,UUID actor) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from cp.stays where tenant_id=? and id=? and justified_by=?)",
   Boolean.class,tenant,stay,actor));
 }
 /** CPX-018: o banco confere morador adulto da unidade e grava STAY_CLOSED (app do morador). */
 public int confirmExit(UUID tenant,UUID unit,UUID stay,UUID actor) {
  return jdbc.update("update cp.stays set state='CLOSED',closure='RESIDENT_CONFIRMED',closed_by=? where tenant_id=? and unit_id=? and id=? and state='OPEN'",
   actor,tenant,unit,stay);
 }
 public int justify(UUID tenant,UUID stay,UUID actor,String reason) {
  return jdbc.update("update cp.stays set justification=?,justified_by=? where tenant_id=? and id=? and state='OPEN' and justification is null",
   reason,actor,tenant,stay);
 }

 public List<StayModels.Rule> rules(UUID tenant) {
  return jdbc.query("""
   select k.kind,e.max_minutes,e.l1_after_minutes,e.l2_after_minutes,e.rule_source,r.version,coalesce(r.enabled,true) as enabled,r.starts_local,r.ends_local
   from unnest(cp.stay_kinds()) with ordinality k(kind,position)
   cross join lateral cp.stay_rule(?,k.kind) e
   left join cp.stay_rules r on r.tenant_id=? and r.kind=k.kind
   order by k.position
   """,(r,n)->new StayModels.Rule(r.getString("kind"),r.getInt("max_minutes"),r.getInt("l1_after_minutes"),r.getInt("l2_after_minutes"),
    r.getString("rule_source"),(Long)r.getObject("version",Long.class),r.getBoolean("enabled"),r.getString("starts_local"),r.getString("ends_local")),tenant,tenant);
 }
 public int createRule(UUID tenant,String kind,int max,int l1,int l2,UUID actor,Boolean enabled,Boolean schedule,String start,String end) {
  return jdbc.update("""
   insert into cp.stay_rules(tenant_id,kind,max_minutes,l1_after_minutes,l2_after_minutes,updated_by,enabled,starts_local,ends_local) values(?,?,?,?,?,?,coalesce(?,true),?::time,?::time)
   on conflict (tenant_id,kind) do nothing
   """,tenant,kind,max,l1,l2,actor,enabled,start,end);
 }
 public int updateRule(UUID tenant,String kind,int max,int l1,int l2,long version,UUID actor,Boolean enabled,Boolean schedule,String start,String end) {
  return jdbc.update("""
   update cp.stay_rules set max_minutes=?,l1_after_minutes=?,l2_after_minutes=?,updated_by=?,version=version+1,
    enabled=coalesce(?,enabled),starts_local=case when ?::boolean is null then starts_local else ?::time end,
    ends_local=case when ?::boolean is null then ends_local else ?::time end
   where tenant_id=? and kind=? and version=?
   """,max,l1,l2,actor,enabled,schedule,start,schedule,end,tenant,kind,version);
 }
}
