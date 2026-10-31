package br.com.condpulse.gateway.queue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import br.com.condpulse.gateway.journal.Journal;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.Spi.OperationState;

/**
 * Fila local idempotente de sincronização por equipamento (manual 12 e 17.2). Regras:
 * <ul>
 * <li>mesma chave de idempotência devolve a entrada existente, sem duplicar;</li>
 * <li>revogação sai antes de qualquer outra mensagem e supera as aplicações pendentes de versão menor ou igual do
 * mesmo escopo; aplicação com versão menor ou igual a uma revogação já recebida é recusada (STALE);</li>
 * <li>acima do limite suave só entra revogação; no limite rígido nem revogação entra e o chamador recebe
 * {@link EnqueueOutcome#REJECTED_FULL} — nada é descartado em silêncio;</li>
 * <li>reinício com comando em execução vira NEEDS_READBACK: consulta antes de qualquer reenvio, nunca repetição às
 * cegas;</li>
 * <li>equipamento fora do ar mantém a pendência visível ({@link #pending}).</li>
 * </ul>
 * A fila nunca guarda foto, PIN, cartão ou QR: só a referência do material, buscado na hora do envio.
 */
public final class LocalCommandQueue implements AutoCloseable {
 public enum CommandType {
  UPSERT_PERSON(false), ENROLL_FACE(false), SET_CREDENTIAL(false), APPLY_RIGHTS(false),
  REMOVE_FACE(true), REMOVE_CREDENTIAL(true), REVOKE_RIGHTS(true), REVOKE_ALL(true);
  private final boolean revocation;
  CommandType(boolean revocation) { this.revocation=revocation; }
  public boolean revocation() { return revocation; }
 }
 public enum Status { PENDING, IN_FLIGHT, NEEDS_READBACK, DONE, FAILED, SUPERSEDED, REJECTED_STALE }
 public enum EnqueueOutcome { ACCEPTED, DUPLICATE, REJECTED_STALE, REJECTED_FULL }

