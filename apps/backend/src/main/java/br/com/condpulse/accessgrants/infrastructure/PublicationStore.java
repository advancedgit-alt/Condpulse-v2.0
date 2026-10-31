package br.com.condpulse.accessgrants.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import br.com.condpulse.accessgrants.application.PublicationModels;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL da publicação por destino (V045). O banco compila documento, ação, prioridade e versão; aqui só se escolhem os
 * destinos e se grava a intenção. A varredura de destinos (vínculos) e a de convites ficam ligadas só durante a compilação.
 */
public class PublicationStore {
 private final JdbcTemplate jdbc;
 public PublicationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private <T> T scan(Supplier<T> action) {
  jdbc.queryForObject("select set_config('app.policy_destination_scan','on',true),set_config('app.subject_context_scan','on',true)",String.class);
  try { return action.get(); }
  finally { jdbc.queryForObject("select set_config('app.policy_destination_scan','',true),set_config('app.subject_context_scan','',true)",String.class); }
 }

 /** Executa um item dentro de um savepoint: a recusa do banco desfaz só o item, nunca o lote. */
 public <T> T item(Supplier<T> action) {
  jdbc.execute("savepoint cp_rights_item");
  try {
   T result=action.get();
   jdbc.execute("release savepoint cp_rights_item");
   return result;
  } catch(RuntimeException error) {
   jdbc.execute("rollback to savepoint cp_rights_item");
   jdbc.execute("release savepoint cp_rights_item");
   throw error;
  }
 }

 /** Sujeitos com direito ativo ou com algo já publicado (destinos de onde pode ser preciso retirar). */
 public int countSubjects(UUID tenant) {
  Integer n=jdbc.queryForObject("""
   select count(*) from (select subject_id from cp.access_grants where tenant_id=? and status='ACTIVE'
    union select subject_id from cp.rights_publications where tenant_id=?) x
   """,Integer.class,tenant,tenant);
  return n==null?0:n;
 }
 public List<UUID> subjects(UUID tenant,int limit) {
  return jdbc.query("""
   select subject_id from (select subject_id from cp.access_grants where tenant_id=? and status='ACTIVE'
    union select subject_id from cp.rights_publications where tenant_id=?) x order by subject_id limit ?
   """,(r,n)->r.getObject(1,UUID.class),tenant,tenant,limit);
 }
 public List<UUID> existingSubjects(UUID tenant,List<UUID> ids) {
  return jdbc.query("select id from cp.access_subjects where tenant_id=? and id=any(?::uuid[]) order by id",(r,n)->r.getObject(1,UUID.class),
   tenant,ids.toArray(UUID[]::new));
 }

 /**
  * Compila o sujeito em todos os destinos: um trabalho por equipamento cujo documento mudou desde o último trabalho do
  * destino; destino que nunca recebeu nada só entra se houver direito a aplicar. Retorna os trabalhos criados.
  */
 public int planSubject(UUID tenant,UUID operation,UUID subject,UUID actor) {
  return scan(()->jdbc.update("""
   insert into cp.rights_publications(tenant_id,operation_id,subject_id,device_id,origin,requested_by)
   select ?,?,?,d.device,'PLAN',? from (select distinct device from cp.rights_destinations(?,?) device) d
    cross join lateral (select cp.rights_compiled(?,?,d.device) doc) c
    left join lateral (select r.document_hash from cp.rights_publications r where r.tenant_id=? and r.subject_id=? and r.device_id=d.device
     and r.state<>'SUPERSEDED' order by r.desired_version desc limit 1) l on true
   where c.doc is not null and l.document_hash is distinct from cp.rights_document_hash(c.doc)
    and (l.document_hash is not null or c.doc->>'action'='APPLY_RIGHTS')
   order by d.device
   """,tenant,operation,subject,actor,tenant,subject,tenant,subject,tenant,subject));
 }

 private static final String COLUMNS="""
  id,operation_id,subject_id,device_id,origin,revocation_marker_id,action,priority,desired_version,rights_generation,subject_restriction_epoch,
  installation_revision,jsonb_array_length(document->'entries') entries,document_hash,state,confirmation_level,result_code,attempts,reported_at,
  confirmed_at,superseded_by,created_at,version
  """;
 private static PublicationModels.Publication map(ResultSet r,int n) throws SQLException {
  return new PublicationModels.Publication(r.getObject("id",UUID.class),r.getObject("operation_id",UUID.class),r.getObject("subject_id",UUID.class),
   r.getObject("device_id",UUID.class),r.getString("origin"),r.getObject("revocation_marker_id",UUID.class),r.getString("action"),r.getInt("priority"),
   r.getLong("desired_version"),r.getLong("rights_generation"),r.getLong("subject_restriction_epoch"),r.getInt("installation_revision"),
   r.getInt("entries"),r.getString("document_hash"),r.getString("state"),r.getString("confirmation_level"),r.getString("result_code"),
   r.getInt("attempts"),instant(r.getTimestamp("reported_at")),instant(r.getTimestamp("confirmed_at")),r.getObject("superseded_by",UUID.class),
   r.getTimestamp("created_at").toInstant(),r.getLong("version"));
 }
 private static final String FILTER="""
  tenant_id=? and (?::uuid is null or operation_id=?::uuid) and (?::uuid is null or subject_id=?::uuid) and (?::uuid is null or device_id=?::uuid)
  """;
 public List<PublicationModels.Publication> publications(UUID tenant,UUID operation,UUID subject,UUID device,int limit) {
  return jdbc.query("select "+COLUMNS+" from cp.rights_publications where "+FILTER+" order by desired_version desc limit ?",PublicationStore::map,
   tenant,operation,operation,subject,subject,device,device,limit);
 }
 /** Contagem por estado sobre todas as linhas do filtro. */
 public Map<String,Integer> countByState(UUID tenant,UUID operation,UUID subject,UUID device) {
  var counts=new LinkedHashMap<String,Integer>();
  for(String s:PublicationModels.STATES) counts.put(s,0);
  jdbc.query("select state,count(*) from cp.rights_publications where "+FILTER+" group by state",(org.springframework.jdbc.core.RowCallbackHandler)r->
   counts.put(r.getString(1),r.getInt(2)),tenant,operation,operation,subject,subject,device,device);
  return counts;
 }

 private static Instant instant(Timestamp t) { return t==null?null:t.toInstant(); }
}
