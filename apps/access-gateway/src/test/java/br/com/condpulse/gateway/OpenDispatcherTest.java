package br.com.condpulse.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import br.com.condpulse.gateway.capability.CapabilityRegistry;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.GatewayConfig.CondoOpenPolicy;
import br.com.condpulse.gateway.config.GatewayConfig.OpenOrigin;
import br.com.condpulse.gateway.open.OpenDispatcher;
import br.com.condpulse.gateway.open.OpenDispatcher.OpenCommand;
import br.com.condpulse.gateway.open.OpenDispatcher.Status;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.ScriptedAdapter;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Abertura pontual: validade curta, recibo durável e nenhum replay em timeout, reinício ou repetição. */
class OpenDispatcherTest {
 private TestSupport.TestClock clock;
 private java.nio.file.Path dir;
 private GatewayConfig config;
 private ScriptedAdapter adapter;

 @BeforeEach void setup() {
  clock=new TestSupport.TestClock();
  dir=TestSupport.tempDir();
  var device=TestSupport.device("d1",Brand.CONTROL_ID,ConnectionMode.LOCAL,DecisionMode.DEVICE_LOCAL_ACL,java.net.URI.create("http://192.0.2.10"),
   List.of(TestSupport.channel("c1","1","door:door=1")),Map.of(),Map.of(),true);
  config=TestSupport.config(List.of(device),true,new CondoOpenPolicy(EnumSet.of(OpenOrigin.PORTARIA_LOCAL,OpenOrigin.PORTARIA_REMOTA,OpenOrigin.MORADOR_APP),true));
  adapter=new ScriptedAdapter(TestSupport.context(device,clock),ScriptedAdapter.all());
 }
 private OpenDispatcher dispatcher() {
  return new OpenDispatcher(dir,clock,config,new CapabilityRegistry(config.profiles()),id->Optional.of(adapter));
 }
 private OpenCommand cmd(String id,String nonce,int ttlSeconds,OpenOrigin origin,String grant) {
  Instant now=clock.instant();
  return new OpenCommand(id,nonce,"d1","c1","ponto-c1",1,1,now,now.plusSeconds(ttlSeconds),origin,"autorizacao-"+id,grant);
 }
 private long opens() { return adapter.calls.stream().filter("OPEN"::equals).count(); }

 @Test void aberturaValidaEnviaUmaVezEMesmoComandoNaoReenvia() {
  try(var d=dispatcher()) {
   assertEquals(Status.SENT,d.dispatch(cmd("o1","n1",5,OpenOrigin.PORTARIA_LOCAL,null)).status());
   assertEquals(Status.SENT,d.dispatch(cmd("o1","n1",5,OpenOrigin.PORTARIA_LOCAL,null)).status());
   assertEquals(1,opens());
  }
 }

 @Test void aberturaVencidaERecusadaSemChamarOEquipamento() {
  try(var d=dispatcher()) {
   var c=cmd("o2","n2",5,OpenOrigin.PORTARIA_LOCAL,null);
   clock.advance(Duration.ofSeconds(6));
   assertEquals(Status.EXPIRED,d.dispatch(c).status());
   assertEquals(0,opens());
  }
 }

 @Test void validadeAcimaDoLimiteENonceRepetidoSaoRecusados() {
  try(var d=dispatcher()) {
   assertEquals("TTL_OUT_OF_RANGE",d.dispatch(cmd("o3","n3",11,OpenOrigin.PORTARIA_LOCAL,null)).reasonCode());
   d.dispatch(cmd("o4","n4",5,OpenOrigin.PORTARIA_LOCAL,null));
   assertEquals("NONCE_REUSED",d.dispatch(cmd("o5","n4",5,OpenOrigin.PORTARIA_LOCAL,null)).reasonCode());
   assertEquals(1,opens());
  }
 }

