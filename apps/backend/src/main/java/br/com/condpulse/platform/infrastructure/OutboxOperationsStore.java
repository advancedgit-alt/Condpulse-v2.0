package br.com.condpulse.platform.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import br.com.condpulse.platform.application.OutboxOperationsModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL da operação da fila (V032). Só dentro do PlatformTransactionExecutor: as políticas do outbox exigem outbox.operate
 * no contexto local. O conteúdo (payload) não tem grant para a API.
 */
@Repository @Profile("api")
public class OutboxOperationsStore {
 public static final int TASK_PAGE=100;
 public static final int TENANT_LIMIT=200;
 private static final String TASK="select id,tenant_id,event_type,aggregate_id,state,attempts,last_error,created_at,available_at,processed_at from cp.outbox";
 private final JdbcTemplate jdbc;
 public OutboxOperationsStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Condomínios com tarefa pendente, arrendada, falha ou ignorada; os com alerta primeiro. */
 public java.util.List<OutboxOperationsModels.TenantQueue> summary() {
  return jdbc.query("""
   with q as(
    select o.tenant_id,
     count(*) filter(where o.state='PENDING') as pending,
     count(*) filter(where o.state='PENDING' and o.available_at<=clock_timestamp()) as ready,
     floor(extract(epoch from clock_timestamp()-min(o.available_at) filter(where o.state='PENDING' and o.available_at<=clock_timestamp())))::bigint as oldest,
     count(*) filter(where o.state='LEASED' and o.lease_until<=clock_timestamp()) as expired,
     count(*) filter(where o.state='FAILED') as failed,
     count(*) filter(where o.state='SKIPPED') as skipped
    from cp.outbox o where o.state in('PENDING','LEASED','FAILED','SKIPPED') group by o.tenant_id)
   select q.*,t.name,(coalesce(q.oldest,0)>? or q.expired>0) as stale from q left join platform.tenants t on t.id=q.tenant_id
   order by stale desc,q.failed desc,q.oldest desc nulls last,q.tenant_id limit ?
   """,(r,n)->new OutboxOperationsModels.TenantQueue(r.getObject("tenant_id",UUID.class),r.getString("name"),r.getLong("pending"),
    r.getLong("ready"),(Long)r.getObject("oldest",Long.class),r.getLong("expired"),r.getLong("failed"),r.getLong("skipped"),r.getBoolean("stale")),
   OutboxOperationsModels.STALE_SECONDS,TENANT_LIMIT);
 }
 public OutboxOperationsModels.TaskPage tasks(UUID tenant,String state) {
  var rows=tenant==null
   ?jdbc.query(TASK+" where state=? order by processed_at desc nulls last,id limit ?",(r,n)->task(r),state,TASK_PAGE+1)
   :jdbc.query(TASK+" where tenant_id=? and state=? order by processed_at desc nulls last,id limit ?",(r,n)->task(r),tenant,state,TASK_PAGE+1);
  return new OutboxOperationsModels.TaskPage(rows.size()>TASK_PAGE?rows.subList(0,TASK_PAGE):rows,rows.size()>TASK_PAGE);
 }
 public OutboxOperationsModels.Task task(UUID id) {
  var rows=jdbc.query(TASK+" where id=?",(r,n)->task(r),id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** FAILED ou SKIPPED de volta para PENDING; o banco zera tentativa e erro e deixa disponível já. */
 public boolean replay(UUID id) {
  return jdbc.update("update cp.outbox set state='PENDING' where id=? and state in('FAILED','SKIPPED')",id)==1;
 }
 private static OutboxOperationsModels.Task task(ResultSet r) throws SQLException {
  return new OutboxOperationsModels.Task(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("event_type"),
   r.getObject("aggregate_id",UUID.class),r.getString("state"),r.getInt("attempts"),r.getString("last_error"),instant(r.getTimestamp("created_at")),
   instant(r.getTimestamp("available_at")),instant(r.getTimestamp("processed_at")));
 }
 private static java.time.Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
