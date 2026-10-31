package br.com.condpulse.gateway.central;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import br.com.condpulse.gateway.central.CentralApiClient.CentralUnavailable;
import br.com.condpulse.gateway.central.CentralApiClient.DecisionResult;
import br.com.condpulse.gateway.central.CentralApiClient.EdgeDecision;
import br.com.condpulse.gateway.journal.Journal;
import br.com.condpulse.gateway.json.Json;

/**
 * Telemetria das decisões locais (POST /v1/edge/decisions) fora do caminho do relé. {@link #offer} só põe em fila
 * de memória (sem rede, sem disco); {@link #flush} — no ciclo de fundo — grava no diário e envia. Fila de memória
 * cheia descarta a mais antiga e conta o descarte (visível na saúde): telemetria nunca segura a resposta ao
 * equipamento. Depois de gravada no diário, a decisão sobrevive a reinício até a plataforma reconhecer.
 */
public final class DecisionOutbox implements AutoCloseable {
 private final Journal journal;
 private final Clock clock;
 private final int memoryLimit;
 private final ConcurrentLinkedQueue<EdgeDecision> memory=new ConcurrentLinkedQueue<>();
 private final AtomicInteger memorySize=new AtomicInteger();
 private final AtomicLong dropped=new AtomicLong();
 private final Map<String,EdgeDecision> pending=new LinkedHashMap<>();

 public DecisionOutbox(Path dir,Clock clock,int memoryLimit) {
  this.clock=clock; this.memoryLimit=memoryLimit;
  this.journal=new Journal(dir.resolve("decision-outbox.jsonl"));
  journal.replay(this::apply);
 }

 /** Não bloqueia: chamado no caminho síncrono da decisão. */
 public void offer(EdgeDecision decision) {
  memory.add(decision);
  if(memorySize.incrementAndGet()>memoryLimit && memory.poll()!=null) { memorySize.decrementAndGet(); dropped.incrementAndGet(); }
 }

 public long dropped() { return dropped.get(); }
 public synchronized int pendingCount() { return pending.size()+memorySize.get(); }

 /** Grava o que está em memória e envia o pendente; para no primeiro erro da plataforma. Devolve quantos foram aceitos. */
 public synchronized int flush(CentralApiClient central) {
  EdgeDecision d;
  while((d=memory.poll())!=null) {
   memorySize.decrementAndGet();
   var m=new LinkedHashMap<String,Object>();
   m.put("t","add"); m.put("decision",toMap(d)); m.put("at",clock.instant().toString());
   journal.append(m); apply(m);
  }
  int sent=0;
  for(var decision:new ArrayList<>(pending.values())) {
   try { central.postDecision(decision,decision.decisionId()); }
   catch(CentralUnavailable|RuntimeException e) { break; }
   var m=new LinkedHashMap<String,Object>();
   m.put("t","sent"); m.put("decisionId",decision.decisionId()); m.put("at",clock.instant().toString());
   journal.append(m); apply(m);
   sent++;
  }
  return sent;
 }

 private void apply(Map<String,Object> m) {
  switch(Json.string(m,"t")) {
   case "add" -> { var d=fromMap(Json.map(m.get("decision"))); pending.putIfAbsent(d.decisionId(),d); }
   case "sent" -> pending.remove(Json.string(m,"decisionId"));
   default -> throw new IllegalStateException("Registro desconhecido na fila de decisões.");
  }
 }

 static Map<String,Object> toMap(EdgeDecision d) {
  var m=new LinkedHashMap<String,Object>();
  m.put("decisionId",d.decisionId()); m.put("attemptId",d.attemptId()); m.put("sourceConnectorId",d.sourceConnectorId());
  m.put("deviceProvisioningId",d.deviceProvisioningId()); m.put("deviceChannelId",d.deviceChannelId()); m.put("pointId",d.pointId());
  m.put("bindingRevision",d.bindingRevision()); m.put("installationRevision",d.installationRevision());
  m.put("recognizedSubjectRef",d.recognizedSubjectRef()); m.put("result",d.result().name());
  m.put("reasonCodes",new ArrayList<Object>(d.reasonCodes())); m.put("evaluatedAt",d.evaluatedAt().toString());
  m.put("expiresAt",d.expiresAt().toString()); m.put("automaticActuationAllowed",d.automaticActuationAllowed());
  m.put("clockQuality",d.clockQuality()); m.put("evaluationDurationMs",d.evaluationDurationMs());
  return m;
 }
 static EdgeDecision fromMap(Map<String,Object> m) {
  var reasons=new ArrayList<String>();
  for(Object o:Json.list(m.get("reasonCodes"))) reasons.add(String.valueOf(o));
  return new EdgeDecision(Json.string(m,"decisionId"),Json.string(m,"attemptId"),Json.string(m,"sourceConnectorId"),
   Json.string(m,"deviceProvisioningId"),Json.string(m,"deviceChannelId"),Json.string(m,"pointId"),Json.longValue(m,"bindingRevision"),
   Json.longValue(m,"installationRevision"),Json.string(m,"recognizedSubjectRef"),DecisionResult.valueOf(Json.string(m,"result")),reasons,
   Instant.parse(Json.string(m,"evaluatedAt")),Instant.parse(Json.string(m,"expiresAt")),Boolean.TRUE.equals(Json.bool(m,"automaticActuationAllowed")),
   Json.string(m,"clockQuality"),Json.longValue(m,"evaluationDurationMs"));
 }

 @Override public void close() { journal.close(); }
}
