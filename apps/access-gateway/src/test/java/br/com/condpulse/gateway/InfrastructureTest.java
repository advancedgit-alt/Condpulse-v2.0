package br.com.condpulse.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import br.com.condpulse.gateway.config.ConfigLoader;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.http.DeviceHttpClient;
import br.com.condpulse.gateway.http.DeviceHttpClient.DeviceCallException;
import br.com.condpulse.gateway.http.DigestAuth;
import br.com.condpulse.gateway.inbound.InboundServer;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.spi.InboundEndpoint;
import br.com.condpulse.gateway.spi.Spi.*;
import br.com.condpulse.gateway.support.FakeDeviceServer;
import br.com.condpulse.gateway.support.FakeDeviceServer.Reply;
import br.com.condpulse.gateway.support.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** JSON, Digest, configuração, servidor de entrada e cliente HTTP dos equipamentos. */
class InfrastructureTest {

 @Test void jsonIdaEVoltaRecusaChaveRepetidaEConteudoExtra() {
  var m=new LinkedHashMap<String,Object>();
  m.put("a","x\"y\n"); m.put("b",List.of(1L,true,Map.of("c",new java.math.BigDecimal("1.5"))));
  m.put("n",null);
  assertEquals(m,Json.parse(Json.write(m)));
  assertThrows(Json.JsonException.class,()->Json.parse("{\"a\":1,\"a\":2}"));
  assertThrows(Json.JsonException.class,()->Json.parse("{} x"));
 }

