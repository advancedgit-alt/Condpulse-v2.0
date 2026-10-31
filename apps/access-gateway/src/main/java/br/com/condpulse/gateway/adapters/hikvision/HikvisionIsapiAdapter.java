package br.com.condpulse.gateway.adapters.hikvision;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.BaseAdapter;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Adaptador Hikvision por ISAPI embarcado (AC-031), com base exclusiva na documentação oficial pública de protocolo
 * de acesso MinMoe (catálogo HK12, open.hikvision.com, consultada em 31/10/2026). Essa fonte cobre pessoa
 * (UserInfo Search/Record/Modify/Count), exclusão de pessoa e permissões com progresso (UserInfoDetail/Delete e
 * DeleteProcess) e cartão (CardInfo Record/Delete). Não cobre — e portanto não estão implementados —
 * abertura remota (RemoteControl/door), busca de eventos (AcsEvent), identificação do equipamento (deviceInfo),
 * remoteCheck, envio de foto em binário (FaceDataRecord aparece truncado; FDSetUp só aceita faceURL), PIN e QR.
 * O modo nuvem (ISUP/EHome) também não está na fonte pública consultada. A fonte é do portal chinês e não comprova
 * disponibilidade em todo SKU brasileiro (manual 22.2): cada combinação exige bancada.
 */
public final class HikvisionIsapiAdapter extends BaseAdapter {
 static final String ADAPTER_VERSION="hikvision-isapi-1.0.0";
 static final String HK12="https://open.hikvision.com/hardware/v2/08%E5%8D%8F%E8%AE%AE%E9%80%8F%E4%BC%A0/%E6%98%8E%E7%9C%B8%E9%97%A8%E7%A6%81.html";
 static final Source COUNT=new Source("HK12-3",HK12,"§3 GET /ISAPI/AccessControl/UserInfo/Count?format=json");
 static final Source SEARCH=new Source("HK12-12",HK12,"§12 POST /ISAPI/AccessControl/UserInfo/Search?format=json");
 static final Source RECORD=new Source("HK12-14",HK12,"§14 POST /ISAPI/AccessControl/UserInfo/Record?format=json");
 static final Source MODIFY=new Source("HK12-15",HK12,"§15 PUT /ISAPI/AccessControl/UserInfo/Modify?format=json");
 static final Source DELETE=new Source("HK12-16",HK12,"§16 PUT /ISAPI/AccessControl/UserInfoDetail/Delete?format=json");
 static final Source PROCESS=new Source("HK12-17",HK12,"§17 GET /ISAPI/AccessControl/UserInfoDetail/DeleteProcess?format=json");
 static final Source CARD_RECORD=new Source("HK12-21",HK12,"§21 POST /ISAPI/AccessControl/CardInfo/Record?format=json");
 static final Source CARD_DELETE=new Source("HK12-23",HK12,"§23 PUT /ISAPI/AccessControl/CardInfo/Delete?format=json");

 static final String P_COUNT="/ISAPI/AccessControl/UserInfo/Count", P_SEARCH="/ISAPI/AccessControl/UserInfo/Search",
  P_RECORD="/ISAPI/AccessControl/UserInfo/Record", P_MODIFY="/ISAPI/AccessControl/UserInfo/Modify",
  P_DELETE="/ISAPI/AccessControl/UserInfoDetail/Delete", P_PROCESS="/ISAPI/AccessControl/UserInfoDetail/DeleteProcess",
  P_CARD_RECORD="/ISAPI/AccessControl/CardInfo/Record", P_CARD_DELETE="/ISAPI/AccessControl/CardInfo/Delete";
 private static final Map<String,String> JSON_FORMAT=Map.of("format","json");
 private static final DateTimeFormatter LOCAL=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

 private final DeviceHttpClient http;
 private final int deleteProgressPolls;

 public HikvisionIsapiAdapter(DeviceContext context) {
  super(context);
  this.http=context.device().connectionMode()==ConnectionMode.LOCAL
   ?context.http(Set.of(P_COUNT,P_SEARCH,P_RECORD,P_MODIFY,P_DELETE,P_PROCESS,P_CARD_RECORD,P_CARD_DELETE)):null;
  String polls=context.device().param("hikvision.deleteProgressPolls");
  this.deleteProgressPolls=polls!=null && polls.matches("\\d{1,2}")?Integer.parseInt(polls):10;
 }

