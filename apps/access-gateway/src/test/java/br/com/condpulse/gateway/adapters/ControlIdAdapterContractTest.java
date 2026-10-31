package br.com.condpulse.gateway.adapters;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import br.com.condpulse.gateway.adapters.controlid.ControlIdAdapter;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.FakeDeviceServer;
import br.com.condpulse.gateway.support.FakeDeviceServer.Reply;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrato Control iD contra um servidor falso que responde no formato documentado (CI-LOGIN, CI-OBJ, CI-FACE,
 * CI-OPEN, CI-ONLINE, CI-PUSH). Simulador não é homologação.
 */
class ControlIdAdapterContractTest {

 /** Objetos do equipamento em memória, com o filtro "where" documentado em load_objects. */
 static final class Store {
  final Map<String,List<Map<String,Object>>> objects=new HashMap<>();
  long nextId=100;
  boolean expireSessionOnce;

  void install(FakeDeviceServer fake) {
   fake.on("POST","/login.fcgi",r->Reply.json("{\"session\":\"sessao-sintetica\"}"));
   fake.on("POST","/session_is_valid.fcgi",r->Reply.json("{\"session_is_valid\":true}"));
   fake.on("POST","/load_objects.fcgi",r->{
    if(expireSessionOnce) { expireSessionOnce=false; return Reply.status(401); }
    var b=Json.object(r.text());
    String o=Json.string(b,"object");
    List<Map<String,Object>> rows=new ArrayList<>(filter(o,Json.map(b.get("where"))));
    var order=Json.list(b.get("order"));
    if(order!=null && order.contains("id")) {
     rows.sort(java.util.Comparator.comparingLong(x->((Number)x.get("id")).longValue()));
     if(order.contains("descending")) java.util.Collections.reverse(rows);
    } else java.util.Collections.reverse(rows); // sem ordem pedida, o simulador devolve fora de ordem
    Long limit=Json.longValue(b,"limit");
    if(limit!=null && rows.size()>limit) rows=rows.subList(0,limit.intValue());
    return Reply.json(Json.write(Map.of(o,rows)));
   });
   fake.on("POST","/create_objects.fcgi",r->{
    var b=Json.object(r.text());
    String o=Json.string(b,"object");
    var ids=new ArrayList<Object>();
    for(Object v:Json.list(b.get("values")))
     if(o.equals("pins") && !filter("pins",Map.of("pins",Map.of("user_id",Json.map(v).get("user_id")))).isEmpty())
      return Reply.status(400); // pins.user_id é único
    for(Object v:Json.list(b.get("values"))) {
     var row=new LinkedHashMap<>(Json.map(v));
     if(!o.startsWith("user_")) { row.put("id",nextId); ids.add(nextId++); }
     objects.computeIfAbsent(o,k->new ArrayList<>()).add(row);
    }
    return Reply.json(Json.write(Map.of("ids",ids)));
   });
   fake.on("POST","/destroy_objects.fcgi",r->{
    var b=Json.object(r.text());
    String o=Json.string(b,"object");
    var rows=filter(o,Json.map(b.get("where")));
    objects.getOrDefault(o,new ArrayList<>()).removeAll(rows);
    return Reply.json("{\"changes\":"+rows.size()+"}");
   });
   fake.on("POST","/modify_objects.fcgi",r->{
    var b=Json.object(r.text());
    String o=Json.string(b,"object");
    var rows=filter(o,Json.map(b.get("where")));
    for(var row:rows) row.putAll(Json.map(b.get("values")));
    return Reply.json("{\"changes\":"+rows.size()+"}");
   });
  }

