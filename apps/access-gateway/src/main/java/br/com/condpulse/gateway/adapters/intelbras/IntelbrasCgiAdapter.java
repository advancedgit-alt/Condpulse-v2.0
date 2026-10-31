package br.com.condpulse.gateway.adapters.intelbras;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.http.KeyValueText;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.BaseAdapter;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;

/**
 * Adaptador Intelbras Bio-T (AC-033; SS 3532/5532 e família), com base exclusiva na documentação oficial de API de
 * controle de acesso da Intelbras (intelbras-caco-api.intelbras.com.br, consultada em 31/10/2026). A fonte confirma
 * API CGI em {@code /cgi-bin/*.cgi} com autenticação Digest — a mesma família de caminhos da Dahua — mas as chamadas
 * Dahua não são reutilizadas por semelhança (manual 23.2): só o que a página Intelbras mostra é implementado.
 * <ul>
 * <li>IB-EVM: {@code GET /cgi-bin/eventManager.cgi?action=attach&codes=[...]&heartbeat=N}, fluxo multipart
 * {@code --myboundary} com blocos {@code Code=AccessControl;action=Pulse;index=0;data={...}};</li>
 * <li>IB-PARAM: tabela de ErrorCode (0 = sem erro), métodos de abertura e códigos de evento;</li>
 * <li>IB-ONLINE/IB-ONLINE-CFG: modo online — keep alive por GET e eventos por POST no caminho configurado
 * (Uploadpath), resposta {@code {"message","code":"200","auth":"true|false"}}; no timeout o equipamento decide pela
 * base local e "todos os usuários têm privilégios VIP, 24 horas por dia, 7 dias por semana".</li>
 * </ul>
 * Cadastro de pessoa, face (página "Cadastrar Faces" recusada por robots nesta sessão), cartão, PIN, QR, direitos,
 * remoção, abertura e leitura de estado: NAO_SUPORTADO_SEM_DOCUMENTACAO até obter a página/pacote atual por SKU.
 */
public final class IntelbrasCgiAdapter extends BaseAdapter {
 static final String ADAPTER_VERSION="intelbras-cgi-1.0.0";
 static final String SITE="https://intelbras-caco-api.intelbras.com.br/";
 static final Source EVM=new Source("IB-EVM",SITE+"obtendo_eventos/eventManager","GET /cgi-bin/eventManager.cgi?action=attach");
 static final Source PARAM=new Source("IB-PARAM",SITE+"parametros_retornados","ErrorCode, métodos e códigos de evento");
 static final Source ONLINE=new Source("IB-ONLINE",SITE+"modo_online","Keep alive GET; eventos POST; resposta auth true/false; offline VIP 24/7");
 static final Source ONLINE_CFG=new Source("IB-ONLINE-CFG",SITE+"modo_online/configurar_via_api","PictureHttpUpload.Uploadpath; Intelbras_ModeCfg.KeepAlive.Path");

 static final String P_EVENTS="/cgi-bin/eventManager.cgi";
 static final String BOUNDARY="myboundary";

 private final DeviceHttpClient http;
 private final Duration streamWindow;

 public IntelbrasCgiAdapter(DeviceContext context) {
  super(context);
  this.http=context.device().connectionMode()==ConnectionMode.LOCAL?context.http(Set.of(P_EVENTS)):null;
  String w=context.device().param("intelbras.streamWindowMillis");
  this.streamWindow=Duration.ofMillis(w!=null && w.matches("\\d{3,5}")?Long.parseLong(w):3000);
 }

