package br.com.condpulse.gateway;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.central.CentralApiClient.SyncResult;
import br.com.condpulse.gateway.central.EdgeSyncContract;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType;
import br.com.condpulse.gateway.spi.Spi.OperationState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contrato V048 deste lado: integridade do documento, tradução sem ampliar e relatório no formato da API. */
class EdgeSyncContractTest {
 private static final String ID="00000000-0000-4000-8000-0000000000a1";
 /** Texto no formato do jsonb do PostgreSQL (o hash é sobre este texto exato). */
 private static String apply(String entries) {
  return "{\"action\": \"APPLY_RIGHTS\", \"entries\": ["+entries+"], \"subjectRef\": \"cps_1\", \"schemaVersion\": \"1.0\", \"coveredMethods\": [], "
   +"\"subjectRestrictionEpoch\": 0}";
 }
 private static String entry(String channel,String from,String until) {
  return "{\"pointId\": \"p1\", \"grantId\": \"g1\", \"channelId\": \""+channel+"\", \"validFrom\": \""+from+"\", \"validUntil\": \""+until+"\"}";
 }
 private static Map<String,Object> raw(String type,String document,String hash) {
  var raw=new LinkedHashMap<String,Object>();
  raw.put("commandId",ID); raw.put("idempotencyKey",ID); raw.put("leaseId","00000000-0000-4000-8000-0000000000b1"); raw.put("attempt",1L);
  raw.put("deviceId","d1"); raw.put("subjectRef","cps_1"); raw.put("type",type); raw.put("desiredVersion",7L); raw.put("priority",1L);
  raw.put("documentHash",hash); raw.put("document",document); raw.put("issuedAt","2026-10-31T12:00:00Z");
  return raw;
 }
 /** Documento não traduzível: vira retirada bloqueante da mesma versão (prioridade 0), nunca descarte silencioso. */
 private static String refused(Map<String,Object> raw) {
  var c=EdgeSyncContract.toCommand(raw);
  assertTrue(c.type().revocation(),"documento não aplicável vira retirada");
  assertEquals(0L,c.payload().get("priority"));
  assertEquals(Json.longValue(raw,"desiredVersion").longValue(),c.desiredVersion(),"mesma versão do trabalho");
  return (String)c.payload().get(EdgeSyncContract.BLOCKED_REASON);
 }

 @Test void documentoViraComandoSoComHashConferidoESemAmpliar() throws Exception {
  String doc=apply(entry("c2","2026-10-31T10:00:00.000000Z","2026-11-30T10:00:00.000000Z")+", "
   +entry("c1","2026-11-01T10:00:00.000000Z","2026-11-20T10:00:00.000000Z")+", "+entry("c1","2026-10-31T10:00:00.000000Z","2026-11-25T10:00:00.000000Z"));
  String hash=EdgeSyncContract.sha256Hex(doc);
  var c=EdgeSyncContract.toCommand(raw("APPLY_RIGHTS",doc,hash));
  assertEquals(ID,c.commandId()); assertEquals(ID,c.idempotencyKey()); assertEquals(CommandType.APPLY_RIGHTS,c.type()); assertEquals(7,c.desiredVersion());
  assertEquals(List.of(Map.of("channelId","c1","scheduleRef","FULL_TIME"),Map.of("channelId","c2","scheduleRef","FULL_TIME")),c.payload().get("rights"));
  assertEquals("2026-11-01T10:00:00Z",c.payload().get("validFrom"),"interseção: o início mais tarde");
  assertEquals("2026-11-20T10:00:00Z",c.payload().get("validUntil"),"interseção: o fim mais cedo");
  assertEquals(hash,c.payload().get("documentHash"));
  assertEquals("DOCUMENT_INTEGRITY_FAILED",refused(raw("APPLY_RIGHTS",doc+" ",hash)),"texto alterado não confere com o hash");
  String other=apply(entry("c1","2026-11-25T10:00:00.000000Z","2026-11-30T10:00:00.000000Z")+", "+entry("c2","2026-10-31T10:00:00.000000Z","2026-11-20T10:00:00.000000Z"));
  assertEquals("DOCUMENT_VALIDITY_EMPTY",refused(raw("APPLY_RIGHTS",other,EdgeSyncContract.sha256Hex(other))));
  assertEquals("DOCUMENT_NOT_REPRESENTABLE",refused(raw("REVOKE_ALL",doc,hash)),"tipo diferente da ação do documento");
  String empty=apply("");
  assertEquals("DOCUMENT_NOT_REPRESENTABLE",refused(raw("APPLY_RIGHTS",empty,EdgeSyncContract.sha256Hex(empty))));
  assertThrows(IllegalArgumentException.class,()->EdgeSyncContract.toCommand(raw("APPLY_RIGHTS",doc,"curto")));
 }

