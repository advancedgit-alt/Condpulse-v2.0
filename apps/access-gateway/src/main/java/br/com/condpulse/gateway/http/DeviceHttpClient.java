package br.com.condpulse.gateway.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.gateway.config.SecretProvider.DeviceCredential;

/**
 * Cliente HTTP de um único equipamento. Regras (manual 12, 15.1 e 16.2):
 * <ul>
 * <li>host e porta vêm só da configuração; o caminho precisa estar na lista fechada do adaptador; parâmetros são
 * codificados — o gateway não executa URL, método ou comando arbitrário;</li>
 * <li>sem repetição implícita: escrita (POST/PUT) e ação física ({@code actuation}, inclusive por GET) vão por
 * {@link SingleShotHttp} — uma requisição, uma conexão, sem o reenvio automático que o {@code HttpClient} do JDK faz
 * em métodos idempotentes quando a conexão cai antes dos cabeçalhos; o {@code HttpClient} fica só com leituras GET.
 * A única segunda tentativa é a resposta ao desafio 401 Digest, que comprova que o pedido anterior não foi
 * executado;</li>
 * <li>falha antes do envio vira {@link Failure#NOT_SENT}; qualquer outra, {@link Failure#AMBIGUOUS};</li>
 * <li>nenhum log contém URL (o Control iD leva a sessão na query), corpo, foto ou credencial.</li>
 * </ul>
 */
public final class DeviceHttpClient {
 public static final int MAX_RESPONSE_BYTES=8*1024*1024;
 private static final System.Logger LOG=System.getLogger(DeviceHttpClient.class.getName());
 private static final ScheduledExecutorService STREAM_CLOSER=Executors.newSingleThreadScheduledExecutor(r->{
  var t=new Thread(r,"gateway-stream-closer"); t.setDaemon(true); return t;
 });

 public enum Failure { NOT_SENT, AMBIGUOUS }
 public static final class DeviceCallException extends Exception {
  private final Failure failure;
  public DeviceCallException(Failure failure,String reason) { super(reason,null,false,false); this.failure=failure; }
  public Failure failure() { return failure; }
 }
 public record Response(int status,String contentType,byte[] body) {
  public String text() { return new String(body,StandardCharsets.UTF_8); }
  public boolean ok() { return status>=200 && status<300; }
 }

 private final URI base;
 private final Duration timeout;
 private final DeviceCredential credential;
 private final Set<String> allowedPaths;
 private final HttpClient shared;
 private volatile DigestAuth digest;

 public DeviceHttpClient(URI base,Duration timeout,DeviceCredential credential,Set<String> allowedPaths) {
  this.base=base; this.timeout=timeout; this.credential=credential; this.allowedPaths=Set.copyOf(allowedPaths);
  for(String p:allowedPaths) if(!p.matches("/[A-Za-z0-9_./-]{1,120}") || p.contains("..")) throw new IllegalArgumentException("Caminho de adaptador inválido: "+p);
  this.shared=newClient();
 }

 private HttpClient newClient() {
  return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
 }

 /**
  * Chamada a um caminho fixo do adaptador. {@code query} preserva a ordem; nomes aceitam só letras, números, ponto,
  * sublinhado e colchetes (ex.: {@code Doors[0]}).
  */
 public Response call(String method,String path,Map<String,String> query,String contentType,byte[] body,boolean actuation) throws DeviceCallException {
  URI uri=uri(path,query);
  if(!method.equals("GET") && !method.equals("POST") && !method.equals("PUT")) throw new IllegalArgumentException("Método não permitido.");
  // Escrita (POST/PUT) e ação física (inclusive por GET): uma requisição, uma conexão, nenhum reenvio automático.
  if(actuation || !"GET".equals(method)) return singleShot(method,uri,contentType,body);
  var client=shared;
  try {
   var response=send(client,method,uri,contentType,body,digest==null?null:authorization(method,uri));
   if(response.statusCode()==401 && credential!=null) {
    var challenge=DigestAuth.fromChallenge(response.headers().firstValue("WWW-Authenticate").orElse(null));
    if(challenge==null) return read(response);
    response.body().close();
    digest=challenge;
    // 401 comprova que o pedido não foi executado; este é o único reenvio permitido.
    response=send(client,method,uri,contentType,body,authorization(method,uri));
   }
   return read(response);
  } catch(HttpConnectTimeoutException|ConnectException|UnresolvedAddressException e) {
   throw new DeviceCallException(Failure.NOT_SENT,"DEVICE_UNREACHABLE");
  } catch(IOException e) {
   if(e.getCause() instanceof ConnectException || e.getCause() instanceof UnknownHostException) throw new DeviceCallException(Failure.NOT_SENT,"DEVICE_UNREACHABLE");
   LOG.log(System.Logger.Level.WARNING,"Chamada ao equipamento sem resposta conclusiva: {0}",e.getClass().getSimpleName());
   throw new DeviceCallException(Failure.AMBIGUOUS,"RESPONSE_NOT_RECEIVED");
  } catch(InterruptedException e) {
   Thread.currentThread().interrupt();
   throw new DeviceCallException(Failure.AMBIGUOUS,"INTERRUPTED");
  }
 }