 @Override public Manifest manifest() {
  var ops=new EnumMap<Operation,OperationSupport>(Operation.class);
  var caps=new EnumMap<CapabilityName,Boolean>(CapabilityName.class);
  if(http==null) {
   // Modo nuvem: ISUP/EHome não consta da documentação pública oficial consultada.
   return new Manifest(Brand.HIKVISION,ADAPTER_VERSION,device().connectionMode(),ops,caps);
  }
  ops.put(Operation.UPSERT_PERSON,OperationSupport.implemented(Operation.UPSERT_PERSON,List.of(SEARCH,RECORD,MODIFY),
   "Validade local (Valid.beginTime/endTime, timeType local) no fuso do condomínio; validade parcial recusada.","Pessoa criada sem doorRight/RightPlan."));
  ops.put(Operation.REMOVE_PERSON,OperationSupport.implemented(Operation.REMOVE_PERSON,List.of(DELETE,PROCESS,SEARCH),
   "Exclusão assíncrona: consulta o progresso e confirma por busca NO MATCH."));
  ops.put(Operation.SET_CARD,OperationSupport.implemented(Operation.SET_CARD,List.of(CARD_RECORD),"cardType normalCard."));
  ops.put(Operation.REMOVE_CARD,OperationSupport.implemented(Operation.REMOVE_CARD,List.of(CARD_DELETE),"Remove todos os cartões da pessoa (EmployeeNoList)."));
  ops.put(Operation.APPLY_RIGHTS,OperationSupport.implemented(Operation.APPLY_RIGHTS,List.of(MODIFY,SEARCH),
   "doorRight e RightPlan[doorNo, planTemplateNo]; modelos de plano comissionados no equipamento.",
   "Combinação de planos sem união permissiva exige ensaio (manual 22.2)."));
  ops.put(Operation.REVOKE_RIGHTS,OperationSupport.implemented(Operation.REVOKE_RIGHTS,List.of(DELETE,PROCESS,SEARCH),
   "A fonte não documenta o efeito de doorRight vazio; a revogação exclui pessoa e permissões (UserInfoDetail/Delete)."));
  ops.put(Operation.READ_STATE,OperationSupport.implemented(Operation.READ_STATE,List.of(SEARCH),
   "Lê pessoa, doorRight e RightPlan; contagem de cartões e face não lidas (null)."));
  ops.put(Operation.HEALTH,OperationSupport.implemented(Operation.HEALTH,List.of(COUNT),"Sonda de alcance; relógio e firmware não lidos."));
  for(Operation op:List.of(Operation.ENROLL_FACE,Operation.REMOVE_FACE,Operation.OPEN,Operation.FETCH_EVENTS,Operation.RECEIVE_EVENTS,
      Operation.IDENTIFY,Operation.ONLINE_DECISION,Operation.SET_PIN,Operation.REMOVE_PIN,Operation.SET_QR,Operation.REMOVE_QR))
   ops.put(op,OperationSupport.missing(op,"NAO_SUPORTADO_SEM_DOCUMENTACAO: ausente na fonte pública HK12 consultada; obter guia TPP aplicável (HK10)."));
  caps.put(CapabilityName.perDoorRights,true);
  caps.put(CapabilityName.localValidity,true);
  return new Manifest(Brand.HIKVISION,ADAPTER_VERSION,device().connectionMode(),ops,caps);
 }

 private boolean local() { return http!=null; }

 // ----- pessoa -----

 @Override public OperationResult upsertPerson(PersonSpec person) {
  if(!local()) return OperationResult.notSupported(Operation.UPSERT_PERSON);
  // Validade parcial (só início ou só fim) não é representável em Valid: recusada sem envio.
  if((person.validFrom()==null)!=(person.validUntil()==null)) return OperationResult.of(OperationState.FAILED,"VALIDITY_NOT_REPRESENTABLE");
  try {
   var existing=search(person.subjectRef());
   if(existing.isEmpty()) return status(send("POST",P_RECORD,Map.of("UserInfo",userInfo(person))));
   var info=userInfo(person);
   // Modificação preserva direitos atuais: só nome e validade mudam aqui.
   return status(send("PUT",P_MODIFY,Map.of("UserInfo",info)));
  } catch(DeviceCallException e) { return failure(e); }
 }