  List<Map<String,Object>> filter(String object,Map<String,Object> where) {
   var out=new ArrayList<Map<String,Object>>();
   var cond=where==null?Map.<String,Object>of():Json.map(where.get(object));
   for(var row:objects.getOrDefault(object,List.of())) {
    boolean ok=true;
    for(var e:cond.entrySet()) {
     Object actual=row.get(e.getKey());
     if(e.getValue() instanceof Map<?,?> ops) {
      for(var op:ops.entrySet()) {
       switch(String.valueOf(op.getKey())) {
        case ">" -> ok&=((Number)actual).longValue()>((Number)op.getValue()).longValue();
        case "<=" -> ok&=((Number)actual).longValue()<=((Number)op.getValue()).longValue();
        case "IN" -> ok&=((List<?>)op.getValue()).stream().anyMatch(x->String.valueOf(x).equals(String.valueOf(actual)));
        default -> throw new IllegalStateException("operador não usado pelo adaptador");
       }
      }
     } else ok&=String.valueOf(actual).equals(String.valueOf(e.getValue()));
    }
    if(ok) out.add(row);
   }
   return out;
  }
 }

 private static ControlIdAdapter lan(FakeDeviceServer fake,TestSupport.TestClock clock,String openAction) {
  var device=TestSupport.device("cid",Brand.CONTROL_ID,ConnectionMode.LOCAL,DecisionMode.DEVICE_LOCAL_ACL,fake.baseUrl(),
   List.of(TestSupport.channel("c1","1",openAction)),Map.of("sempre","5"),Map.of(),true);
  return new ControlIdAdapter(TestSupport.context(device,clock));
 }

 @Test void pessoaDireitosELeituraDeVoltaComSessaoNaQuery() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   store.objects.computeIfAbsent("user_groups",k->new ArrayList<>());
   var a=lan(fake,new TestSupport.TestClock(),"sec_box:id=65793,reason=3");
   var created=a.upsertPerson(new PersonSpec("ref-001","Morador 101",null,null));
   assertEquals(OperationState.APPLIED_UNVERIFIED,created.state());
   assertEquals("100",created.nativeRef());
   var login=fake.requests("/login.fcgi").getFirst();
   assertEquals(Map.of("login","usuario-sintetico","password","senha-sintetica"),Json.object(login.text()));
   assertEquals("session=sessao-sintetica",fake.requests("/create_objects.fcgi").getFirst().query());
   assertEquals(1,fake.requests("/login.fcgi").size(),"sessão reaproveitada");

   var rights=new RightsSpec("ref-001",List.of(new Right("c1","sempre")),null,null);
   assertEquals(OperationState.APPLIED_UNVERIFIED,a.applyRights(rights).state());
   var state=a.readState("ref-001");
   assertEquals(OperationState.CONFIRMED,state.state());
   assertEquals(a.rightKeys(rights).orElseThrow(),state.rightKeys());
   assertEquals(java.util.Set.of("rule:5"),state.rightKeys());

