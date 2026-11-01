package br.com.condpulse.securitylog.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura e gravação do Log de Segurança (V050) sempre dentro da transação com contexto do condomínio. O banco decide
 * seq, hash, autoria e horários; a leitura depende da capacidade publicada (RLS).
 */
@Repository @Profile("api")
public class SecurityLogStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final String COLUMNS="""
  id,seq,occurred_at,recorded_at,clock_skew_ms,event_type,severity,source,partner_id,actor_account_id,actor_role,subject_type,subject_id,
  unit_id,point_id,device_id,reason,evidence_ref,correlation_id,trace_id,corrects_id,details::text as details,prev_hash,hash
  """;
 private final JdbcTemplate jdbc;
 public SecurityLogStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Mais recente primeiro (seq decrescente), com até limit+1 linhas para saber se há próxima página. */
 public List<SecurityLogModels.Event> page(UUID tenant,SecurityLogModels.Filter filter) {
  var sql=new StringBuilder("select ").append(COLUMNS).append(" from cp.security_log where tenant_id=?");
  var args=new ArrayList<Object>(); args.add(tenant);
  if(filter.eventType()!=null) { sql.append(" and event_type=?"); args.add(filter.eventType()); }
  if(filter.severity()!=null) { sql.append(" and severity=?"); args.add(filter.severity()); }
  if(filter.actorId()!=null) { sql.append(" and actor_account_id=?"); args.add(filter.actorId()); }
  if(filter.from()!=null) { sql.append(" and recorded_at>=?"); args.add(Timestamp.from(filter.from())); }
  if(filter.until()!=null) { sql.append(" and recorded_at<?"); args.add(Timestamp.from(filter.until())); }
  if(filter.beforeSeq()!=null) { sql.append(" and seq<?"); args.add(filter.beforeSeq()); }
  sql.append(" order by seq desc limit ?"); args.add(filter.limit()+1);
  return jdbc.query(sql.toString(),(r,n)->event(r),args.toArray());
 }
 public List<SecurityLogModels.Event> range(UUID tenant,long fromSeq,long toSeq) {
  return jdbc.query("select "+COLUMNS+" from cp.security_log where tenant_id=? and seq between ? and ? order by seq",
   (r,n)->event(r),tenant,fromSeq,toSeq);
 }
 /**
  * Linha do tempo de um incidente (V053): eventos com correlation_id = incidente e eventos anexados a ele, em ordem de seq,
  * até limit+1 linhas (para saber se passou do limite). O RLS do log e dos anexos vale como em qualquer leitura.
  */
 public List<SecurityLogModels.Event> incidentTimeline(UUID tenant,UUID incident,int limit) {
  return jdbc.query("select "+COLUMNS+" from cp.security_log s where s.tenant_id=? and (s.correlation_id=? or s.id in "
   +"(select l.log_event_id from cp.incident_links l where l.tenant_id=? and l.incident_id=?)) order by s.seq limit ?",
   (r,n)->event(r),tenant,incident,tenant,incident,limit+1);
 }
 /**
  * Intervalo íntegro pelo banco: digest recalculado de cada evento igual ao hash gravado e, a partir do segundo evento
  * do condomínio, prev_hash do primeiro igual ao hash do anterior.
  */
 public boolean rangeIntact(UUID tenant,long fromSeq,long toSeq) {
  Boolean intact=jdbc.queryForObject("""
   select not exists(select 1 from cp.security_log s where s.tenant_id=? and s.seq between ? and ?
     and cp.security_log_digest(s.prev_hash,s)<>s.hash)
    and (?=1 and exists(select 1 from cp.security_log f where f.tenant_id=? and f.seq=1 and f.prev_hash=repeat('0',64))
     or exists(select 1 from cp.security_log f join cp.security_log b on b.tenant_id=f.tenant_id and b.seq=f.seq-1
      where f.tenant_id=? and f.seq=? and f.prev_hash=b.hash))
   """,Boolean.class,tenant,fromSeq,toSeq,fromSeq,tenant,tenant,fromSeq);
  return Boolean.TRUE.equals(intact);
 }
 /** Último seq visível do condomínio (0 sem eventos). */
 public long lastSeq(UUID tenant) {
  Long last=jdbc.queryForObject("select coalesce(max(seq),0) from cp.security_log where tenant_id=?",Long.class,tenant);
  return last==null?0:last;
 }
 public SecurityLogModels.Integrity verify(UUID tenant) {
  return jdbc.queryForObject("""
   select v.intact,v.checked_events,v.last_seq,v.last_hash,v.broken_seq,v.problem,v.anchors_checked,clock_timestamp() as checked_at
   from cp.security_log_verify(?) v
   """,(r,n)->new SecurityLogModels.Integrity(r.getBoolean("intact"),r.getLong("checked_events"),r.getLong("last_seq"),r.getString("last_hash"),
    r.getObject("broken_seq",Long.class),r.getString("problem"),r.getInt("anchors_checked"),r.getTimestamp("checked_at").toInstant()),tenant);
 }
 /** SENSITIVE_VIEWED da leitura do próprio log (R5.6), na mesma transação da consulta. */
 public void recordView(UUID tenant,String view,Map<String,?> details) {
  var payload=new TreeMap<String,Object>(details); payload.put("view",view);
  jdbc.update("""
   insert into cp.security_log(id,tenant_id,event_type,severity,source,subject_type,details)
   values(?,?,'SENSITIVE_VIEWED','INFO','MANAGEMENT','SECURITY_LOG',?::jsonb)
   """,UUID.randomUUID(),tenant,JSON.writeValueAsString(payload));
 }
 /** Grava LOG_EXPORTED (motivo obrigatório no banco) e devolve o horário do banco da gravação. */
 public Instant recordExport(UUID tenant,UUID eventId,String reason,Map<String,Object> details) {
  jdbc.update("""
   insert into cp.security_log(id,tenant_id,event_type,severity,source,subject_type,reason,details)
   values(?,?,'LOG_EXPORTED','INFO','MANAGEMENT','SECURITY_LOG',?,?::jsonb)
   """,eventId,tenant,reason,JSON.writeValueAsString(new TreeMap<>(details)));
  return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
 }

 @SuppressWarnings("unchecked")
 private static SecurityLogModels.Event event(ResultSet r) throws SQLException {
  String details=r.getString("details");
  Map<String,Object> parsed=details==null?Map.of():JSON.readValue(details,Map.class);
  return new SecurityLogModels.Event(r.getObject("id",UUID.class),r.getLong("seq"),instant(r,"occurred_at"),instant(r,"recorded_at"),
   r.getObject("clock_skew_ms",Long.class),r.getString("event_type"),r.getString("severity"),r.getString("source"),
   r.getObject("partner_id",UUID.class),r.getObject("actor_account_id",UUID.class),r.getString("actor_role"),r.getString("subject_type"),
   r.getObject("subject_id",UUID.class),r.getObject("unit_id",UUID.class),r.getObject("point_id",UUID.class),r.getObject("device_id",UUID.class),
   r.getString("reason"),r.getString("evidence_ref"),r.getObject("correlation_id",UUID.class),r.getString("trace_id"),
   r.getObject("corrects_id",UUID.class),parsed,r.getString("prev_hash"),r.getString("hash"));
 }
 private static Instant instant(ResultSet r,String column) throws SQLException {
  Timestamp value=r.getTimestamp(column); return value==null?null:value.toInstant();
 }
}