 Map<String,Object> userInfo(PersonSpec person) {
  var info=new LinkedHashMap<String,Object>();
  info.put("employeeNo",person.subjectRef());
  info.put("name",person.displayName());
  info.put("userType","normal");
  if(person.validFrom()!=null && person.validUntil()!=null) info.put("Valid",valid(person.validFrom(),person.validUntil()));
  return info;
 }

 /** Campos gravados por upsertPerson: nome e, quando houver, validade (início/fim). */
 @Override public Optional<Map<String,String>> personAttributes(PersonSpec person) {
  if((person.validFrom()==null)!=(person.validUntil()==null)) return Optional.empty();
  var out=new java.util.HashMap<String,String>();
  out.put("name",person.displayName());
  if(person.validFrom()!=null) out.put("valid",validKey(person.validFrom(),person.validUntil()));
  return Optional.of(out);
 }

 @Override public OperationResult removePerson(String subjectRef) {
  if(!local()) return OperationResult.notSupported(Operation.REMOVE_PERSON);
  try {
   if(search(subjectRef).isEmpty()) return OperationResult.of(OperationState.CONFIRMED,"PERSON_ABSENT");
   var body=Map.<String,Object>of("UserInfoDetail",Map.of("mode","byEmployeeNo","EmployeeNoList",List.of(Map.of("employeeNo",subjectRef))));
   var started=status(send("PUT",P_DELETE,body));
   if(started.state()!=OperationState.ACK) return started;
   for(int i=0;i<deleteProgressPolls;i++) {
    var response=http.call("GET",P_PROCESS,JSON_FORMAT,null,null,false);
    if(!response.ok()) return OperationResult.of(OperationState.UNKNOWN,"HIKVISION_HTTP_"+response.status());
    String status=Json.string(Json.map(Json.object(response.text()).get("UserInfoDetailDeleteProcess")),"status");
    if("success".equals(status)) return search(subjectRef).isEmpty()?OperationResult.of(OperationState.CONFIRMED,null):OperationResult.of(OperationState.UNKNOWN,"STILL_PRESENT_AFTER_DELETE");
    if("failed".equals(status)) return OperationResult.of(OperationState.FAILED,"HIKVISION_DELETE_FAILED");
    pause();
   }
   // Ainda em processamento após as consultas: resultado incerto; a fila guarda NEEDS_READBACK e acompanha.
   return OperationResult.of(OperationState.UNKNOWN,"DELETE_IN_PROGRESS");
  } catch(DeviceCallException e) { return failure(e); }
 }

 @Override public OperationResult revokeRights(String subjectRef) {
  if(!local()) return OperationResult.notSupported(Operation.REVOKE_RIGHTS);
  return removePerson(subjectRef);
 }

 // ----- cartão -----

 @Override public OperationResult setCredential(CredentialSpec credential) {
  if(!local() || credential.kind()!=CredentialKind.CARD) return super.setCredential(credential);
  try {
   if(search(credential.subjectRef()).isEmpty()) return OperationResult.of(OperationState.FAILED,"PERSON_NOT_ON_DEVICE");
   var card=new LinkedHashMap<String,Object>();
   card.put("employeeNo",credential.subjectRef());
   card.put("cardNo",credential.value());
   card.put("cardType","normalCard");
   return status(send("POST",P_CARD_RECORD,Map.of("CardInfo",card)));
  } catch(DeviceCallException e) { return failure(e); }
 }

 @Override public OperationResult removeCredential(String subjectRef,CredentialKind kind) {
  if(!local() || kind!=CredentialKind.CARD) return super.removeCredential(subjectRef,kind);
  try {
   if(search(subjectRef).isEmpty()) return OperationResult.of(OperationState.CONFIRMED,"PERSON_ABSENT");
   return status(send("PUT",P_CARD_DELETE,Map.of("CardInfoDelCond",Map.of("EmployeeNoList",List.of(Map.of("employeeNo",subjectRef))))));
  } catch(DeviceCallException e) { return failure(e); }
 }

 // ----- direitos -----

