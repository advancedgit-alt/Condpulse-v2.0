package br.com.condpulse.accessedge.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import br.com.condpulse.accessedge.infrastructure.EdgeStore;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V048: credencial só como hash, relatório validado antes do banco e decisão pura igual à regra do banco. */
class EdgeRulesTest {
 private static final String DOC="a".repeat(64),OTHER="b".repeat(64);
 private static final String TOKEN="Q2lyY3VpdG8tc2ludGV0aWNvLWRlLXRlc3RlLTAxMjM0NTY3ODk";
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static EdgeReportRules.Report report(String state,String observed) {
  return EdgeReportRules.validate(new EdgeModels.ReportRequest(state,null,DOC,observed));
 }

 @Test void credentialIsOnlyEverAHash() {
  assertTrue(EdgeCredentials.hashOf(null).isEmpty());
  assertTrue(EdgeCredentials.hashOf("curto").isEmpty(),"Menos de 256 bits não é credencial");
  assertTrue(EdgeCredentials.hashOf(TOKEN+" ").isEmpty(),"Espaço ou caractere fora do base64url é recusado");
  assertTrue(EdgeCredentials.hashOf("x".repeat(129)).isEmpty());
  String hash=EdgeCredentials.hashOf(TOKEN).orElseThrow();
  assertEquals(EdgeCredentials.sha256Hex(TOKEN.getBytes(StandardCharsets.UTF_8)),hash);
  assertTrue(EdgeCredentials.validHash(hash)); assertFalse(EdgeCredentials.validHash(hash.toUpperCase()));
  assertEquals(12,EdgeCredentials.fingerprint(hash).length()); assertNull(EdgeCredentials.fingerprint(TOKEN));
  assertNotEquals(TOKEN,hash);
 }

 @Test void reportIsValidatedBeforeTheDatabase() {
  assertEquals("EDGE_REPORT_STATE_INVALID",code(()->EdgeReportRules.validate(new EdgeModels.ReportRequest("SUPERSEDED",null,DOC,null))),"Gateway nunca supera");
  assertEquals("EDGE_REPORT_STATE_INVALID",code(()->EdgeReportRules.validate(new EdgeModels.ReportRequest("PENDING",null,DOC,null))));
  assertEquals("EDGE_REPORT_DOCUMENT_HASH_REQUIRED",code(()->EdgeReportRules.validate(new EdgeModels.ReportRequest("ACK",null,null,null))));
  assertEquals("EDGE_REPORT_READBACK_REQUIRED",code(()->report("CONFIRMED",null)),"Confirmação sem leitura de volta");
  assertEquals("EDGE_REPORT_OBSERVED_HASH_INVALID",code(()->report("ACK",DOC)),"ACK não traz leitura");
  assertEquals("EDGE_REPORT_CODE_INVALID",code(()->EdgeReportRules.validate(new EdgeModels.ReportRequest("FAILED","NAO:SUPORTADO",DOC,null))));
  assertEquals("EDGE_IDEMPOTENCY_KEY_REQUIRED",code(()->EdgeReportRules.idempotencyKey("curta")));
  UUID job=UUID.randomUUID();
  assertEquals(EdgeReportRules.requestHash(job,report("ACK",null)),EdgeReportRules.requestHash(job,report("ACK",null)));
  assertNotEquals(EdgeReportRules.requestHash(job,report("ACK",null)),EdgeReportRules.requestHash(job,report("FAILED",null)));
  assertNotEquals(EdgeReportRules.requestHash(job,report("ACK",null)),EdgeReportRules.requestHash(UUID.randomUUID(),report("ACK",null)));
 }