 private Response singleShot(String method,URI uri,String contentType,byte[] body) throws DeviceCallException {
  try {
   var result=SingleShotHttp.exchange(uri,method,contentType,body,digest==null?null:authorization(method,uri),timeout,MAX_RESPONSE_BYTES,STREAM_CLOSER);
   if(result.status()==401 && credential!=null) {
    var challenge=DigestAuth.fromChallenge(result.header("WWW-Authenticate"));
    if(challenge!=null) {
     digest=challenge;
     // 401 comprova que o pedido não foi executado; este é o único reenvio permitido.
     result=SingleShotHttp.exchange(uri,method,contentType,body,authorization(method,uri),timeout,MAX_RESPONSE_BYTES,STREAM_CLOSER);
    }
   }
   return new Response(result.status(),result.header("Content-Type"),result.body());
  } catch(SingleShotHttp.NotSentException e) {
   throw new DeviceCallException(Failure.NOT_SENT,"DEVICE_UNREACHABLE");
  } catch(IOException e) {
   LOG.log(System.Logger.Level.WARNING,"Chamada ao equipamento sem resposta conclusiva: {0}",e.getClass().getSimpleName());
   throw new DeviceCallException(Failure.AMBIGUOUS,"RESPONSE_NOT_RECEIVED");
  }
 }

