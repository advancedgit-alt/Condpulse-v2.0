package br.com.condpulse.gateway.adapters.controlid;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.inbound.InboundServer;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.BaseAdapter;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Adaptador Control iD (AC-021) para a API de acesso pública (iDFace, iDFace Max, iDBlock). Fontes consultadas em
 * 31/10/2026 (documentação "Access API", versão em inglês do mesmo portal citado no catálogo como CI06–CI13):
 * <ul>
 * <li>CI-LOGIN/CI-SESSION: {@code POST /login.fcgi} → {@code {"session"}}; sessão em {@code ?session=};</li>
 * <li>CI-OBJ: {@code create_objects}, {@code load_objects}, {@code modify_objects}, {@code destroy_objects};</li>
 * <li>CI-LIST: objetos {@code users}, {@code cards}, {@code pins}, {@code qrcodes}, {@code user_access_rules},
 * {@code user_groups}, {@code access_logs};</li>
 * <li>CI-FACE: {@code user_set_image_list.fcgi} (base64, resultado por pessoa) e {@code user_destroy_image.fcgi};</li>
 * <li>CI-OPEN/CI-PARTIC: {@code execute_actions.fcgi} com {@code door}, {@code sec_box} (iDFace) ou {@code catra};</li>
 * <li>CI-ONLINE/CI-MODES: modo Pro, {@code POST /new_user_identified.fcgi} com resposta {@code result} do servidor;
 * contingência após três falhas e {@code POST /device_is_alive.fcgi};</li>
 * <li>CI-PUSH: {@code GET /push} e {@code POST /result} para o modo nuvem.</li>
 * </ul>
 * Não implementado por falta de documentação consultada: identificação de firmware/série
 * ({@code system_information}), unidade de {@code users.begin_time/end_time} e de {@code access_logs.time}, formato
 * das notificações do Monitor e leitura de presença de face.
 */
public final class ControlIdAdapter extends BaseAdapter {
 static final String ADAPTER_VERSION="controlid-1.0.0";
 static final String DOCS="https://www.controlid.com.br/docs/access-api-en/";
 static final Source LOGIN=new Source("CI-LOGIN",DOCS+"session-management/do-login/","POST /login.fcgi");
 static final Source SESSION=new Source("CI-SESSION",DOCS+"session-management/check-session-validity/","POST /session_is_valid.fcgi");
 static final Source CREATE=new Source("CI-OBJ-CREATE",DOCS+"objects/create-objects/","POST /create_objects.fcgi");
 static final Source LOAD=new Source("CI-OBJ-LOAD",DOCS+"objects/load-objects/","POST /load_objects.fcgi (where, limit)");
 static final Source MODIFY=new Source("CI-OBJ-MODIFY",DOCS+"objects/modify-objects/","POST /modify_objects.fcgi");
 static final Source DESTROY=new Source("CI-OBJ-DESTROY",DOCS+"objects/destroy-objects/","POST /destroy_objects.fcgi");
 static final Source LIST=new Source("CI-LIST",DOCS+"objects/list-of-objects/","users, cards, pins, qrcodes, user_access_rules, user_groups, access_logs");
 static final Source RULES=new Source("CI-RULES",DOCS+"first-steps/enroll-users-and-their-rules/","user_access_rules (regra direta)");
 static final Source FACE=new Source("CI-FACE",DOCS+"facial-recognition/facial-enroll/","user_set_image_list.fcgi; user_destroy_image.fcgi");
 static final Source OPEN=new Source("CI-OPEN",DOCS+"actions/remote-door-and-turnstile-opening/","POST /execute_actions.fcgi");
 static final Source PARTIC=new Source("CI-PARTIC","https://controlid.com.br/docs/access-api-en/particularities-of-the-products/particularities-control-id-terminals","iDFace: sec_box; iDBlock: catra");
 static final Source ONLINE=new Source("CI-ONLINE",DOCS+"operating-modes/online-identification-events/","POST /new_user_identified.fcgi; /device_is_alive.fcgi");
 static final Source MODES=new Source("CI-MODES","https://controlid.com.br/docs/access-api-en/operating-modes/introduction-to-operating-modes","Pro: autorização no servidor; contingência");
 static final Source PUSH=new Source("CI-PUSH",DOCS+"push-mode/introduction-to-push/","GET /push; POST /result");

