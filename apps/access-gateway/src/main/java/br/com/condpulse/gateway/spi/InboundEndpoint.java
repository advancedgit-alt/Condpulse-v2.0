package br.com.condpulse.gateway.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import br.com.condpulse.gateway.spi.Spi.EdgeEvent;

/**
 * Chamadas que o equipamento faz ao gateway (modo online, push ou nuvem). O servidor de entrada já autenticou o
 * equipamento pelo token do caminho antes de chegar aqui; o subcaminho é comparado com a lista fechada do adaptador.
 */
public interface InboundEndpoint {
 Response handle(Request request,Context context);

 record Request(String method,String subPath,Map<String,String> query,String contentType,byte[] body,String remoteAddress) {
  public Request { query=Map.copyOf(query); }
 }
 record Response(int status,String contentType,byte[] body) {
  public static Response empty() { return new Response(200,"text/plain",new byte[0]); }
  public static Response notFound() { return new Response(404,"text/plain",new byte[0]); }
  public static Response badRequest() { return new Response(400,"text/plain",new byte[0]); }
  public static Response json(String json) { return new Response(200,"application/json",json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
 }

 /** Serviços do núcleo disponíveis ao adaptador durante a chamada. */
 interface Context {
  Instant now();
  /** Decisão local antes do relé (EDGE_ONLINE). Nunca consulta a nuvem no caminho do relé. */
  LocalDecision decide(String nativeUserRef,String nativeChannel);
  /** Grava eventos na fila durável local antes de responder ao equipamento. */
  void events(List<EdgeEvent> events);
  /** Batimento recebido do equipamento (modo nuvem/contingência). */
  void heartbeat();
 }

 /** Resultado da decisão local. allow só é true com contexto vigente, relógio confiável e política fresca. */
 record LocalDecision(boolean allow,List<String> reasonCodes,String subjectRef,String channelId) {
  public LocalDecision { reasonCodes=List.copyOf(reasonCodes); }
 }
}
