package br.com.condpulse.gateway.adapters;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import br.com.condpulse.gateway.adapters.hikvision.HikvisionIsapiAdapter;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.FakeDeviceServer;
import br.com.condpulse.gateway.support.FakeDeviceServer.Reply;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contrato Hikvision ISAPI (HK12 §3, §12, §14–§17, §21, §23) contra servidor falso com Digest. Não é homologação. */
class HikvisionIsapiAdapterContractTest {
 private static final String OK="{\"statusCode\":1,\"statusString\":\"ok\",\"subStatusCode\":\"ok\"}";

 /** Pessoas do equipamento em memória, no formato UserInfoSearch documentado. */
 static final class Store {
  final Map<String,Map<String,Object>> users=new LinkedHashMap<>();
  String deleteStatus="success";
  void install(FakeDeviceServer fake) {
   fake.on("POST","/ISAPI/AccessControl/UserInfo/Search",r->{
    var cond=Json.map(Json.object(r.text()).get("UserInfoSearchCond"));
    String emp=Json.string(Json.map(Json.list(cond.get("EmployeeNoList")).getFirst()),"employeeNo");
    var u=users.get(emp);
    var result=new LinkedHashMap<String,Object>();
    result.put("searchID",Json.string(cond,"searchID"));
    result.put("responseStatusStrg",u==null?"NO MATCH":"OK");
    result.put("numOfMatches",u==null?0:1);
    result.put("totalMatches",u==null?0:1);
    if(u!=null) result.put("UserInfo",List.of(u));
    return Reply.json(Json.write(Map.of("UserInfoSearch",result)));
   });
   fake.on("POST","/ISAPI/AccessControl/UserInfo/Record",r->{
    var info=Json.map(Json.object(r.text()).get("UserInfo"));
    users.put(Json.string(info,"employeeNo"),new LinkedHashMap<>(info));
    return Reply.json(OK);
   });
   fake.on("PUT","/ISAPI/AccessControl/UserInfo/Modify",r->{
    var info=Json.map(Json.object(r.text()).get("UserInfo"));
    users.get(Json.string(info,"employeeNo")).putAll(info);
    return Reply.json(OK);
   });
   fake.on("PUT","/ISAPI/AccessControl/UserInfoDetail/Delete",r->{
    var d=Json.map(Json.object(r.text()).get("UserInfoDetail"));
    assertEquals("byEmployeeNo",Json.string(d,"mode"),"nunca apaga todos");
    if(deleteStatus.equals("success")) for(Object o:Json.list(d.get("EmployeeNoList"))) users.remove(Json.string(Json.map(o),"employeeNo"));
    return Reply.json(OK);
   });
   fake.on("GET","/ISAPI/AccessControl/UserInfoDetail/DeleteProcess",r->Reply.json("{\"UserInfoDetailDeleteProcess\":{\"status\":\""+deleteStatus+"\"}}"));
   fake.on("POST","/ISAPI/AccessControl/CardInfo/Record",r->Reply.json(OK));
   fake.on("PUT","/ISAPI/AccessControl/CardInfo/Delete",r->Reply.json(OK));
   fake.on("GET","/ISAPI/AccessControl/UserInfo/Count",r->Reply.json("{\"UserInfoCount\":{\"userNumber\":"+users.size()+"}}"));
  }
 }

 private static HikvisionIsapiAdapter adapter(FakeDeviceServer fake) {
  var device=TestSupport.device("hik",Brand.HIKVISION,ConnectionMode.LOCAL,DecisionMode.DEVICE_LOCAL_ACL,fake.baseUrl(),
   List.of(TestSupport.channel("c1","1",null),TestSupport.channel("c3","3",null)),Map.of("sempre","1","comercial","3"),Map.of("hikvision.deleteProgressPolls","2"),false);
  return new HikvisionIsapiAdapter(TestSupport.context(device,new TestSupport.TestClock()));
 }