 @Override public Manifest manifest() {
  var ops=new EnumMap<Operation,OperationSupport>(Operation.class);
  var caps=new EnumMap<CapabilityName,Boolean>(CapabilityName.class);
  if(http!=null) {
   ops.put(Operation.FETCH_EVENTS,OperationSupport.implemented(Operation.FETCH_EVENTS,List.of(EVM,PARAM),
    "Fluxo ao vivo lido por janela curta: sem backfill; eventos ocorridos com o gateway desconectado não são recuperados.",
    "Sem identificador nativo de evento: chave composta (UTC, porta, usuário, método, erro, índice); tentativas idênticas no mesmo segundo colapsam."));
   ops.put(Operation.HEALTH,OperationSupport.implemented(Operation.HEALTH,List.of(EVM),"Conexão ao fluxo com heartbeat=1 dentro da janela."));
  }
  ops.put(Operation.RECEIVE_EVENTS,OperationSupport.implemented(Operation.RECEIVE_EVENTS,List.of(ONLINE,ONLINE_CFG,PARAM),
   "Modo online: eventos chegam por POST no caminho configurado no equipamento (contém o token do equipamento)."));
  ops.put(Operation.ONLINE_DECISION,OperationSupport.implemented(Operation.ONLINE_DECISION,List.of(ONLINE),
   "Credencial biométrica não cadastrada no equipamento é negada antes de chegar ao servidor.",
   "RISCO: no timeout a decisão é local e todos os usuários locais têm privilégio VIP 24/7 — base local precisa ser mínima."));
  for(Operation op:Operation.values()) ops.putIfAbsent(op,OperationSupport.missing(op,
   "NAO_SUPORTADO_SEM_DOCUMENTACAO: não obtido na documentação oficial Intelbras nesta sessão; solicitar pacote atual por SKU (IB16/IB17)."));
  caps.put(CapabilityName.onlineDecisionBeforeRelay,true);
  caps.put(CapabilityName.offlineRestriction,false);
  caps.put(CapabilityName.eventBackfill,false);
  if(http!=null) caps.put(CapabilityName.doorContactEvents,null);
  return new Manifest(Brand.INTELBRAS,ADAPTER_VERSION,device().connectionMode(),ops,caps);
 }

 @Override public EventPage fetchEvents(String cursor,int limit) {
  if(http==null) return super.fetchEvents(cursor,limit);
  var q=new LinkedHashMap<String,String>();
  q.put("action","attach");
  q.put("codes","[AccessControl,BreakIn,DoorNotClosed]");
  q.put("heartbeat","1");
  try {
   var response=http.stream(P_EVENTS,q,streamWindow,1024*1024);
   if(response.status()!=200) return EventPage.unavailable(OperationState.FAILED,"INTELBRAS_HTTP_"+response.status(),cursor);
   var parsed=events(new String(response.body(),StandardCharsets.UTF_8));
   return new EventPage(OperationState.CONFIRMED,null,parsed.events(),cursor,parsed.unmapped());
  } catch(DeviceCallException e) {
   return EventPage.unavailable(e.failure()==DeviceHttpClient.Failure.NOT_SENT?OperationState.FAILED:OperationState.UNKNOWN,e.getMessage(),cursor);
  }
 }

 @Override public HealthReport health() {
  if(http==null) return super.health();
  try {
   var response=http.stream(P_EVENTS,Map.of("action","attach","codes","[All]","heartbeat","1"),Duration.ofMillis(2500),64*1024);
   boolean ok=response.status()==200;
   return new HealthReport(ok,ok?null:"INTELBRAS_HTTP_"+response.status(),null,null,null);
  } catch(DeviceCallException e) { return new HealthReport(false,e.getMessage(),null,null,null); }
 }

 record Parsed(List<EdgeEvent> events,int unmapped) {}