 @Test void retiradaNaoTrazDireitos() throws Exception {
  String doc="{\"action\": \"REVOKE_ALL\", \"entries\": [], \"subjectRef\": \"cps_1\", \"schemaVersion\": \"1.0\"}";
  var c=EdgeSyncContract.toCommand(raw("REVOKE_ALL",doc,EdgeSyncContract.sha256Hex(doc)));
  assertEquals(CommandType.REVOKE_ALL,c.type()); assertEquals(java.util.Set.of("documentHash","priority"),c.payload().keySet()); assertTrue(c.type().revocation());
 }

 /**
  * Codex P1 (#153), probe reproduzido: concessão v1 pendente e retirada parcial v2 (APPLY_RIGHTS reduzido, prioridade 0)
  * pela conversão e pela fila reais. A retirada sai primeiro e a v1 é superada antes de qualquer execução física; v1
  * reenviada depois é recusada.
  */
 @Test void retiradaParcialComPrioridadeZeroVemAntesESuperaAConcessaoAntiga() throws Exception {
  String grant=apply(entry("c1","2026-10-31T10:00:00.000000Z","2026-11-30T10:00:00.000000Z")+", "+entry("c2","2026-10-31T10:00:00.000000Z","2026-11-30T10:00:00.000000Z"));
  String reduced=apply(entry("c1","2026-10-31T10:00:00.000000Z","2026-11-30T10:00:00.000000Z"));
  var v1raw=raw("APPLY_RIGHTS",grant,EdgeSyncContract.sha256Hex(grant));
  v1raw.put("commandId","00000000-0000-4000-8000-0000000000f1"); v1raw.put("idempotencyKey","00000000-0000-4000-8000-0000000000f1"); v1raw.put("desiredVersion",1L);
  var v2raw=raw("APPLY_RIGHTS",reduced,EdgeSyncContract.sha256Hex(reduced));
  v2raw.put("commandId","00000000-0000-4000-8000-0000000000f2"); v2raw.put("idempotencyKey","00000000-0000-4000-8000-0000000000f2");
  v2raw.put("desiredVersion",2L); v2raw.put("priority",0L);
  var v1=EdgeSyncContract.toCommand(v1raw); var v2=EdgeSyncContract.toCommand(v2raw);
  assertEquals(0L,v2.payload().get("priority")); assertEquals(2,v2.desiredVersion());
  assertTrue(br.com.condpulse.gateway.queue.LocalCommandQueue.restrictive(v2)); assertFalse(br.com.condpulse.gateway.queue.LocalCommandQueue.restrictive(v1));
  var dir=br.com.condpulse.gateway.support.TestSupport.tempDir();
  var clock=new br.com.condpulse.gateway.support.TestSupport.TestClock();
  try(var queue=new br.com.condpulse.gateway.queue.LocalCommandQueue(dir,clock,100,200)) {
   queue.enqueue(v1);
   var result=queue.enqueue(v2);
   assertEquals(List.of(v1.commandId()),result.superseded(),"a concessão antiga é superada antes de rodar");
   assertEquals(v2.commandId(),queue.next("d1").orElseThrow().command().commandId(),"a retirada parcial sai primeiro");
   assertEquals(br.com.condpulse.gateway.queue.LocalCommandQueue.Status.SUPERSEDED,queue.get(v1.commandId()).orElseThrow().status());
   var late=v1raw; late.put("commandId","00000000-0000-4000-8000-0000000000f3"); late.put("idempotencyKey","00000000-0000-4000-8000-0000000000f3");
   assertEquals(br.com.condpulse.gateway.queue.LocalCommandQueue.EnqueueOutcome.REJECTED_STALE,queue.enqueue(EdgeSyncContract.toCommand(late)).outcome(),
    "versão menor nunca roda depois de uma maior");
  }
  // Releitura do diário: mesma decisão (superada continua superada, a retirada continua primeiro).
  try(var reopened=new br.com.condpulse.gateway.queue.LocalCommandQueue(dir,clock,100,200)) {
   assertEquals(br.com.condpulse.gateway.queue.LocalCommandQueue.Status.SUPERSEDED,reopened.get(v1.commandId()).orElseThrow().status());
   assertEquals(v2.commandId(),reopened.next("d1").orElseThrow().command().commandId());
  }
  var bad=raw("APPLY_RIGHTS",grant,EdgeSyncContract.sha256Hex(grant)); bad.remove("priority");
  assertEquals("DOCUMENT_NOT_REPRESENTABLE",refused(bad),"sem prioridade da plataforma não entra");
 }