 /**
  * Chaves: {@code door:<doorNo>:plan:<planTemplateNo>} e, quando houver validade, {@code valid:<início>/<fim>} em
  * segundos epoch. Validade só é representável com início e fim (Valid.beginTime/endTime, HK12 §14/§15); só um dos
  * dois não é representável e a operação é recusada.
  */
 @Override public Optional<Set<String>> rightKeys(RightsSpec rights) {
  var keys=new HashSet<String>();
  for(Right r:rights.rights()) {
   var channel=device().channel(r.channelId());
   String plan=device().nativeSchedules().get(r.scheduleRef());
   if(channel.isEmpty() || !channel.get().nativeChannel().matches("\\d{1,3}") || plan==null || !plan.matches("\\d{1,4}")) return Optional.empty();
   keys.add("door:"+channel.get().nativeChannel()+":plan:"+plan);
  }
  if((rights.validFrom()==null)!=(rights.validUntil()==null)) return Optional.empty();
  if(rights.validFrom()!=null) keys.add(validKey(rights.validFrom(),rights.validUntil()));
  return Optional.of(keys);
 }

 static String validKey(Instant begin,Instant end) {
  return "valid:"+begin.truncatedTo(ChronoUnit.SECONDS).getEpochSecond()+"/"+end.truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
 }

 /**
  * Direitos e validade no mesmo UserInfo/Modify (§15): no modo DEVICE_LOCAL_ACL é o equipamento que expira o
  * direito. Validade parcial (só início ou só fim) é recusada (VALIDITY_NOT_REPRESENTABLE).
  */
 @Override public OperationResult applyRights(RightsSpec rights) {
  if(!local()) return OperationResult.notSupported(Operation.APPLY_RIGHTS);
  if((rights.validFrom()==null)!=(rights.validUntil()==null)) return OperationResult.of(OperationState.FAILED,"VALIDITY_NOT_REPRESENTABLE");
  var keys=rightKeys(rights);
  if(keys.isEmpty() || keys.get().stream().noneMatch(k->k.startsWith("door:"))) return OperationResult.of(OperationState.FAILED,"RIGHT_NOT_REPRESENTABLE");
  try {
   var existing=search(rights.subjectRef());
   if(existing.isEmpty()) return OperationResult.of(OperationState.FAILED,"PERSON_NOT_ON_DEVICE");
   // Sem prazo pedido, um Valid anterior continuaria valendo; a fonte não documenta como removê-lo: recusa.
   var previous=Json.map(existing.get().get("Valid"));
   if(rights.validFrom()==null && previous!=null && Boolean.TRUE.equals(Json.bool(previous,"enable")))
    return OperationResult.of(OperationState.FAILED,"VALIDITY_CLEAR_NOT_DOCUMENTED");
   var plans=new TreeMap<Integer,TreeSet<Integer>>();
   for(String k:keys.get()) {
    if(!k.startsWith("door:")) continue;
    String[] p=k.split(":");
    plans.computeIfAbsent(Integer.parseInt(p[1]),x->new TreeSet<>()).add(Integer.parseInt(p[3]));
   }
   var info=new LinkedHashMap<String,Object>();
   info.put("employeeNo",rights.subjectRef());
   if(rights.validFrom()!=null) info.put("Valid",valid(rights.validFrom(),rights.validUntil()));
   info.put("doorRight",String.join(",",plans.keySet().stream().map(String::valueOf).toList()));
   var rightPlan=new ArrayList<Object>();
   for(var e:plans.entrySet()) {
    var plan=new LinkedHashMap<String,Object>();
    plan.put("doorNo",e.getKey());
    plan.put("planTemplateNo",String.join(",",e.getValue().stream().map(String::valueOf).toList()));
    rightPlan.add(plan);
   }
   info.put("RightPlan",rightPlan);
   return status(send("PUT",P_MODIFY,Map.of("UserInfo",info)));
  } catch(DeviceCallException e) { return failure(e); }
 }

 private Map<String,Object> valid(Instant begin,Instant end) {
  var valid=new LinkedHashMap<String,Object>();
  valid.put("enable",true);
  valid.put("beginTime",LOCAL.format(begin.truncatedTo(ChronoUnit.SECONDS).atZone(context.zone())));
  valid.put("endTime",LOCAL.format(end.truncatedTo(ChronoUnit.SECONDS).atZone(context.zone())));
  valid.put("timeType","local");
  return valid;
 }

