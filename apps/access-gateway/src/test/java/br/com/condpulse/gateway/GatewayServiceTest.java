package br.com.condpulse.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import br.com.condpulse.gateway.capability.CapabilityRegistry;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.CondoOpenPolicy;
import br.com.condpulse.gateway.decision.LocalDecisionEngine;
import br.com.condpulse.gateway.events.EventOutbox;
import br.com.condpulse.gateway.open.OpenDispatcher;
import br.com.condpulse.gateway.queue.LocalCommandQueue;
import br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType;
import br.com.condpulse.gateway.queue.LocalCommandQueue.Status;
import br.com.condpulse.gateway.runtime.GatewayService;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.ScriptedAdapter;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static br.com.condpulse.gateway.support.TestSupport.command;
import static org.junit.jupiter.api.Assertions.*;

/** Núcleo: verificação por leitura, equipamento fora do ar, revogação sem documentação, quarentena e decisão local. */
class GatewayServiceTest {
 private static final Map<String,Object> RIGHTS=Map.of("rights",List.of(Map.of("channelId","c1","scheduleRef","sempre")));
 private TestSupport.TestClock clock;
 private java.nio.file.Path dir;
 private GatewayConfig config;
 private ScriptedAdapter adapter;
 private TestSupport.StubCentral central;
 private LocalCommandQueue queue;
 private EventOutbox outbox;
 private LocalDecisionEngine decisions;
 private GatewayService service;

 @BeforeEach void setup() { build(ScriptedAdapter.all(),true,DecisionMode.DEVICE_LOCAL_ACL); }