 @Test void timeoutAmbiguoViraUnknownSemNovaTentativa() {
  adapter.openResults.add(new OpenResult(OpenOutcome.AMBIGUOUS,"RESPONSE_NOT_RECEIVED"));
  try(var d=dispatcher()) {
   assertEquals(Status.UNKNOWN,d.dispatch(cmd("o6","n6",5,OpenOrigin.PORTARIA_REMOTA,null)).status());
   assertEquals(Status.UNKNOWN,d.dispatch(cmd("o6","n6",5,OpenOrigin.PORTARIA_REMOTA,null)).status());
   assertEquals(1,opens());
  }
 }

 @Test void reinicioDepoisDoCompromissoNaoReexecuta() throws Exception {
  // Simula queda entre o compromisso durável e a resposta: grava DISPATCH_COMMITTED e fecha sem terminar.
  var journal=new br.com.condpulse.gateway.journal.Journal(dir.resolve("open-receipts.jsonl"));
  var m=new java.util.LinkedHashMap<String,Object>();
  m.put("commandId","o7"); m.put("deviceId","d1"); m.put("channelId","c1"); m.put("nonce","n7");
  m.put("status","DISPATCH_COMMITTED"); m.put("reason",null); m.put("at",clock.instant().toString()); m.put("committedAt",clock.instant().toString());
  journal.append(m); journal.close();
  try(var d=dispatcher()) {
   assertEquals(Status.UNKNOWN,d.receipt("o7").orElseThrow().status());
   assertEquals(Status.UNKNOWN,d.dispatch(cmd("o7","n7",5,OpenOrigin.PORTARIA_LOCAL,null)).status());
   assertEquals(0,opens());
  }
 }

 @Test void destinoComRevisaoAntigaOuNaoComissionadoERecusado() {
  try(var d=dispatcher()) {
   Instant now=clock.instant();
   var stale=new OpenCommand("o8","n8","d1","c1","ponto-c1",2,1,now,now.plusSeconds(5),OpenOrigin.PORTARIA_LOCAL,"a",null);
   assertEquals("STALE_BINDING",d.dispatch(stale).reasonCode());
   var other=new OpenCommand("o9","n9","d1","c1","outro-ponto",1,1,now,now.plusSeconds(5),OpenOrigin.PORTARIA_LOCAL,"a",null);
   assertEquals("NOT_COMMISSIONED",d.dispatch(other).reasonCode());
   assertEquals(0,opens());
  }
 }

 @Test void moradorPeloAppExigeConcessaoIndividualAtestada() {
  try(var d=dispatcher()) {
   assertEquals("RESIDENT_GRANT_MISSING",d.dispatch(cmd("o10","n10",5,OpenOrigin.MORADOR_APP,null)).reasonCode());
   assertEquals(Status.SENT,d.dispatch(cmd("o11","n11",5,OpenOrigin.MORADOR_APP,"concessao-morador-1")).status());
  }
 }

 @Test void origemDesligadaNoCondominioERecusada() {
  config=TestSupport.config(config.devices(),true,new CondoOpenPolicy(EnumSet.of(OpenOrigin.PORTARIA_LOCAL),false));
  try(var d=dispatcher()) {
   assertEquals("ORIGIN_DISABLED_FOR_CONDO",d.dispatch(cmd("o12","n12",5,OpenOrigin.PORTARIA_REMOTA,null)).reasonCode());
   assertEquals("ORIGIN_DISABLED_FOR_CONDO",d.dispatch(cmd("o13","n13",5,OpenOrigin.MORADOR_APP,"g")).reasonCode());
   assertEquals(0,opens());
  }
 }

 @Test void aberturaSemDocumentacaoNoAdaptadorERecusada() {
  adapter=new ScriptedAdapter(TestSupport.context(config.devices().getFirst(),clock),EnumSet.of(Operation.HEALTH));
  try(var d=dispatcher()) {
   assertEquals("NAO_SUPORTADO_SEM_DOCUMENTACAO",d.dispatch(cmd("o14","n14",5,OpenOrigin.PORTARIA_LOCAL,null)).reasonCode());
  }
 }
}
