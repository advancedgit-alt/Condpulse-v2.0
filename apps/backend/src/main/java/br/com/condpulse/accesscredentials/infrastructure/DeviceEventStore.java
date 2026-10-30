package br.com.condpulse.accesscredentials.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gravação dos eventos de equipamento pelo worker (cp_worker, V027). Equipamento e credencial são procurados só entre os
 * do conector; a unidade é definida pelo banco a partir da credencial. Repetição do mesmo eventId não grava de novo.
 */
public class DeviceEventStore {
 private final JdbcTemplate jdbc;
 public DeviceEventStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(); }
 /** Posição salva: último recebimento e, se a passada anterior parou no limite de páginas, continuação e janela. */
 public record Position(Instant lastReceived,String continuation,Instant windowFrom,Instant windowUntil) {}
 public Position position(UUID tenant,UUID connector) {
  var rows=jdbc.query("select last_received_at,continuation,window_from,window_until from cp.device_event_cursors where tenant_id=? and connector_id=?",
   (r,n)->new Position(instant(r.getTimestamp("last_received_at")),r.getString("continuation"),instant(r.getTimestamp("window_from")),
    instant(r.getTimestamp("window_until"))),tenant,connector);
  return rows.isEmpty()?new Position(null,null,null,null):rows.getFirst();
 }
 /**
  * Grava o fim da passada: avança o último recebimento (nunca volta) e guarda a continuação com a janela, ou limpa a
  * continuação quando a busca chegou ao fim.
  */
 public void save(UUID tenant,UUID connector,Instant lastReceived,String continuation,Instant windowFrom,Instant windowUntil) {
  jdbc.update("""
   insert into cp.device_event_cursors(tenant_id,connector_id,last_received_at,continuation,window_from,window_until) values(?,?,?,?,?,?)
   on conflict(tenant_id,connector_id) do update set
    last_received_at=case when cp.device_event_cursors.last_received_at is null then excluded.last_received_at
     when excluded.last_received_at is null then cp.device_event_cursors.last_received_at
     else greatest(cp.device_event_cursors.last_received_at,excluded.last_received_at) end,
    continuation=excluded.continuation,window_from=excluded.window_from,window_until=excluded.window_until,updated_at=clock_timestamp()
   """,tenant,connector,ts(lastReceived),continuation,ts(windowFrom),ts(windowUntil));
 }
 private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
 private static Timestamp ts(Instant value) { return value==null?null:Timestamp.from(value); }
 public UUID device(UUID tenant,UUID connector,String externalId) {
  var rows=jdbc.query("select id from cp.access_devices where tenant_id=? and connector_id=? and external_id=?",
   (r,n)->r.getObject("id",UUID.class),tenant,connector,externalId);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public UUID credential(UUID tenant,UUID connector,String externalRef) {
  if(externalRef==null) return null;
  var rows=jdbc.query("select id from cp.access_credentials where tenant_id=? and connector_id=? and external_ref=?",
   (r,n)->r.getObject("id",UUID.class),tenant,connector,externalRef);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** true quando gravou; false quando o mesmo evento já existia (repetição do parceiro). */
 public boolean insert(UUID tenant,UUID connector,String eventId,UUID device,UUID credential,String kind,String direction,String evidence,
     Long appliedVersion,Instant occurredAt,Instant receivedAt,String quarantineReason) {
  return jdbc.update("""
   insert into cp.device_events(tenant_id,connector_id,external_event_id,device_id,credential_id,kind,direction,evidence,applied_version,
    occurred_at,received_at,state,quarantine_reason)
   values(?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(tenant_id,connector_id,external_event_id) do nothing
   """,tenant,connector,eventId,device,credential,kind,direction,evidence,appliedVersion,Timestamp.from(occurredAt),Timestamp.from(receivedAt),
   quarantineReason==null?"ACCEPTED":"QUARANTINED",quarantineReason)==1;
 }
}