 /**
  * Lê doorRight, RightPlan e Valid. RightPlan ausente não prova ausência de acesso: a fonte de verdade é doorRight.
  * Sem doorRight, doorRight vazio, porta sem plano lido ou validade ilegível, rightKeys é null (desconhecido) — um
  * conjunto vazio nunca é inferido; a revogação só se confirma pela pessoa ausente (busca NO MATCH).
  */
 @Override public AppliedState readState(String subjectRef) {
  if(!local()) return super.readState(subjectRef);
  try {
   var found=search(subjectRef);
   if(found.isEmpty()) return new AppliedState(OperationState.CONFIRMED,null,false,Set.of(),null,null);
   var user=found.get();
   var attributes=new java.util.HashMap<String,String>();
   if(Json.string(user,"name")!=null) attributes.put("name",Json.string(user,"name"));
   var valid=Json.map(user.get("Valid"));
   if(valid!=null && Boolean.TRUE.equals(Json.bool(valid,"enable"))) {
    try { attributes.put("valid",validKey(parseValid(Json.string(valid,"beginTime"),Json.string(valid,"timeType")),
     parseValid(Json.string(valid,"endTime"),Json.string(valid,"timeType")))); }
    catch(RuntimeException e) { attributes=null; }
   }
   return new AppliedState(OperationState.CONFIRMED,null,true,observedKeys(user),null,null,attributes);
  } catch(DeviceCallException e) {
   return AppliedState.unavailable(e.failure()==DeviceHttpClient.Failure.NOT_SENT?OperationState.FAILED:OperationState.UNKNOWN,e.getMessage());
  } catch(Json.JsonException e) { return AppliedState.unavailable(OperationState.FAILED,"INVALID_RESPONSE"); }
 }

 Set<String> observedKeys(Map<String,Object> user) {
  String doorRight=Json.string(user,"doorRight");
  if(doorRight==null || doorRight.isBlank()) return null;
  var plansByDoor=new java.util.HashMap<String,List<String>>();
  var plans=Json.list(user.get("RightPlan"));
  if(plans!=null) for(Object o:plans) {
   var p=Json.map(o);
   Long door=Json.longValue(p,"doorNo");
   String templates=Json.string(p,"planTemplateNo");
   if(door==null || templates==null) return null;
   var list=new ArrayList<String>();
   for(String t:templates.split(",")) if(!t.isBlank()) list.add(t.trim());
   plansByDoor.put(String.valueOf(door),list);
  }
  var keys=new HashSet<String>();
  for(String door:doorRight.split(",")) {
   String d=door.trim();
   if(!d.matches("\\d{1,3}")) return null;
   var list=plansByDoor.get(d);
   if(list==null || list.isEmpty()) return null; // porta com direito e plano não lido: desconhecido
   for(String t:list) keys.add("door:"+d+":plan:"+t);
  }
  var valid=Json.map(user.get("Valid"));
  if(valid!=null && Boolean.TRUE.equals(Json.bool(valid,"enable"))) {
   String type=Json.string(valid,"timeType");
   try { keys.add(validKey(parseValid(Json.string(valid,"beginTime"),type),parseValid(Json.string(valid,"endTime"),type))); }
   catch(RuntimeException e) { return null; }
  }
  return keys;
 }

 @Override public HealthReport health() {
  if(!local()) return super.health();
  try {
   var response=http.call("GET",P_COUNT,JSON_FORMAT,null,null,false);
   if(!response.ok()) return new HealthReport(false,"HIKVISION_HTTP_"+response.status(),null,null,null);
   Long users=Json.longValue(Json.map(Json.object(response.text()).get("UserInfoCount")),"userNumber");
   return new HealthReport(users!=null,users==null?"INVALID_RESPONSE":null,null,null,null);
  } catch(DeviceCallException e) { return new HealthReport(false,e.getMessage(),null,null,null); }
  catch(RuntimeException e) { return new HealthReport(false,"INVALID_RESPONSE",null,null,null); }
 }

 // ----- utilitários -----

