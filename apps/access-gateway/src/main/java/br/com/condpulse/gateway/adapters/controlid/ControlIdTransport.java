package br.com.condpulse.gateway.adapters.controlid;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import br.com.condpulse.gateway.config.SecretProvider.DeviceCredential;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.http.DeviceHttpClient.Failure;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.InboundEndpoint.Request;
import br.com.condpulse.gateway.spi.InboundEndpoint.Response;

/**
 * Transporte das chamadas da API Control iD. Os mesmos comandos documentados ({@code create_objects},
 * {@code load_objects}...) seguem por dois caminhos:
 * <ul>
 * <li>LAN: sessão por {@code POST /login.fcgi} e {@code ?session=} em cada chamada (fonte CI-LOGIN, CI-SESSION);</li>
 * <li>Push (modo nuvem): o equipamento busca o comando com {@code GET /push?deviceId=&uuid=}, recebe
 * {@code {verb, endpoint, body, contentType}} e devolve o resultado em {@code POST /result} (fonte CI-PUSH).</li>
 * </ul>
 */
interface ControlIdTransport {
 /** endpoint sem barra nem sufixo (ex.: "create_objects"). Devolve o JSON da resposta (mapa vazio se corpo vazio). */
 Map<String,Object> call(String endpoint,Map<String,Object> body,boolean actuation,Instant notAfter) throws DeviceCallException;

 /** Último sinal de vida (push) ou null quando o transporte não depende do equipamento chamar. */
 default Instant lastSeen() { return null; }

 // ------------------------------------------------------------------------------------------------------------

 /** Sessão na LAN. A sessão nunca é registrada; vai na query porque é assim que a fonte oficial documenta. */
 final class Lan implements ControlIdTransport {
  static final Set<String> ENDPOINTS=Set.of("login","logout","session_is_valid","load_objects","create_objects","modify_objects",
   "destroy_objects","user_set_image_list","user_destroy_image","execute_actions");
  private final DeviceHttpClient http;
  private final DeviceCredential credential;
  private final Clock clock;
  private volatile String session;

  Lan(DeviceHttpClient http,DeviceCredential credential,Clock clock) { this.http=http; this.credential=credential; this.clock=clock; }

  static Set<String> paths() {
   var out=new java.util.HashSet<String>();
   for(String e:ENDPOINTS) out.add("/"+e+".fcgi");
   return out;
  }

  @Override public Map<String,Object> call(String endpoint,Map<String,Object> body,boolean actuation,Instant notAfter) throws DeviceCallException {
   if(!ENDPOINTS.contains(endpoint)) throw new IllegalArgumentException("Endpoint fora da lista.");
   String s=session==null?login():session;
   requireNotExpired(notAfter);
   var response=post(endpoint,s,body,actuation);
   if(response.status()==401 || response.status()==403) {
    // Sessão expirada: o pedido foi recusado sem execução; nova sessão e um único envio, se ainda no prazo.
    session=null;
    String renewed=login();
    requireNotExpired(notAfter);
    response=post(endpoint,renewed,body,actuation);
   }
   // 5xx não é recusa explícita: o pedido pode ter sido executado (resultado incerto). 4xx é recusa.
   if(response.status()>=500) throw new DeviceCallException(Failure.AMBIGUOUS,"HTTP_"+response.status());
   if(!response.ok()) throw new ControlIdHttpError(response.status());
   String text=response.text().trim();
   // Resposta ininterpretável: resultado incerto, nunca recusa.
   try { return text.isEmpty()?Map.of():Json.object(text); }
   catch(Json.JsonException e) { throw new DeviceCallException(Failure.AMBIGUOUS,"INVALID_RESPONSE"); }
  }

  /** Prazo revalidado imediatamente antes de cada envio (inclusive após login ou renovação de sessão). */
  private void requireNotExpired(Instant notAfter) throws DeviceCallException {
   if(notAfter!=null && !clock.instant().isBefore(notAfter)) throw new DeviceCallException(Failure.NOT_SENT,"EXPIRED_BEFORE_SEND");
  }

