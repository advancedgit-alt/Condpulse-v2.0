package br.com.condpulse.gateway.adapters;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.adapters.dahua.DahuaCgiAdapter;
import br.com.condpulse.gateway.adapters.intelbras.IntelbrasCgiAdapter;
import br.com.condpulse.gateway.inbound.InboundServer;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.FakeDeviceServer;
import br.com.condpulse.gateway.support.FakeDeviceServer.Reply;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contratos Dahua (documento de integração V1.0) e Intelbras (documentação oficial de API de controle de acesso)
 * contra servidores falsos. Simulador não é homologação.
 */
class DahuaIntelbrasAdapterContractTest {

 private static DahuaCgiAdapter dahua(FakeDeviceServer fake,TestSupport.TestClock clock) {
  var device=TestSupport.device("dh",Brand.DAHUA,ConnectionMode.LOCAL,DecisionMode.DEVICE_LOCAL_ACL,fake.baseUrl(),
   List.of(TestSupport.channel("c1","1",null)),Map.of(),Map.of(),true);
  return new DahuaCgiAdapter(TestSupport.context(device,clock));
 }

 @Test void dahuaCartaoFaceEAberturaNoFormatoDocumentado() throws Exception {
  try(var fake=new FakeDeviceServer().requireDigest("dahua-sintetico")) {
   fake.on("GET","/cgi-bin/recordUpdater.cgi",Reply.text("RecNo=12345\r\n"));
   fake.on("POST","/cgi-bin/FaceInfoManager.cgi",Reply.text("OK"));
   fake.on("GET","/cgi-bin/accessControl.cgi",Reply.text("OK"));
   var a=dahua(fake,new TestSupport.TestClock());
   var card=a.setCredential(new CredentialSpec("ref-1",CredentialKind.CARD,"12345"));
   assertEquals(OperationState.APPLIED_UNVERIFIED,card.state());
   assertEquals("12345",card.nativeRef());
   assertEquals("action=insert&name=AccessControlCard&CardNo=12345&UserID=ref-1&CardStatus=0&CardType=0",fake.requests("/cgi-bin/recordUpdater.cgi").getLast().query());
   assertEquals(OperationState.APPLIED_UNVERIFIED,a.enrollFace(new FaceSpec("ref-1",TestSupport.syntheticJpeg())).state());
   var face=Json.object(fake.requests("/cgi-bin/FaceInfoManager.cgi").getLast().text());
   assertEquals("ref-1",face.get("UserID"));
   assertEquals(1,Json.list(Json.map(face.get("Info")).get("PhotoData")).size());
   assertEquals(OpenOutcome.ACCEPTED,a.open(new OpenAction("o","c1","1",null,Instant.now().plusSeconds(5))).outcome());
   assertEquals("action=openDoor&channel=1&Type=Remote",fake.requests("/cgi-bin/accessControl.cgi").getLast().query());
   int before=fake.requests().size();
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.removeCredential("ref-1",CredentialKind.CARD).state());
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.removeFace("ref-1").state());
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.revokeRights("ref-1").state());
   assertEquals(before,fake.requests().size(),"nenhuma remoção inventada");
  }
 }

 @Test void dahuaRegistrosPaginadosPorCreateTimeComDeduplicacao() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var clock=new TestSupport.TestClock();
   long t=clock.instant().getEpochSecond()-100;
   fake.on("GET","/cgi-bin/recordFinder.cgi",r->Reply.text(String.join("\r\n",
    "totalCount=3","found=3",
    "records[0].RecNo=10","records[0].CreateTime="+t,"records[0].Status=1","records[0].Type=Entry","records[0].Door=1","records[0].Method=15","records[0].UserID=777",
    "records[1].RecNo=11","records[1].CreateTime="+t,"records[1].Status=0","records[1].Type=Exit","records[1].Door=1","records[1].ErrorCode=16",
    "records[2].RecNo=12","records[2].CreateTime="+(t+5),"records[2].Status=9","records[2].Door=1")));
   var a=dahua(fake,clock);
   var page=a.fetchEvents(null,100);
   assertEquals(2,page.events().size());
   assertEquals(1,page.unmapped());
   assertEquals((t+5)+":12",page.nextCursor());
   var first=page.events().getFirst();
   assertEquals(EventKind.ACCESS_ALLOWED,first.kind());
   assertEquals(Direction.IN,first.direction());
   assertEquals(Instant.ofEpochSecond(t),first.occurredAt());
   assertEquals(TimeQuality.DEVICE_UNVERIFIED,first.timeQuality());
   assertEquals("DAHUA_STATUS_1_METHOD_15",first.evidence().nativeKind());
   assertNull(first.subjectRef(),"UserID nativo não vira referência do CondPulse");
   assertEquals(Direction.OUT,page.events().get(1).direction());
   var again=a.fetchEvents(t+":11",100);
   assertEquals(0,again.events().size(),"mesmo segundo e RecNo já visto não repete");
   assertTrue(fake.requests("/cgi-bin/recordFinder.cgi").getFirst().query().startsWith("action=find&name=AccessControlCardRec&StartTime="));
   assertTrue(a.health().reachable());
  }
 }

 // ----- Intelbras -----

 static final String STREAM=String.join("\r\n",
  "--myboundary","Content-Type: text/plain","Content-Length: 39","","Heartbeat","",
  "--myboundary","Content-Type: text/plain","Content-Length: 300","",
  "Code=AccessControl;action=Pulse;index=0;data={","\"CardNo\" : \"\",","\"Door\" : 0,","\"ErrorCode\" : 0,","\"Method\" : 15,",
  "\"Status\" : 1,","\"UTC\" : 1893456000,","\"UserID\" : \"501\"","}","",
  "--myboundary","Content-Type: text/plain","Content-Length: 300","",
  "Code=AccessControl;action=Pulse;index=0;data={\"Door\" : 0, \"ErrorCode\" : 18, \"Method\" : 1, \"UTC\" : 1893456001, \"UserID\" : \"502\"}","",
  "--myboundary","Content-Type: text/plain","","Code=BreakIn;action=Start;index=0;data={\"UTC\" : 1893456002}","",
  "--myboundary","Content-Type: text/plain","","Code=ManagerLog;action=Pulse;index=0;data={\"UTC\" : 1893456003}","");

 private static IntelbrasCgiAdapter intelbras(FakeDeviceServer fake,ConnectionMode mode) {
  var device=TestSupport.device("ib",Brand.INTELBRAS,mode,DecisionMode.EDGE_ONLINE,fake==null?null:fake.baseUrl(),
   List.of(TestSupport.channel("c1","0",null)),Map.of(),Map.of("intelbras.streamWindowMillis","500"),false);
  var ctx=TestSupport.context(device,new TestSupport.TestClock());
  if(mode==ConnectionMode.NUVEM) ctx=new br.com.condpulse.gateway.spi.BaseAdapter.DeviceContext(device,ctx.connectorId(),null,ctx.timeout(),ctx.pushPickupTimeout(),ctx.clock(),ctx.zone());
  return new IntelbrasCgiAdapter(ctx);
 }

 @Test void intelbrasFluxoDeEventosNormalizaComEvidenciaExplicita() throws Exception {
  try(var fake=new FakeDeviceServer().requireDigest("intelbras-sintetico")) {
   fake.on("GET","/cgi-bin/eventManager.cgi",r->new Reply(200,"multipart/x-mixed-replace; boundary=myboundary",STREAM,Map.of(),0));
   var a=intelbras(fake,ConnectionMode.LOCAL);
   var page=a.fetchEvents(null,100);
   assertEquals(OperationState.CONFIRMED,page.state());
   assertEquals(3,page.events().size());
   assertEquals(1,page.unmapped(),"ManagerLog não é evento de acesso");
   var allowed=page.events().get(0);
   assertEquals(EventKind.ACCESS_ALLOWED,allowed.kind());
   assertEquals("INTELBRAS_ERROR_0_METHOD_15",allowed.evidence().nativeKind());
   assertEquals(Instant.ofEpochSecond(1893456000L),allowed.occurredAt());
   assertEquals("c1",allowed.deviceChannelId());
   assertEquals(EventKind.ACCESS_DENIED,page.events().get(1).kind());
   assertEquals(EventKind.DOOR_FORCED,page.events().get(2).kind());
   var again=a.fetchEvents(null,100);
   assertEquals(allowed.eventId(),again.events().getFirst().eventId(),"mesma chave composta, mesmo eventId");
   String q=fake.requests("/cgi-bin/eventManager.cgi").getLast().query();
   assertTrue(q.startsWith("action=attach&codes="),q);
   int before=fake.requests().size();
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.upsertPerson(new PersonSpec("r","n",null,null)).state());
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.enrollFace(new FaceSpec("r",TestSupport.syntheticJpeg())).state());
   assertEquals(OpenOutcome.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.open(new OpenAction("o","c1","0",null,Instant.now().plusSeconds(5))).outcome());
   assertEquals(before,fake.requests().size());
   assertFalse(a.manifest().documented().get(CapabilityName.offlineRestriction),"offline: todos VIP 24/7 (IB-ONLINE)");
  }
 }

 @Test void intelbrasModoOnlineResponde_authComDecisaoLocal() {
  var a=intelbras(null,ConnectionMode.NUVEM);
  var endpoint=a.inbound().orElseThrow();
  var events=new ArrayList<EdgeEvent>();
  var heartbeats=new int[1];
  var seen=new ArrayList<String>();
  InboundEndpoint.Context allow=new InboundEndpoint.Context() {
   @Override public Instant now() { return Instant.now(); }
   @Override public InboundEndpoint.LocalDecision decide(String user,String channel) { seen.add(user+"@"+channel); return new InboundEndpoint.LocalDecision(user.equals("501"),List.of("x"),null,null); }
   @Override public void events(List<EdgeEvent> e) { events.addAll(e); }
   @Override public void heartbeat() { heartbeats[0]++; }
  };
  assertEquals(200,endpoint.handle(new InboundEndpoint.Request("GET","/keepalive",Map.of(),null,new byte[0],"127.0.0.1"),allow).status());
  String one="--myboundary\r\nContent-Type: text/plain\r\n\r\nCode=AccessControl;action=Pulse;index=0;data={\"Door\":0,\"ErrorCode\":96,\"UTC\":1893456000,\"UserID\":\"501\"}\r\n";
  var r=Json.object(new String(endpoint.handle(new InboundEndpoint.Request("POST","/notification",Map.of(),"multipart/mixed",one.getBytes(),"127.0.0.1"),allow).body()));
  assertEquals("true",r.get("auth"));
  assertEquals("200",r.get("code"));
  var deny=Json.object(new String(endpoint.handle(new InboundEndpoint.Request("POST","/notification",Map.of(),"multipart/mixed",one.replace("501","999").getBytes(),"127.0.0.1"),allow).body()));
  assertEquals("false",deny.get("auth"));
  var garbage=Json.object(new String(endpoint.handle(new InboundEndpoint.Request("POST","/notification",Map.of(),null,"lixo".getBytes(),"127.0.0.1"),allow).body()));
  assertEquals("false",garbage.get("auth"),"formato desconhecido nunca libera");
  assertEquals(List.of("501@0","999@0"),seen);
  assertEquals(2,events.size());
  assertEquals(4,heartbeats[0]);
  assertEquals(404,endpoint.handle(new InboundEndpoint.Request("GET","/qualquer",Map.of(),null,new byte[0],"127.0.0.1"),allow).status());
 }

 @Test void consultaDoServidorDeEntradaDecodificaFormulario() {
  assertEquals(Map.of("user_id","7","portal_id","1"),InboundServer.parseQuery("user_id=7&portal_id=1"));
 }
}
