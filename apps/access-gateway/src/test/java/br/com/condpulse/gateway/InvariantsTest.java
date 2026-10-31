package br.com.condpulse.gateway;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import br.com.condpulse.gateway.capability.CapabilityRegistry;
import br.com.condpulse.gateway.central.DecisionOutbox;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.CondoOpenPolicy;
import br.com.condpulse.gateway.config.GatewayConfig.OpenOrigin;
import br.com.condpulse.gateway.decision.LocalDecisionEngine;
import br.com.condpulse.gateway.events.EventOutbox;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.open.OpenDispatcher;
import br.com.condpulse.gateway.queue.LocalCommandQueue;
import br.com.condpulse.gateway.queue.LocalCommandQueue.CommandType;
import br.com.condpulse.gateway.queue.LocalCommandQueue.Status;
import br.com.condpulse.gateway.runtime.GatewayRuntime;
import br.com.condpulse.gateway.runtime.GatewayService;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.ScriptedAdapter;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.Test;
import static br.com.condpulse.gateway.support.TestSupport.command;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariantes do gateway, um teste por invariante:
 * 1 nada é consumido/confirmado antes de persistido; 2 queda entre passos do diário não ressuscita estado antigo;
 * 3 atuação passa por prazo, quarentena e perfil logo antes do envio; 4 leitura incerta nunca confirma;
 * 5 falha numa etapa do ciclo não bloqueia as outras; 6 todo I/O bloqueante tem prazo ou watchdog.
 */
class InvariantsTest {
 private static final Map<String,Object> RIGHTS=Map.of("rights",List.of(Map.of("channelId","c1","scheduleRef","sempre")));

 private record Fixture(GatewayConfig config,java.nio.file.Path dir,TestSupport.TestClock clock,ScriptedAdapter adapter,
     LocalCommandQueue queue,EventOutbox outbox,LocalDecisionEngine decisions,DecisionOutbox decisionOutbox,OpenDispatcher opens,
     GatewayService service,TestSupport.StubCentral central) {}

 private static Fixture fixture(java.nio.file.Path dir,TestSupport.TestClock clock,DecisionMode mode,ScriptedAdapter given) {
  var device=TestSupport.device("d1",Brand.CONTROL_ID,ConnectionMode.LOCAL,mode,URI.create("http://192.0.2.10"),
   List.of(TestSupport.channel("c1","1","door:door=1")),Map.of("sempre","1"),Map.of(),true);
  var config=TestSupport.config(List.of(device),true,new CondoOpenPolicy(EnumSet.of(OpenOrigin.PORTARIA_LOCAL),false));
  var adapter=given!=null?given:new ScriptedAdapter(TestSupport.context(device,clock),ScriptedAdapter.all());
  var central=new TestSupport.StubCentral();
  var queue=new LocalCommandQueue(dir,clock,100,200);
  var outbox=new EventOutbox(dir,clock,1000);
  var decisions=new LocalDecisionEngine(dir,clock,config);
  var decisionOutbox=new DecisionOutbox(dir,clock,100);
  var registry=new CapabilityRegistry(config.profiles());
  var opens=new OpenDispatcher(dir,clock,config,registry,id->Optional.of(adapter));
  var service=new GatewayService(config,registry,Map.of("d1",adapter),queue,opens,outbox,decisions,decisionOutbox,central,clock);
  return new Fixture(config,dir,clock,adapter,queue,outbox,decisions,decisionOutbox,opens,service,central);
 }
 private static void close(Fixture f) { f.queue().close(); f.outbox().close(); f.decisions().close(); f.decisionOutbox().close(); f.opens().close(); }