  private DeviceHttpClient.Response post(String endpoint,String s,Map<String,Object> body,boolean actuation) throws DeviceCallException {
   var query=new LinkedHashMap<String,String>();
   query.put("session",s);
   return http.call("POST","/"+endpoint+".fcgi",query,"application/json",Json.write(body).getBytes(StandardCharsets.UTF_8),actuation);
  }

  private synchronized String login() throws DeviceCallException {
   if(session!=null) return session;
   var body=new LinkedHashMap<String,Object>();
   body.put("login",credential.username());
   body.put("password",credential.passwordString());
   var response=http.call("POST","/login.fcgi",Map.of(),"application/json",Json.write(body).getBytes(StandardCharsets.UTF_8),false);
   if(!response.ok()) throw new DeviceCallException(Failure.NOT_SENT,"LOGIN_REJECTED");
   String s=Json.string(Json.object(response.text()),"session");
   if(s==null || s.isBlank()) throw new DeviceCallException(Failure.NOT_SENT,"LOGIN_REJECTED");
   session=s;
   return s;
  }

  /** {@code POST /session_is_valid.fcgi?session=} (CI-SESSION): sonda de alcance documentada. */
  boolean sessionValid() throws DeviceCallException {
   String s=session==null?login():session;
   var response=http.call("POST","/session_is_valid.fcgi",Map.of("session",s),"application/json",new byte[0],false);
   if(!response.ok()) { session=null; return false; }
   return Boolean.TRUE.equals(Json.bool(Json.object(response.text()),"session_is_valid"));
  }
 }

 /** O equipamento informou erro explícito no /result do push (campo "error"). */
 final class DeviceReportedError extends RuntimeException {
  DeviceReportedError() { super("device error",null,false,false); }
 }

 /** HTTP de erro do equipamento: o pedido chegou e foi recusado. */
 final class ControlIdHttpError extends RuntimeException {
  final int status;
  ControlIdHttpError(int status) { super("HTTP "+status,null,false,false); this.status=status; }
 }

 // ------------------------------------------------------------------------------------------------------------

 /**
  * Push (modo nuvem): fila de comandos que o equipamento retira. Se o equipamento não retirar dentro do prazo, o
  * comando sai da fila e é NOT_SENT (comprovadamente não entregue). Retirado e sem resultado no prazo: AMBIGUOUS.
  * Abertura vencida nunca é entregue, mesmo que já esteja na fila.
  */
 final class Push implements ControlIdTransport {
  private record Pending(String endpoint,Map<String,Object> body,Instant notAfter,CompletableFuture<Map<String,Object>> result,
      CompletableFuture<Boolean> picked) {}

  /** Comandos entregues aguardando /result (para diagnóstico e testes de vazamento). */
  public synchronized int awaitingResults() { return delivered.size(); }

  /** Espera terminou sem resultado: esquece o comando entregue (resultado tardio é ignorado; nada é reenviado). */
  private synchronized void forget(Pending pending) { delivered.values().removeIf(p->p==pending); }

  private final Clock clock;
  private final Duration pickupTimeout,resultTimeout;
  private final String nativeDeviceId;
  private final ArrayDeque<Pending> queue=new ArrayDeque<>();
  private final Map<String,Pending> delivered=new HashMap<>();
  private volatile Instant lastSeen;

  Push(Clock clock,Duration pickupTimeout,Duration resultTimeout,String nativeDeviceId) {
   this.clock=clock; this.pickupTimeout=pickupTimeout; this.resultTimeout=resultTimeout; this.nativeDeviceId=nativeDeviceId;
  }

  @Override public Instant lastSeen() { return lastSeen; }

