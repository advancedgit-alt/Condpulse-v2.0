package br.com.condpulse.gateway;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.central.CentralApiClient.SyncResult;
import br.com.condpulse.gateway.central.EdgeSyncContract;
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
 private static String refused(Map<String,Object> raw) {
  return assertThrows(EdgeSyncContract.Refused.class,()->EdgeSyncContract.toCommand(raw)).reasonCode();
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
  assertEquals(CommandType.REVOKE_ALL,c.type()); assertEquals(1,c.payload().size()); assertTrue(c.type().revocation());
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