 @Test void pessoaComValidadeLocalDireitosPorPortaELeituraDeVolta() throws Exception {
  try(var fake=new FakeDeviceServer().requireDigest("hik-sintetico")) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   var person=new PersonSpec("ref-1","Morador 1",Instant.parse("2030-01-01T03:00:00Z"),Instant.parse("2031-01-01T03:00:00Z"));
   assertEquals(OperationState.ACK,a.upsertPerson(person).state());
   var record=Json.map(Json.object(fake.requests("/ISAPI/AccessControl/UserInfo/Record").getLast().text()).get("UserInfo"));
   assertEquals("normal",record.get("userType"));
   var valid=Json.map(record.get("Valid"));
   assertEquals("2030-01-01T00:00:00-03:00",valid.get("beginTime"),"timeType local no fuso do condomínio");
   assertEquals("local",valid.get("timeType"));
   assertFalse(record.containsKey("doorRight"),"cadastro sem direito");
   assertEquals("format=json",fake.requests("/ISAPI/AccessControl/UserInfo/Record").getLast().query());

   var rights=new RightsSpec("ref-1",List.of(new Right("c1","sempre"),new Right("c3","comercial"),new Right("c3","sempre")),
    Instant.parse("2030-01-01T03:00:00Z"),Instant.parse("2031-01-01T03:00:00Z"));
   assertEquals(OperationState.ACK,a.applyRights(rights).state());
   var modify=Json.map(Json.object(fake.requests("/ISAPI/AccessControl/UserInfo/Modify").getLast().text()).get("UserInfo"));
   assertEquals("1,3",modify.get("doorRight"));
   assertEquals(List.of(Map.of("doorNo",1L,"planTemplateNo","1"),Map.of("doorNo",3L,"planTemplateNo","1,3")),modify.get("RightPlan"));
   assertEquals("2031-01-01T00:00:00-03:00",Json.map(modify.get("Valid")).get("endTime"),"validade vai junto com os direitos");
   assertEquals(a.rightKeys(rights).orElseThrow(),a.readState("ref-1").rightKeys());
   assertTrue(a.readState("ref-1").rightKeys().contains("valid:1893466800/1925002800"));
   assertTrue(fake.requests().stream().filter(r->r.authorization()!=null).allMatch(r->r.authorization().startsWith("Digest ")));
  }
 }

 @Test void revogacaoExcluiPessoaComProgressoEConfirmaPorBusca() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-2","Morador 2",null,null));
   var r=a.revokeRights("ref-2");
   assertEquals(OperationState.CONFIRMED,r.state());
   assertEquals(Boolean.FALSE,a.readState("ref-2").personPresent());
   assertEquals("PERSON_ABSENT",a.removeCredential("ref-2",CredentialKind.CARD).reasonCode());
  }
 }

 @Test void exclusaoAindaEmAndamentoFicaIncerta() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-3","Morador 3",null,null));
   store.deleteStatus="processing";
   var r=a.removePerson("ref-3");
   assertEquals(OperationState.UNKNOWN,r.state(),"em processamento: incerto, a fila acompanha com leitura");
   assertEquals("DELETE_IN_PROGRESS",r.reasonCode());
   store.deleteStatus="failed";
   assertEquals("HIKVISION_DELETE_FAILED",a.removePerson("ref-3").reasonCode());
  }
 }

 @Test void cartaoUsaCardInfoEDemaisOperacoesSaoNaoSuportadas() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-4","Morador 4",null,null));
   assertEquals(OperationState.ACK,a.setCredential(new CredentialSpec("ref-4",CredentialKind.CARD,"1234567890")).state());
   var card=Json.map(Json.object(fake.requests("/ISAPI/AccessControl/CardInfo/Record").getLast().text()).get("CardInfo"));
   assertEquals(Map.of("employeeNo","ref-4","cardNo","1234567890","cardType","normalCard"),card);
   int before=fake.requests().size();
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.setCredential(new CredentialSpec("ref-4",CredentialKind.PIN,"1234")).state());
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.enrollFace(new FaceSpec("ref-4",TestSupport.syntheticJpeg())).state());
   assertEquals(OpenOutcome.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.open(new OpenAction("o","c1","1",null,Instant.now().plusSeconds(5))).outcome());
   assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.fetchEvents(null,10).state());
   assertEquals(before,fake.requests().size(),"nenhum endpoint inventado");
   assertTrue(a.health().reachable());
  }
 }

 @Test void falhaDoEquipamentoTrazSubStatus() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   fake.on("POST","/ISAPI/AccessControl/UserInfo/Record",new Reply(400,"application/json",
    "{\"statusCode\":6,\"statusString\":\"Invalid Content\",\"subStatusCode\":\"badJsonContent\"}",Map.of(),0));
   var r=adapter(fake).upsertPerson(new PersonSpec("ref-5","Morador 5",null,null));
   assertEquals(OperationState.FAILED,r.state());
   assertEquals("HIKVISION_badJsonContent",r.reasonCode());
  }
 }

 @Test void modoNuvemNaoTemOperacaoDocumentada() {
  var device=TestSupport.device("hikn",Brand.HIKVISION,ConnectionMode.NUVEM,DecisionMode.DEVICE_LOCAL_ACL,null,List.of(),Map.of(),Map.of(),false);
  var ctx=TestSupport.context(device,new TestSupport.TestClock());
  var a=new HikvisionIsapiAdapter(new br.com.condpulse.gateway.spi.BaseAdapter.DeviceContext(device,ctx.connectorId(),null,ctx.timeout(),ctx.pushPickupTimeout(),ctx.clock(),ctx.zone()));
  var supported=new ArrayList<Operation>();
  for(var s:a.manifest().operations().values()) if(s.implemented()) supported.add(s.operation());
  assertEquals(List.of(),supported);
  assertEquals(OperationState.NAO_SUPORTADO_SEM_DOCUMENTACAO,a.upsertPerson(new PersonSpec("r","n",null,null)).state());
  assertEquals(Set.of(),Set.copyOf(supported));
 }

 // ----- achados de revisão (#131) -----

 @Test void validadeParcialNaoERepresentavelEERecusadaSemEnviar() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-6","Morador 6",null,null));
   int before=fake.requests("/ISAPI/AccessControl/UserInfo/Modify").size();
   var r=a.applyRights(new RightsSpec("ref-6",List.of(new Right("c1","sempre")),null,Instant.parse("2031-01-01T03:00:00Z")));
   assertEquals("VALIDITY_NOT_REPRESENTABLE",r.reasonCode());
   assertEquals(before,fake.requests("/ISAPI/AccessControl/UserInfo/Modify").size());
   assertTrue(a.rightKeys(new RightsSpec("ref-6",List.of(new Right("c1","sempre")),Instant.parse("2030-01-01T03:00:00Z"),null)).isEmpty());
  }
 }

 @Test void validadeDiferenteNoEquipamentoNaoConfirma() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-7","Morador 7",null,null));
   var desired=new RightsSpec("ref-7",List.of(new Right("c1","sempre")),Instant.parse("2030-01-01T03:00:00Z"),Instant.parse("2031-01-01T03:00:00Z"));
   a.applyRights(desired);
   store.users.get("ref-7").put("Valid",Map.of("enable",true,"beginTime","2030-01-01T00:00:00-03:00","endTime","2035-01-01T00:00:00-03:00","timeType","local"));
   assertNotEquals(a.rightKeys(desired).orElseThrow(),a.readState("ref-7").rightKeys());
  }
 }

 @Test void rightPlanAusenteOuDoorRightVazioNaoProvaAusenciaDeAcesso() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-8","Morador 8",null,null));
   assertNull(a.readState("ref-8").rightKeys(),"sem doorRight: desconhecido");
   store.users.get("ref-8").put("doorRight","1");
   assertNull(a.readState("ref-8").rightKeys(),"porta com direito e RightPlan ausente: desconhecido, nunca vazio");
   store.users.get("ref-8").put("doorRight","");
   store.users.get("ref-8").put("RightPlan",List.of());
   assertNull(a.readState("ref-8").rightKeys(),"doorRight vazio não é prova de revogação");
   a.removePerson("ref-8");
   var gone=a.readState("ref-8");
   assertEquals(Boolean.FALSE,gone.personPresent());
   assertEquals(Set.of(),gone.rightKeys());
  }
 }

 @Test void validadeLocalSemOffsetUsaOFusoDoCondominio() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-9","Morador 9",null,null));
   var desired=new RightsSpec("ref-9",List.of(new Right("c1","sempre")),Instant.parse("2030-01-01T03:00:00Z"),Instant.parse("2031-01-01T03:00:00Z"));
   a.applyRights(desired);
   store.users.get("ref-9").put("Valid",Map.of("enable",true,"beginTime","2030-01-01T00:00:00","endTime","2031-01-01T00:00:00","timeType","local"));
   assertEquals(a.rightKeys(desired).orElseThrow(),a.readState("ref-9").rightKeys());
   store.users.get("ref-9").put("Valid",Map.of("enable",true,"beginTime","2030-01-01T00:00:00","endTime","2031-01-01T00:00:00","timeType","UTC"));
   assertNull(a.readState("ref-9").rightKeys(),"timeType não local sem offset não é interpretado");
  }
 }

 // ----- terceira rodada (#131) -----

 @Test void pessoaComValidadeParcialERecusadaSemEnviar() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   var r=a.upsertPerson(new PersonSpec("ref-10","Morador 10",null,Instant.parse("2031-01-01T03:00:00Z")));
   assertEquals("VALIDITY_NOT_REPRESENTABLE",r.reasonCode());
   assertEquals(0,fake.requests().size());
  }
 }

 @Test void direitosSemPrazoNaoDeixamValidAnteriorValendoEmSilencio() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   var person=new PersonSpec("ref-11","Morador 11",Instant.parse("2030-01-01T03:00:00Z"),Instant.parse("2031-01-01T03:00:00Z"));
   a.upsertPerson(person);
   assertEquals(a.personAttributes(person).orElseThrow(),a.readState("ref-11").personAttributes(),"nome e validade lidos de volta");
   int before=fake.requests("/ISAPI/AccessControl/UserInfo/Modify").size();
   var r=a.applyRights(new RightsSpec("ref-11",List.of(new Right("c1","sempre")),null,null));
   assertEquals("VALIDITY_CLEAR_NOT_DOCUMENTED",r.reasonCode());
   assertEquals(before,fake.requests("/ISAPI/AccessControl/UserInfo/Modify").size());
  }
 }

 // ----- quarta rodada (#131) -----

 @Test void buscaContraditoriaNuncaProvaAusenciaNemConfirmaRevogacao() throws Exception {
  for(String body:List.of(
    "{\"UserInfoSearch\":{\"responseStatusStrg\":\"OK\",\"numOfMatches\":1,\"totalMatches\":1}}",
    "{\"UserInfoSearch\":{\"responseStatusStrg\":\"NO MATCH\",\"numOfMatches\":0,\"totalMatches\":1}}",
    "{\"UserInfoSearch\":{\"responseStatusStrg\":\"OK\",\"numOfMatches\":1,\"UserInfo\":[{\"employeeNo\":\"outra\"}]}}",
    "{\"UserInfoSearch\":{\"responseStatusStrg\":\"ESTRANHO\"}}",
    "{}")) {
   try(var fake=new FakeDeviceServer()) {
    fake.on("POST","/ISAPI/AccessControl/UserInfo/Search",Reply.json(body));
    fake.on("PUT","/ISAPI/AccessControl/UserInfoDetail/Delete",Reply.json(OK));
    var a=adapter(fake);
    var state=a.readState("ref-x");
    assertNotEquals(OperationState.CONFIRMED,state.state(),body);
    var removal=a.removePerson("ref-x");
    assertNotEquals(OperationState.CONFIRMED,removal.state(),"revogação não confirma com leitura contraditória: "+body);
    assertEquals(0,fake.requests("/ISAPI/AccessControl/UserInfoDetail/Delete").size(),"sem prova, também não age às cegas");
   }
  }
 }

 // ----- sexta rodada (#131) -----

 @Test void pessoaSemPrazoNaoPreservaJanelaAntigaEmSilencio() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   var store=new Store(); store.install(fake);
   var a=adapter(fake);
   a.upsertPerson(new PersonSpec("ref-12","Morador 12",Instant.parse("2030-01-01T03:00:00Z"),Instant.parse("2031-01-01T03:00:00Z")));
   int before=fake.requests("/ISAPI/AccessControl/UserInfo/Modify").size();
   var semPrazo=new PersonSpec("ref-12","Morador 12",null,null);
   assertEquals("VALIDITY_CLEAR_NOT_DOCUMENTED",a.upsertPerson(semPrazo).reasonCode());
   assertEquals(before,fake.requests("/ISAPI/AccessControl/UserInfo/Modify").size());
   assertNotEquals(a.personAttributes(semPrazo).orElseThrow(),a.readState("ref-12").personAttributes(),"leitura não confirma pessoa sem prazo");
   var nova=new PersonSpec("ref-13","Morador 13",null,null);
   a.upsertPerson(nova);
   assertEquals(a.personAttributes(nova).orElseThrow(),a.readState("ref-13").personAttributes(),"sem Valid: \"none\" lido de volta");
  }
 }
}
