package br.com.condpulse.files;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import br.com.condpulse.files.application.AttachmentStorage;
import br.com.condpulse.files.infrastructure.SupabaseAttachmentStorage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cliente do Storage contra um servidor local fictício: caminhos, cabeçalhos, URLs assinadas aceitas e recusadas. */
class SupabaseAttachmentStorageTest {
 private static final String BUCKET="cp-upload-quarantine";
 private static final String KEY="11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222";
 private static final String FAKE_KEY="chave-de-servico-ficticia-para-teste";
 private HttpServer server;
 private URI base;
 private final List<String> seen=new CopyOnWriteArrayList<>();
 private volatile int status=200;
 private volatile byte[] body=new byte[0];

 @BeforeEach void start() throws Exception {
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   seen.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath()+" "
    +exchange.getRequestHeaders().getFirst("Authorization")+" "+exchange.getRequestHeaders().getFirst("apikey")+" "
    +new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   exchange.sendResponseHeaders(status,body.length==0?-1:body.length);
   if(body.length>0) exchange.getResponseBody().write(body);
   exchange.close();
  });
  server.start();
  base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
 }
 @AfterEach void stop() { server.stop(0); }
 private SupabaseAttachmentStorage storage() { return new SupabaseAttachmentStorage(HttpClient.newHttpClient(),base,FAKE_KEY); }
 private void respond(int code,String text) { status=code; body=text.getBytes(StandardCharsets.UTF_8); }

 @Test void signsUploadForTheExactObjectWithServerSideCredentials() {
  respond(200,"{\"url\":\"/object/upload/sign/"+BUCKET+"/"+KEY+"?token=eyJhbGciOi.synthetic_token-123\"}");
  URI url=storage().signUpload(BUCKET,KEY);
  assertEquals(base+"/storage/v1/object/upload/sign/"+BUCKET+"/"+KEY+"?token=eyJhbGciOi.synthetic_token-123",url.toString());
  assertEquals("POST /storage/v1/object/upload/sign/"+BUCKET+"/"+KEY+" Bearer "+FAKE_KEY+" "+FAKE_KEY+" {}",seen.getFirst());
 }

 @Test void refusesSignedUrlsForAnotherObjectOrWithoutToken() {
  respond(200,"{\"url\":\"/object/upload/sign/cp-documents/"+KEY+"?token=eyJhbGciOi.synthetic_token-123\"}");
  assertThrows(AttachmentStorage.StorageUnavailable.class,()->storage().signUpload(BUCKET,KEY));
  respond(200,"{\"url\":\"/object/upload/sign/"+BUCKET+"/"+KEY+"\"}");
  assertThrows(AttachmentStorage.StorageUnavailable.class,()->storage().signUpload(BUCKET,KEY));
  respond(200,"{\"url\":\"https://outro.invalid/object/upload/sign/"+BUCKET+"/"+KEY+"?token=eyJhbGciOi.synthetic_token-123\"}");
  assertThrows(AttachmentStorage.StorageUnavailable.class,()->storage().signUpload(BUCKET,KEY));
  respond(500,"{}");
  assertThrows(AttachmentStorage.StorageUnavailable.class,()->storage().signUpload(BUCKET,KEY));
  assertThrows(IllegalArgumentException.class,()->storage().signUpload(BUCKET,"../outro/objeto"));
  assertThrows(IllegalArgumentException.class,()->storage().signUpload("Bucket_Invalido",KEY));
 }

 @Test void inspectsSizeHeadAndHashOrReportsMissing() throws Exception {
  byte[] content=new byte[100_000];
  content[0]=(byte)0x89; content[1]=0x50;
  status=200; body=content;
  var object=storage().inspect(BUCKET,KEY,content.length).orElseThrow();
  assertEquals(content.length,object.size()); assertEquals(16,object.head().length); assertEquals((byte)0x89,object.head()[0]);
  assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),object.sha256());
  assertTrue(seen.getLast().startsWith("GET /storage/v1/object/authenticated/"+BUCKET+"/"+KEY+" Bearer "));
  assertTrue(storage().inspect(BUCKET,KEY,10).orElseThrow().size()>10,"maior que o declarado é detectado sem ler tudo");
  respond(404,"");
  assertTrue(storage().inspect(BUCKET,KEY,10).isEmpty());
  respond(400,"{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\"}");
  assertTrue(storage().inspect(BUCKET,KEY,10).isEmpty());
  respond(400,"{\"statusCode\":\"400\",\"error\":\"invalid\"}");
  assertThrows(AttachmentStorage.StorageUnavailable.class,()->storage().inspect(BUCKET,KEY,10));
  respond(503,"");
  assertThrows(AttachmentStorage.StorageUnavailable.class,()->storage().inspect(BUCKET,KEY,10));
 }

 @Test void signsShortDownloadWithSafeFileName() {
  respond(200,"{\"signedURL\":\"/object/sign/"+BUCKET+"/"+KEY+"?token=eyJhbGciOi.download_token-456\"}");
  URI url=storage().signDownload(BUCKET,KEY,Duration.ofSeconds(60),"laudo da obra & fotos.pdf");
  assertEquals(base+"/storage/v1/object/sign/"+BUCKET+"/"+KEY+"?token=eyJhbGciOi.download_token-456&download=laudo%20da%20obra%20%26%20fotos.pdf",
   url.toString());
  assertTrue(seen.getLast().endsWith("{\"expiresIn\":60}"));
  assertThrows(IllegalArgumentException.class,()->storage().signDownload(BUCKET,KEY,Duration.ofHours(2),"a.pdf"));
 }

 @Test void configurationIsValidatedAndCredentialsNeverPrinted() {
  assertThrows(IllegalArgumentException.class,()->new SupabaseAttachmentStorage(HttpClient.newHttpClient(),URI.create("http://exemplo.invalid"),FAKE_KEY));
  assertThrows(IllegalArgumentException.class,()->new SupabaseAttachmentStorage(HttpClient.newHttpClient(),URI.create("https://exemplo.invalid/storage"),FAKE_KEY));
  assertThrows(IllegalArgumentException.class,()->new SupabaseAttachmentStorage(HttpClient.newHttpClient(),base,"curta"));
  assertFalse(storage().toString().contains(FAKE_KEY));
 }
}
