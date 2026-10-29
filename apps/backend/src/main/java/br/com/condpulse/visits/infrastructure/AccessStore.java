package br.com.condpulse.visits.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.visits.application.AccessModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL do registro manual de entrada e saída (V014), sempre dentro do TenantTransactionExecutor: visibilidade (unidade
 * por vínculo, portaria por capacidade), autorização vigente, unidade/pessoa/horários e unicidade dos registros válidos
 * também são impostos pelo banco.
 */
@Repository @Profile("api")
public class AccessStore {
 public static final int PAGE=100;
 private final JdbcTemplate jdbc;
 public AccessStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Entrada com a correção dela, a saída mais recente e a correção da saída (registros visíveis a quem consulta). */
 private static final String RECORD_SELECT="""
  select e.id,e.unit_id,u.label as unit_label,b.name as block_name,v.full_name,v.document_last4,e.arrival_id,e.permit_id,
    e.event_type,e.source,e.evidence,e.note,e.occurred_at,e.recorded_by=platform.current_actor_id() as by_me,
    ec.id as ec_id,ec.occurred_at as ec_at,ec.note as ec_note,ec.recorded_by=platform.current_actor_id() as ec_by_me,
    x.id as x_id,x.occurred_at as x_at,x.note as x_note,x.recorded_by=platform.current_actor_id() as x_by_me,
    xc.id as xc_id,xc.occurred_at as xc_at,xc.note as xc_note,xc.recorded_by=platform.current_actor_id() as xc_by_me
  from cp.access_events e
  join cp.visitors v on v.tenant_id=e.tenant_id and v.id=e.visitor_id
  join cp.units u on u.tenant_id=e.tenant_id and u.id=e.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  left join cp.access_events ec on ec.tenant_id=e.tenant_id and ec.corrects_event_id=e.id and ec.event_type='CORRECTION'
  left join lateral (select o.id,o.occurred_at,o.note,o.recorded_by from cp.access_events o
    where o.tenant_id=e.tenant_id and o.entry_event_id=e.id and o.event_type='MANUAL_OUT'
    order by o.occurred_at desc,o.id desc limit 1) x on true
  left join cp.access_events xc on xc.tenant_id=e.tenant_id and xc.corrects_event_id=x.id and xc.event_type='CORRECTION'
  """;
 private static final RowMapper<AccessModels.AccessRecord> RECORD=(r,n)->{
  var correction=r.getObject("ec_id",UUID.class)==null?null:new AccessModels.Correction(r.getObject("ec_id",UUID.class),
   instant(r,"ec_at"),r.getString("ec_note"),r.getBoolean("ec_by_me"));
  AccessModels.Exit exit=null;
  if(r.getObject("x_id",UUID.class)!=null) {
   var exitCorrection=r.getObject("xc_id",UUID.class)==null?null:new AccessModels.Correction(r.getObject("xc_id",UUID.class),
    instant(r,"xc_at"),r.getString("xc_note"),r.getBoolean("xc_by_me"));
   exit=new AccessModels.Exit(r.getObject("x_id",UUID.class),instant(r,"x_at"),r.getString("x_note"),r.getBoolean("x_by_me"),exitCorrection);
  }
  return new AccessModels.AccessRecord(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("unit_label"),
   r.getString("block_name"),r.getString("full_name"),r.getString("document_last4"),r.getObject("arrival_id",UUID.class),
   r.getObject("permit_id",UUID.class),r.getString("event_type"),r.getString("source"),r.getString("evidence"),r.getString("note"),
   instant(r,"occurred_at"),r.getBoolean("by_me"),correction,exit);
 };
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column);
  return value==null?null:value.toInstant();
 }

 /** Registros da portaria: entradas das últimas 24 horas, da mais recente. */
 public AccessModels.RecordPage recent(UUID tenant) {
  var rows=jdbc.query(RECORD_SELECT+" where e.tenant_id=? and e.event_type='MANUAL_IN' and e.occurred_at>now()-interval '24 hours'"
   +" order by e.occurred_at desc,e.id limit ?",RECORD,tenant,PAGE+1);
  return new AccessModels.RecordPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 /** Registros da unidade: entradas dos últimos 7 dias, da mais recente. */
 public AccessModels.RecordPage unitRecords(UUID tenant,UUID unit) {
  var rows=jdbc.query(RECORD_SELECT+" where e.tenant_id=? and e.unit_id=? and e.event_type='MANUAL_IN'"
   +" and e.occurred_at>now()-interval '7 days' order by e.occurred_at desc,e.id limit ?",RECORD,tenant,unit,PAGE+1);
  return new AccessModels.RecordPage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public AccessModels.AccessRecord record(UUID tenant,UUID entry) {
  if(entry==null) return null;
  var rows=jdbc.query(RECORD_SELECT+" where e.tenant_id=? and e.id=? and e.event_type='MANUAL_IN'",RECORD,tenant,entry);
  return rows.isEmpty()?null:rows.getFirst();
 }

 /** Evento de referência visível: tipo e a entrada a que pertence. */
 public record EventRef(UUID id,String type,UUID entryId) {}
 public EventRef ref(UUID tenant,UUID event) {
  var rows=jdbc.query("""
   select e.id,e.event_type,case e.event_type when 'MANUAL_IN' then e.id when 'MANUAL_OUT' then e.entry_event_id
     else coalesce(t.entry_event_id,t.id) end as entry_id
   from cp.access_events e left join cp.access_events t on t.tenant_id=e.tenant_id and t.id=e.corrects_event_id
   where e.tenant_id=? and e.id=?
   """,(r,n)->new EventRef(r.getObject("id",UUID.class),r.getString("event_type"),r.getObject("entry_id",UUID.class)),tenant,event);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean corrected(UUID tenant,UUID event) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.access_events where tenant_id=? and corrects_event_id=? and event_type='CORRECTION')
   """,Boolean.class,tenant,event));
 }
 /** Entrada válida (sem correção) já registrada para a chegada. */
 public boolean validEntryForArrival(UUID tenant,UUID arrival) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.access_events x where x.tenant_id=? and x.arrival_id=? and x.event_type='MANUAL_IN'
    and not exists(select 1 from cp.access_events c where c.tenant_id=x.tenant_id and c.corrects_event_id=x.id and c.event_type='CORRECTION'))
   """,Boolean.class,tenant,arrival));
 }
 /** Saída válida (sem correção) já registrada para a entrada. */
 public boolean validExit(UUID tenant,UUID entry) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.access_events x where x.tenant_id=? and x.entry_event_id=? and x.event_type='MANUAL_OUT'
    and not exists(select 1 from cp.access_events c where c.tenant_id=x.tenant_id and c.corrects_event_id=x.id and c.event_type='CORRECTION'))
   """,Boolean.class,tenant,entry));
 }
 /** Unidade, pessoa, horários e origem são preenchidos pelo banco a partir da referência. */
 public void insert(UUID id,UUID tenant,UUID arrival,UUID permit,String type,UUID entry,UUID corrects,String evidence,String note,UUID actor) {
  jdbc.update("""
   insert into cp.access_events(id,tenant_id,arrival_id,permit_id,event_type,entry_event_id,corrects_event_id,evidence,note,recorded_by)
   values(?,?,?,?,?,?,?,?,?,?)
   """,id,tenant,arrival,permit,type,entry,corrects,evidence,note,actor);
 }
 /** Intenção de aviso na mesma transação, com dados mínimos (sem nome do visitante). Registros são imutáveis: versão 0. */
 public void event(UUID tenant,String eventType,UUID aggregate,UUID unit,String type) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,0,jsonb_build_object('id',?::uuid,'unitId',?::uuid,'type',?::text))
   """,tenant,eventType,aggregate,aggregate,unit,type);
 }
}
