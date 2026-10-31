package br.com.condpulse.gateway.central;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import br.com.condpulse.gateway.config.GatewayConfig;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.events.EventOutbox;
import br.com.condpulse.gateway.json.Json;
import br.com.condpulse.gateway.open.OpenDispatcher.OpenCommand;
import br.com.condpulse.gateway.open.OpenDispatcher.Receipt;
import br.com.condpulse.gateway.queue.LocalCommandQueue.SyncCommand;
import br.com.condpulse.gateway.spi.Spi.EdgeEvent;

/**
 * Cliente HTTP da API central com certificado do gateway (mTLS). Implementa só o que existe no OpenAPI proposto;
 * as operações marcadas PROPOSTO em {@link CentralApiClient} falham com {@code CENTRAL_ENDPOINT_NOT_AVAILABLE} até o
 * backend expô-las. Sem repetição implícita; a fila local repete com a mesma chave de idempotência.
 */
public final class HttpCentralApiClient implements CentralApiClient {
 private final URI base;
 private final HttpClient client;
 private final String connectorId;

 public HttpCentralApiClient(GatewayConfig config,SecretProvider secrets) {
  var central=config.central();
  if(central==null) throw new IllegalStateException("Configuração central ausente.");
  this.base=central.baseUrl(); this.connectorId=config.connectorId();
  this.client=HttpClient.newBuilder().sslContext(sslContext(central,secrets)).connectTimeout(Duration.ofSeconds(10))
   .followRedirects(HttpClient.Redirect.NEVER).build();
 }

 private static SSLContext sslContext(GatewayConfig.Central central,SecretProvider secrets) {
  if(central.keystorePath()==null || central.keystorePasswordRef()==null) throw new IllegalStateException("Certificado do gateway (mTLS) obrigatório.");
  char[] password=secrets.secret(central.keystorePasswordRef()).orElseThrow(()->new IllegalStateException("Senha do keystore ausente no cofre."));
  try(InputStream in=Files.newInputStream(Path.of(central.keystorePath()))) {
   var keystore=KeyStore.getInstance("PKCS12");
   keystore.load(in,password);
   var kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
   kmf.init(keystore,password);
   TrustManagerFactory tmf=null;
   if(central.truststorePath()!=null) {
    try(InputStream t=Files.newInputStream(Path.of(central.truststorePath()))) {
     var truststore=KeyStore.getInstance("PKCS12");
     truststore.load(t,null);
     tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
     tmf.init(truststore);
    }
   }
   var ctx=SSLContext.getInstance("TLS");
   ctx.init(kmf.getKeyManagers(),tmf==null?null:tmf.getTrustManagers(),null);
   return ctx;
  } catch(IOException|GeneralSecurityException e) {
   throw new IllegalStateException("Certificado do gateway ilegível.");
  } finally { Arrays.fill(password,'\0'); }
 }

 @Override public List<EventIngestResult> postEvents(List<EdgeEvent> events,String idempotencyKey) throws CentralUnavailable {
  if(events.isEmpty() || events.size()>100) throw new IllegalArgumentException("Lote de 1 a 100 eventos.");
  var body=new LinkedHashMap<String,Object>();
  body.put("schemaVersion","1.0");
  var list=new ArrayList<Object>();
  for(var e:events) {
   if(!connectorId.equals(e.sourceConnectorId())) throw new IllegalArgumentException("Evento de outro conector.");
   list.add(EventOutbox.toMap(e));
  }
  body.put("events",list);
  var response=post("/v1/edge/events",Json.write(body),idempotencyKey);
  var results=new ArrayList<EventIngestResult>();
  for(Object o:Json.list(response.get("results"))) {
   var r=Json.map(o);
   results.add(new EventIngestResult(Json.string(r,"eventId"),IngestStatus.valueOf(Json.string(r,"status")),Json.string(r,"reasonCode")));
  }
  return results;
 }

 @Override public DecisionReceipt postDecision(EdgeDecision d,String idempotencyKey) throws CentralUnavailable {
  var m=new LinkedHashMap<String,Object>();
  m.put("schemaVersion","1.0"); m.put("decisionId",d.decisionId()); m.put("attemptId",d.attemptId()); m.put("sourceConnectorId",d.sourceConnectorId());
  m.put("deviceProvisioningId",d.deviceProvisioningId()); m.put("deviceChannelId",d.deviceChannelId()); m.put("pointId",d.pointId());
  m.put("bindingRevision",d.bindingRevision()); m.put("installationRevision",d.installationRevision()); m.put("policyVersion",null);
  m.put("recognizedSubjectRef",d.recognizedSubjectRef()); m.put("subjectContextRef",null); m.put("recognitionEventId",null);
  m.put("result",d.result().name()); m.put("reasonCodes",new ArrayList<Object>(d.reasonCodes())); m.put("evaluatedAt",d.evaluatedAt().toString());
  m.put("expiresAt",d.expiresAt().toString()); m.put("executionMode","EDGE_ONLINE"); m.put("automaticActuationAllowed",d.automaticActuationAllowed());
  m.put("policySnapshotHash",null); m.put("clockQuality",d.clockQuality()); m.put("evaluationDurationMs",d.evaluationDurationMs());
  var r=post("/v1/edge/decisions",Json.write(m),idempotencyKey);
  return new DecisionReceipt(Json.string(r,"decisionId"),IngestStatus.valueOf(Json.string(r,"status")),Json.string(r,"reasonCode"));
 }

 @Override public List<SyncCommand> pullSyncCommands(int max) throws CentralUnavailable { throw missing(); }
 @Override public List<OpenCommand> pullOpenCommands(int max) throws CentralUnavailable { throw missing(); }
 @Override public void reportSyncResult(SyncResult result) throws CentralUnavailable { throw missing(); }
 @Override public void reportOpenReceipt(Receipt receipt) throws CentralUnavailable { throw missing(); }
 @Override public void reportHealth(GatewayHealth health) throws CentralUnavailable { throw missing(); }
 @Override public byte[] fetchMaterial(String materialRef,String deviceId) throws CentralUnavailable { throw missing(); }

 private static CentralUnavailable missing() { return new CentralUnavailable("CENTRAL_ENDPOINT_NOT_AVAILABLE"); }

 private Map<String,Object> post(String path,String json,String idempotencyKey) throws CentralUnavailable {
  var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
   .header("Idempotency-Key",idempotencyKey).POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();
  try {
   var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
   if(response.statusCode()!=202 && response.statusCode()!=200) throw new CentralUnavailable("CENTRAL_HTTP_"+response.statusCode());
   return Json.object(response.body());
  } catch(IOException e) {
   throw new CentralUnavailable("CENTRAL_UNREACHABLE");
  } catch(InterruptedException e) {
   Thread.currentThread().interrupt();
   throw new CentralUnavailable("INTERRUPTED");
  } catch(Json.JsonException e) {
   throw new CentralUnavailable("CENTRAL_INVALID_RESPONSE");
  }
 }
}
