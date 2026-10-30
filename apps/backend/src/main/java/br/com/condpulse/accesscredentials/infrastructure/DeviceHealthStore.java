package br.com.condpulse.accesscredentials.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Saúde informada pelo adaptador (CP-048), gravada pelo worker (cp_worker, sem conta) no condomínio do conector. Os
 * horários de verificação, observação e mudança de estado são do banco (V028).
 */
public class DeviceHealthStore {
 private final JdbcTemplate jdbc;
 public DeviceHealthStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Identificador do parceiro → equipamento ativo do mesmo conector. */
 public Map<String,UUID> devices(UUID tenant,UUID connector) {
  Map<String,UUID> result=new HashMap<>();
  jdbc.query("select id,external_id from cp.access_devices where tenant_id=? and connector_id=? and status='ACTIVE'",
   r->{ result.put(r.getString("external_id"),r.getObject("id",UUID.class)); },tenant,connector);
  return result;
 }
 public void reachable(UUID tenant,UUID connector,Integer pendingCommands,Long observedDelayMillis) {
  connector(tenant,connector,true,pendingCommands,observedDelayMillis,null);
 }
 public void unreachable(UUID tenant,UUID connector,String reason) { connector(tenant,connector,false,null,null,reason); }
 private void connector(UUID tenant,UUID connector,boolean reachable,Integer pending,Long delay,String reason) {
  jdbc.update("""
   insert into cp.access_connector_health(tenant_id,connector_id,reachable,pending_commands,observed_delay_ms,failure_reason) values(?,?,?,?,?,?)
   on conflict(tenant_id,connector_id) do update set reachable=excluded.reachable,pending_commands=excluded.pending_commands,
    observed_delay_ms=excluded.observed_delay_ms,failure_reason=excluded.failure_reason
   """,tenant,connector,reachable,pending,delay,reason);
 }
 public void device(UUID tenant,UUID device,boolean online,Instant lastHeartbeat,Long clockSkewMillis) {
  jdbc.update("""
   insert into cp.access_device_health(tenant_id,device_id,online,last_heartbeat,clock_skew_ms) values(?,?,?,?,?)
   on conflict(tenant_id,device_id) do update set online=excluded.online,last_heartbeat=excluded.last_heartbeat,clock_skew_ms=excluded.clock_skew_ms
   """,tenant,device,online,lastHeartbeat==null?null:Timestamp.from(lastHeartbeat),clockSkewMillis);
 }
}