 private final ControlIdTransport transport;
 private final ControlIdTransport.Lan lan;
 private final ControlIdTransport.Push push;

 public ControlIdAdapter(DeviceContext context) {
  super(context);
  if(context.device().connectionMode()==ConnectionMode.LOCAL) {
   lan=new ControlIdTransport.Lan(context.http(ControlIdTransport.Lan.paths()),context.credential());
   push=null;
   transport=lan;
  } else {
   lan=null;
   push=new ControlIdTransport.Push(context.clock(),context.pushPickupTimeout(),context.timeout().plus(context.pushPickupTimeout()),context.device().nativeDeviceId());
   transport=push;
  }
 }

 @Override public Manifest manifest() {
  var t=lan!=null?LOGIN:PUSH;
  var ops=new EnumMap<Operation,OperationSupport>(Operation.class);
  ops.put(Operation.UPSERT_PERSON,OperationSupport.implemented(Operation.UPSERT_PERSON,List.of(t,LOAD,CREATE,MODIFY,LIST),
   "Validade da pessoa (begin_time/end_time) não é gravada: unidade não documentada na fonte consultada."));
  ops.put(Operation.REMOVE_PERSON,OperationSupport.implemented(Operation.REMOVE_PERSON,List.of(t,DESTROY),
   "A fonte não declara remoção em cascata de cartões/PIN/QR/face; o núcleo retira cada método antes."));
  ops.put(Operation.ENROLL_FACE,OperationSupport.implemented(Operation.ENROLL_FACE,List.of(t,FACE),"Foto até 2 MB; resultado por pessoa (success/errors)."));
  ops.put(Operation.REMOVE_FACE,OperationSupport.implemented(Operation.REMOVE_FACE,List.of(t,FACE),"Nunca usa all/dangling; resposta vazia: APPLIED_UNVERIFIED."));
  for(CredentialKind k:CredentialKind.values()) {
   String limit=k==CredentialKind.QR?"qrcodes só armazena valor no modo alfanumérico (CI-LIST).":"Valor nunca registrado em log.";
   ops.put(setOperation(k),OperationSupport.implemented(setOperation(k),List.of(t,CREATE,LOAD,LIST),limit));
   ops.put(removeOperation(k),OperationSupport.implemented(removeOperation(k),List.of(t,DESTROY,LIST)));
  }
  ops.put(Operation.APPLY_RIGHTS,OperationSupport.implemented(Operation.APPLY_RIGHTS,List.of(t,RULES,DESTROY,CREATE),
   "Regras (access_rules/portal_access_rules/time_zones) são comissionadas no equipamento; o gateway só vincula a pessoa.",
   "Retira as regras antigas antes de gravar as novas: há intervalo curto sem direito."));
  ops.put(Operation.REVOKE_RIGHTS,OperationSupport.implemented(Operation.REVOKE_RIGHTS,List.of(t,DESTROY,LIST),"Remove user_access_rules e user_groups da pessoa."));
  ops.put(Operation.READ_STATE,OperationSupport.implemented(Operation.READ_STATE,List.of(t,LOAD,LIST),"Presença de face não é lida (null)."));
  ops.put(Operation.OPEN,OperationSupport.implemented(Operation.OPEN,List.of(t,OPEN,PARTIC),
   "Resposta só existe com intertravamento de rede; sem ela o máximo é ACCEPTED.",
   lan!=null?"LAN: conexão nova por comando, sem repetição.":"Push: comando vencido nunca é entregue; atraso depende do intervalo de busca do equipamento."));
  ops.put(Operation.FETCH_EVENTS,OperationSupport.implemented(Operation.FETCH_EVENTS,List.of(t,LOAD,LIST),
   "Códigos de access_logs numerados pela ordem da tabela (3 e 7 confirmados no texto CI-ONLINE).",
   "Unidade de access_logs.time não documentada: occurredAt fica null e timeQuality UNKNOWN."));
  ops.put(Operation.HEALTH,OperationSupport.implemented(Operation.HEALTH,lan!=null?List.of(SESSION):List.of(PUSH,ONLINE),
   lan!=null?"Sonda de sessão; relógio e firmware não lidos.":"Último GET /push ou device_is_alive recebido."));
  ops.put(Operation.ONLINE_DECISION,OperationSupport.implemented(Operation.ONLINE_DECISION,List.of(ONLINE,MODES),
   "Negativa usa event=6 (\"Access Denied\") inferido pela ordem da tabela e nenhuma ação; a ausência de ação é o que impede o relé.",
   "Em contingência o equipamento decide pela base local: a base local precisa estar restrita."));
  ops.put(Operation.RECEIVE_EVENTS,OperationSupport.missing(Operation.RECEIVE_EVENTS,"Formato das notificações do Monitor não consultado (CI09 bloqueada nesta sessão)."));
  ops.put(Operation.IDENTIFY,OperationSupport.missing(Operation.IDENTIFY,"system_information não consultado (página bloqueada nesta sessão)."));
  var caps=new EnumMap<CapabilityName,Boolean>(CapabilityName.class);
  caps.put(CapabilityName.faceEnrollment,true);
  caps.put(CapabilityName.faceDeletion,true);
  caps.put(CapabilityName.perDoorRights,true);
  caps.put(CapabilityName.onlineDecisionBeforeRelay,true);
  caps.put(CapabilityName.scopedRemoteOpen,true);
  caps.put(CapabilityName.eventBackfill,true);
  return new Manifest(Brand.CONTROL_ID,ADAPTER_VERSION,device().connectionMode(),ops,caps);
 }

