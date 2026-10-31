package br.com.condpulse.gateway.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.spi.InboundEndpoint.Request;
import br.com.condpulse.gateway.spi.InboundEndpoint.Response;

/**
 * Servidor de entrada para chamadas iniciadas pelo equipamento (modo online, push e nuvem). Cada equipamento tem um
 * token próprio (cofre) que compõe o caminho configurado nele: {@code /d/<token>/<subcaminho>}. Sem token válido a
 * resposta é 404, sem distinguir "token errado" de "rota inexistente". O token identifica equipamento e condomínio; o
 * corpo nunca escolhe o equipamento. Onde o fabricante não permite configurar caminho, o modo nuvem fica indisponível
 * até haver túnel autenticado aprovado (GATEWAY.md). Logs não contêm token, corpo ou parâmetros.
 */
public final class InboundServer implements AutoCloseable {
 private static final System.Logger LOG=System.getLogger(InboundServer.class.getName());
 public static final String PREFIX="/d/";

 private final HttpServer server;
 private final Map<String,byte[]> tokenDigests=new HashMap<>();
 private final int maxBody;
 private final BiFunction<String,Request,Response> dispatcher;
 private final java.util.concurrent.ExecutorService executor=Executors.newFixedThreadPool(8,r->{ var t=new Thread(r,"gateway-inbound"); t.setDaemon(true); return t; });

 /** dispatcher recebe o deviceId autenticado e a requisição e devolve a resposta do adaptador. */
 public InboundServer(GatewayConfig config,SecretProvider secrets,BiFunction<String,Request,Response> dispatcher) throws IOException {
  var inbound=config.inbound();
  this.maxBody=inbound.maxBodyBytes();
  this.dispatcher=dispatcher;
  for(var d:config.devices()) {
   if(d.inboundTokenRef()==null) continue;
   char[] token=secrets.secret(d.inboundTokenRef()).orElseThrow(()->new IllegalStateException("Token de entrada ausente no cofre: "+d.deviceId()));
   String t=new String(token);
   Arrays.fill(token,'\0');
   if(!t.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalStateException("Token de entrada fora do formato (32 a 128 caracteres seguros): "+d.deviceId());
   tokenDigests.put(d.deviceId(),sha256(t));
  }
  var address=new InetSocketAddress(inbound.bindAddress(),inbound.port());
  if(inbound.tlsKeystorePath()!=null) {
   var https=HttpsServer.create(address,64);
   https.setHttpsConfigurator(new HttpsConfigurator(tls(inbound,secrets)));
   server=https;
  } else {
   server=HttpServer.create(address,64);
  }
  server.createContext("/",this::handle);
  server.setExecutor(executor);
 }

 public void start() { server.start(); }
 public int port() { return server.getAddress().getPort(); }

 private void handle(HttpExchange exchange) throws IOException {
  try(exchange) {
   String path=exchange.getRequestURI().getRawPath();
   if(path==null || !path.startsWith(PREFIX)) { send(exchange,Response.notFound()); return; }
   String rest=path.substring(PREFIX.length());
   int slash=rest.indexOf('/');
   if(slash<=0) { send(exchange,Response.notFound()); return; }
   String deviceId=authenticate(rest.substring(0,slash));
   if(deviceId==null) { send(exchange,Response.notFound()); return; }
   String subPath=rest.substring(slash);
   if(!subPath.matches("/[A-Za-z0-9_./-]{1,120}") || subPath.contains("..")) { send(exchange,Response.notFound()); return; }
   byte[] body;
   try(InputStream in=exchange.getRequestBody()) { body=in.readNBytes(maxBody+1); }
   if(body.length>maxBody) { send(exchange,new Response(413,"text/plain",new byte[0])); return; }
   var request=new Request(exchange.getRequestMethod(),subPath,parseQuery(exchange.getRequestURI().getRawQuery()),
    exchange.getRequestHeaders().getFirst("Content-Type"),body,exchange.getRemoteAddress().getAddress().getHostAddress());
   Response response;
   try { response=dispatcher.apply(deviceId,request); }
   catch(RuntimeException e) {
    LOG.log(System.Logger.Level.WARNING,"Entrada do equipamento recusada: {0}",e.getClass().getSimpleName());
    response=Response.badRequest();
   }
   send(exchange,response);
  }
 }

 private String authenticate(String token) {
  byte[] digest=sha256(token);
  String match=null;
  for(var e:tokenDigests.entrySet()) if(MessageDigest.isEqual(digest,e.getValue())) match=e.getKey();
  return match;
 }

 public static Map<String,String> parseQuery(String raw) {
  var out=new LinkedHashMap<String,String>();
  if(raw==null || raw.isEmpty()) return out;
  for(String pair:raw.split("&")) {
   if(pair.isEmpty()) continue;
   int eq=pair.indexOf('=');
   String k=URLDecoder.decode(eq<0?pair:pair.substring(0,eq),StandardCharsets.UTF_8);
   String v=eq<0?"":URLDecoder.decode(pair.substring(eq+1),StandardCharsets.UTF_8);
   if(out.size()<64) out.putIfAbsent(k,v);
  }
  return out;
 }

 private static void send(HttpExchange exchange,Response response) throws IOException {
  if(response.contentType()!=null) exchange.getResponseHeaders().set("Content-Type",response.contentType());
  exchange.getResponseHeaders().set("Cache-Control","no-store");
  byte[] body=response.body()==null?new byte[0]:response.body();
  exchange.sendResponseHeaders(response.status(),body.length==0?-1:body.length);
  if(body.length>0) try(OutputStream out=exchange.getResponseBody()) { out.write(body); }
 }

 private static SSLContext tls(GatewayConfig.Inbound inbound,SecretProvider secrets) {
  char[] password=secrets.secret(inbound.tlsKeystorePasswordRef()).orElseThrow(()->new IllegalStateException("Senha do keystore de entrada ausente."));
  try(InputStream in=Files.newInputStream(Path.of(inbound.tlsKeystorePath()))) {
   var ks=KeyStore.getInstance("PKCS12");
   ks.load(in,password);
   var kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
   kmf.init(ks,password);
   var ctx=SSLContext.getInstance("TLS");
   ctx.init(kmf.getKeyManagers(),null,null);
   return ctx;
  } catch(Exception e) {
   throw new IllegalStateException("Keystore de entrada ilegível.");
  } finally { Arrays.fill(password,'\0'); }
 }

 static byte[] sha256(String value) {
  try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
  catch(Exception e) { throw new IllegalStateException(e); }
 }

 @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