 private void build(java.util.Set<Operation> ops,boolean profiles,DecisionMode mode) {
  clock=new TestSupport.TestClock();
  dir=TestSupport.tempDir();
  var device=TestSupport.device("d1",Brand.CONTROL_ID,ConnectionMode.LOCAL,mode,URI.create("http://192.0.2.10"),
   List.of(TestSupport.channel("c1","1","door:door=1")),Map.of("sempre","1","comercial","2"),Map.of(),false);
  config=TestSupport.config(List.of(device),profiles,CondoOpenPolicy.closed());
  adapter=new ScriptedAdapter(TestSupport.context(device,clock),ops);
  central=new TestSupport.StubCentral();
  queue=new LocalCommandQueue(dir,clock,100,200);
  outbox=new EventOutbox(dir,clock,1000);
  decisions=new LocalDecisionEngine(dir,clock,config);
  var registry=new CapabilityRegistry(config.profiles());
  var opens=new OpenDispatcher(dir,clock,config,registry,id->Optional.of(adapter));
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,opens,outbox,decisions,central,clock);
 }

 @Test void aplicacaoAceitaEConfirmadaPelaLeituraDeVolta() {
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador 101")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.processQueues(10);
  assertEquals(OperationState.CONFIRMED,queue.get("p").orElseThrow().lastState());
  assertEquals(OperationState.CONFIRMED,queue.get("r").orElseThrow().lastState());
  assertEquals(2,central.results.size());
 }

 @Test void equipamentoForaDoArMantemPendenciaVisivel() {
  adapter.script(Operation.REVOKE_RIGHTS,OperationResult.of(OperationState.FAILED,"DEVICE_UNREACHABLE"));
  service.submit(command("rv","d1","s1",CommandType.REVOKE_RIGHTS,1,Map.of()));
  service.processQueues(10);
  var e=queue.get("rv").orElseThrow();
  assertEquals(Status.PENDING,e.status());
  assertEquals("DEVICE_UNREACHABLE",e.lastReason());
  var summary=service.healthSummary();
  @SuppressWarnings("unchecked") var device=(Map<String,Object>)((List<Object>)summary.get("devices")).getFirst();
  assertEquals(1L,device.get("pendingRevocations"));
  assertNotNull(device.get("oldestPendingRevocation"));
  assertEquals(Boolean.FALSE,device.get("reachable"));
 }

 @Test void timeoutAmbiguoLeAntesDeReenviarENaoRepeteSeJaAplicou() {
  adapter.script(Operation.APPLY_RIGHTS,OperationResult.of(OperationState.UNKNOWN,"RESPONSE_NOT_RECEIVED"));
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.processQueues(10);
  var e=queue.get("r").orElseThrow();
  assertEquals(OperationState.CONFIRMED,e.lastState(),"a leitura mostrou que chegou");
  assertEquals("CONFIRMED_BY_READBACK",e.lastReason());
  assertEquals(1,adapter.calls.stream().filter("APPLY_RIGHTS"::equals).count(),"nenhum reenvio às cegas");
 }

 @Test void semLeituraDeVoltaAplicacaoAmbiguaFicaParaDecisaoHumana() {
  build(EnumSet.complementOf(EnumSet.of(Operation.READ_STATE)),true,DecisionMode.DEVICE_LOCAL_ACL);
  adapter.script(Operation.APPLY_RIGHTS,OperationResult.of(OperationState.UNKNOWN,"RESPONSE_NOT_RECEIVED"));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.processQueues(10);
  service.processQueues(10);
  var e=queue.get("r").orElseThrow();
  assertEquals(Status.NEEDS_READBACK,e.status());
  assertEquals("READBACK_NOT_AVAILABLE",e.lastReason());
  assertEquals(1,adapter.calls.stream().filter("APPLY_RIGHTS"::equals).count());
 }

 @Test void gravacaoSemRetiradaDocumentadaERecusada() {
  build(EnumSet.of(Operation.SET_CARD,Operation.ENROLL_FACE,Operation.APPLY_RIGHTS,Operation.HEALTH),true,DecisionMode.DEVICE_LOCAL_ACL);
  central.materials.put("m1","123456".getBytes());
  service.submit(command("c","d1","s1",CommandType.SET_CREDENTIAL,1,Map.of("kind","CARD","materialRef","m1")));
  service.submit(command("f","d1","s1",CommandType.ENROLL_FACE,1,Map.of("materialRef","m1")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,1,RIGHTS));
  service.processQueues(10);
  assertEquals("REVOGACAO_SEM_DOCUMENTACAO:REMOVE_CARD",queue.get("c").orElseThrow().lastReason());
  assertEquals("REVOGACAO_SEM_DOCUMENTACAO:REMOVE_FACE",queue.get("f").orElseThrow().lastReason());
  assertEquals("REVOGACAO_SEM_DOCUMENTACAO:REVOKE_RIGHTS",queue.get("r").orElseThrow().lastReason());
  assertTrue(adapter.calls.stream().noneMatch(c->c.equals("SET_CARD") || c.equals("ENROLL_FACE") || c.equals("APPLY_RIGHTS")));
 }

 @Test void perfilNaoRegistradoBloqueiaAplicacaoMasNaoRevogacao() {
  build(ScriptedAdapter.all(),false,DecisionMode.DEVICE_LOCAL_ACL);
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,1,RIGHTS));
  service.submit(command("rv","d1","s2",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(10);
  assertEquals("PROFILE_NOT_REGISTERED",queue.get("r").orElseThrow().lastReason());
  assertEquals(Status.DONE,queue.get("rv").orElseThrow().status());
 }

 @Test void revogacaoCompletaAlcancaTodosOsMetodos() {
  service.submit(command("rv","d1","s1",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(10);
  assertTrue(adapter.calls.containsAll(List.of("REVOKE_RIGHTS","REMOVE_CARD","REMOVE_PIN","REMOVE_QR","REMOVE_FACE","REMOVE_PERSON")));
  assertEquals(OperationState.CONFIRMED,queue.get("rv").orElseThrow().lastState());
 }

 @Test void revogacaoComEtapaSemDocumentacaoFicaParcial() {
  build(EnumSet.of(Operation.APPLY_RIGHTS,Operation.REVOKE_RIGHTS,Operation.ENROLL_FACE,Operation.READ_STATE),true,DecisionMode.DEVICE_LOCAL_ACL);
  service.submit(command("rv","d1","s1",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(10);
  var e=queue.get("rv").orElseThrow();
  assertEquals(OperationState.PARTIAL,e.lastState());
  assertEquals("REVOKE_INCOMPLETE",e.lastReason());
 }

 @Test void identidadeDivergenteNaReconexaoColocaEmQuarentena() {
  var mismatched=new ScriptedAdapter(TestSupport.context(config.devices().getFirst(),clock),ScriptedAdapter.all()) {
   @Override public DeviceIdentity identify() { return new DeviceIdentity(OperationState.CONFIRMED,null,"SKU-TESTE","FW-OUTRO","serie"); }
  };
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",mismatched),queue,new OpenDispatcher(dir,clock,config,registry,id->Optional.of(mismatched)),
   outbox,decisions,central,clock);
  service.checkHealth();
  assertTrue(service.status("d1").orElseThrow().quarantined());
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,1,RIGHTS));
  service.processQueues(10);
  assertEquals("DEVICE_IDENTITY_MISMATCH",queue.get("r").orElseThrow().lastReason());
 }

 @Test void eventosSoSaemDaFilaComAceiteDaPlataforma() {
  var e=new EdgeEvent(null,"d1","prov-d1","epoca-1","42","conector-teste",null,EventKind.ACCESS_ALLOWED,null,Direction.UNKNOWN,
   TimeQuality.UNKNOWN,new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null),null,"1");
  var rejected=new EdgeEvent(null,"d1","prov-d1","epoca-1","43","conector-teste",null,EventKind.ACCESS_DENIED,null,Direction.UNKNOWN,
   TimeQuality.UNKNOWN,new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null),null,"1");
  outbox.add("d1",List.of(e,rejected,e),"cursor-1");
  central.available=false;
  assertEquals(0,service.forwardEvents());
  assertEquals(2,outbox.pendingCount());
  central.available=true;
  central.rejectEvents=java.util.Set.of(rejected.eventId());
  assertEquals(1,service.forwardEvents());
  assertEquals(0,outbox.pendingCount());
  assertEquals(1,outbox.quarantinedCount());
  assertEquals("cursor-1",outbox.cursor("d1").orElseThrow());
 }

 @Test void decisaoLocalRespeitaCapacidadeRevogacaoEPoliticaVelha() {
  build(ScriptedAdapter.all(),true,DecisionMode.EDGE_ONLINE);
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  decisions.markSynced(clock.instant());
  decisions.observeClockSkew(Duration.ofMillis(200));
  var ctx=context();
  assertTrue(ctx.decide("77","1").allow());
  assertFalse(ctx.decide("78","1").allow(),"pessoa desconhecida");
  assertEquals(List.of("CONTEXT_AMBIGUOUS"),ctx.decide("77","9").reasonCodes());
  service.submit(command("rv","d1","s1",CommandType.REVOKE_ALL,3,Map.of()));
  assertEquals(List.of("REVOKED"),ctx.decide("77","1").reasonCodes(),"revogação vale antes da confirmação do equipamento");
  service.submit(command("r2","d1","s1",CommandType.APPLY_RIGHTS,4,RIGHTS));
  assertTrue(ctx.decide("77","1").allow());
  clock.advance(Duration.ofSeconds(config.limits().maxPolicyAgeSeconds()+1));
  assertEquals(List.of("STALE_POLICY"),ctx.decide("77","1").reasonCodes());
  assertFalse(central.decisions.isEmpty());
  assertTrue(central.decisions.stream().filter(d->d.result()!=br.com.condpulse.gateway.central.CentralApiClient.DecisionResult.ALLOW)
   .noneMatch(br.com.condpulse.gateway.central.CentralApiClient.EdgeDecision::automaticActuationAllowed));
 }

 @Test void decisaoLocalNegaForaDoHorarioESemRelogioConfiavel() {
  build(ScriptedAdapter.all(),true,DecisionMode.EDGE_ONLINE);
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,Map.of("rights",List.of(Map.of("channelId","c1","scheduleRef","comercial")))));
  decisions.markSynced(clock.instant());
  var ctx=context();
  assertEquals(List.of("CLOCK_UNTRUSTED"),ctx.decide("77","1").reasonCodes());
  decisions.observeClockSkew(Duration.ZERO);
  assertTrue(ctx.decide("77","1").allow());
  clock.advance(Duration.ofHours(8)); // 20h em São Paulo
  decisions.markSynced(clock.instant());
  assertEquals(List.of("OUTSIDE_SCHEDULE"),ctx.decide("77","1").reasonCodes());
 }

 private InboundEndpoint.Context context() {
  var captured=new InboundEndpoint.Context[1];
  adapter.inboundCapture=ctx->captured[0]=ctx;
  service.handleInbound("d1",new InboundEndpoint.Request("GET","/x",Map.of(),null,new byte[0],"127.0.0.1"));
  return captured[0];
 }
}
