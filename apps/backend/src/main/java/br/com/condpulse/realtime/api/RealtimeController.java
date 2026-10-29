package br.com.condpulse.realtime.api;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.identity.bff.BffCookies;
import br.com.condpulse.identity.bff.WebSessionService;
import br.com.condpulse.realtime.application.RealtimeHub;
import br.com.condpulse.realtime.application.RealtimeService;
import br.com.condpulse.realtime.infrastructure.RealtimeStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Canal de atualização da unidade (CP-036), atrás de CP_REALTIME_ENABLED=false. Só leitura (GET, sem CSRF); a primeira
 * consulta valida tudo antes de abrir o canal, então acesso negado volta como problema JSON e nunca como canal vazio.
 * Os eventos levam apenas nomes de assuntos; nada de dado pessoal ou identificador de registro.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.realtime.enabled",havingValue="true")
public class RealtimeController {
 private final RealtimeService service;
 private final RealtimeHub hub;
 private final ObjectProvider<WebSessionService> sessions;
 private final Clock clock;
 @Autowired
 public RealtimeController(RealtimeService service,RealtimeHub hub,ObjectProvider<WebSessionService> sessions) {
  this(service,hub,sessions,Clock.systemUTC());
 }
 RealtimeController(RealtimeService service,RealtimeHub hub,ObjectProvider<WebSessionService> sessions,Clock clock) {
  this.service=service; this.hub=hub; this.sessions=sessions; this.clock=clock;
 }

 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/events")
 public ResponseEntity<SseEmitter> events(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
   HttpServletRequest request,HttpServletResponse response) throws IOException {
  try {
   UUID account=UUID.fromString(jwt.getSubject());
   RealtimeHub.Credential credential=credential(jwt,account,request);
   var initial=service.snapshot(account,tenantId,unitId);
   var emitter=new SseEmitter(hub.settings().lifetime().plus(hub.settings().tick()).plusSeconds(30).toMillis());
   UUID id=hub.register(account,credential,()->service.snapshot(account,tenantId,unitId),initial,new RealtimeHub.Sink() {
    @Override public void send(String event,String data) throws IOException {
     emitter.send(SseEmitter.event().name(event).data(data,MediaType.APPLICATION_JSON));
    }
    @Override public void complete() { emitter.complete(); }
   });
   emitter.onCompletion(()->hub.remove(id)); emitter.onTimeout(()->hub.remove(id)); emitter.onError(error->hub.remove(id));
   try {
    emitter.send(SseEmitter.event().name("ready").reconnectTime(retryMillis())
     .data(RealtimeHub.topics(RealtimeStore.TOPICS),MediaType.APPLICATION_JSON));
   } catch(IOException gone) { hub.remove(id); emitter.complete(); }
   return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
    .contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  } catch(BusinessProblem problem) {
   // Escrito aqui: o pedido aceita só text/event-stream e o problema precisa chegar como JSON com o status certo
   // (sem passar pela negociação de conteúdo). Retorno nulo encerra a requisição sem canal.
   response.setStatus(problem.status()); response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
   response.setHeader("Cache-Control","no-store");
   response.getWriter().write("{\"type\":\"urn:condpulse:problem:%s\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\",\"traceId\":\"%s\"}"
    .formatted(json(problem.code()),json(problem.getMessage()),problem.status(),json(problem.code()),UUID.randomUUID()));
   response.flushBuffer();
   return null;
  }
 }
 static String json(String value) {
  var out=new StringBuilder();
  for(char c:(value==null?"":value).toCharArray()) {
   if(c=='"' || c=='\\') out.append('\\').append(c);
   else if(c<0x20) out.append(String.format("\\u%04x",(int)c));
   else out.append(c);
  }
  return out.toString();
 }

 /** Reconexão automática do navegador depois de um intervalo, com folga para não martelar a API. */
 private long retryMillis() { return Math.max(3000,hub.settings().tick().toMillis()); }

 /**
  * Token Bearer (celular): vale até expirar; o canal fecha nesse instante. Cookie BFF (web): cada passada confere a sessão
  * no banco, então sair ou ter a sessão removida fecha o canal na passada seguinte.
  */
 private RealtimeHub.Credential credential(Jwt jwt,UUID account,HttpServletRequest request) {
  var expires=jwt.getExpiresAt();
  RealtimeHub.Credential token=()->{
   if(expires==null || !clock.instant().isBefore(expires)) throw new JwtException("REALTIME_TOKEN_EXPIRED");
  };
  if(request.getHeader("Authorization")!=null) return token;
  String raw=BffCookies.read(request);
  WebSessionService web=sessions.getIfAvailable();
  if(raw==null || web==null) return token;
  return ()->{
   var current=web.authenticate(raw);
   if(!account.toString().equalsIgnoreCase(current.getSubject()))
    throw new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar.");
   var currentExpiry=current.getExpiresAt();
   if(currentExpiry==null || !clock.instant().isBefore(currentExpiry)) throw new JwtException("REALTIME_TOKEN_EXPIRED");
  };
 }
}
