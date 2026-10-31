package br.com.condpulse.gateway.adapters.dahua;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.http.KeyValueText;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.BaseAdapter;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Adaptador Dahua por CGI (AC-032), com base exclusiva no documento "DAHUA ACCESS CONTROL PRODUCTS INTEGRATION
 * INSTRUCTION" V1.0 (28/09/2021; files.dahua.support; modelos declarados ASI3XXX, ASI6XXX, ASI7XXX). Ele documenta:
 * inserção de registro de cartão ({@code recordUpdater.cgi?action=insert&name=AccessControlCard}), cadastro de face
 * ({@code FaceInfoManager.cgi?action=add}), abertura ({@code accessControl.cgi?action=openDoor}) e registros de acesso
 * ({@code recordFinder.cgi?action=find&name=AccessControlCardRec}). Não documenta atualização nem remoção de registro
 * ou face: sem retirada documentada, o núcleo recusa gravar cartão e face (REVOGACAO_SEM_DOCUMENTACAO). Autenticação
 * não é descrita na fonte: o cliente responde ao desafio Digest do próprio equipamento, a confirmar em bancada. O
 * modo nuvem (auto-registro/plataforma) não está documentado na fonte pública consultada. As chamadas da Intelbras
 * não são reutilizadas aqui (manual 23.2).
 */
public final class DahuaCgiAdapter extends BaseAdapter {
 static final String ADAPTER_VERSION="dahua-cgi-1.0.0";
 static final String DH_INT="https://files.dahua.support/Solutions/Access%20Control%20Solution/Integration/DAHUA%20ACCESS%20CONTROL%20PRODUCTS%20INTEGRATION%20INSTRUCTION%20Ver1.0.pdf";
 static final Source INSERT=new Source("DH-INT-V1.0",DH_INT,"CGI: recordUpdater.cgi?action=insert&name=AccessControlCard");
 static final Source FACE=new Source("DH-INT-V1.0",DH_INT,"CGI: FaceInfoManager.cgi?action=add");
 static final Source OPEN=new Source("DH-INT-V1.0",DH_INT,"CGI: accessControl.cgi?action=openDoor");
 static final Source RECORDS=new Source("DH-INT-V1.0",DH_INT,"CGI: recordFinder.cgi?action=find&name=AccessControlCardRec");

 static final String P_RECORD_UPDATER="/cgi-bin/recordUpdater.cgi", P_FACE="/cgi-bin/FaceInfoManager.cgi",
  P_ACCESS="/cgi-bin/accessControl.cgi", P_FINDER="/cgi-bin/recordFinder.cgi";

 private final DeviceHttpClient http;

 public DahuaCgiAdapter(DeviceContext context) {
  super(context);
  this.http=context.device().connectionMode()==ConnectionMode.LOCAL?context.http(Set.of(P_RECORD_UPDATER,P_FACE,P_ACCESS,P_FINDER)):null;
 }

