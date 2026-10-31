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
 private br.com.condpulse.gateway.central.DecisionOutbox decisionOutbox;
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
  decisionOutbox=new br.com.condpulse.gateway.central.DecisionOutbox(dir,clock,100);
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,opens,outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth(); // sonda de identidade antes de gravar, como no ciclo real
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
  assertEquals(Status.HELD,e.status(),"fora da seleção, aguardando decisão humana");
  assertEquals("READBACK_CANNOT_PROVE",e.lastReason());
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
  var opens=new OpenDispatcher(dir.resolve("q"),clock,config,registry,id->Optional.of(mismatched));
  service=new GatewayService(config,registry,Map.of("d1",mismatched),queue,opens,
   outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth();
  assertTrue(service.status("d1").orElseThrow().quarantined());
  var now=clock.instant();
  var open=opens.dispatch(new OpenDispatcher.OpenCommand("o1","n1","d1","c1","ponto-c1",1,1,now,now.plusSeconds(5),
   br.com.condpulse.gateway.config.GatewayConfig.OpenOrigin.PORTARIA_LOCAL,"a",null));
  assertEquals("DEVICE_IDENTITY_MISMATCH",open.reasonCode(),"quarentena bloqueia abertura antes do compromisso");
  assertTrue(mismatched.calls.stream().noneMatch("OPEN"::equals));
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
  assertTrue(central.decisions.isEmpty(),"telemetria fora do caminho síncrono da decisão");
  assertTrue(service.forwardDecisions()>0);
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

 // ----- achados de revisão (#129) -----

 @Test void leituraIndisponivelEmNeedsReadbackMantemPendenciaSemReenviar() {
  adapter.script(Operation.APPLY_RIGHTS,OperationResult.of(OperationState.UNKNOWN,"RESPONSE_NOT_RECEIVED"));
  adapter.readStateThrows=true;
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.processQueues(10);
  service.processQueues(10);
  var e=queue.get("r").orElseThrow();
  assertEquals(Status.NEEDS_READBACK,e.status());
  assertEquals("READBACK_UNAVAILABLE",e.lastReason());
  assertEquals(1,adapter.calls.stream().filter("APPLY_RIGHTS"::equals).count(),"leitura que falhou não autoriza reenvio");
  adapter.readStateThrows=false;
  adapter.rights.clear(); // leitura bem-sucedida mostra ausência: aí sim reenvia
  service.processQueues(10);
  assertEquals(2,adapter.calls.stream().filter("APPLY_RIGHTS"::equals).count());
  assertEquals(OperationState.CONFIRMED,queue.get("r").orElseThrow().lastState());
 }

 @Test void credencialSoConfirmaComValorAplicadoNuncaPorContagem() {
  central.materials.put("m1","123456".getBytes());
  service.submit(command("c1","d1","s1",CommandType.SET_CREDENTIAL,1,Map.of("kind","PIN","materialRef","m1")));
  service.processQueues(10);
  assertEquals(OperationState.APPLIED_UNVERIFIED,queue.get("c1").orElseThrow().lastState(),"adaptador que só conta não confirma");
  adapter.credentialMatch=Optional.of(true);
  service.submit(command("c2","d1","s1",CommandType.SET_CREDENTIAL,2,Map.of("kind","PIN","materialRef","m1")));
  service.processQueues(10);
  assertEquals(OperationState.CONFIRMED,queue.get("c2").orElseThrow().lastState());
 }

 @Test void facePresenteNaoConfirmaSubstituicao() {
  central.materials.put("f1",TestSupport.syntheticJpeg());
  adapter.readStates.add(new AppliedState(OperationState.CONFIRMED,null,true,java.util.Set.of(),null,true));
  service.submit(command("f","d1","s1",CommandType.ENROLL_FACE,1,Map.of("materialRef","f1")));
  service.processQueues(10);
  assertEquals(OperationState.APPLIED_UNVERIFIED,queue.get("f").orElseThrow().lastState());
 }

 @Test void resultadoTerminalNaoPublicadoFicaPendenteESoORelatorioEReenviado() {
  central.available=false;
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador")));
  service.processQueues(10);
  assertEquals(Status.DONE,queue.get("p").orElseThrow().status());
  assertEquals(1,queue.unreported().size());
  queue.close();
  queue=new LocalCommandQueue(dir,clock,100,200);
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,new OpenDispatcher(dir,clock,config,registry,id->Optional.of(adapter)),
   outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth();
  assertEquals(1,queue.unreported().size(),"pendência de publicação sobrevive a reinício");
  long calls=adapter.calls.size();
  central.available=true;
  assertEquals(1,service.publishPendingResults());
  assertEquals(calls,adapter.calls.size(),"nenhuma operação física repetida");
  assertEquals(List.of("p"),central.results.stream().map(r->r.commandId()).toList());
  assertTrue(queue.unreported().isEmpty());
 }

 @Test void telemetriaDeDecisaoSobreviveAReinicioAteOAceite() {
  build(ScriptedAdapter.all(),true,DecisionMode.EDGE_ONLINE);
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  decisions.markSynced(clock.instant());
  decisions.observeClockSkew(Duration.ZERO);
  context().decide("77","1");
  central.available=false;
  assertEquals(0,service.forwardDecisions());
  decisionOutbox.close();
  decisionOutbox=new br.com.condpulse.gateway.central.DecisionOutbox(dir,clock,100);
  assertEquals(1,decisionOutbox.pendingCount());
  central.available=true;
  assertEquals(1,decisionOutbox.flush(central));
  assertEquals(0,decisionOutbox.pendingCount());
 }

 @Test void diarioTruncaCaudaIncompletaAntesDeAcrescentar() throws Exception {
  var file=dir.resolve("teste.jsonl");
  java.nio.file.Files.writeString(file,"{\"a\":1}\n{\"b\":");
  try(var j=new br.com.condpulse.gateway.journal.Journal(file)) { j.append(Map.of("c",3L)); }
  var seen=new java.util.ArrayList<Map<String,Object>>();
  try(var j=new br.com.condpulse.gateway.journal.Journal(file)) { j.replay(seen::add); }
  assertEquals(List.of(Map.of("a",1L),Map.of("c",3L)),seen);
  java.nio.file.Files.writeString(file,"{\"a\":1}\n{\"b\":2\n",java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
  try(var j=new br.com.condpulse.gateway.journal.Journal(file)) { j.append(Map.of("d",4L)); }
  seen.clear();
  try(var j=new br.com.condpulse.gateway.journal.Journal(file)) { j.replay(seen::add); }
  assertEquals(List.of(Map.of("a",1L),Map.of("d",4L)),seen,"última linha inválida também é cortada");
 }

 @Test void coletaComFilaCheiaNaoImpedeDrenarOGravado() {
  outbox.close();
  outbox=new EventOutbox(dir.resolve("cheia"),clock,1);
  var registry=new CapabilityRegistry(config.profiles());
  var fetching=new ScriptedAdapter(TestSupport.context(config.devices().getFirst(),clock),ScriptedAdapter.all()) {
   @Override public EventPage fetchEvents(String cursor,int limit) {
    var ev=new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null);
    return new EventPage(OperationState.CONFIRMED,null,List.of(
     new EdgeEvent(null,"d1","prov-d1","epoca-1","1","conector-teste",null,EventKind.ACCESS_ALLOWED,null,Direction.UNKNOWN,TimeQuality.UNKNOWN,ev,null,"1"),
     new EdgeEvent(null,"d1","prov-d1","epoca-1","2","conector-teste",null,EventKind.ACCESS_ALLOWED,null,Direction.UNKNOWN,TimeQuality.UNKNOWN,ev,null,"1")),"c2",0);
   }
  };
  outbox.add("d1",List.of(new EdgeEvent(null,"d1","prov-d1","epoca-1","0","conector-teste",null,EventKind.ACCESS_ALLOWED,null,Direction.UNKNOWN,
   TimeQuality.UNKNOWN,new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null),null,"1")),"c1");
  service=new GatewayService(config,registry,Map.of("d1",fetching),queue,new OpenDispatcher(dir.resolve("o2"),clock,config,registry,id->Optional.of(fetching)),
   outbox,decisions,decisionOutbox,central,clock);
  service.pollEvents(); // fila cheia: não lança e não avança o cursor
  assertEquals("c1",outbox.cursor("d1").orElseThrow());
  assertEquals(1,service.forwardEvents());
 }

 // ----- quarta rodada (#129) -----

 @Test void revogacaoRecusadaPorFilaCheiaNegaLocalmenteENaoMarcaPoliticaAtual() {
  build(ScriptedAdapter.all(),true,DecisionMode.EDGE_ONLINE);
  queue.close();
  queue=new LocalCommandQueue(dir.resolve("pequena"),clock,2,3);
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,new OpenDispatcher(dir.resolve("o3"),clock,config,registry,id->Optional.of(adapter)),
   outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth();
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.submit(command("x","d1","s2",CommandType.REVOKE_ALL,1,Map.of()));
  central.nextSync.add(command("rv","d1","s1",CommandType.REVOKE_ALL,3,Map.of()));
  service.pullFromCentral();
  decisions.observeClockSkew(Duration.ZERO);
  var ctx=context();
  assertEquals(List.of("STALE_POLICY"),ctx.decide("77","1").reasonCodes(),"lote com recusa não marca a política como atual");
  decisions.markSynced(clock.instant());
  assertEquals(List.of("REVOKED"),ctx.decide("77","1").reasonCodes(),"revogação recusada pela fila vale na decisão local");
  assertEquals(1L,service.healthSummary().get("revocationsRejectedQueueFull"));
 }

 @Test void batimentoNaoDispensaConferenciaDeIdentidadeAntesDeAbrir() {
  var registry=new CapabilityRegistry(config.profiles());
  var opens=new OpenDispatcher(dir.resolve("o4"),clock,config,registry,id->Optional.of(adapter));
  var dev=config.devices().getFirst();
  var openConfig=TestSupport.config(List.of(new GatewayConfig.Device(dev.deviceId(),dev.deviceProvisioningId(),dev.label(),dev.brand(),dev.sku(),dev.firmware(),
   dev.apiVersion(),dev.connectionMode(),dev.decisionMode(),dev.baseUrl(),dev.credentialRef(),dev.inboundTokenRef(),dev.nativeDeviceId(),dev.eventEpoch(),true,
   dev.phase(),true,java.util.EnumSet.of(GatewayConfig.OpenOrigin.PORTARIA_LOCAL),1,dev.channels(),dev.nativeSchedules(),dev.params())),true,
   new CondoOpenPolicy(java.util.EnumSet.of(GatewayConfig.OpenOrigin.PORTARIA_LOCAL),false));
  opens.close();
  opens=new OpenDispatcher(dir.resolve("o5"),clock,openConfig,registry,id->Optional.of(adapter));
  service=new GatewayService(openConfig,registry,Map.of("d1",adapter),queue,opens,outbox,decisions,decisionOutbox,central,clock);
  context().heartbeat();
  var now=clock.instant();
  var first=opens.dispatch(new OpenDispatcher.OpenCommand("o1","n1","d1","c1","ponto-c1",1,1,now,now.plusSeconds(5),GatewayConfig.OpenOrigin.PORTARIA_LOCAL,"a",null));
  assertEquals("DEVICE_IDENTITY_NOT_VERIFIED",first.reasonCode());
  assertFalse(service.status("d1").orElseThrow().reachable(),"batimento não torna alcançável");
  service.checkHealth();
  var second=opens.dispatch(new OpenDispatcher.OpenCommand("o2","n2","d1","c1","ponto-c1",1,1,now,now.plusSeconds(5),GatewayConfig.OpenOrigin.PORTARIA_LOCAL,"a",null));
  assertEquals(OpenDispatcher.Status.SENT,second.status(),"após a sonda conferir a identidade (BANCADA)");
 }

 @Test void entradaRetidaParaDecisaoHumanaNaoTravaAFila() {
  build(EnumSet.complementOf(EnumSet.of(Operation.READ_STATE)),true,DecisionMode.DEVICE_LOCAL_ACL);
  adapter.script(Operation.APPLY_RIGHTS,OperationResult.of(OperationState.UNKNOWN,"RESPONSE_NOT_RECEIVED"));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.submit(command("c","d1","s2",CommandType.APPLY_RIGHTS,1,RIGHTS));
  service.processQueues(10);
  assertEquals(Status.HELD,queue.get("r").orElseThrow().status());
  assertEquals(Status.DONE,queue.get("c").orElseThrow().status(),"o comando seguinte foi processado");
  assertEquals(1,queue.pending("d1").size(),"a retida continua visível");
  assertEquals(OperationState.UNKNOWN,central.results.stream().filter(x->x.commandId().equals("r")).findFirst().orElseThrow().state());
 }

 // ----- quinta rodada (#129) -----

 @Test void gravacaoEsperaAConferenciaDeIdentidadeRevogacaoNao() {
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,new OpenDispatcher(dir.resolve("o6"),clock,config,registry,id->Optional.of(adapter)),
   outbox,decisions,decisionOutbox,central,clock);
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  service.submit(command("rv","d1","s2",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(10);
  assertEquals("DEVICE_IDENTITY_NOT_VERIFIED",queue.get("r").orElseThrow().lastReason());
  assertTrue(adapter.calls.stream().noneMatch("APPLY_RIGHTS"::equals));
  assertEquals(Status.DONE,queue.get("rv").orElseThrow().status(),"revogação não espera a identidade");
  service.checkHealth();
  service.processQueues(10);
  assertEquals(Status.DONE,queue.get("r").orElseThrow().status());
 }

 @Test void qualquerLimiteTemporalExigeValidadeLocalNoModoAcl() {
  adapter.documented.put(CapabilityName.localValidity,null);
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,Map.of("rights",List.of(Map.of("channelId","c1","scheduleRef","sempre")),
   "validFrom","2030-01-01T00:00:00Z")));
  service.submit(command("p","d1","s2",CommandType.UPSERT_PERSON,1,Map.of("displayName","Visitante","validUntil","2031-01-01T00:00:00Z")));
  service.processQueues(10);
  assertEquals("LOCAL_VALIDITY_NOT_SUPPORTED",queue.get("r").orElseThrow().lastReason(),"só início também é limite");
  assertEquals("LOCAL_VALIDITY_NOT_SUPPORTED",queue.get("p").orElseThrow().lastReason());
 }

 @Test void eventoRecebidoSemGravacaoDuravelFazOAdaptadorNaoConfirmar() {
  outbox.close();
  outbox=new EventOutbox(dir.resolve("cheia2"),clock,0);
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,new OpenDispatcher(dir.resolve("o7"),clock,config,registry,id->Optional.of(adapter)),
   outbox,decisions,decisionOutbox,central,clock);
  var ev=new EdgeEvent(null,"d1","prov-d1","epoca-1","in-1","conector-teste",null,EventKind.ACCESS_ALLOWED,null,Direction.UNKNOWN,TimeQuality.UNKNOWN,
   new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null),null,"1");
  context().events(List.of(ev)); // cabe na reserva própria de eventos recebidos: gravado de forma durável
  assertEquals(1,outbox.pendingCount());
  var many=new java.util.ArrayList<EdgeEvent>();
  for(int i=0;i<EventOutbox.INBOUND_RESERVE;i++) many.add(new EdgeEvent(null,"d1","prov-d1","epoca-1","x"+i,"conector-teste",null,EventKind.ACCESS_ALLOWED,
   null,Direction.UNKNOWN,TimeQuality.UNKNOWN,new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null),null,"1"));
  var ctx=context();
  assertThrows(IllegalStateException.class,()->ctx.events(many),"sem espaço durável a falha chega ao adaptador");
 }

 // ----- sexta rodada (#129) -----

 @Test void pessoaAusenteSemCascataNaoProvaRevogacaoCompleta() {
  adapter.readStates.add(new AppliedState(OperationState.CONFIRMED,null,false,java.util.Set.of(),
   Map.of(CredentialKind.CARD,1,CredentialKind.PIN,0,CredentialKind.QR,0),false));
  service.submit(command("rv","d1","s1",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(1);
  assertNotEquals(OperationState.CONFIRMED,queue.get("rv").orElseThrow().lastState(),"cartão restante: revogação não confirmada");
  adapter.coversCredentials=true;
  adapter.coversRights=true;
  adapter.readStates.add(new AppliedState(OperationState.CONFIRMED,null,false,null,null,null));
  service.submit(command("rv2","d1","s2",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(1);
  assertEquals(OperationState.CONFIRMED,queue.get("rv2").orElseThrow().lastState(),"com cascata declarada, a ausência prova");
 }

 @Test void prazoNosDireitosSemProvaDePrazoAplicadoFicaNaoVerificado() {
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,Map.of("rights",List.of(Map.of("channelId","c1","scheduleRef","sempre")),
   "validFrom","2030-01-01T00:00:00Z","validUntil","2031-01-01T00:00:00Z")));
  service.processQueues(10);
  assertEquals(OperationState.APPLIED_UNVERIFIED,queue.get("r").orElseThrow().lastState(),"canal/horário iguais não provam o prazo");
 }

 @Test void lacunaDeEventosApareceNaSaude() {
  var registry=new CapabilityRegistry(config.profiles());
  var gappy=new ScriptedAdapter(TestSupport.context(config.devices().getFirst(),clock),ScriptedAdapter.all()) {
   @Override public EventPage fetchEvents(String cursor,int limit) { return new EventPage(OperationState.CONFIRMED,"BUFFER_OVERFLOW:3",List.of(),cursor,0); }
  };
  service=new GatewayService(config,registry,Map.of("d1",gappy),queue,new OpenDispatcher(dir.resolve("o8"),clock,config,registry,id->Optional.of(gappy)),
   outbox,decisions,decisionOutbox,central,clock);
  service.pollEvents();
  @SuppressWarnings("unchecked") var device=(Map<String,Object>)((List<Object>)service.healthSummary().get("devices")).getFirst();
  assertTrue(String.valueOf(device.get("lastEventGap")).startsWith("BUFFER_OVERFLOW:3@"));
 }

 // ----- sétima rodada (#129) -----

 @Test void adiarPorIdentidadePreservaANecessidadeDeLeitura() {
  queue.enqueue(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador")));
  queue.mark("p",Status.IN_FLIGHT,null,null);
  queue.close();
  queue=new LocalCommandQueue(dir,clock,100,200); // reinício: IN_FLIGHT vira NEEDS_READBACK
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,new OpenDispatcher(dir.resolve("o9"),clock,config,registry,id->Optional.of(adapter)),
   outbox,decisions,decisionOutbox,central,clock);
  service.processQueues(10); // antes da sonda de identidade
  assertEquals(Status.NEEDS_READBACK,queue.get("p").orElseThrow().status());
  service.checkHealth();
  adapter.calls.clear();
  adapter.personPresent=true; adapter.personName="Morador";
  service.processQueues(10);
  assertEquals("READ_STATE",adapter.calls.getFirst(),"leitura antes de qualquer reenvio");
  assertTrue(adapter.calls.stream().noneMatch("UPSERT_PERSON"::equals));
  assertEquals(OperationState.CONFIRMED,queue.get("p").orElseThrow().lastState());
 }

 @Test void reconciliacaoPreservaAOrdemDasVersoes() {
  build(ScriptedAdapter.all(),true,DecisionMode.EDGE_ONLINE);
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  service.submit(command("rv","d1","s1",CommandType.REVOKE_ALL,2,Map.of()));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,3,RIGHTS));
  assertEquals(LocalCommandQueue.EnqueueOutcome.REJECTED_STALE,service.submit(command("velha","d1","s1",CommandType.REVOKE_RIGHTS,1,Map.of())).outcome(),
   "revogação mais antiga que a concessão aceita é mensagem velha");
  decisions.markSynced(clock.instant());
  decisions.observeClockSkew(Duration.ZERO);
  assertTrue(context().decide("77","1").allow());
  decisions.close();
  decisions=new LocalDecisionEngine(dir,clock,config);
  decisions.markSynced(clock.instant());
  decisions.observeClockSkew(Duration.ZERO);
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",adapter),queue,new OpenDispatcher(dir.resolve("o10"),clock,config,registry,id->Optional.of(adapter)),
   outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth(); // identidade conferida após a recuperação, como no ciclo real
  assertTrue(context().decide("77","1").allow(),"recuperação não converte revogação v2 em revogação da concessão v3");
 }

 @Test void atalhoDeRevogacaoExigeCascataDeDireitosECredenciais() {
  adapter.coversCredentials=true;
  adapter.coversRights=false;
  service.submit(command("rv","d1","s1",CommandType.REVOKE_ALL,1,Map.of()));
  service.processQueues(1);
  assertTrue(adapter.calls.contains("REVOKE_RIGHTS"),"sem cascata de direitos, retira direitos separadamente");
 }

 @Test void identificacaoParcialNaoEOk() {
  var registry=new CapabilityRegistry(config.profiles());
  var d=config.devices().getFirst();
  assertEquals(br.com.condpulse.gateway.capability.CapabilityRegistry.IdentityCheck.UNVERIFIED,
   registry.checkIdentity(d,new DeviceIdentity(OperationState.CONFIRMED,null,d.sku(),null,null)));
  assertEquals(br.com.condpulse.gateway.capability.CapabilityRegistry.IdentityCheck.UNVERIFIED,
   registry.checkIdentity(d,new DeviceIdentity(OperationState.CONFIRMED,null,null,d.firmware(),null)));
  assertEquals(br.com.condpulse.gateway.capability.CapabilityRegistry.IdentityCheck.OK,
   registry.checkIdentity(d,new DeviceIdentity(OperationState.CONFIRMED,null,d.sku(),d.firmware(),null)));
 }

 // ----- oitava rodada (#129): varredura de identidade e cascata -----

 @Test void retiradaDeFacePelaPessoaSoComCascataDeclarada() {
  build(EnumSet.of(Operation.REMOVE_PERSON,Operation.READ_STATE,Operation.HEALTH),true,DecisionMode.DEVICE_LOCAL_ACL);
  service.submit(command("f","d1","s1",CommandType.REMOVE_FACE,1,Map.of()));
  service.processQueues(1);
  assertTrue(queue.get("f").orElseThrow().lastReason().startsWith("NAO_SUPORTADO_SEM_DOCUMENTACAO"));
  assertTrue(adapter.calls.stream().noneMatch("REMOVE_PERSON"::equals),"sem cascata, remover a pessoa não substitui a retirada da face");
 }

 @Test void decisaoOnlineExigeIdentidadeReconferidaAposDesconexao() {
  var flaky=new boolean[]{true};
  var registry=new CapabilityRegistry(config.profiles());
  var dev=config.devices().getFirst();
  var online=TestSupport.config(List.of(TestSupport.device("d1",dev.brand(),dev.connectionMode(),DecisionMode.EDGE_ONLINE,dev.baseUrl(),dev.channels(),
   dev.nativeSchedules(),dev.params(),false)),true,CondoOpenPolicy.closed());
  var a=new ScriptedAdapter(TestSupport.context(online.devices().getFirst(),clock),ScriptedAdapter.all()) {
   @Override public HealthReport health() { return new HealthReport(flaky[0],flaky[0]?null:"DOWN",null,null,null); }
  };
  config=online;
  adapter=a;
  decisions.close();
  decisions=new LocalDecisionEngine(dir.resolve("dd"),clock,online);
  service=new GatewayService(online,new CapabilityRegistry(online.profiles()),Map.of("d1",a),queue,
   new OpenDispatcher(dir.resolve("o11"),clock,online,new CapabilityRegistry(online.profiles()),id->Optional.of(a)),outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth();
  service.submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  service.submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  decisions.markSynced(clock.instant());
  decisions.observeClockSkew(Duration.ZERO);
  assertTrue(context().decide("77","1").allow());
  flaky[0]=false; service.checkHealth(); // desconectou
  flaky[0]=true;
  assertFalse(context().decide("77","1").allow(),"sem reconferir a identidade após a reconexão: nega");
  service.checkHealth();
  assertTrue(context().decide("77","1").allow());
 }

 @Test void falhaTransitoriaNaIdentificacaoMantemPendencia() {
  var attempts=new int[1];
  var a=new ScriptedAdapter(TestSupport.context(config.devices().getFirst(),clock),ScriptedAdapter.all()) {
   @Override public DeviceIdentity identify() {
    if(attempts[0]++==0) return new DeviceIdentity(OperationState.UNKNOWN,"RESPONSE_NOT_RECEIVED",null,null,null);
    return new DeviceIdentity(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,null,null,null,null);
   }
  };
  var registry=new CapabilityRegistry(config.profiles());
  service=new GatewayService(config,registry,Map.of("d1",a),queue,new OpenDispatcher(dir.resolve("o12"),clock,config,registry,id->Optional.of(a)),
   outbox,decisions,decisionOutbox,central,clock);
  service.checkHealth();
  assertTrue(service.status("d1").orElseThrow().identityPending(),"falha transitória não encerra a pendência");
  service.checkHealth();
  assertFalse(service.status("d1").orElseThrow().identityPending());
  assertEquals(2,attempts[0]);
 }

 @Test void credencialAceitaPorCascataTemRetiradaPelaMesmaCascata() {
  build(EnumSet.of(Operation.SET_CARD,Operation.REMOVE_PERSON,Operation.READ_STATE,Operation.HEALTH),true,DecisionMode.DEVICE_LOCAL_ACL);
  adapter.coversCredentials=true;
  central.materials.put("m1","123".getBytes());
  service.submit(command("c","d1","s1",CommandType.SET_CREDENTIAL,1,Map.of("kind","CARD","materialRef","m1")));
  service.submit(command("rc","d1","s1",CommandType.REMOVE_CREDENTIAL,2,Map.of("kind","CARD")));
  service.processQueues(10);
  assertTrue(adapter.calls.contains("REMOVE_PERSON"),"retirada pela cascata declarada, nunca NAO_SUPORTADO depois de aceitar o cadastro");
  assertFalse(String.valueOf(queue.get("rc").orElseThrow().lastReason()).startsWith("NAO_SUPORTADO"));
 }
}