 Optional<Map<String,Object>> search(String employeeNo) throws DeviceCallException {
  var cond=new LinkedHashMap<String,Object>();
  cond.put("searchID",UUID.randomUUID().toString());
  cond.put("searchResultPosition",0);
  cond.put("maxResults",1);
  cond.put("EmployeeNoList",List.of(Map.of("employeeNo",employeeNo)));
  var response=http.call("POST",P_SEARCH,JSON_FORMAT,"application/json",Json.write(Map.of("UserInfoSearchCond",cond)).getBytes(StandardCharsets.UTF_8),false);
  if(!response.ok()) throw new DeviceCallException(DeviceHttpClient.Failure.AMBIGUOUS,"HIKVISION_SEARCH_HTTP_"+response.status());
  Map<String,Object> result;
  try { result=Json.map(Json.object(response.text()).get("UserInfoSearch")); }
  catch(RuntimeException e) { throw new DeviceCallException(DeviceHttpClient.Failure.AMBIGUOUS,"SEARCH_INCONCLUSIVE"); }
  if(result==null) throw new DeviceCallException(DeviceHttpClient.Failure.AMBIGUOUS,"SEARCH_INCONCLUSIVE");
  String status=Json.string(result,"responseStatusStrg");
  var users=Json.list(result.get("UserInfo"));
  Map<String,Object> match=null;
  if(users!=null) for(Object o:users) { var u=Json.map(o); if(employeeNo.equals(Json.string(u,"employeeNo"))) match=u; }
  // Ausência só com prova: "NO MATCH" coerente (sem contagens positivas nem a pessoa na lista).
  if("NO MATCH".equals(status)) {
   if(match!=null || positive(result,"numOfMatches") || positive(result,"totalMatches")) throw new DeviceCallException(DeviceHttpClient.Failure.AMBIGUOUS,"SEARCH_INCONCLUSIVE");
   return Optional.empty();
  }
  // "OK"/"MORE" sem a pessoa pedida (lista vazia, outra pessoa, contagem sem registro) é leitura indisponível.
  if(("OK".equals(status) || "MORE".equals(status)) && match!=null) return Optional.of(match);
  throw new DeviceCallException(DeviceHttpClient.Failure.AMBIGUOUS,"SEARCH_INCONCLUSIVE");
 }

 private static boolean positive(Map<String,Object> m,String key) {
  try { Long v=Json.longValue(m,key); return v!=null && v>0; } catch(RuntimeException e) { return true; }
 }

 private DeviceHttpClient.Response send(String method,String path,Map<String,Object> body) throws DeviceCallException {
  return http.call(method,path,JSON_FORMAT,"application/json",Json.write(body).getBytes(StandardCharsets.UTF_8),false);
 }

 /** ResponseStatus (statusCode 1 / statusString "ok"): ACK; o efeito é confirmado pela leitura de volta. */
 static OperationResult status(DeviceHttpClient.Response response) {
  Map<String,Object> body;
  try { body=response.body().length==0?Map.of():Json.object(response.text()); }
  catch(Json.JsonException e) { return OperationResult.of(response.ok()?OperationState.UNKNOWN:OperationState.FAILED,"INVALID_RESPONSE"); }
  Long code=Json.longValue(body,"statusCode");
  if(response.ok() && (code==null || code==1)) return OperationResult.of(OperationState.ACK,null);
  String sub=Json.string(body,"subStatusCode");
  return OperationResult.of(OperationState.FAILED,"HIKVISION_"+(sub==null?"HTTP_"+response.status():sub.replaceAll("[^A-Za-z0-9_]","_")));
 }

 private static void pause() {
  try { Thread.sleep(200); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
 }

 /**
  * Horário do Valid (HK12 §14/§15): com offset é absoluto; sem offset e timeType "local" (ou ausente), é horário
  * local do equipamento, interpretado no fuso do condomínio. Outro timeType sem offset não é interpretado.
  */
 Instant parseValid(String text,String timeType) {
  if(text==null) throw new IllegalArgumentException("Horário ausente.");
  if(text.matches(".*(Z|[+-]\\d{2}:\\d{2})$")) return java.time.OffsetDateTime.parse(text).toInstant();
  if(timeType!=null && !"local".equals(timeType)) throw new IllegalArgumentException("timeType sem offset não interpretável.");
  return java.time.LocalDateTime.parse(text).atZone(context.zone()).toInstant();
 }
}