 /**
  * Codex P1 (#153): v1 pendente e v2 restritiva que não traduz (vigência vazia). v2 entra como retirada bloqueante da
  * mesma versão: v1 nunca roda, v2 é relatada FAILED com o motivo e a releitura do diário mantém o bloqueio.
  */
 @Test void documentoRestritivoNaoTraduzivelBloqueiaVersaoAntiga() throws Exception {
  String grant=apply(entry("c1","2026-10-31T10:00:00.000000Z","2026-11-30T10:00:00.000000Z"));
  String empty=apply(entry("c1","2026-11-25T10:00:00.000000Z","2026-11-30T10:00:00.000000Z")+", "+entry("c2","2026-10-31T10:00:00.000000Z","2026-11-20T10:00:00.000000Z"));
  var v1raw=raw("APPLY_RIGHTS",grant,EdgeSyncContract.sha256Hex(grant)); v1raw.put("desiredVersion",1L);
  v1raw.put("commandId","00000000-0000-4000-8000-0000000000e1"); v1raw.put("idempotencyKey","00000000-0000-4000-8000-0000000000e1");
  var v2raw=raw("APPLY_RIGHTS",empty,EdgeSyncContract.sha256Hex(empty)); v2raw.put("desiredVersion",2L); v2raw.put("priority",0L);
  v2raw.put("commandId","00000000-0000-4000-8000-0000000000e2"); v2raw.put("idempotencyKey","00000000-0000-4000-8000-0000000000e2");
  var v1=EdgeSyncContract.toCommand(v1raw); var v2=EdgeSyncContract.toCommand(v2raw);
  assertEquals(CommandType.REVOKE_RIGHTS,v2.type()); assertEquals("DOCUMENT_VALIDITY_EMPTY",v2.payload().get(EdgeSyncContract.BLOCKED_REASON));
  var dir=br.com.condpulse.gateway.support.TestSupport.tempDir();
  var clock=new br.com.condpulse.gateway.support.TestSupport.TestClock();
  try(var queue=new br.com.condpulse.gateway.queue.LocalCommandQueue(dir,clock,100,200)) {
   queue.enqueue(v1);
   assertEquals(List.of(v1.commandId()),queue.enqueue(v2).superseded(),"v1 superada antes de rodar");
   assertEquals(v2.commandId(),queue.next("d1").orElseThrow().command().commandId());
   queue.mark(v2.commandId(),br.com.condpulse.gateway.queue.LocalCommandQueue.Status.IN_FLIGHT,null,null);
   assertTrue(queue.next("d1").isEmpty(),"v1 nunca roda");
  }
  try(var reopened=new br.com.condpulse.gateway.queue.LocalCommandQueue(dir,clock,100,200)) {
   assertEquals(br.com.condpulse.gateway.queue.LocalCommandQueue.Status.SUPERSEDED,reopened.get(v1.commandId()).orElseThrow().status());
   var again=v1raw; again.put("commandId","00000000-0000-4000-8000-0000000000e3"); again.put("idempotencyKey","00000000-0000-4000-8000-0000000000e3");
   assertEquals(br.com.condpulse.gateway.queue.LocalCommandQueue.EnqueueOutcome.REJECTED_STALE,reopened.enqueue(EdgeSyncContract.toCommand(again)).outcome(),
    "bloqueio sobrevive à releitura: versão menor nunca roda depois");
  }
 }

 @Test void relatorioNoFormatoDaApi() {
  String hash="a".repeat(64);
  var confirmed=new SyncResult(ID,"d1",OperationState.CONFIRMED,"CONFIRMED_BY_READBACK",Instant.EPOCH,hash);
  var body=EdgeSyncContract.reportBody(confirmed);
  assertEquals("CONFIRMED",body.get("state")); assertEquals(hash,body.get("observedHash")); assertEquals(hash,body.get("documentHash"));
  var unsupported=EdgeSyncContract.reportBody(new SyncResult(ID,"d1",OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,"NAO_SUPORTADO_SEM_DOCUMENTACAO:APPLY_RIGHTS",Instant.EPOCH,hash));
  assertEquals("FAILED",unsupported.get("state")); assertEquals("NAO_SUPORTADO_SEM_DOCUMENTACAO_APPLY_RIGHTS",unsupported.get("resultCode"));
  assertNull(unsupported.get("observedHash"));
  var unverified=EdgeSyncContract.reportBody(new SyncResult(ID,"d1",OperationState.APPLIED_UNVERIFIED,null,Instant.EPOCH,hash));
  assertNull(unverified.get("observedHash"),"sem leitura de volta, nada de hash lido");
  assertTrue(((String)EdgeSyncContract.reportBody(new SyncResult(ID,"d1",OperationState.FAILED,"x".repeat(80),Instant.EPOCH,hash)).get("resultCode")).length()<=64);
  String key=EdgeSyncContract.idempotencyKey(confirmed);
  assertEquals(key,EdgeSyncContract.idempotencyKey(confirmed),"mesma chave em toda repetição");
  assertTrue(key.matches("[A-Za-z0-9_.:-]{8,128}"));
  assertNotEquals(key,EdgeSyncContract.idempotencyKey(new SyncResult(ID,"d1",OperationState.ACK,null,Instant.EPOCH,hash)));
  assertThrows(IllegalArgumentException.class,()->EdgeSyncContract.reportBody(new SyncResult(ID,"d1",OperationState.ACK,null,Instant.EPOCH,null)));
 }
}