 /** Normaliza blocos do fluxo/POST: AccessControl, BreakIn (DOOR_FORCED) e DoorNotClosed (DOOR_HELD). */
 Parsed events(String body) {
  var out=new ArrayList<EdgeEvent>();
  int unmapped=0;
  for(var block:KeyValueText.eventBlocks(body,BOUNDARY)) {
   var data=block.data();
   String utc=data==null?null:Json.string(data,"UTC");
   Instant occurred=utc!=null && utc.matches("\\d{1,12}")?Instant.ofEpochSecond(Long.parseLong(utc)):null;
   String door=data==null?null:Json.string(data,"Door");
   if(door!=null && !door.matches("\\d{1,3}")) door=null;
   switch(block.code()) {
    case "AccessControl" -> {
     String error=data==null?null:Json.string(data,"ErrorCode");
     if(error==null || !error.matches("\\d{1,4}")) { unmapped++; continue; }
     String user=Json.string(data,"UserID"), method=Json.string(data,"Method");
     EventKind kind=error.equals("0")?EventKind.ACCESS_ALLOWED:EventKind.ACCESS_DENIED;
     String nativeId=compositeId(block.code(),utc,door,user,method,error,block.index());
     out.add(event(nativeId,kind,occurred,Direction.UNKNOWN,occurred==null?TimeQuality.UNKNOWN:TimeQuality.DEVICE_UNVERIFIED,null,
      "INTELBRAS_ERROR_"+error+(method!=null && method.matches("\\d{1,3}")?"_METHOD_"+method:""),door));
    }
    case "BreakIn" -> out.add(event(compositeId(block.code(),utc,door,null,null,null,block.index()),EventKind.DOOR_FORCED,occurred,Direction.UNKNOWN,
     occurred==null?TimeQuality.UNKNOWN:TimeQuality.DEVICE_UNVERIFIED,null,"INTELBRAS_BREAKIN",door));
    case "DoorNotClosed" -> out.add(event(compositeId(block.code(),utc,door,null,null,null,block.index()),EventKind.DOOR_HELD,occurred,Direction.UNKNOWN,
     occurred==null?TimeQuality.UNKNOWN:TimeQuality.DEVICE_UNVERIFIED,null,"INTELBRAS_DOORNOTCLOSED",door));
    default -> unmapped++;
   }
  }
  return new Parsed(out,unmapped);
 }

 static String compositeId(String code,String utc,String door,String user,String method,String error,String index) {
  String key=String.join("|",code,String.valueOf(utc),String.valueOf(door),String.valueOf(user),String.valueOf(method),String.valueOf(error),String.valueOf(index));
  return java.util.UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
 }

 /**
  * Modo online (IB-ONLINE), na LAN ou na nuvem: o equipamento chama {@code GET /keepalive} e {@code POST
  * /notification} sob o caminho com o token dele. Para AccessControl com UserID, a resposta carrega a decisão local
  * ({@code auth}); qualquer dúvida responde {@code auth: "false"}.
  */
 @Override public Optional<InboundEndpoint> inbound() {
  return Optional.of((request,ctx)->{
   switch(request.subPath()) {
    case "/keepalive" -> { ctx.heartbeat(); return InboundEndpoint.Response.empty(); }
    case "/notification" -> {
     if(!request.method().equals("POST")) return InboundEndpoint.Response.notFound();
     ctx.heartbeat();
     String body=new String(request.body(),StandardCharsets.UTF_8);
     var parsed=events(body);
     ctx.events(parsed.events());
     boolean allow=false;
     String message="Acesso negado";
     for(var block:KeyValueText.eventBlocks(body,BOUNDARY)) {
      if(!"AccessControl".equals(block.code()) || block.data()==null) continue;
      String user=Json.string(block.data(),"UserID");
      String door=Json.string(block.data(),"Door");
      if(user==null || !user.matches("[A-Za-z0-9_-]{1,64}")) continue;
      var decision=ctx.decide(user,door!=null && door.matches("\\d{1,3}")?door:null);
      allow=decision.allow();
      if(allow) message="Acesso liberado";
      break;
     }
     var response=new LinkedHashMap<String,Object>();
     response.put("message",message);
     response.put("code","200");
     response.put("auth",allow?"true":"false");
     return InboundEndpoint.Response.json(Json.write(response));
    }
    default -> { return InboundEndpoint.Response.notFound(); }
   }
  });
 }
}