 @Test void confirmationNeedsTheSameHashReadBack() {
  var pending=new EdgeReportRules.Job("PENDING",DOC,"NONE");
  var ok=EdgeReportRules.decide(report("CONFIRMED",DOC),pending);
  assertEquals("APPLIED",ok.outcome()); assertEquals("STATE_READ",ok.confirmationLevel()); assertEquals(DOC,ok.observedHash()); assertTrue(ok.finish());
  assertEquals("READBACK_HASH_MISMATCH",EdgeReportRules.decide(report("CONFIRMED",OTHER),pending).outcomeCode());
  var otherDoc=EdgeReportRules.validate(new EdgeModels.ReportRequest("CONFIRMED",null,OTHER,OTHER));
  assertEquals("DOCUMENT_HASH_MISMATCH",EdgeReportRules.decide(otherDoc,pending).outcomeCode(),"Leitura de outro documento não confirma este");
  var ack=EdgeReportRules.decide(report("ACK",null),pending);
  assertEquals("TRANSPORT_ACK",ack.confirmationLevel()); assertTrue(ack.acknowledge()); assertFalse(ack.finish());
  var unverified=EdgeReportRules.decide(report("APPLIED_UNVERIFIED",null),new EdgeReportRules.Job("ACK",DOC,"TRANSPORT_ACK"));
  assertEquals("TRANSPORT_ACK",unverified.confirmationLevel());
  assertEquals("NONE",EdgeReportRules.decide(report("FAILED",null),pending).confirmationLevel(),"Falha sem transporte não ganha nível");
  assertEquals("TRANSPORT_ACK",EdgeReportRules.decide(report("UNKNOWN",null),new EdgeReportRules.Job("ACK",DOC,"TRANSPORT_ACK")).confirmationLevel());
  assertEquals("ACK_AFTER_RESULT",EdgeReportRules.decide(report("ACK",null),new EdgeReportRules.Job("FAILED",DOC,"NONE")).outcomeCode());
  var closed=EdgeReportRules.decide(report("CONFIRMED",DOC),new EdgeReportRules.Job("SUPERSEDED",DOC,"NONE"));
  assertEquals("CLOSED",closed.outcome()); assertEquals("JOB_SUPERSEDED",closed.outcomeCode());
  assertEquals("JOB_ALREADY_CONFIRMED",EdgeReportRules.decide(report("FAILED",null),new EdgeReportRules.Job("CONFIRMED",DOC,"STATE_READ")).outcomeCode());
 }

 @Test void servicesRefuseBadInputWithoutTouchingTheDatabase() {
  var edge=mock(EdgeTransactionExecutor.class); var store=mock(EdgeStore.class); var technical=mock(TechnicalTransactionExecutor.class);
  var gateway=new EdgeGatewayService(edge,store);
  var agents=new EdgeAgentService(technical,store,mock(AuditRecorder.class));
  UUID t=UUID.randomUUID(),a=UUID.randomUUID(),k=UUID.randomUUID();
  assertEquals("EDGE_UNAUTHENTICATED",code(()->gateway.lease(null,null)),"Sem credencial: 401 antes de qualquer validação");
  assertEquals("EDGE_UNAUTHENTICATED",code(()->gateway.report("curto",UUID.randomUUID(),"chave-12345",new EdgeModels.ReportRequest("ACK",null,DOC,null))));
  assertEquals("EDGE_LEASE_MAX_INVALID",code(()->gateway.lease(TOKEN,new EdgeModels.LeaseRequest(51,null))));
  assertEquals("EDGE_LEASE_SECONDS_INVALID",code(()->gateway.lease(TOKEN,new EdgeModels.LeaseRequest(1,601))));
  assertEquals("EDGE_IDEMPOTENCY_KEY_REQUIRED",code(()->gateway.report(TOKEN,UUID.randomUUID(),null,new EdgeModels.ReportRequest("ACK",null,DOC,null))));
  assertEquals("EDGE_CREDENTIAL_HASH_INVALID",code(()->agents.register(a,t,AssuranceLevel.AAL2,new EdgeModels.RegisterAgent(a,k,"Gateway",TOKEN,null))),
   "Credencial em claro nunca é aceita no cadastro");
  assertEquals("EDGE_CREDENTIAL_VALIDITY_INVALID",code(()->agents.register(a,t,AssuranceLevel.AAL2,new EdgeModels.RegisterAgent(a,k,"Gateway",DOC,181))));
  assertEquals("EDGE_AGENT_LABEL_INVALID",code(()->agents.register(a,t,AssuranceLevel.AAL2,new EdgeModels.RegisterAgent(a,k," ",DOC,null))));
  assertEquals("EDGE_VERSION_REQUIRED",code(()->agents.rotate(a,t,AssuranceLevel.AAL2,a,new EdgeModels.RotateAgent(DOC,30,null))));
  assertEquals("EDGE_AGENT_REASON_REQUIRED",code(()->agents.revoke(a,t,AssuranceLevel.AAL2,a,new EdgeModels.RevokeAgent("x",0L))));
  verifyNoInteractions(edge,store,technical);
 }

 @Test void responsesNeverCarryTheCredential() {
  for(var type:new Class<?>[]{EdgeModels.Agent.class,EdgeModels.Identity.class,EdgeModels.SyncCommand.class,EdgeModels.ReportResult.class})
   for(var component:type.getRecordComponents())
    assertFalse(component.getName().matches("(?i).*(token|secret|password|credentialHash).*"),type.getSimpleName()+"."+component.getName());
 }
}