 /** Comando tipado da plataforma. payload não contém segredo nem biometria (só referências). */
 public record SyncCommand(String commandId,String idempotencyKey,String deviceId,String subjectRef,CommandType type,long desiredVersion,
     Map<String,Object> payload,Instant issuedAt) {
  public SyncCommand {
   for(String v:new String[]{commandId,idempotencyKey,deviceId,subjectRef}) if(v==null || v.isBlank() || v.length()>200) throw new IllegalArgumentException("Campo obrigatório do comando.");
   if(type==null || desiredVersion<1 || issuedAt==null) throw new IllegalArgumentException("Tipo, versão e emissão obrigatórios.");
   payload=payload==null?Map.of():java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
  /** Escopo afetado: ALL, RIGHTS, FACE, CRED:&lt;tipo&gt; ou PERSON. */
  public String scope() {
   return switch(type) {
    case REVOKE_ALL -> "ALL";
    case APPLY_RIGHTS,REVOKE_RIGHTS -> "RIGHTS";
    case ENROLL_FACE,REMOVE_FACE -> "FACE";
    case SET_CREDENTIAL,REMOVE_CREDENTIAL -> "CRED:"+payload.get("kind");
    case UPSERT_PERSON -> "PERSON";
   };
  }
 }
 /** reported: o resultado terminal já foi publicado na plataforma (publicação pendente sobrevive a reinício). */
 public record Entry(SyncCommand command,long sequence,Status status,OperationState lastState,String lastReason,int attempts,
     Instant enqueuedAt,Instant lastAttemptAt,boolean reported) {
  public boolean terminal() { return status==Status.DONE || status==Status.FAILED || status==Status.SUPERSEDED || status==Status.REJECTED_STALE; }
 }
 public record EnqueueResult(EnqueueOutcome outcome,Entry entry,List<String> superseded) {}

 private final Journal journal;
 private final Clock clock;
 private final int softLimit,hardLimit;
 private final Map<String,Entry> byId=new LinkedHashMap<>();
 private final Map<String,String> byKey=new HashMap<>();
 private final Map<String,Long> revokedVersion=new HashMap<>();
 /** Maior versão de aplicação aceita por escopo: versão anterior que chega depois é recusada (não restaura estado antigo). */
 private final Map<String,Long> acceptedVersion=new HashMap<>();
 private long sequence;
 private final List<String> lastSuperseded=new ArrayList<>();

 public LocalCommandQueue(Path dir,Clock clock,int softLimit,int hardLimit) {
  this.clock=clock; this.softLimit=softLimit; this.hardLimit=hardLimit;
  this.journal=new Journal(dir.resolve("sync-queue.jsonl"));
  journal.replay(this::apply);
  // Comando que estava em execução quando o processo caiu: resultado incerto.
  for(var e:List.copyOf(byId.values())) if(e.status()==Status.IN_FLIGHT) mark(e.command().commandId(),Status.NEEDS_READBACK,OperationState.UNKNOWN,"GATEWAY_RESTARTED_IN_FLIGHT");
 }

 public synchronized EnqueueResult enqueue(SyncCommand command) {
  String existing=byKey.get(command.idempotencyKey());
  if(existing!=null) return new EnqueueResult(EnqueueOutcome.DUPLICATE,byId.get(existing),List.of());
  if(byId.containsKey(command.commandId())) return new EnqueueResult(EnqueueOutcome.DUPLICATE,byId.get(command.commandId()),List.of());
  long active=byId.values().stream().filter(LocalCommandQueue::active).count();
  if(active>=hardLimit || (!command.type().revocation() && active>=softLimit))
   return new EnqueueResult(EnqueueOutcome.REJECTED_FULL,null,List.of());
  var record=new LinkedHashMap<String,Object>();
  record.put("t","enq");
  record.put("cmd",toMap(command));
  record.put("at",clock.instant().toString());
  journal.append(record);
  apply(record);
  var entry=byId.get(command.commandId());
  // A supersessão decorre do próprio registro da revogação (apply), igual no envio e na releitura do diário.
  var superseded=new ArrayList<String>(lastSuperseded);
  entry=byId.get(command.commandId());
  return new EnqueueResult(entry.status()==Status.REJECTED_STALE?EnqueueOutcome.REJECTED_STALE:EnqueueOutcome.ACCEPTED,entry,superseded);
 }

 /** Próximo comando do equipamento: revogações primeiro, depois ordem de chegada. */
 public synchronized Optional<Entry> next(String deviceId) {
  return byId.values().stream()
   .filter(e->e.command().deviceId().equals(deviceId) && (e.status()==Status.PENDING || e.status()==Status.NEEDS_READBACK))
   .min(Comparator.comparing((Entry e)->!e.command().type().revocation()).thenComparingLong(Entry::sequence));
 }

 public synchronized void mark(String commandId,Status status,OperationState state,String reason) {
  var record=new LinkedHashMap<String,Object>();
  record.put("t","mark");
  record.put("id",commandId);
  record.put("status",status.name());
  record.put("state",state==null?null:state.name());
  record.put("reason",reason);
  record.put("at",clock.instant().toString());
  journal.append(record);
  apply(record);
 }

 /** Resultado terminal publicado na plataforma. */
 public synchronized void markReported(String commandId) {
  var record=new LinkedHashMap<String,Object>();
  record.put("t","reported");
  record.put("id",commandId);
  record.put("at",clock.instant().toString());
  journal.append(record);
  apply(record);
 }

 /** Resultados terminais ainda não publicados, na ordem de chegada. */
 public synchronized List<Entry> unreported() {
  return byId.values().stream().filter(e->e.terminal() && !e.reported()).toList();
 }

 /** Todas as entradas na ordem de chegada (reconciliação na recuperação). */
 public synchronized List<Entry> all() { return List.copyOf(byId.values()); }

 public synchronized Optional<Entry> get(String commandId) { return Optional.ofNullable(byId.get(commandId)); }

 /** Pendências visíveis do equipamento (inclusive quando ele está fora do ar). */
 public synchronized List<Entry> pending(String deviceId) {
  return byId.values().stream().filter(e->e.command().deviceId().equals(deviceId) && active(e)).toList();
 }
 public synchronized Optional<Instant> oldestPendingRevocation(String deviceId) {
  return pending(deviceId).stream().filter(e->e.command().type().revocation()).map(Entry::enqueuedAt).min(Comparator.naturalOrder());
 }
 public synchronized long activeCount() { return byId.values().stream().filter(LocalCommandQueue::active).count(); }

 private static boolean active(Entry e) { return e.status()==Status.PENDING || e.status()==Status.IN_FLIGHT || e.status()==Status.NEEDS_READBACK; }

 /** A revogação cobre o escopo da outra mensagem? */
 static boolean covers(SyncCommand revocation,SyncCommand other) {
  String scope=revocation.scope();
  if(scope.equals("ALL")) return true;
  return scope.equals(other.scope());
 }

 private void apply(Map<String,Object> record) {
  String t=Json.string(record,"t");
  Instant at=Instant.parse(Json.string(record,"at"));
  if("enq".equals(t)) {
   var command=fromMap(Json.map(record.get("cmd")));
   long seq=++sequence;
   Status status=Status.PENDING;
   String reason=null;
   if(!command.type().revocation()) {
    long revoked=Math.max(revokedVersion.getOrDefault(key(command,"ALL"),0L),revokedVersion.getOrDefault(key(command,command.scope()),0L));
    long accepted=acceptedVersion.getOrDefault(key(command,command.scope()),0L);
    if(command.desiredVersion()<=revoked) { status=Status.REJECTED_STALE; reason="STALE_VERSION_AFTER_REVOCATION"; }
    else if(command.desiredVersion()<=accepted) { status=Status.REJECTED_STALE; reason="STALE_VERSION"; }
    else acceptedVersion.put(key(command,command.scope()),command.desiredVersion());
   } else {
    revokedVersion.merge(key(command,command.scope()),command.desiredVersion(),Math::max);
   }
   byId.put(command.commandId(),new Entry(command,seq,status,null,reason,0,at,null,false));
   byKey.put(command.idempotencyKey(),command.commandId());
   lastSuperseded.clear();
   if(command.type().revocation()) {
    for(var other:List.copyOf(byId.values())) {
     var oc=other.command();
     if(!active(other) || oc.type().revocation() || !oc.deviceId().equals(command.deviceId()) || !oc.subjectRef().equals(command.subjectRef())) continue;
     if(oc.desiredVersion()>command.desiredVersion() || !covers(command,oc)) continue;
     byId.put(oc.commandId(),new Entry(oc,other.sequence(),Status.SUPERSEDED,other.lastState(),"SUPERSEDED_BY_REVOCATION",other.attempts(),
      other.enqueuedAt(),other.lastAttemptAt(),false));
     lastSuperseded.add(oc.commandId());
    }
   }
  } else if("mark".equals(t)) {
   var e=byId.get(Json.string(record,"id"));
   if(e==null) return;
   Status status=Status.valueOf(Json.string(record,"status"));
   String state=Json.string(record,"state");
   int attempts=e.attempts()+(status==Status.IN_FLIGHT?1:0);
   byId.put(e.command().commandId(),new Entry(e.command(),e.sequence(),status,state==null?e.lastState():OperationState.valueOf(state),
    Json.string(record,"reason"),attempts,e.enqueuedAt(),status==Status.IN_FLIGHT?at:e.lastAttemptAt(),false));
  } else if("reported".equals(t)) {
   var e=byId.get(Json.string(record,"id"));
   if(e==null) return;
   byId.put(e.command().commandId(),new Entry(e.command(),e.sequence(),e.status(),e.lastState(),e.lastReason(),e.attempts(),e.enqueuedAt(),e.lastAttemptAt(),true));
  }
 }

 private static String key(SyncCommand c,String scope) { return c.deviceId()+"|"+c.subjectRef()+"|"+scope; }

 static Map<String,Object> toMap(SyncCommand c) {
  var m=new LinkedHashMap<String,Object>();
  m.put("commandId",c.commandId()); m.put("idempotencyKey",c.idempotencyKey()); m.put("deviceId",c.deviceId());
  m.put("subjectRef",c.subjectRef()); m.put("type",c.type().name()); m.put("desiredVersion",c.desiredVersion());
  m.put("payload",new LinkedHashMap<>(c.payload())); m.put("issuedAt",c.issuedAt().toString());
  return m;
 }
 static SyncCommand fromMap(Map<String,Object> m) {
  return new SyncCommand(Json.string(m,"commandId"),Json.string(m,"idempotencyKey"),Json.string(m,"deviceId"),Json.string(m,"subjectRef"),
   CommandType.valueOf(Json.string(m,"type")),Json.longValue(m,"desiredVersion"),Json.map(m.get("payload")),Instant.parse(Json.string(m,"issuedAt")));
 }

 @Override public void close() { journal.close(); }
}
