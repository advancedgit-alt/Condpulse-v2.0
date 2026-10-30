package br.com.condpulse.occurrences.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.occurrences.application.OccurrenceModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL das ocorrências (V033). Autoria, nomes e horários são gravados pelo banco; as políticas exigem a capacidade da equipe. */
@Repository @Profile("api")
public class OccurrenceStore {
 public static final int PAGE=100;
 private static final String SELECT="""
  select o.id,o.category,o.title,o.description,o.unit_id,u.label as unit_label,o.state,o.created_by_name,
   o.created_by=platform.current_actor_id() as mine,o.created_at,o.resolution,o.resolved_by_name,o.resolved_at,o.version
  from cp.concierge_occurrences o left join cp.units u on u.tenant_id=o.tenant_id and u.id=o.unit_id
  """;
 private final JdbcTemplate jdbc;
 public OccurrenceStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Abertas primeiro (mais antigas no topo: esperam verificação); encerradas dos últimos 30 dias depois, quando pedidas. */
 public OccurrenceModels.Page page(UUID tenant,boolean includeResolved) {
  var rows=jdbc.query(SELECT+"""
   where o.tenant_id=? and (o.state='OPEN' or (? and o.resolved_at>clock_timestamp()-interval '30 days'))
   order by (o.state='OPEN') desc,case when o.state='OPEN' then o.created_at end asc,o.resolved_at desc,o.id limit ?
   """,(r,n)->occurrence(r),tenant,includeResolved,PAGE+1);
  Long open=jdbc.queryForObject("select count(*) from cp.concierge_occurrences where tenant_id=? and state='OPEN'",Long.class,tenant);
  return new OccurrenceModels.Page(rows.size()>PAGE?rows.subList(0,PAGE):rows,open==null?0:open,rows.size()>PAGE);
 }
 public OccurrenceModels.Occurrence find(UUID tenant,UUID id) {
  var rows=jdbc.query(SELECT+" where o.tenant_id=? and o.id=?",(r,n)->occurrence(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean unitExists(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from cp.units where tenant_id=? and id=?)",Boolean.class,tenant,unit));
 }
 /** false: o identificador já existia (repetição ou colisão), nada gravado. */
 public boolean insert(UUID tenant,OccurrenceModels.Create request,String title,String description) {
  return jdbc.update("""
   insert into cp.concierge_occurrences(id,tenant_id,category,title,description,unit_id) values(?,?,?,?,?,?) on conflict(id) do nothing
   """,request.occurrenceId(),tenant,request.category(),title,description,request.unitId())==1;
 }
 public boolean resolve(UUID tenant,UUID id,String resolution,long expectedVersion) {
  return jdbc.update("""
   update cp.concierge_occurrences set state='RESOLVED',resolution=?,version=version+1 where tenant_id=? and id=? and state='OPEN' and version=?
   """,resolution,tenant,id,expectedVersion)==1;
 }
 private static OccurrenceModels.Occurrence occurrence(ResultSet r) throws SQLException {
  return new OccurrenceModels.Occurrence(r.getObject("id",UUID.class),r.getString("category"),r.getString("title"),r.getString("description"),
   r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("state"),r.getString("created_by_name"),r.getBoolean("mine"),
   instant(r.getTimestamp("created_at")),r.getString("resolution"),r.getString("resolved_by_name"),instant(r.getTimestamp("resolved_at")),r.getLong("version"));
 }
 private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