 @Override public Manifest manifest() {
  var ops=new EnumMap<Operation,OperationSupport>(Operation.class);
  var caps=new EnumMap<CapabilityName,Boolean>(CapabilityName.class);
  if(http==null) return new Manifest(Brand.DAHUA,ADAPTER_VERSION,device().connectionMode(),ops,caps);
  ops.put(Operation.SET_CARD,OperationSupport.implemented(Operation.SET_CARD,List.of(INSERT),
   "Sem remoção documentada: bloqueado pelo núcleo (REVOGACAO_SEM_DOCUMENTACAO).","Requisição GET com efeito: conexão nova, sem repetição."));
  ops.put(Operation.ENROLL_FACE,OperationSupport.implemented(Operation.ENROLL_FACE,List.of(FACE),
   "Envia só PhotoData (base64, sem prefixo data-URI); FaceData é do algoritmo Dahua e não é gerado aqui.",
   "Sem remoção documentada: bloqueado pelo núcleo (REVOGACAO_SEM_DOCUMENTACAO)."));
  ops.put(Operation.OPEN,OperationSupport.implemented(Operation.OPEN,List.of(OPEN),"Resposta OK = aceite; não comprova porta aberta."));
  ops.put(Operation.FETCH_EVENTS,OperationSupport.implemented(Operation.FETCH_EVENTS,List.of(RECORDS),
   "Paginação por CreateTime com deduplicação por RecNo, como a fonte orienta.","UserID é referência nativa: subjectRef fica null."));
  ops.put(Operation.HEALTH,OperationSupport.implemented(Operation.HEALTH,List.of(RECORDS),"Sonda por consulta de registros com count=1."));
  for(Operation op:List.of(Operation.UPSERT_PERSON,Operation.REMOVE_PERSON,Operation.REMOVE_FACE,Operation.REMOVE_CARD,Operation.SET_PIN,
      Operation.REMOVE_PIN,Operation.SET_QR,Operation.REMOVE_QR,Operation.APPLY_RIGHTS,Operation.REVOKE_RIGHTS,Operation.READ_STATE,
      Operation.IDENTIFY,Operation.RECEIVE_EVENTS,Operation.ONLINE_DECISION))
   ops.put(op,OperationSupport.missing(op,"NAO_SUPORTADO_SEM_DOCUMENTACAO: ausente no documento de integração Dahua V1.0; obter pacote CGI/SDK por SKU e firmware (DH09)."));
  caps.put(CapabilityName.faceEnrollment,true);
  caps.put(CapabilityName.faceDeletion,false);
  caps.put(CapabilityName.scopedRemoteOpen,true);
  caps.put(CapabilityName.eventBackfill,true);
  return new Manifest(Brand.DAHUA,ADAPTER_VERSION,device().connectionMode(),ops,caps);
 }

 @Override public OperationResult setCredential(CredentialSpec credential) {
  if(http==null || credential.kind()!=CredentialKind.CARD) return super.setCredential(credential);
  var q=new LinkedHashMap<String,String>();
  q.put("action","insert");
  q.put("name","AccessControlCard");
  q.put("CardNo",credential.value());
  q.put("UserID",credential.subjectRef());
  q.put("CardStatus","0");
  q.put("CardType","0");
  try {
   var response=http.call("GET",P_RECORD_UPDATER,q,null,null,true);
   if(!response.ok()) return OperationResult.of(OperationState.FAILED,"DAHUA_HTTP_"+response.status());
   String recNo=KeyValueText.lines(response.text()).get("RecNo");
   return new OperationResult(OperationState.APPLIED_UNVERIFIED,recNo==null?"NO_RECNO":null,recNo);
  } catch(DeviceCallException e) { return failure(e); }
 }

 @Override public OperationResult enrollFace(FaceSpec face) {
  if(http==null) return super.enrollFace(face);
  var body=new LinkedHashMap<String,Object>();
  body.put("UserID",face.subjectRef());
  body.put("Info",Map.of("PhotoData",List.of(Base64.getEncoder().encodeToString(face.jpeg()))));
  try {
   var response=http.call("POST",P_FACE,Map.of("action","add"),"application/json",Json.write(body).getBytes(StandardCharsets.UTF_8),false);
   if(response.ok() && response.text().trim().equals("OK")) return OperationResult.of(OperationState.APPLIED_UNVERIFIED,null);
   return OperationResult.of(OperationState.FAILED,"DAHUA_HTTP_"+response.status());
  } catch(DeviceCallException e) { return failure(e); }
 }

 @Override public OpenResult open(OpenAction action) {
  if(http==null) return super.open(action);
  if(!action.nativeChannel().matches("\\d{1,2}")) return new OpenResult(OpenOutcome.NOT_SENT,"OPEN_CHANNEL_NOT_COMMISSIONED");
  var q=new LinkedHashMap<String,String>();
  q.put("action","openDoor");
  q.put("channel",action.nativeChannel());
  q.put("Type","Remote");
  try {
   var response=http.call("GET",P_ACCESS,q,null,null,true);
   if(response.ok() && response.text().trim().equals("OK")) return new OpenResult(OpenOutcome.ACCEPTED,null);
   return new OpenResult(OpenOutcome.REJECTED_BY_DEVICE,"DAHUA_HTTP_"+response.status());
  } catch(DeviceCallException e) { return openFailure(e); }
 }