 /** 1. O cursor de coleta só avança depois que a página está gravada; falha de gravação devolve a mesma página. */
 @Test void invariante1_nadaConsumidoAntesDePersistido() {
  var dir=TestSupport.tempDir();
  var clock=new TestSupport.TestClock();
  var cursors=new ArrayList<String>();
  var device=TestSupport.device("d1",Brand.CONTROL_ID,ConnectionMode.LOCAL,DecisionMode.DEVICE_LOCAL_ACL,URI.create("http://192.0.2.10"),
   List.of(TestSupport.channel("c1","1",null)),Map.of(),Map.of(),false);
  var adapter=new ScriptedAdapter(TestSupport.context(device,clock),ScriptedAdapter.all()) {
   @Override public EventPage fetchEvents(String cursor,int limit) {
    cursors.add(cursor);
    var ev=new Evidence(EvidenceLevel.DEVICE_REPORTED,EvidenceSource.DEVICE_EVENT,"X",null,null);
    return new EventPage(OperationState.CONFIRMED,null,List.of(new EdgeEvent(null,"d1","prov-d1","epoca-1","n"+cursors.size(),"conector-teste",null,
     EventKind.ACCESS_ALLOWED,null,Direction.UNKNOWN,TimeQuality.UNKNOWN,ev,null,"1")),"pos-"+cursors.size(),0);
   }
  };
  var f=fixture(dir,clock,DecisionMode.DEVICE_LOCAL_ACL,adapter);
  try {
   f.outbox().close();
   var full=new EventOutbox(dir.resolve("cheia"),clock,0);
   var service=new GatewayService(f.config(),new CapabilityRegistry(f.config().profiles()),Map.of("d1",adapter),f.queue(),f.opens(),full,
    f.decisions(),f.decisionOutbox(),f.central(),clock);
   service.pollEvents();
   service.pollEvents();
   assertEquals(java.util.Arrays.asList(null,null),cursors,"gravação recusada: o cursor não avança");
   full.close();
  } finally { close(f); }
 }