  @Override public Map<String,Object> call(String endpoint,Map<String,Object> body,boolean actuation,Instant notAfter) throws DeviceCallException {
   if(!Lan.ENDPOINTS.contains(endpoint) || endpoint.equals("login") || endpoint.equals("logout") || endpoint.equals("session_is_valid"))
    throw new IllegalArgumentException("Endpoint fora da lista do push.");
   var pending=new Pending(endpoint,body,notAfter,new CompletableFuture<>(),new CompletableFuture<>());
   synchronized(this) { queue.add(pending); }
   try {
    long wait=pickupTimeout.toMillis();
    if(notAfter!=null) wait=Math.min(wait,Math.max(0,Duration.between(clock.instant(),notAfter).toMillis()));
    pending.picked().get(wait,TimeUnit.MILLISECONDS);
   } catch(TimeoutException e) {
    synchronized(this) {
     if(queue.remove(pending)) throw new DeviceCallException(Failure.NOT_SENT,"PUSH_NOT_PICKED_UP");
    }
   } catch(InterruptedException e) {
    Thread.currentThread().interrupt();
    synchronized(this) { if(queue.remove(pending)) throw new DeviceCallException(Failure.NOT_SENT,"INTERRUPTED"); }
    throw new DeviceCallException(Failure.AMBIGUOUS,"INTERRUPTED");
   } catch(ExecutionException e) {
    throw new DeviceCallException(Failure.NOT_SENT,"PUSH_EXPIRED_BEFORE_PICKUP");
   }
   try {
    return pending.result().get(resultTimeout.toMillis(),TimeUnit.MILLISECONDS);
   } catch(TimeoutException e) {
    forget(pending);
    throw new DeviceCallException(Failure.AMBIGUOUS,"PUSH_RESULT_NOT_RECEIVED");
   } catch(InterruptedException e) {
    forget(pending);
    Thread.currentThread().interrupt();
    throw new DeviceCallException(Failure.AMBIGUOUS,"INTERRUPTED");
   } catch(ExecutionException e) {
    // Só o campo "error" do /result é recusa explícita; resultado ininterpretável é incerto (pode ter acionado).
    if(e.getCause() instanceof DeviceReportedError) throw new ControlIdHttpError(422);
    throw new DeviceCallException(Failure.AMBIGUOUS,"INVALID_PUSH_RESULT");
   }
  }

  /** {@code GET /push?deviceId=&uuid=}: entrega o próximo comando ou corpo vazio (CI-PUSH). */
  Response poll(Request request) {
   if(!deviceMatches(request)) return Response.badRequest();
   lastSeen=clock.instant();
   String uuid=request.query().get("uuid");
   if(uuid==null || uuid.isBlank() || uuid.length()>100) return Response.badRequest();
   Pending next;
   synchronized(this) {
    while(true) {
     next=queue.poll();
     if(next==null) return Response.empty();
     if(next.notAfter()!=null && !clock.instant().isBefore(next.notAfter())) {
      next.picked().completeExceptionally(new IllegalStateException("expired"));
      continue;
     }
     delivered.put(uuid,next);
     break;
    }
   }
   next.picked().complete(true);
   var out=new LinkedHashMap<String,Object>();
   out.put("verb","POST");
   out.put("endpoint",next.endpoint());
   out.put("body",next.body());
   out.put("contentType","application/json");
   return Response.json(Json.write(out));
  }

  /** {@code POST /result?deviceId=&uuid=}: resultado do comando entregue (CI-PUSH). Resposta vazia. */
  Response result(Request request) {
   if(!deviceMatches(request)) return Response.badRequest();
   lastSeen=clock.instant();
   Pending pending;
   synchronized(this) { pending=delivered.remove(request.query().getOrDefault("uuid","")); }
   if(pending==null) return Response.empty();
   try {
    var body=Json.object(new String(request.body(),StandardCharsets.UTF_8));
    if(body.get("error")!=null) { pending.result().completeExceptionally(new DeviceReportedError()); return Response.empty(); }
    Object response=body.get("response");
    Map<String,Object> parsed=response instanceof String s?(s.isBlank()?Map.of():Json.object(s)):response==null?Map.of():Json.map(response);
    pending.result().complete(parsed);
   } catch(Json.JsonException e) {
    pending.result().completeExceptionally(e);
   }
   return Response.empty();
  }

  private boolean deviceMatches(Request request) {
   String id=request.query().get("deviceId");
   return nativeDeviceId==null || nativeDeviceId.equals(id);
  }

  static String newUuid() { return UUID.randomUUID().toString(); }
 }
}