 /**
  * Cursor {@code <CreateTime>:<maior RecNo nesse segundo>}. A fonte orienta repetir com StartTime = último
  * CreateTime e deduplicar, porque registros do mesmo segundo se sobrepõem.
  */
 @Override public EventPage fetchEvents(String cursor,int limit) {
  if(http==null) return super.fetchEvents(cursor,limit);
  long now=context.clock().instant().getEpochSecond();
  long start; long lastRec;
  if(cursor==null) { start=now-Duration.ofHours(1).getSeconds(); lastRec=-1; }
  else { String[] p=cursor.split(":"); start=Long.parseLong(p[0]); lastRec=Long.parseLong(p[1]); }
  var q=new LinkedHashMap<String,String>();
  q.put("action","find");
  q.put("name","AccessControlCardRec");
  q.put("StartTime",String.valueOf(start));
  q.put("EndTime",String.valueOf(now));
  q.put("count",String.valueOf(limit));
  try {
   var response=http.call("GET",P_FINDER,q,null,null,false);
   if(!response.ok()) return EventPage.unavailable(OperationState.FAILED,"DAHUA_HTTP_"+response.status(),cursor);
   var kv=KeyValueText.lines(response.text());
   int found=kv.get("found")==null?0:Integer.parseInt(kv.get("found"));
   var events=new ArrayList<EdgeEvent>();
   long maxTime=start, maxRec=lastRec;
   int unmapped=0;
   for(int i=0;i<found;i++) {
    String p="records["+i+"].";
    String rec=kv.get(p+"RecNo"), created=kv.get(p+"CreateTime");
    if(rec==null || created==null || !rec.matches("\\d{1,18}") || !created.matches("\\d{1,12}")) { unmapped++; continue; }
    long recNo=Long.parseLong(rec), time=Long.parseLong(created);
    if(time<start || (time==start && recNo<=lastRec)) continue;
    if(time>maxTime) { maxTime=time; maxRec=recNo; } else if(time==maxTime) maxRec=Math.max(maxRec,recNo);
    String status=kv.get(p+"Status");
    EventKind kind="1".equals(status)?EventKind.ACCESS_ALLOWED:"0".equals(status)?EventKind.ACCESS_DENIED:null;
    if(kind==null) { unmapped++; continue; }
    String type=kv.get(p+"Type");
    Direction direction="Entry".equals(type)?Direction.IN:"Exit".equals(type)?Direction.OUT:Direction.UNKNOWN;
    String door=kv.get(p+"Door");
    String method=kv.get(p+"Method");
    String nativeKind="DAHUA_STATUS_"+status+(method!=null && method.matches("\\d{1,3}")?"_METHOD_"+method:"");
    events.add(event(rec,kind,Instant.ofEpochSecond(time),direction,TimeQuality.DEVICE_UNVERIFIED,null,nativeKind,
     door!=null && door.matches("\\d{1,3}")?door:null));
   }
   String next=maxTime==start && maxRec==lastRec?cursor:maxTime+":"+maxRec;
   return new EventPage(OperationState.CONFIRMED,null,events,next,unmapped);
  } catch(DeviceCallException e) {
   return EventPage.unavailable(e.failure()==DeviceHttpClient.Failure.NOT_SENT?OperationState.FAILED:OperationState.UNKNOWN,e.getMessage(),cursor);
  } catch(NumberFormatException e) { return EventPage.unavailable(OperationState.FAILED,"INVALID_RESPONSE",cursor); }
 }

 @Override public HealthReport health() {
  if(http==null) return super.health();
  long now=context.clock().instant().getEpochSecond();
  var q=new LinkedHashMap<String,String>();
  q.put("action","find"); q.put("name","AccessControlCardRec"); q.put("StartTime",String.valueOf(now-60)); q.put("EndTime",String.valueOf(now)); q.put("count","1");
  try {
   var response=http.call("GET",P_FINDER,q,null,null,false);
   boolean ok=response.ok() && KeyValueText.lines(response.text()).containsKey("found");
   return new HealthReport(ok,ok?null:"DAHUA_HTTP_"+response.status(),null,null,null);
  } catch(DeviceCallException e) { return new HealthReport(false,e.getMessage(),null,null,null); }
 }
}