   assertEquals(OperationState.APPLIED_UNVERIFIED,a.setCredential(new CredentialSpec("ref-001",CredentialKind.CARD,"123456789")).state());
   assertEquals(Integer.valueOf(1),a.readState("ref-001").credentialCounts().get(CredentialKind.CARD));
   assertEquals("ALREADY_PRESENT",a.setCredential(new CredentialSpec("ref-001",CredentialKind.CARD,"123456789")).reasonCode());
   a.removeCredential("ref-001",CredentialKind.CARD);
   a.revokeRights("ref-001");
   var after=a.readState("ref-001");
   assertTrue(after.rightKeys().isEmpty());
   assertEquals(Integer.valueOf(0),after.credentialCounts().get(CredentialKind.CARD));
   a.removePerson("ref-001");
   assertEquals(Boolean.FALSE,a.readState("ref-001").personPresent());
  }
 }

 @Test void sessaoExpiradaRenovaUmaVez() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=lan(fake,new TestSupport.TestClock(),null);
   a.readState("x");
   store.expireSessionOnce=true;
   assertEquals(OperationState.CONFIRMED,a.readState("x").state());
   assertEquals(2,fake.requests("/login.fcgi").size());
  }
 }

 @Test void cadastroFacialUsaResultadoIndividualENuncaRemocaoGlobal() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   store.objects.computeIfAbsent("users",k->new ArrayList<>()).add(new LinkedHashMap<>(Map.of("id",7L,"registration","ref-7","name","M")));
   fake.on("POST","/user_set_image_list.fcgi",r->Reply.json("{\"results\":[{\"user_id\":7,\"success\":false,\"errors\":[{\"code\":8,\"message\":\"Low sharpness\"}]}]}"));
   fake.on("POST","/user_destroy_image.fcgi",Reply.text(""));
   var a=lan(fake,new TestSupport.TestClock(),null);
   var r=a.enrollFace(new FaceSpec("ref-7",TestSupport.syntheticJpeg()));
   assertEquals(OperationState.FAILED,r.state());
   assertEquals("CONTROLID_FACE_ERROR_8",r.reasonCode());
   var body=Json.object(fake.requests("/user_set_image_list.fcgi").getFirst().text());
   assertEquals(Boolean.TRUE,body.get("match"));
   var image=Json.map(Json.list(body.get("user_images")).getFirst());
   assertEquals(7L,image.get("user_id"));
   assertArrayEquals(TestSupport.syntheticJpeg(),java.util.Base64.getDecoder().decode(Json.string(image,"image")));
   a.removeFace("ref-7");
   assertEquals(Map.of("user_id",7L),Json.object(fake.requests("/user_destroy_image.fcgi").getFirst().text()));
  }
 }

 @Test void aberturaUsaAcaoTipadaDoCanal() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   fake.on("POST","/login.fcgi",Reply.json("{\"session\":\"s\"}"));
   fake.on("POST","/execute_actions.fcgi",Reply.text(""));
   var clock=new TestSupport.TestClock();
   var a=lan(fake,clock,"sec_box:id=65793,reason=3");
   var r=a.open(new OpenAction("o1","c1","1","sec_box:id=65793,reason=3",clock.instant().plusSeconds(5)));
   assertEquals(OpenOutcome.ACCEPTED,r.outcome());
   assertEquals("{\"actions\":[{\"action\":\"sec_box\",\"parameters\":\"id=65793, reason=3\"}]}",fake.requests("/execute_actions.fcgi").getFirst().text());
   assertEquals(OpenOutcome.NOT_SENT,a.open(new OpenAction("o2","c1","1","door:door=1;reboot",clock.instant().plusSeconds(5))).outcome());
   assertEquals(1,fake.requests("/execute_actions.fcgi").size());
   fake.on("POST","/execute_actions.fcgi",Reply.json("{\"actions\":[{\"action\":\"sec_box\",\"status\":\"denied\"}]}"));
   assertEquals(OpenOutcome.REJECTED_BY_DEVICE,a.open(new OpenAction("o3","c1","1","sec_box:id=65793,reason=3",clock.instant().plusSeconds(5))).outcome());
  }
 }

 @Test void eventosPorIdCrescenteSaltamLacunasSemPular() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   store.objects.put("users",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id",7L,"registration","ref-7")))));
   var logs=new ArrayList<Map<String,Object>>();
   logs.add(new LinkedHashMap<>(Map.of("id",1L,"event",7L,"user_id",7L,"portal_id",1L,"time",1L)));
   logs.add(new LinkedHashMap<>(Map.of("id",2L,"event",6L,"user_id",0L,"portal_id",1L,"time",1L)));
   logs.add(new LinkedHashMap<>(Map.of("id",3L,"event",11L,"user_id",0L,"portal_id",1L,"time",1L)));
   logs.add(new LinkedHashMap<>(Map.of("id",250000L,"event",3L,"user_id",0L,"portal_id",0L,"time",1L)));
   store.objects.put("access_logs",logs);
   var a=lan(fake,new TestSupport.TestClock(),null);
   var page=a.fetchEvents(null,3);
   assertEquals(2,page.events().size());
   assertEquals(1,page.unmapped(),"botoeira (11) não tem tipo normalizado");
   assertEquals("3",page.nextCursor());
   var first=page.events().getFirst();
   assertEquals(EventKind.ACCESS_ALLOWED,first.kind());
   assertEquals("ref-7",first.subjectRef());
   assertEquals("c1",first.deviceChannelId());
   assertNull(first.occurredAt(),"unidade de time não documentada");
   assertEquals(EvidenceLevel.DEVICE_REPORTED,first.evidence().level());
   var jump=a.fetchEvents("3",3);
   assertEquals("250000",jump.nextCursor(),"lacuna saltada direto ao menor id existente");
   assertEquals(EventKind.ACCESS_DENIED,jump.events().getFirst().kind());
   assertNull(jump.events().getFirst().deviceChannelId());
   var body=Json.object(fake.requests("/load_objects.fcgi").stream().filter(r->r.text().contains("access_logs")).findFirst().orElseThrow().text());
   assertEquals(List.of("id","ascending"),body.get("order"));
   var empty=a.fetchEvents("250000",3);
   assertEquals("250000",empty.nextCursor());
   assertTrue(empty.events().isEmpty());
  }
 }

 @Test void ordemNaoRespeitadaNaoAvancaOCursor() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   fake.on("POST","/login.fcgi",Reply.json("{\"session\":\"s\"}"));
   fake.on("POST","/load_objects.fcgi",Reply.json("{\"access_logs\":[{\"id\":9,\"event\":7},{\"id\":5,\"event\":7}]}"));
   var page=lan(fake,new TestSupport.TestClock(),null).fetchEvents("4",10);
   assertEquals(OperationState.UNKNOWN,page.state());
   assertEquals("ORDER_NOT_HONORED",page.reasonCode());
   assertEquals("4",page.nextCursor());
  }
 }

 // ----- modo nuvem (push) -----

 private static ControlIdAdapter push(TestSupport.TestClock clock) {
  var device=TestSupport.device("cidn",Brand.CONTROL_ID,ConnectionMode.NUVEM,DecisionMode.EDGE_ONLINE,null,
   List.of(TestSupport.channel("c1","1","sec_box:id=65793,reason=3")),Map.of("sempre","5"),Map.of(),true);
  var ctx=TestSupport.context(device,clock);
  ctx=new br.com.condpulse.gateway.spi.BaseAdapter.DeviceContext(device,ctx.connectorId(),null,Duration.ofMillis(800),Duration.ofMillis(800),clock,ctx.zone());
  return new ControlIdAdapter(ctx);
 }
 private static InboundEndpoint.Context ctx(boolean allow) {
  return new InboundEndpoint.Context() {
   @Override public Instant now() { return Instant.now(); }
   @Override public InboundEndpoint.LocalDecision decide(String user,String channel) { return new InboundEndpoint.LocalDecision(allow,List.of(allow?"ALLOWED":"REVOKED"),"ref-7",allow?"c1":null); }
   @Override public void events(List<EdgeEvent> events) {}
   @Override public void heartbeat() {}
  };
 }
 private static InboundEndpoint.Request req(String method,String path,Map<String,String> query,String body) {
  return new InboundEndpoint.Request(method,path,query,"application/json",body.getBytes(),"127.0.0.1");
 }

 @Test void pushEntregaComandoDocumentadoERecebeResultado() throws Exception {
  var a=push(new TestSupport.TestClock());
  var endpoint=a.inbound().orElseThrow();
  assertEquals(0,endpoint.handle(req("GET","/push",Map.of("deviceId","1","uuid","u0"),""),ctx(false)).body().length,"sem comando: resposta vazia");
  var future=CompletableFuture.supplyAsync(()->a.readState("ref-7"));
  InboundEndpoint.Response delivered=null;
  for(int i=0;i<50 && (delivered==null || delivered.body().length==0);i++) {
   Thread.sleep(10);
   delivered=endpoint.handle(req("GET","/push",Map.of("deviceId","1","uuid","u1"),""),ctx(false));
  }
  var command=Json.object(new String(delivered.body()));
  assertEquals("POST",command.get("verb"));
  assertEquals("load_objects",command.get("endpoint"));
  assertEquals("users",Json.string(Json.map(command.get("body")),"object"));
  endpoint.handle(req("POST","/result",Map.of("deviceId","1","uuid","u1"),"{\"response\":{\"users\":[]}}"),ctx(false));
  var state=future.get();
  assertEquals(OperationState.CONFIRMED,state.state());
  assertEquals(Boolean.FALSE,state.personPresent());
  assertEquals(400,endpoint.handle(req("GET","/push",Map.of("deviceId","outro","uuid","u2"),""),ctx(false)).status());
 }

 @Test void pushNaoRetiradoNoPrazoNaoFoiEnviadoEAberturaVencidaNuncaSai() throws Exception {
  var clock=new TestSupport.TestClock();
  var a=push(clock);
  var r=a.upsertPerson(new PersonSpec("ref-9","Morador",null,null));
  assertEquals(OperationState.FAILED,r.state());
  assertEquals("DEVICE_UNREACHABLE",r.reasonCode());
  var future=CompletableFuture.supplyAsync(()->a.open(new OpenAction("o1","c1","1","sec_box:id=65793,reason=3",clock.instant().plusSeconds(5))));
  Thread.sleep(100);
  clock.advance(Duration.ofSeconds(6));
  var endpoint=a.inbound().orElseThrow();
  assertEquals(0,endpoint.handle(req("GET","/push",Map.of("deviceId","1","uuid","u9"),""),ctx(false)).body().length,"vencida não é entregue");
  assertEquals(OpenOutcome.NOT_SENT,future.get().outcome());
 }

 @Test void modoProResponde7ComAcaoOu6SemAcao() {
  var a=push(new TestSupport.TestClock());
  var endpoint=a.inbound().orElseThrow();
  var allow=Json.map(Json.object(new String(endpoint.handle(req("POST","/new_user_identified.fcgi",Map.of(),"user_id=7&portal_id=1&event=7"),ctx(true)).body())).get("result"));
  assertEquals(7L,allow.get("event"));
  assertEquals(1,Json.list(allow.get("actions")).size());
  var deny=Json.map(Json.object(new String(endpoint.handle(req("POST","/new_user_identified.fcgi",Map.of(),"user_id=7&portal_id=1"),ctx(false)).body())).get("result"));
  assertEquals(6L,deny.get("event"));
  assertTrue(Json.list(deny.get("actions")).isEmpty());
 }

 @Test void manifestoDeclaraFontesEPendencias() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var m=lan(fake,new TestSupport.TestClock(),null).manifest();
   for(var s:m.operations().values()) if(s.implemented()) assertFalse(s.sources().isEmpty(),s.operation().name());
   assertFalse(m.supports(Operation.IDENTIFY));
   assertNull(m.documented().get(CapabilityName.localValidity),"desconhecido nunca vira true");
  }
 }

 static void assertArrayEquals(byte[] e,byte[] a) { assertTrue(java.util.Arrays.equals(e,a),"bytes diferentes"); }

 // ----- achados de revisão (#130) -----

 @Test void pinExistenteESubstituidoPorModifyEConfirmadoPeloValor() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   store.objects.computeIfAbsent("users",k->new ArrayList<>()).add(new LinkedHashMap<>(Map.of("id",7L,"registration","ref-7","name","M")));
   var a=lan(fake,new TestSupport.TestClock(),null);
   assertEquals(OperationState.APPLIED_UNVERIFIED,a.setCredential(new CredentialSpec("ref-7",CredentialKind.PIN,"1234")).state());
   var r=a.setCredential(new CredentialSpec("ref-7",CredentialKind.PIN,"5678"));
   assertEquals("REPLACED",r.reasonCode());
   assertEquals(1,fake.requests("/modify_objects.fcgi").size());
   assertEquals(1,store.objects.get("pins").size());
   assertEquals(java.util.Optional.of(true),a.credentialPresent(new CredentialSpec("ref-7",CredentialKind.PIN,"5678")));
   assertEquals(java.util.Optional.of(false),a.credentialPresent(new CredentialSpec("ref-7",CredentialKind.PIN,"1234")));
  }
 }

 @Test void prazoRevalidadoAntesDoEnvioMesmoDepoisDeRenovarSessao() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var clock=new TestSupport.TestClock();
   var logins=new int[1];
   fake.on("POST","/login.fcgi",r->{ if(++logins[0]>1) clock.advance(Duration.ofSeconds(6)); return Reply.json("{\"session\":\"s"+logins[0]+"\"}"); });
   var executes=new int[1];
   fake.on("POST","/execute_actions.fcgi",r->++executes[0]==1?Reply.status(401):Reply.text(""));
   var a=lan(fake,clock,"door:door=1");
   var expired=a.open(new OpenAction("o0","c1","1","door:door=1",clock.instant().minusSeconds(1)));
   assertEquals(OpenOutcome.NOT_SENT,expired.outcome());
   assertEquals(0,fake.requests("/execute_actions.fcgi").size(),"vencida não chega ao equipamento");
   var r=a.open(new OpenAction("o1","c1","1","door:door=1",clock.instant().plusSeconds(5)));
   assertEquals(OpenOutcome.NOT_SENT,r.outcome(),"renovação de sessão consumiu o prazo");
   assertEquals("EXPIRED_BEFORE_SEND",r.reasonCode());
   assertEquals(1,fake.requests("/execute_actions.fcgi").size(),"recusado por 401 e não reenviado após vencer");
  }
 }

 @Test void pushSemResultadoNoPrazoNaoVazaComandoEntregue() throws Exception {
  var a=push(new TestSupport.TestClock());
  var endpoint=a.inbound().orElseThrow();
  var future=CompletableFuture.supplyAsync(()->a.removeFace("ref-7"));
  InboundEndpoint.Response delivered=null;
  for(int i=0;i<50 && (delivered==null || delivered.body().length==0);i++) {
   Thread.sleep(10);
   delivered=endpoint.handle(req("GET","/push",Map.of("deviceId","1","uuid","u5"),""),ctx(false));
  }
  assertEquals(1,a.pushAwaitingResults());
  var r=future.get();
  assertEquals(OperationState.UNKNOWN,r.state(),"entregue sem resultado: ambíguo, nada reenviado");
  assertEquals(0,a.pushAwaitingResults());
  assertEquals(0,endpoint.handle(req("POST","/result",Map.of("deviceId","1","uuid","u5"),"{\"response\":{}}"),ctx(false)).body().length);
 }

 // ----- terceira rodada (#130) -----

 @Test void fotoQueEstouraORequestDeDoisMbERecusadaSemEnviar() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   store.objects.computeIfAbsent("users",k->new ArrayList<>()).add(new LinkedHashMap<>(Map.of("id",7L,"registration","ref-7","name","M")));
   var a=lan(fake,new TestSupport.TestClock(),null);
   byte[] big=new byte[1_600_000];
   big[0]=(byte)0xFF; big[1]=(byte)0xD8;
   var r=a.enrollFace(new FaceSpec("ref-7",big));
   assertEquals("PHOTO_TOO_LARGE_FOR_REQUEST",r.reasonCode(),"1,6 MB vira ~2,1 MB em base64");
   assertEquals(0,fake.requests("/user_set_image_list.fcgi").size());
  }
 }

 @Test void idDaSecBoxValidoPelaFaixaDe64Bits() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   fake.on("POST","/login.fcgi",Reply.json("{\"session\":\"s\"}"));
   fake.on("POST","/execute_actions.fcgi",Reply.text(""));
   var clock=new TestSupport.TestClock();
   var a=lan(fake,clock,null);
   assertEquals(OpenOutcome.ACCEPTED,a.open(new OpenAction("o1","c1","1","sec_box:id=122641794705017745,reason=3",clock.instant().plusSeconds(5))).outcome());
   assertEquals("{\"actions\":[{\"action\":\"sec_box\",\"parameters\":\"id=122641794705017745, reason=3\"}]}",fake.requests("/execute_actions.fcgi").getLast().text());
   assertEquals(OpenOutcome.NOT_SENT,a.open(new OpenAction("o2","c1","1","sec_box:id=18446744073709551616,reason=3",clock.instant().plusSeconds(5))).outcome());
   assertEquals(1,fake.requests("/execute_actions.fcgi").size());
  }
 }

 @Test void leituraDaPessoaTrazOsCamposAplicados() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=lan(fake,new TestSupport.TestClock(),null);
   var person=new PersonSpec("ref-20","Morador 20",null,null);
   a.upsertPerson(person);
   assertEquals(a.personAttributes(person).orElseThrow(),a.readState("ref-20").personAttributes());
   a.upsertPerson(new PersonSpec("ref-20","Morador 20 B",null,null));
   assertNotEquals(a.personAttributes(person).orElseThrow(),a.readState("ref-20").personAttributes());
  }
 }

 // ----- quinta rodada (#130) -----

 @Test void respostaSemOCampoDoObjetoNuncaProvaAusencia() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   fake.on("POST","/login.fcgi",Reply.json("{\"session\":\"s\"}"));
   fake.on("POST","/load_objects.fcgi",Reply.json("{}"));
   fake.on("POST","/destroy_objects.fcgi",Reply.json("{\"changes\":0}"));
   var a=lan(fake,new TestSupport.TestClock(),null);
   var state=a.readState("ref-1");
   assertEquals(OperationState.UNKNOWN,state.state());
   assertNull(state.personPresent());
   assertNotEquals(OperationState.CONFIRMED,a.removeFace("ref-1").state(),"sem prova de ausência não confirma retirada");
   assertNotEquals(OperationState.CONFIRMED,a.revokeRights("ref-1").state());
   assertEquals(OperationState.UNKNOWN,a.fetchEvents("1",10).state());
   assertEquals("1",a.fetchEvents("1",10).nextCursor());
  }
 }

 // ----- sexta rodada (#129 propagado) -----

 @Test void pessoaAusenteNaoProvaAusenciaDeRegistrosVinculados() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   store.objects.computeIfAbsent("cards",k->new ArrayList<>()).add(new LinkedHashMap<>(Map.of("id",1L,"value",123L,"user_id",99L)));
   var a=lan(fake,new TestSupport.TestClock(),null);
   var state=a.readState("ref-sem-usuario");
   assertEquals(Boolean.FALSE,state.personPresent());
   assertNull(state.credentialCounts(),"cartão órfão possível: contagem desconhecida, não zero");
   assertNull(state.rightKeys());
   assertNotEquals(OperationState.CONFIRMED,a.removeCredential("ref-sem-usuario",CredentialKind.CARD).state());
   assertFalse(a.removePersonCoversCredentials());
  }
 }

 // ----- sétima rodada (#130) -----

 @Test void portalPresenteEInvalidoNegaSemCairNoCanalUnico() {
  var a=push(new TestSupport.TestClock());
  var endpoint=a.inbound().orElseThrow();
  var asked=new ArrayList<String>();
  InboundEndpoint.Context allowAll=new InboundEndpoint.Context() {
   @Override public Instant now() { return Instant.now(); }
   @Override public InboundEndpoint.LocalDecision decide(String user,String channel) { asked.add(user+"@"+channel); return new InboundEndpoint.LocalDecision(true,List.of("ALLOWED"),"ref-7","c1"); }
   @Override public void events(List<EdgeEvent> events) {}
   @Override public void heartbeat() {}
  };
  for(String form:List.of("user_id=7&portal_id=invalid","user_id=x7&portal_id=1")) {
   var r=Json.map(Json.object(new String(endpoint.handle(req("POST","/new_user_identified.fcgi",Map.of(),form),allowAll).body())).get("result"));
   assertEquals(6L,r.get("event"),form);
   assertTrue(Json.list(r.get("actions")).isEmpty());
  }
  assertTrue(asked.isEmpty(),"nem consulta a decisão com campo inválido");
  var ok=Json.map(Json.object(new String(endpoint.handle(req("POST","/new_user_identified.fcgi",Map.of(),"user_id=7"),allowAll).body())).get("result"));
  assertEquals(7L,ok.get("event"),"portal ausente segue a regra do canal único");
 }
}