 /** 2. Queda entre gravar a revogação e atualizar a cópia de decisão: a recuperação reconcilia antes de atender. */
 @Test void invariante2_quedaEntrePassosNaoRessuscitaEstado() {
  var dir=TestSupport.tempDir();
  var clock=new TestSupport.TestClock();
  var f=fixture(dir,clock,DecisionMode.EDGE_ONLINE,null);
  f.service().submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador","nativeUserRef","77")));
  f.service().submit(command("r","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS));
  // A revogação chega à fila, mas o processo cai antes de atualizar a cópia de decisão.
  f.queue().enqueue(command("rv","d1","s1",CommandType.REVOKE_ALL,3,Map.of()));
  // E uma aplicação anterior chega fora de ordem depois de uma mais nova.
  close(f);
  var g=fixture(dir,clock,DecisionMode.EDGE_ONLINE,null);
  try {
   g.decisions().markSynced(clock.instant());
   g.decisions().observeClockSkew(Duration.ZERO);
   var captured=new InboundEndpoint.Context[1];
   g.adapter().inboundCapture=ctx->captured[0]=ctx;
   g.service().handleInbound("d1",new InboundEndpoint.Request("GET","/x",Map.of(),null,new byte[0],"127.0.0.1"));
   assertEquals(List.of("REVOKED"),captured[0].decide("77","1").reasonCodes(),"revogação persistida vale após a recuperação");
   assertEquals(LocalCommandQueue.EnqueueOutcome.REJECTED_STALE,
    g.service().submit(command("velha","d1","s1",CommandType.APPLY_RIGHTS,2,RIGHTS)).outcome());
   g.service().submit(command("r4","d1","s9",CommandType.APPLY_RIGHTS,4,RIGHTS));
   assertEquals(LocalCommandQueue.EnqueueOutcome.REJECTED_STALE,
    g.service().submit(command("r3","d1","s9",CommandType.APPLY_RIGHTS,3,RIGHTS)).outcome(),"v3 depois de v4 não restaura direitos antigos");
  } finally { close(g); }
 }

 /** 3. Prazo, quarentena e perfil são conferidos imediatamente antes do envio: nada disso chega ao equipamento. */
 @Test void invariante3_atuacaoConfereTudoAntesDoEnvio() {
  var dir=TestSupport.tempDir();
  var clock=new TestSupport.TestClock();
  var f=fixture(dir,clock,DecisionMode.DEVICE_LOCAL_ACL,null);
  try {
   var now=clock.instant();
   var expired=new OpenDispatcher.OpenCommand("e","ne","d1","c1","ponto-c1",1,1,now.minusSeconds(6),now.minusSeconds(1),OpenOrigin.PORTARIA_LOCAL,"a",null);
   assertEquals(OpenDispatcher.Status.EXPIRED,f.opens().dispatch(expired).status());
   assertEquals("DEVICE_IDENTITY_NOT_VERIFIED",f.opens().dispatch(new OpenDispatcher.OpenCommand("v","nv","d1","c1","ponto-c1",1,1,now,now.plusSeconds(5),
    OpenOrigin.PORTARIA_LOCAL,"a",null)).reasonCode(),"sem sonda de identidade desde a conexão: recusa");
   f.opens().quarantineCheck(id->"DEVICE_IDENTITY_MISMATCH");
   assertEquals("DEVICE_IDENTITY_MISMATCH",f.opens().dispatch(new OpenDispatcher.OpenCommand("q","nq","d1","c1","ponto-c1",1,1,now,now.plusSeconds(5),
    OpenOrigin.PORTARIA_LOCAL,"a",null)).reasonCode());
   var noProfile=new OpenDispatcher(dir.resolve("np"),clock,f.config(),new CapabilityRegistry(List.of()),id->Optional.of(f.adapter()));
   assertEquals("PROFILE_NOT_REGISTERED",noProfile.dispatch(new OpenDispatcher.OpenCommand("p","np","d1","c1","ponto-c1",1,1,now,now.plusSeconds(5),
    OpenOrigin.PORTARIA_LOCAL,"a",null)).reasonCode());
   noProfile.close();
   assertTrue(f.adapter().calls.stream().noneMatch("OPEN"::equals));
  } finally { close(f); }
 }

 /** 4. Leitura incerta (estado UNKNOWN) nunca vira confirmação. */
 @Test void invariante4_leituraIncertaNuncaConfirma() {
  var f=fixture(TestSupport.tempDir(),new TestSupport.TestClock(),DecisionMode.DEVICE_LOCAL_ACL,null);
  try {
   f.adapter().readStates.add(AppliedState.unavailable(OperationState.UNKNOWN,"RESPONSE_NOT_RECEIVED"));
   f.service().submit(command("p","d1","s1",CommandType.UPSERT_PERSON,1,Map.of("displayName","Morador")));
   f.service().processQueues(10);
   assertEquals(OperationState.APPLIED_UNVERIFIED,f.queue().get("p").orElseThrow().lastState());
   // Pessoa presente com outro nome também não confirma: os campos precisam bater.
   f.adapter().personName="Outro nome";
   f.service().submit(command("p2","d1","s1",CommandType.UPSERT_PERSON,2,Map.of("displayName","Morador 2")));
   f.adapter().script(Operation.UPSERT_PERSON,OperationResult.of(OperationState.ACK,null));
   f.adapter().readStates.add(new AppliedState(OperationState.CONFIRMED,null,true,Set.of(),null,null,Map.of("name","Outro nome")));
   f.service().processQueues(10);
   assertNotEquals(OperationState.CONFIRMED,f.queue().get("p2").orElseThrow().lastState());
  } finally { close(f); }
 }

 /** 5. Falha numa etapa do ciclo não impede as seguintes. */
 @Test void invariante5_falhaDeEtapaNaoBloqueiaAsOutras() {
  var ran=new ArrayList<String>();
  GatewayRuntime.steps(
   GatewayRuntime.guard("coleta",()->{ ran.add("coleta"); throw new IllegalStateException("EVENT_OUTBOX_FULL"); }),
   GatewayRuntime.guard("envio",()->ran.add("envio")),
   GatewayRuntime.guard("saúde",()->ran.add("saúde"))).run();
  assertEquals(List.of("coleta","envio","saúde"),ran);
 }

 /** 6. Corpo que para no meio e cabeçalhos que não chegam têm prazo. */
 @Test void invariante6_ioBloqueanteTemPrazo() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
  var hits=new AtomicInteger();
  server.createContext("/corpo",ex->{
   hits.incrementAndGet();
   ex.sendResponseHeaders(200,100);
   OutputStream os=ex.getResponseBody();
   os.write("parcial".getBytes()); os.flush();
   try { Thread.sleep(10_000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
  });
  server.createContext("/sem-cabecalho",ex->{ try { Thread.sleep(10_000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
  server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r->{ var t=new Thread(r); t.setDaemon(true); return t; }));
  server.start();
  try {
   var http=new DeviceHttpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(600),null,Set.of("/corpo","/sem-cabecalho"));
   long t0=System.nanoTime();
   var body=assertThrows(DeviceCallException.class,()->http.call("GET","/corpo",Map.of(),null,null,false));
   assertEquals(DeviceHttpClient.Failure.AMBIGUOUS,body.failure());
   assertTrue((System.nanoTime()-t0)/1_000_000<5_000,"corpo parado interrompido pelo prazo");
   t0=System.nanoTime();
   assertThrows(DeviceCallException.class,()->http.openStream("/sem-cabecalho",Map.of()));
   assertTrue((System.nanoTime()-t0)/1_000_000<5_000,"assinatura sem cabeçalho interrompida pelo prazo");
   assertEquals(1,hits.get(),"sem repetição implícita");
  } finally { server.stop(0); }
 }
}
