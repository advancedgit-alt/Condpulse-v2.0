package br.com.condpulse.accesscredentials.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reconciliação pelo worker (cp_worker, sem conta). Lê o estado desejado e os destinos e grava só o resultado por
 * destino; o banco recusa (23514) resultado de versão ou ação que não é mais a desejada.
 */
public class CredentialSyncStore {
 public static final int BATCH=200;
 private final JdbcTemplate jdbc;
 public CredentialSyncStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public record Connector(UUID id,String provider) {}
 /** Destino com o último comando registrado (null quando nunca enviado). */
 public record Target(UUID deviceId,String externalId,Long attemptedVersion,String attemptedAction,String outcome,int attempts) {}
 public record Work(UUID credentialId,String externalRef,String desiredState,long desiredVersion,java.time.Instant validUntil,List<Target> targets) {
  public Work { targets=List.copyOf(targets); }
  public String action() { return "REVOKED".equals(desiredState)?"REVOKE":"APPLY"; }
 }

 public List<UUID> activeTenants() {
  return jdbc.query("select id from platform.tenants where active order by id",(r,n)->r.getObject("id",UUID.class));
 }
 public List<Connector> connectors(UUID tenant) {
  return jdbc.query("select id,provider from cp.access_connectors where tenant_id=? and status='ACTIVE' order by id",
   (r,n)->new Connector(r.getObject("id",UUID.class),r.getString("provider")),tenant);
 }
 /**
  * Destinos que ainda não confirmaram o estado desejado atual e estão no prazo de nova tentativa (ou mudaram de versão).
  * Revogações primeiro, das mais antigas; recusa definitiva da versão atual não volta sem mudança.
  */
 public List<Work> due(UUID tenant,UUID connector) {
  Map<UUID,Work> works=new LinkedHashMap<>();
  Map<UUID,List<Target>> targets=new LinkedHashMap<>();
  jdbc.query("""
   select c.id,c.external_ref,c.desired_state,c.desired_version,c.valid_until,d.id as device_id,d.external_id,s.attempted_version,s.attempted_action,s.outcome,
    coalesce(s.attempts,0) as attempts
   from cp.access_credentials c
   join cp.access_devices d on d.tenant_id=c.tenant_id and d.connector_id=c.connector_id and d.status='ACTIVE'
   left join cp.credential_syncs s on s.tenant_id=c.tenant_id and s.credential_id=c.id and s.device_id=d.id
   where c.tenant_id=? and c.connector_id=?
    -- Vínculo vencido: nenhuma ativação nova (o equipamento já recebeu o mesmo limite de validade).
    and not (c.desired_state='ACTIVE' and c.valid_until is not null and c.valid_until<=clock_timestamp())
    and not (s.observed_version is not distinct from c.desired_version and s.observed_action is not distinct from cp.credential_action(c.desired_state))
    and (s.credential_id is null or s.attempted_version<>c.desired_version or s.attempted_action<>cp.credential_action(c.desired_state)
     or (s.outcome<>'REJECTED' and s.next_attempt_at<=clock_timestamp()))
   order by (c.desired_state='REVOKED') desc,c.revoked_at nulls last,c.created_at,c.id,d.id
   limit ?
   """,r->{
    UUID id=r.getObject("id",UUID.class);
    var until=r.getTimestamp("valid_until");
    works.putIfAbsent(id,new Work(id,r.getString("external_ref"),r.getString("desired_state"),r.getLong("desired_version"),
     until==null?null:until.toInstant(),List.of()));
    targets.computeIfAbsent(id,k->new ArrayList<>()).add(new Target(r.getObject("device_id",UUID.class),r.getString("external_id"),
     r.getObject("attempted_version",Long.class),r.getString("attempted_action"),r.getString("outcome"),r.getInt("attempts")));
   },tenant,connector,BATCH);
  return works.values().stream().map(w->new Work(w.credentialId(),w.externalRef(),w.desiredState(),w.desiredVersion(),w.validUntil(),
   targets.get(w.credentialId()))).toList();
 }
 /** Versão e ação desejadas atuais da credencial ("1:APPLY"), ou null. */
 public String desired(UUID tenant,UUID credential) {
  var rows=jdbc.query("select desired_version||':'||cp.credential_action(desired_state) as desired from cp.access_credentials where tenant_id=? and id=?",
   (r,n)->r.getString("desired"),tenant,credential);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Grava o resultado de um comando ou consulta; {@code delaySeconds} até a próxima tentativa (relógio do banco). */
 public void record(UUID tenant,UUID credential,UUID device,long version,String action,String outcome,String reason,long delaySeconds) {
  jdbc.update("""
   insert into cp.credential_syncs(tenant_id,credential_id,device_id,attempted_version,attempted_action,outcome,attempts,next_attempt_at,last_reason)
   values(?,?,?,?,?,?,1,clock_timestamp()+make_interval(secs=>?),?)
   on conflict(tenant_id,credential_id,device_id) do update set attempted_version=excluded.attempted_version,
    attempted_action=excluded.attempted_action,outcome=excluded.outcome,
    attempts=case when credential_syncs.attempted_version=excluded.attempted_version and credential_syncs.attempted_action=excluded.attempted_action
     then least(credential_syncs.attempts+1,1000000) else 1 end,
    next_attempt_at=excluded.next_attempt_at,last_reason=excluded.last_reason
   """,tenant,credential,device,version,action,outcome,(double)delaySeconds,reason);
 }
}