 /** Vetor da RFC 2617 §3.5 (usuário Mufasa). */
 @Test void digestReproduzOVetorDaRfc() throws Exception {
  var d=DigestAuth.fromChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
  var m=DigestAuth.class.getDeclaredMethod("authorization",String.class,String.class,String.class,char[].class,String.class);
  m.setAccessible(true);
  String header=(String)m.invoke(d,"GET","/dir/index.html","Mufasa","Circle Of Life".toCharArray(),"0a4f113b");
  assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""),header);
  assertTrue(header.contains("nc=00000001"));
  assertNull(DigestAuth.fromChallenge("Basic realm=\"x\""));
 }

 @Test void configuracaoRecusaCampoDesconhecidoEModosIncoerentes() {
  String base="""
   {"gatewayId":"g","connectorId":"c","zone":"America/Sao_Paulo","devices":[%s]%s}""";
  String local="""
   {"deviceId":"d1","deviceProvisioningId":"p1","label":"Portaria","brand":"CONTROL_ID","sku":"iDFace","firmware":"x","apiVersion":"x",
    "connectionMode":"LOCAL","decisionMode":"DEVICE_LOCAL_ACL","baseUrl":"http://192.0.2.10","credentialRef":"cred-d1","eventEpoch":"e1",
    "phase":"BANCADA","installationRevision":1,"channels":[{"channelId":"c1","pointId":"p","bindingRevision":1,"nativeChannel":"1","direction":"IN"}]}""";
  var cfg=ConfigLoader.parse(base.formatted(local,""));
  assertFalse(cfg.devices().getFirst().enabled(),"equipamento começa desligado");
  assertFalse(cfg.devices().getFirst().openEnabled(),"abertura começa desligada");
  assertTrue(cfg.openPolicy().allowedOrigins().isEmpty());
  assertThrows(IllegalArgumentException.class,()->ConfigLoader.parse(base.formatted(local,",\"extra\":1")));
  assertThrows(IllegalArgumentException.class,()->ConfigLoader.parse(base.formatted(local.replace("http://192.0.2.10","http://192.0.2.10/cgi-bin/x"),"")));
  assertThrows(IllegalArgumentException.class,()->ConfigLoader.parse(base.formatted(local.replace("\"LOCAL\"","\"NUVEM\""),"")),"nuvem sem token e com baseUrl");
  assertThrows(IllegalArgumentException.class,()->ConfigLoader.parse(base.formatted(local,",\"openPolicy\":{\"allowedOrigins\":[\"MORADOR_APP\"]}")),
   "morador pelo app exige a opção do condomínio ligada");
  String twoSameNative=local.replace("\"direction\":\"IN\"}]","\"direction\":\"IN\"},{\"channelId\":\"c2\",\"pointId\":\"p2\",\"bindingRevision\":1,\"nativeChannel\":\"1\",\"direction\":\"OUT\"}]");
  assertNotEquals(local,twoSameNative);
  var error=assertThrows(IllegalArgumentException.class,()->ConfigLoader.parse(base.formatted(twoSameNative,"")),
   "canal nativo repetido no mesmo equipamento tornaria a decisão local ambígua");
  assertTrue(error.getMessage().contains("Canal nativo repetido"),error.getMessage());
  assertEquals(2,ConfigLoader.parse(base.formatted(twoSameNative.replace("\"nativeChannel\":\"1\",\"direction\":\"OUT\"","\"nativeChannel\":\"2\",\"direction\":\"OUT\""),""))
   .devices().getFirst().channels().size(),"canais nativos distintos continuam aceitos");
 }

 @Test void exemploDeConfiguracaoDoRepositorioEValidoEComecaDesligado() {
  var file=java.nio.file.Path.of("../../infra/access-gateway/gateway.example.json");
  var cfg=ConfigLoader.load(file);
  assertTrue(cfg.devices().stream().noneMatch(d->d.enabled() || d.openEnabled()));
  assertTrue(cfg.openPolicy().allowedOrigins().isEmpty());
  assertFalse(cfg.openPolicy().residentAppOpenEnabled());
 }

 @Test void credencialNaoApareceEmToString() {
  var c=new SecretProvider.DeviceCredential("admin","segredo-sintetico".toCharArray());
  assertFalse(c.toString().contains("segredo"));
  assertFalse(c.toString().contains("admin"));
  assertFalse(new CredentialSpec("s",CredentialKind.PIN,"123456").toString().contains("123456"));
 }

 @Test void servidorDeEntradaSoAceitaTokenDoEquipamento() throws Exception {
  var device=TestSupport.device("d1",Brand.INTELBRAS,ConnectionMode.NUVEM,DecisionMode.EDGE_ONLINE,null,
   List.of(TestSupport.channel("c1","1",null)),Map.of(),Map.of(),false);
  var config=TestSupport.config(List.of(device),true,br.com.condpulse.gateway.config.GatewayConfig.CondoOpenPolicy.closed());
  String token="tokensinteticodeentrada_0123456789abcdef";
  var seen=new java.util.ArrayList<String>();
  try(var server=new InboundServer(config,TestSupport.secrets(Map.of("token-d1",token)),(id,req)->{ seen.add(id+" "+req.subPath()); return InboundEndpoint.Response.json("{}"); })) {
   server.start();
   var client=HttpClient.newHttpClient();
   String base="http://127.0.0.1:"+server.port();
   var ok=client.send(HttpRequest.newBuilder(URI.create(base+"/d/"+token+"/keepalive")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,ok.statusCode());
   var wrong=client.send(HttpRequest.newBuilder(URI.create(base+"/d/outrotokensinteticodeentrada_01234567/keepalive")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(404,wrong.statusCode());
   var traversal=client.send(HttpRequest.newBuilder(URI.create(base+"/d/"+token+"/a/../b")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(404,traversal.statusCode());
   assertEquals(List.of("d1 /keepalive"),seen);
  }
 }

 @Test void servidorDeEntradaRecusaTokenCompartilhadoEntreEquipamentos() throws Exception {
  var d1=TestSupport.device("d1",Brand.INTELBRAS,ConnectionMode.NUVEM,DecisionMode.EDGE_ONLINE,null,List.of(TestSupport.channel("c1","1",null)),Map.of(),Map.of(),false);
  var d2=TestSupport.device("d2",Brand.INTELBRAS,ConnectionMode.NUVEM,DecisionMode.EDGE_ONLINE,null,List.of(TestSupport.channel("c2","1",null)),Map.of(),Map.of(),false);
  var config=TestSupport.config(List.of(d1,d2),true,br.com.condpulse.gateway.config.GatewayConfig.CondoOpenPolicy.closed());
  String shared="tokensinteticocompartilhado_0123456789ab";
  var seen=new java.util.ArrayList<String>();
  var error=assertThrows(IllegalStateException.class,()->new InboundServer(config,TestSupport.secrets(Map.of("token-d1",shared,"token-d2",shared)),
   (id,req)->{ seen.add(id); return InboundEndpoint.Response.json("{}"); }),"mesmo token resolvido para dois equipamentos tornaria a origem ambígua");
  assertTrue(error.getMessage().contains("Token de entrada repetido"),error.getMessage());
  assertTrue(seen.isEmpty());
  String t1="tokensinteticod1_0123456789abcdef0123", t2="tokensinteticod2_0123456789abcdef0123";
  try(var server=new InboundServer(config,TestSupport.secrets(Map.of("token-d1",t1,"token-d2",t2)),(id,req)->{ seen.add(id); return InboundEndpoint.Response.json("{}"); })) {
   server.start();
   var client=HttpClient.newHttpClient();
   String base="http://127.0.0.1:"+server.port();
   assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(base+"/d/"+t2+"/keepalive")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
   assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(base+"/d/"+t1+"/keepalive")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
   assertEquals(List.of("d2","d1"),seen,"tokens distintos resolvem cada um o seu equipamento");
  }
 }

 /**
  * Servidor que lê o pedido inteiro e fecha a conexão sem responder: é o caso em que o HttpClient do JDK reenvia
  * GET/PUT sozinho. Conta quantos pedidos chegaram.
  */
 static final class DropAfterRequestServer implements AutoCloseable {
  final java.net.ServerSocket socket=new java.net.ServerSocket(0,50,java.net.InetAddress.getLoopbackAddress());
  final java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
  final Thread loop;
  DropAfterRequestServer() throws java.io.IOException {
   loop=new Thread(()->{
    while(!socket.isClosed()) {
     try(var client=socket.accept()) {
      var in=client.getInputStream();
      var head=new StringBuilder();
      int b;
      while((b=in.read())>=0) { head.append((char)b); if(head.toString().endsWith("\r\n\r\n")) break; }
      var m=java.util.regex.Pattern.compile("(?i)content-length: *(\\d+)").matcher(head);
      if(m.find()) in.readNBytes(Integer.parseInt(m.group(1)));
      if(head.length()>0) received.incrementAndGet();
     } catch(java.io.IOException ignored) {}
    }
   },"drop-after-request");
   loop.setDaemon(true); loop.start();
  }
  URI base() { return URI.create("http://127.0.0.1:"+socket.getLocalPort()); }
  @Override public void close() throws java.io.IOException { socket.close(); }
 }

 @Test void escritaEAcaoFisicaNaoSaoReenviadasQuandoAConexaoCaiAntesDaResposta() throws Exception {
  try(var server=new DropAfterRequestServer()) {
   var http=new DeviceHttpClient(server.base(),Duration.ofSeconds(2),null,Set.of("/abrir.cgi","/gravar"));
   for(String[] c:new String[][]{{"GET","true"},{"PUT","false"},{"POST","false"},{"POST","true"}}) {
    int before=server.received.get();
    byte[] body="GET".equals(c[0])?null:"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String path="GET".equals(c[0])?"/abrir.cgi":"/gravar";
    var e=assertThrows(DeviceCallException.class,()->http.call(c[0],path,Map.of("channel","1"),body==null?null:"application/json",body,Boolean.parseBoolean(c[1])));
    assertEquals(DeviceHttpClient.Failure.AMBIGUOUS,e.failure(),"pedido entregue sem resposta é incerto: "+c[0]);
    Thread.sleep(300);
    assertEquals(before+1,server.received.get(),"exatamente um pedido no equipamento, sem reenvio automático: "+c[0]+" actuation="+c[1]);
   }
  }
 }

 @Test void clienteDoEquipamentoSoUsaCaminhosDaListaENaoRepete() throws Exception {
  try(var fake=new FakeDeviceServer()) {
   fake.on("POST","/ok.cgi",Reply.text("OK")).on("POST","/lento.cgi",Reply.text("OK").delayed(1500));
   var http=new DeviceHttpClient(fake.baseUrl(),Duration.ofMillis(500),null,Set.of("/ok.cgi","/lento.cgi"));
   assertThrows(IllegalArgumentException.class,()->http.call("POST","/outro.cgi",Map.of(),null,null,false));
   assertThrows(IllegalArgumentException.class,()->http.call("DELETE","/ok.cgi",Map.of(),null,null,false));
   assertThrows(IllegalArgumentException.class,()->http.call("POST","/ok.cgi",Map.of("a&b","x"),null,null,false));
   assertEquals("OK",http.call("POST","/ok.cgi",Map.of("v","a b&c"),null,null,false).text());
   assertEquals("v=a%20b%26c",fake.requests("/ok.cgi").getFirst().query());
   var e=assertThrows(DeviceCallException.class,()->http.call("POST","/lento.cgi",Map.of(),null,null,true));
   assertEquals(DeviceHttpClient.Failure.AMBIGUOUS,e.failure());
   Thread.sleep(1200);
   assertEquals(1,fake.requests("/lento.cgi").size(),"ação física sem repetição implícita");
  }
  var down=new DeviceHttpClient(URI.create("http://127.0.0.1:1"),Duration.ofMillis(500),null,Set.of("/ok.cgi"));
  var e=assertThrows(DeviceCallException.class,()->down.call("POST","/ok.cgi",Map.of(),null,null,true));
  assertEquals(DeviceHttpClient.Failure.NOT_SENT,e.failure());
 }

 @Test void clienteRespondeAoDesafioDigestUmaUnicaVez() throws Exception {
  try(var fake=new FakeDeviceServer().requireDigest("equipamento-sintetico")) {
   fake.on("GET","/cgi-bin/x.cgi",Reply.text("OK"));
   var http=new DeviceHttpClient(fake.baseUrl(),Duration.ofSeconds(2),new SecretProvider.DeviceCredential("u","p".toCharArray()),Set.of("/cgi-bin/x.cgi"));
   assertEquals("OK",http.call("GET","/cgi-bin/x.cgi",Map.of("action","y"),null,null,true).text());
   var reqs=fake.requests("/cgi-bin/x.cgi");
   assertEquals(2,reqs.size());
   assertNull(reqs.get(0).authorization());
   assertTrue(reqs.get(1).authorization().contains("uri=\"/cgi-bin/x.cgi?action=y\""));
  }
 }

 @Test void corposIncompletosNaoPrendemOAtendimentoDosDemais() throws Exception {
  var device=TestSupport.device("d1",Brand.INTELBRAS,ConnectionMode.NUVEM,DecisionMode.EDGE_ONLINE,null,
   List.of(TestSupport.channel("c1","1",null)),Map.of(),Map.of(),false);
  var config=TestSupport.config(List.of(device),true,br.com.condpulse.gateway.config.GatewayConfig.CondoOpenPolicy.closed());
  String token="tokensinteticodeentrada_0123456789abcdef";
  try(var server=new InboundServer(config,TestSupport.secrets(Map.of("token-d1",token)),(id,req)->InboundEndpoint.Response.json("{}"))) {
   server.start();
   var stalled=new java.util.ArrayList<java.net.Socket>();
   try {
    for(int i=0;i<12;i++) {
     var sock=new java.net.Socket("127.0.0.1",server.port());
     sock.getOutputStream().write(("POST /d/"+token+"/notification HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\nparcial").getBytes());
     sock.getOutputStream().flush();
     stalled.add(sock);
    }
    Thread.sleep(200);
    var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var ok=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/d/"+token+"/keepalive")).timeout(Duration.ofSeconds(3)).GET().build(),
     HttpResponse.BodyHandlers.ofString());
    assertEquals(200,ok.statusCode(),"outro equipamento atendido apesar de 12 corpos incompletos");
   } finally { for(var sock:stalled) sock.close(); }
  }
  assertEquals("5",System.getProperty("sun.net.httpserver.maxReqTime"),"prazo de requisição configurado antes do servidor");
 }
}
