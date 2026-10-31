package br.com.condpulse.gateway.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP falso que imita respostas documentadas de um fabricante (equivalente ao MockWebServer, só com JDK).
 * Simulador não é homologação: só prova que o adaptador fala o formato documentado.
 */
public final class FakeDeviceServer implements AutoCloseable {
 public record Recorded(String method,String path,String query,String contentType,String authorization,byte[] body) {
  public String text() { return new String(body,StandardCharsets.UTF_8); }
 }
 public record Reply(int status,String contentType,String body,Map<String,String> headers,long delayMillis) {
  public static Reply json(String body) { return new Reply(200,"application/json",body,Map.of(),0); }
  public static Reply text(String body) { return new Reply(200,"text/plain",body,Map.of(),0); }
  public static Reply status(int status) { return new Reply(status,"text/plain","",Map.of(),0); }
  public Reply delayed(long millis) { return new Reply(status,contentType,body,headers,millis); }
 }

 private final HttpServer server;
 private final List<Recorded> requests=new ArrayList<>();
 private final Map<String,Function<Recorded,Reply>> routes=new HashMap<>();
 private volatile String digestRealm;

 public FakeDeviceServer() throws IOException {
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);
  server.createContext("/",exchange->{
   try(exchange) {
    byte[] body;
    try(InputStream in=exchange.getRequestBody()) { body=in.readAllBytes(); }
    var uri=exchange.getRequestURI();
    String auth=exchange.getRequestHeaders().getFirst("Authorization");
    var rec=new Recorded(exchange.getRequestMethod(),uri.getRawPath(),uri.getRawQuery(),exchange.getRequestHeaders().getFirst("Content-Type"),auth,body);
    synchronized(requests) { requests.add(rec); }
    Reply reply;
    if(digestRealm!=null && (auth==null || !auth.startsWith("Digest "))) {
     reply=new Reply(401,"text/plain","",Map.of("WWW-Authenticate","Digest realm=\""+digestRealm+"\", qop=\"auth\", nonce=\"abc123\", opaque=\"o1\""),0);
    } else {
     var route=routes.get(exchange.getRequestMethod()+" "+uri.getRawPath());
     reply=route==null?Reply.status(404):route.apply(rec);
    }
    if(reply.delayMillis()>0) { try { Thread.sleep(reply.delayMillis()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
    reply.headers().forEach((k,v)->exchange.getResponseHeaders().set(k,v));
    if(reply.contentType()!=null) exchange.getResponseHeaders().set("Content-Type",reply.contentType());
    byte[] out=reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(reply.status(),out.length==0?-1:out.length);
    if(out.length>0) try(OutputStream os=exchange.getResponseBody()) { os.write(out); }
   }
  });
  server.start();
 }

 public FakeDeviceServer requireDigest(String realm) { this.digestRealm=realm; return this; }
 public FakeDeviceServer on(String method,String path,Function<Recorded,Reply> handler) { routes.put(method+" "+path,handler); return this; }
 public FakeDeviceServer on(String method,String path,Reply reply) { return on(method,path,r->reply); }
 public URI baseUrl() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
 public List<Recorded> requests() { synchronized(requests) { return List.copyOf(requests); } }
 public List<Recorded> requests(String path) { return requests().stream().filter(r->r.path().equals(path)).toList(); }
 public void clear() { synchronized(requests) { requests.clear(); } }

 @Override public void close() { server.stop(0); }
}