 /**
  * Lê um fluxo contínuo (ex.: eventos em multipart) por no máximo {@code window} ou {@code maxBytes} e fecha. O
  * equipamento é só lido; nada é executado.
  */
 public Response stream(String path,Map<String,String> query,Duration window,int maxBytes) throws DeviceCallException {
  URI uri=uri(path,query);
  try {
   // timeout da requisição vale até os cabeçalhos; o corpo é limitado pela janela abaixo.
   var builder=HttpRequest.newBuilder(uri).timeout(timeout).GET();
   if(digest!=null) builder.header("Authorization",authorization("GET",uri));
   var response=shared.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
   if(response.statusCode()==401 && credential!=null) {
    response.body().close();
    var challenge=DigestAuth.fromChallenge(response.headers().firstValue("WWW-Authenticate").orElse(null));
    if(challenge==null) return new Response(401,null,new byte[0]);
    digest=challenge;
    response=shared.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().header("Authorization",authorization("GET",uri)).build(),HttpResponse.BodyHandlers.ofInputStream());
   }
   InputStream in=response.body();
   var closer=STREAM_CLOSER.schedule(()->{ try { in.close(); } catch(IOException ignored) {} },window.toMillis(),TimeUnit.MILLISECONDS);
   var out=new ByteArrayOutputStream();
   byte[] buffer=new byte[8192];
   try {
    int n;
    while(out.size()<maxBytes && (n=in.read(buffer,0,Math.min(buffer.length,maxBytes-out.size())))>=0) out.write(buffer,0,n);
   } catch(IOException closedByWindow) {
    // Fim da janela de leitura.
   } finally {
    closer.cancel(false);
    try { in.close(); } catch(IOException ignored) {}
   }
   return new Response(response.statusCode(),response.headers().firstValue("Content-Type").orElse(null),out.toByteArray());
  } catch(HttpConnectTimeoutException|ConnectException|UnresolvedAddressException e) {
   throw new DeviceCallException(Failure.NOT_SENT,"DEVICE_UNREACHABLE");
  } catch(IOException e) {
   throw new DeviceCallException(Failure.AMBIGUOUS,"RESPONSE_NOT_RECEIVED");
  } catch(InterruptedException e) {
   Thread.currentThread().interrupt();
   throw new DeviceCallException(Failure.AMBIGUOUS,"INTERRUPTED");
  }
 }

 /** Fluxo contínuo aberto (assinatura de eventos). Quem chama lê, vigia (watchdog) e fecha. */
 public record OpenStream(int status,InputStream body) implements AutoCloseable {
  @Override public void close() { try { body.close(); } catch(IOException ignored) {} }
 }

 /**
  * Abre um fluxo de leitura contínuo com Digest. Os cabeçalhos têm o prazo da chamada; o corpo não tem prazo
  * próprio — quem lê precisa de watchdog (ex.: heartbeat) para fechar fluxo parado.
  */
 public OpenStream openStream(String path,Map<String,String> query) throws DeviceCallException {
  URI uri=uri(path,query);
  try {
   var builder=HttpRequest.newBuilder(uri).timeout(timeout).GET();
   if(digest!=null) builder.header("Authorization",authorization("GET",uri));
   var response=shared.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
   if(response.statusCode()==401 && credential!=null) {
    response.body().close();
    var challenge=DigestAuth.fromChallenge(response.headers().firstValue("WWW-Authenticate").orElse(null));
    if(challenge==null) return new OpenStream(401,InputStream.nullInputStream());
    digest=challenge;
    response=shared.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().header("Authorization",authorization("GET",uri)).build(),HttpResponse.BodyHandlers.ofInputStream());
   }
   return new OpenStream(response.statusCode(),response.body());
  } catch(HttpConnectTimeoutException|ConnectException|UnresolvedAddressException e) {
   throw new DeviceCallException(Failure.NOT_SENT,"DEVICE_UNREACHABLE");
  } catch(IOException e) {
   throw new DeviceCallException(Failure.AMBIGUOUS,"RESPONSE_NOT_RECEIVED");
  } catch(InterruptedException e) {
   Thread.currentThread().interrupt();
   throw new DeviceCallException(Failure.AMBIGUOUS,"INTERRUPTED");
  }
 }

 private HttpResponse<InputStream> send(HttpClient client,String method,URI uri,String contentType,byte[] body,String authorization) throws IOException,InterruptedException {
  if(!method.equals("GET") && !method.equals("POST") && !method.equals("PUT")) throw new IllegalArgumentException("Método não permitido.");
  var builder=HttpRequest.newBuilder(uri).timeout(timeout)
   .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
  if(contentType!=null) builder.header("Content-Type",contentType);
  if(authorization!=null) builder.header("Authorization",authorization);
  return client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
 }

 /** Lê o corpo com o mesmo prazo da chamada: corpo que para no meio é cortado e vira falha ambígua. */
 private Response read(HttpResponse<InputStream> response) throws IOException {
  InputStream in=response.body();
  var closer=STREAM_CLOSER.schedule(()->{ try { in.close(); } catch(IOException ignored) {} },timeout.toMillis(),TimeUnit.MILLISECONDS);
  try(in) {
   byte[] bytes=in.readNBytes(MAX_RESPONSE_BYTES+1);
   if(closer.isDone()) throw new IOException("Prazo de leitura do corpo esgotado.");
   if(bytes.length>MAX_RESPONSE_BYTES) throw new IOException("Resposta acima do limite.");
   return new Response(response.statusCode(),response.headers().firstValue("Content-Type").orElse(null),bytes);
  } finally {
   closer.cancel(false);
  }
 }

 private String authorization(String method,URI uri) {
  var d=digest;
  if(d==null || credential==null) return null;
  String requestUri=uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
  return d.authorization(method,requestUri,credential.username(),credential.password());
 }

 URI uri(String path,Map<String,String> query) {
  if(!allowedPaths.contains(path)) throw new IllegalArgumentException("Caminho fora da lista do adaptador.");
  var q=new StringBuilder();
  for(var e:new LinkedHashMap<>(query).entrySet()) {
   if(!e.getKey().matches("[A-Za-z0-9_.\\[\\]]{1,64}")) throw new IllegalArgumentException("Parâmetro fora do formato.");
   if(e.getValue()==null) throw new IllegalArgumentException("Parâmetro sem valor.");
   if(q.length()>0) q.append('&');
   q.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8).replace("+","%20"));
  }
  String authority=base.getRawAuthority();
  return URI.create(base.getScheme()+"://"+authority+path+(q.length()==0?"":"?"+q));
 }
}
