package br.com.condpulse.gateway.events;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import br.com.condpulse.gateway.journal.Journal;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Fila durável de eventos a enviar à plataforma (POST /v1/edge/events). O evento só sai da fila quando a plataforma
 * responde ACCEPTED ou DUPLICATE para ele; REJECTED vai para quarentena local com o motivo. O cursor de busca de cada
 * equipamento só avança depois que os eventos da página estão gravados aqui. Deduplicação local pela chave
 * (equipamento provisionado, época, id nativo); a plataforma deduplica de novo.
 */
public final class EventOutbox implements AutoCloseable {
 public enum State { PENDING, SENT, QUARANTINED }
 public record Item(EdgeEvent event,State state,String reasonCode) {}

 private final Journal journal;
 private final Clock clock;
 private final int limit;
 private final Map<String,Item> items=new LinkedHashMap<>();
 private final Map<String,String> cursors=new HashMap<>();

 public EventOutbox(Path dir,Clock clock,int limit) {
  this.clock=clock; this.limit=limit;
  this.journal=new Journal(dir.resolve("event-outbox.jsonl"));
  journal.replay(this::apply);
 }

 /** Grava a página e, na mesma passagem, a nova posição do cursor do equipamento. Devolve quantos eram novos. */
 public synchronized int add(String deviceId,List<EdgeEvent> events,String nextCursor) {
  long pending=items.values().stream().filter(i->i.state()==State.PENDING).count();
  if(pending+events.size()>limit) throw new IllegalStateException("EVENT_OUTBOX_FULL");
  int fresh=0;
  for(EdgeEvent e:events) {
   if(items.containsKey(e.eventId())) continue;
   var m=new LinkedHashMap<String,Object>();
   m.put("t","add"); m.put("event",toMap(e)); m.put("at",clock.instant().toString());
   journal.append(m); apply(m); fresh++;
  }
  if(nextCursor!=null && !nextCursor.equals(cursors.get(deviceId))) {
   var m=new LinkedHashMap<String,Object>();
   m.put("t","cursor"); m.put("deviceId",deviceId); m.put("cursor",nextCursor); m.put("at",clock.instant().toString());
   journal.append(m); apply(m);
  }
  return fresh;
 }
 public synchronized Optional<String> cursor(String deviceId) { return Optional.ofNullable(cursors.get(deviceId)); }

 public synchronized List<EdgeEvent> nextBatch(int max) {
  var out=new ArrayList<EdgeEvent>();
  for(var i:items.values()) { if(out.size()>=max) break; if(i.state()==State.PENDING) out.add(i.event()); }
  return out;
 }
 public synchronized void result(String eventId,State state,String reason) {
  if(!items.containsKey(eventId)) return;
  var m=new LinkedHashMap<String,Object>();
  m.put("t","state"); m.put("eventId",eventId); m.put("state",state.name()); m.put("reason",reason); m.put("at",clock.instant().toString());
  journal.append(m); apply(m);
 }
 public synchronized long pendingCount() { return items.values().stream().filter(i->i.state()==State.PENDING).count(); }
 public synchronized long quarantinedCount() { return items.values().stream().filter(i->i.state()==State.QUARANTINED).count(); }
 public synchronized Optional<Item> item(String eventId) { return Optional.ofNullable(items.get(eventId)); }

 private void apply(Map<String,Object> m) {
  switch(Json.string(m,"t")) {
   case "add" -> { var e=fromMap(Json.map(m.get("event"))); items.putIfAbsent(e.eventId(),new Item(e,State.PENDING,null)); }
   case "cursor" -> cursors.put(Json.string(m,"deviceId"),Json.string(m,"cursor"));
   case "state" -> {
    var i=items.get(Json.string(m,"eventId"));
    if(i!=null) items.put(i.event().eventId(),new Item(i.event(),State.valueOf(Json.string(m,"state")),Json.string(m,"reason")));
   }
   default -> throw new IllegalStateException("Registro desconhecido na fila de eventos.");
  }
 }

 public static Map<String,Object> toMap(EdgeEvent e) {
  var m=new LinkedHashMap<String,Object>();
  m.put("schemaVersion","1.0");
  m.put("eventId",e.eventId()); m.put("deviceId",e.deviceId()); m.put("deviceProvisioningId",e.deviceProvisioningId());
  m.put("eventEpoch",e.eventEpoch()); m.put("nativeEventId",e.nativeEventId()); m.put("sourceConnectorId",e.sourceConnectorId());
  m.put("bindingRevision",null); m.put("pointId",null); m.put("subjectRef",e.subjectRef()); m.put("kind",e.kind().name());
  m.put("occurredAt",e.occurredAt()==null?null:e.occurredAt().toString()); m.put("direction",e.direction().name());
  m.put("timeQuality",e.timeQuality().name()); m.put("correlationId",null); m.put("policyVersion",null);
  var ev=new LinkedHashMap<String,Object>();
  ev.put("level",e.evidence().level().name()); ev.put("sourceKind",e.evidence().sourceKind().name()); ev.put("nativeKind",e.evidence().nativeKind());
  ev.put("evidenceRef",e.evidence().evidenceRef()); ev.put("inferenceReason",e.evidence().inferenceReason());
  m.put("evidence",ev);
  m.put("deviceChannelId",e.deviceChannelId()); m.put("nativeChannelId",e.nativeChannelId());
  return m;
 }
 static EdgeEvent fromMap(Map<String,Object> m) {
  var ev=Json.map(m.get("evidence"));
  String occurred=Json.string(m,"occurredAt");
  return new EdgeEvent(Json.string(m,"eventId"),Json.string(m,"deviceId"),Json.string(m,"deviceProvisioningId"),Json.string(m,"eventEpoch"),
   Json.string(m,"nativeEventId"),Json.string(m,"sourceConnectorId"),Json.string(m,"subjectRef"),EventKind.valueOf(Json.string(m,"kind")),
   occurred==null?null:Instant.parse(occurred),Direction.valueOf(Json.string(m,"direction")),TimeQuality.valueOf(Json.string(m,"timeQuality")),
   new Evidence(EvidenceLevel.valueOf(Json.string(ev,"level")),EvidenceSource.valueOf(Json.string(ev,"sourceKind")),Json.string(ev,"nativeKind"),
    Json.string(ev,"evidenceRef"),Json.string(ev,"inferenceReason")),Json.string(m,"deviceChannelId"),Json.string(m,"nativeChannelId"));
 }

 @Override public void close() { journal.close(); }
}