 // ----- pessoa -----

 @Override public OperationResult upsertPerson(PersonSpec person) {
  try {
   Long id=userId(person.subjectRef());
   if(id!=null) {
    transport.call("modify_objects",Map.of("object","users","values",Map.of("name",person.displayName()),
     "where",Map.of("users",Map.of("registration",person.subjectRef()))),false,null);
    return new OperationResult(OperationState.APPLIED_UNVERIFIED,null,String.valueOf(id));
   }
   var values=new LinkedHashMap<String,Object>();
   values.put("registration",person.subjectRef());
   values.put("name",person.displayName());
   var created=transport.call("create_objects",Map.of("object","users","values",List.of(values)),false,null);
   var ids=Json.list(created.get("ids"));
   String nativeRef=ids==null || ids.isEmpty()?null:String.valueOf(ids.getFirst());
   return new OperationResult(OperationState.APPLIED_UNVERIFIED,null,nativeRef);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 @Override public OperationResult removePerson(String subjectRef) {
  return destroy("users",Map.of("users",Map.of("registration",subjectRef)));
 }

 // ----- face -----

 @Override public OperationResult enrollFace(FaceSpec face) {
  try {
   Long id=userId(face.subjectRef());
   if(id==null) return OperationResult.of(OperationState.FAILED,"PERSON_NOT_ON_DEVICE");
   var image=new LinkedHashMap<String,Object>();
   image.put("user_id",id);
   image.put("timestamp",context.clock().instant().getEpochSecond());
   image.put("image",Base64.getEncoder().encodeToString(face.jpeg()));
   var response=transport.call("user_set_image_list",Map.of("match",true,"user_images",List.of(image)),false,null);
   var results=Json.list(response.get("results"));
   if(results==null || results.isEmpty()) return OperationResult.of(OperationState.APPLIED_UNVERIFIED,"NO_INDIVIDUAL_RESULT");
   var r=Json.map(results.getFirst());
   if(Boolean.TRUE.equals(Json.bool(r,"success"))) return OperationResult.of(OperationState.APPLIED_UNVERIFIED,"DEVICE_REPORTED_SUCCESS");
   var errors=Json.list(r.get("errors"));
   String code=errors==null || errors.isEmpty()?"UNKNOWN":String.valueOf(Json.longValue(Json.map(errors.getFirst()),"code"));
   return OperationResult.of(OperationState.FAILED,"CONTROLID_FACE_ERROR_"+code);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 @Override public OperationResult removeFace(String subjectRef) {
  try {
   Long id=userId(subjectRef);
   if(id==null) return OperationResult.of(OperationState.CONFIRMED,"PERSON_ABSENT");
   // Somente user_id: nunca "all" ou "dangling" (remoção global acidental).
   transport.call("user_destroy_image",Map.of("user_id",id),false,null);
   return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 // ----- cartão, PIN, QR -----

 static String object(CredentialKind kind) { return switch(kind) { case CARD -> "cards"; case PIN -> "pins"; case QR -> "qrcodes"; }; }

 @Override public OperationResult setCredential(CredentialSpec credential) {
  try {
   Long id=userId(credential.subjectRef());
   if(id==null) return OperationResult.of(OperationState.FAILED,"PERSON_NOT_ON_DEVICE");
   String object=object(credential.kind());
   // cards.value é inteiro sem sinal de 64 bits (CI-LIST); pins/qrcodes.value são texto.
   Object value=credential.kind()==CredentialKind.CARD?new BigDecimal(credential.value()):credential.value();
   var existing=load(object,Map.of(object,Map.of("user_id",id)));
   for(var row:existing) if(String.valueOf(row.get("value")).equals(String.valueOf(value))) return OperationResult.of(OperationState.APPLIED_UNVERIFIED,"ALREADY_PRESENT");
   var values=new LinkedHashMap<String,Object>();
   values.put("value",value);
   values.put("user_id",id);
   transport.call("create_objects",Map.of("object",object,"values",List.of(values)),false,null);
   return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 @Override public OperationResult removeCredential(String subjectRef,CredentialKind kind) {
  try {
   Long id=userId(subjectRef);
   if(id==null) return OperationResult.of(OperationState.CONFIRMED,"PERSON_ABSENT");
   String object=object(kind);
   transport.call("destroy_objects",Map.of("object",object,"where",Map.of(object,Map.of("user_id",id))),false,null);
   return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 // ----- direitos -----

 /** Regra nativa (access_rules.id) comissionada para o canal e a faixa de horário. */
 Optional<Long> ruleId(Right right) {
  String byChannel=device().param("rule."+right.channelId()+"."+right.scheduleRef());
  String value=byChannel!=null?byChannel:device().channels().size()==1?device().nativeSchedules().get(right.scheduleRef()):null;
  if(value==null || !value.matches("\\d{1,18}")) return Optional.empty();
  return Optional.of(Long.parseLong(value));
 }

 @Override public Optional<Set<String>> rightKeys(RightsSpec rights) {
  var keys=new HashSet<String>();
  for(Right r:rights.rights()) {
   var id=ruleId(r);
   if(id.isEmpty()) return Optional.empty();
   keys.add("rule:"+id.get());
  }
  return Optional.of(keys);
 }

 @Override public OperationResult applyRights(RightsSpec rights) {
  var keys=rightKeys(rights);
  if(keys.isEmpty()) return OperationResult.of(OperationState.FAILED,"RIGHT_NOT_REPRESENTABLE");
  try {
   Long id=userId(rights.subjectRef());
   if(id==null) return OperationResult.of(OperationState.FAILED,"PERSON_NOT_ON_DEVICE");
   // Restrição antes de ampliação: retira tudo, depois grava exatamente o desejado.
   transport.call("destroy_objects",Map.of("object","user_access_rules","where",Map.of("user_access_rules",Map.of("user_id",id))),false,null);
   transport.call("destroy_objects",Map.of("object","user_groups","where",Map.of("user_groups",Map.of("user_id",id))),false,null);
   if(!rights.rights().isEmpty()) {
    var values=new ArrayList<Object>();
    for(String k:new java.util.TreeSet<>(keys.get())) {
     var v=new LinkedHashMap<String,Object>();
     v.put("user_id",id);
     v.put("access_rule_id",Long.parseLong(k.substring(5)));
     values.add(v);
    }
    transport.call("create_objects",Map.of("object","user_access_rules","values",values),false,null);
   }
   return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 @Override public OperationResult revokeRights(String subjectRef) {
  try {
   Long id=userId(subjectRef);
   if(id==null) return OperationResult.of(OperationState.CONFIRMED,"PERSON_ABSENT");
   transport.call("destroy_objects",Map.of("object","user_access_rules","where",Map.of("user_access_rules",Map.of("user_id",id))),false,null);
   transport.call("destroy_objects",Map.of("object","user_groups","where",Map.of("user_groups",Map.of("user_id",id))),false,null);
   return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 @Override public AppliedState readState(String subjectRef) {
  try {
   Long id=userId(subjectRef);
   if(id==null) return new AppliedState(OperationState.CONFIRMED,null,false,Set.of(),Map.of(CredentialKind.CARD,0,CredentialKind.PIN,0,CredentialKind.QR,0),false);
   var keys=new HashSet<String>();
   for(var row:load("user_access_rules",Map.of("user_access_rules",Map.of("user_id",id)))) keys.add("rule:"+Json.longValue(row,"access_rule_id"));
   for(var row:load("user_groups",Map.of("user_groups",Map.of("user_id",id)))) keys.add("group:"+Json.longValue(row,"group_id"));
   var counts=new EnumMap<CredentialKind,Integer>(CredentialKind.class);
   for(CredentialKind k:CredentialKind.values()) counts.put(k,load(object(k),Map.of(object(k),Map.of("user_id",id))).size());
   return new AppliedState(OperationState.CONFIRMED,null,true,keys,counts,null);
  } catch(DeviceCallException e) {
   return AppliedState.unavailable(e.failure()==br.com.condpulse.gateway.http.DeviceHttpClient.Failure.NOT_SENT?OperationState.FAILED:OperationState.UNKNOWN,e.getMessage());
  } catch(ControlIdTransport.ControlIdHttpError e) { return AppliedState.unavailable(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }

 // ----- abertura -----

 /**
  * Ação nativa tipada do canal (CI-OPEN): {@code door:door=N}, {@code sec_box:id=N,reason=N} ou
  * {@code catra:allow=clockwise|anticlockwise|both}. Qualquer outro texto é recusado.
  */
 static Optional<Map<String,Object>> action(String nativeOpenAction) {
  if(nativeOpenAction==null) return Optional.empty();
  String parameters;
  String action;
  if(nativeOpenAction.matches("door:door=\\d{1,2}")) { action="door"; parameters=nativeOpenAction.substring(5); }
  else if(nativeOpenAction.matches("sec_box:id=\\d{1,10},reason=\\d{1,2}")) {
   action="sec_box";
   String[] p=nativeOpenAction.substring(8).split(",");
   parameters=p[0]+", "+p[1];
  }
  else if(nativeOpenAction.matches("catra:allow=(clockwise|anticlockwise|both)")) { action="catra"; parameters=nativeOpenAction.substring(6); }
  else return Optional.empty();
  var m=new LinkedHashMap<String,Object>();
  m.put("action",action);
  m.put("parameters",parameters);
  return Optional.of(m);
 }

 @Override public OpenResult open(OpenAction open) {
  var action=action(open.nativeOpenAction());
  if(action.isEmpty()) return new OpenResult(OpenOutcome.NOT_SENT,"OPEN_ACTION_NOT_COMMISSIONED");
  try {
   var response=transport.call("execute_actions",Map.of("actions",List.of(action.get())),true,open.expiresAt());
   var actions=Json.list(response.get("actions"));
   if(actions!=null && !actions.isEmpty() && "denied".equals(Json.string(Json.map(actions.getFirst()),"status")))
    return new OpenResult(OpenOutcome.REJECTED_BY_DEVICE,"CONTROLID_INTERLOCK_DENIED");
   return new OpenResult(OpenOutcome.ACCEPTED,null);
  } catch(DeviceCallException e) { return openFailure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return new OpenResult(OpenOutcome.REJECTED_BY_DEVICE,"CONTROLID_HTTP_"+e.status); }
 }

 // ----- eventos -----

 /** Códigos de access_logs.event pela ordem da tabela de CI-LIST (3 e 7 confirmados em CI-ONLINE). */
 static EventKind kind(long event) {
  return switch((int)event) {
   case 7 -> EventKind.ACCESS_ALLOWED;
   case 6 -> EventKind.ACCESS_DENIED;
   case 3 -> EventKind.ACCESS_DENIED;
   default -> null;
  };
 }

 /**
  * Janela determinística de ids: {@code cursor < id <= cursor+limit}. Sem ordenação não documentada; se a janela vier
  * vazia mas existir id maior, o cursor avança a janela (ids podem ter lacunas).
  */
 @Override public EventPage fetchEvents(String cursor,int limit) {
  long from=cursor==null?0:Long.parseLong(cursor);
  long to=from+limit;
  try {
   var rows=load("access_logs",Map.of("access_logs",Map.of("id",Map.of(">",from,"<=",to))));
   long max=from;
   var userIds=new HashSet<Long>();
   for(var r:rows) { max=Math.max(max,Json.longValue(r,"id")); Long u=Json.longValue(r,"user_id"); if(u!=null && u>0) userIds.add(u); }
   if(rows.isEmpty()) {
    var later=transport.call("load_objects",Map.of("object","access_logs","where",Map.of("access_logs",Map.of("id",Map.of(">",to))),"limit",1),false,null);
    var list=Json.list(later.get("access_logs"));
    String next=list==null || list.isEmpty()?cursor:String.valueOf(to);
    return new EventPage(OperationState.CONFIRMED,null,List.of(),next,0);
   }
   var subjects=new HashMap<Long,String>();
   if(!userIds.isEmpty()) {
    for(var u:load("users",Map.of("users",Map.of("id",Map.of("IN",new ArrayList<Object>(userIds))))))
     subjects.put(Json.longValue(u,"id"),Json.string(u,"registration"));
   }
   var events=new ArrayList<EdgeEvent>();
   int unmapped=0;
   for(var r:rows) {
    long code=Json.longValue(r,"event");
    var kind=kind(code);
    if(kind==null) { unmapped++; continue; }
    Long portal=Json.longValue(r,"portal_id");
    Long user=Json.longValue(r,"user_id");
    events.add(event(String.valueOf(Json.longValue(r,"id")),kind,null,Direction.UNKNOWN,TimeQuality.UNKNOWN,
     user==null?null:subjects.get(user),"CONTROLID_ACCESS_LOG_EVENT_"+code,portal==null || portal==0?null:String.valueOf(portal)));
   }
   return new EventPage(OperationState.CONFIRMED,null,events,String.valueOf(max),unmapped);
  } catch(DeviceCallException e) {
   return EventPage.unavailable(e.failure()==br.com.condpulse.gateway.http.DeviceHttpClient.Failure.NOT_SENT?OperationState.FAILED:OperationState.UNKNOWN,e.getMessage(),cursor);
  } catch(ControlIdTransport.ControlIdHttpError e) { return EventPage.unavailable(OperationState.FAILED,"CONTROLID_HTTP_"+e.status,cursor); }
 }

 // ----- saúde -----

 @Override public HealthReport health() {
  if(lan!=null) {
   try { return new HealthReport(lan.sessionValid(),null,null,null,null); }
   catch(DeviceCallException e) { return new HealthReport(false,e.getMessage(),null,null,null); }
   catch(RuntimeException e) { return new HealthReport(false,"INVALID_RESPONSE",null,null,null); }
  }
  Instant seen=push.lastSeen();
  boolean recent=seen!=null && Duration.between(seen,context.clock().instant()).compareTo(context.pushPickupTimeout().multipliedBy(4))<=0;
  return new HealthReport(recent,recent?null:"NO_RECENT_DEVICE_CALL",null,null,null);
 }

 // ----- entrada (Pro online e push) -----

 @Override public Optional<InboundEndpoint> inbound() {
  return Optional.of((request,ctx)->{
   switch(request.subPath()) {
    case "/push" -> { if(push==null || !request.method().equals("GET")) return InboundEndpoint.Response.notFound(); ctx.heartbeat(); return push.poll(request); }
    case "/result" -> { if(push==null || !request.method().equals("POST")) return InboundEndpoint.Response.notFound(); ctx.heartbeat(); return push.result(request); }
    case "/device_is_alive.fcgi" -> { ctx.heartbeat(); return InboundEndpoint.Response.empty(); }
    case "/new_rex_log.fcgi" -> { return InboundEndpoint.Response.empty(); }
    case "/new_user_identified.fcgi" -> { return identified(request,ctx); }
    default -> { return InboundEndpoint.Response.notFound(); }
   }
  });
 }

 /**
  * Modo Pro (CI-ONLINE): o equipamento identificou localmente e pergunta ao servidor. Permitido: event 7 e a ação
  * nativa do canal; negado: event 6 sem ação. Erro de formato também é negativa.
  */
 InboundEndpoint.Response identified(InboundEndpoint.Request request,InboundEndpoint.Context ctx) {
  if(!request.method().equals("POST")) return InboundEndpoint.Response.notFound();
  var form=InboundServer.parseQuery(new String(request.body(),StandardCharsets.UTF_8));
  String user=form.get("user_id"), portal=form.get("portal_id");
  if(user!=null && !user.matches("\\d{1,18}")) user=null;
  if(portal!=null && !portal.matches("\\d{1,18}")) portal=null;
  var decision=ctx.decide(user,portal==null || portal.equals("0")?null:portal);
  var result=new LinkedHashMap<String,Object>();
  if(user!=null) result.put("user_id",Long.parseLong(user));
  if(portal!=null) result.put("portal_id",Long.parseLong(portal));
  var action=decision.allow()?device().channel(decision.channelId()).flatMap(c->action(c.nativeOpenAction())):Optional.<Map<String,Object>>empty();
  if(decision.allow() && action.isPresent()) {
   result.put("event",7);
   result.put("actions",List.of(action.get()));
  } else {
   result.put("event",6);
   result.put("actions",List.of());
  }
  return InboundEndpoint.Response.json(Json.write(Map.of("result",result)));
 }

 // ----- utilitários -----

 private Long userId(String subjectRef) throws DeviceCallException {
  var rows=load("users",Map.of("users",Map.of("registration",subjectRef)));
  if(rows.isEmpty()) return null;
  if(rows.size()>1) throw new ControlIdTransport.ControlIdHttpError(409);
  return Json.longValue(rows.getFirst(),"id");
 }

 private List<Map<String,Object>> load(String object,Map<String,Object> where) throws DeviceCallException {
  var response=transport.call("load_objects",Map.of("object",object,"where",where),false,null);
  var list=Json.list(response.get(object));
  var out=new ArrayList<Map<String,Object>>();
  if(list!=null) for(Object o:list) out.add(Json.map(o));
  return out;
 }

 private OperationResult destroy(String object,Map<String,Object> where) {
  try {
   transport.call("destroy_objects",Map.of("object",object,"where",where),false,null);
   return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
  } catch(DeviceCallException e) { return failure(e); }
  catch(ControlIdTransport.ControlIdHttpError e) { return OperationResult.of(OperationState.FAILED,"CONTROLID_HTTP_"+e.status); }
 }
}
