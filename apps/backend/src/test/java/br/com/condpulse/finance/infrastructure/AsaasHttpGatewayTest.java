package br.com.condpulse.finance.infrastructure;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import br.com.condpulse.finance.application.AsaasGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Asaas simulado em 127.0.0.1 (sem rede externa): cabeçalhos, corpo enviado, validação das respostas e chave redigida. */
class AsaasHttpGatewayTest {
 private static final String KEY="$aact_hmlg_SYNTHETIC0000000000000000000000NOTREAL::$aach_00000000";
 private static final String REF="00000000-0000-4000-8000-000000000001";
 private HttpServer server;
 private final List<String> seen=new CopyOnWriteArrayList<>();
 private volatile int status=200;
 private volatile String response="{}";
 private AsaasGateway gateway;

 @BeforeEach void start() throws IOException {
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v3/",this::handle);
  server.start();
  gateway=AsaasHttpGateway.forTest(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v3"),KEY);
 }
 @AfterEach void stop() { server.stop(0); }
 private void handle(HttpExchange exchange) throws IOException {
  String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
  seen.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" key="+KEY.equals(exchange.getRequestHeaders().getFirst("access_token"))
   +" ua="+exchange.getRequestHeaders().getFirst("User-Agent")+" body="+body);
  byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().add("Content-Type","application/json");
  exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
  try(var out=exchange.getResponseBody()) { out.write(bytes); }
 }

 @Test void onlySandboxKeyAndAddressAreAccepted() {
  var http=HttpClient.newHttpClient();
  assertThrows(IllegalArgumentException.class,()->new AsaasHttpGateway(http,"$aact_prod_SYNTHETIC000000000000000000000000"));
  assertThrows(IllegalArgumentException.class,()->new AsaasHttpGateway(http,""));
  assertThrows(IllegalArgumentException.class,()->new AsaasHttpGateway(http,"${CP_ASAAS_API_KEY}xxxxxxxxxxxxxxxxxxxxxxxxxx"));
  assertThrows(IllegalArgumentException.class,()->AsaasHttpGateway.forTest(http,URI.create("https://api.asaas.com/v3"),KEY));
  var sandbox=new AsaasHttpGateway(http,KEY);
  assertFalse(sandbox.toString().contains(KEY)); assertTrue(sandbox.toString().contains("api-sandbox.asaas.com"));
 }
 @Test void createsPaymentWithExternalReferenceAndValidatesTheAnswer() {
  response="""
   {"object":"payment","id":"pay_080225913252","customer":"cus_000005219613","value":150.5,"netValue":148.51,"billingType":"BOLETO",
    "status":"PENDING","dueDate":"2026-11-10","externalReference":"%s","invoiceUrl":"https://sandbox.asaas.com/i/080225913252",
    "bankSlipUrl":"https://sandbox.asaas.com/b/pdf/080225913252","deleted":false}
   """.formatted(REF);
  var payment=gateway.createPayment(new AsaasGateway.PaymentRequest("cus_000005219613","BOLETO",15050,LocalDate.of(2026,11,10),"Taxa",REF));
  assertEquals("pay_080225913252",payment.id()); assertEquals(15050,payment.valueCents()); assertEquals("PENDING",payment.status());
  assertEquals("https://sandbox.asaas.com/i/080225913252",payment.invoiceUrl());
  String request=seen.getFirst();
  assertTrue(request.startsWith("POST /v3/payments key=true ua=CondPulse"),request);
  assertTrue(request.contains("\"externalReference\":\""+REF+"\"") && request.contains("\"value\":150.50") && request.contains("\"billingType\":\"BOLETO\""),request);
  // Resposta com referência de outra cobrança ou fora do formato nunca vira sucesso.
  response=response.replace(REF,"00000000-0000-4000-8000-000000000002");
  assertThrows(AsaasGateway.Unavailable.class,()->gateway.createPayment(new AsaasGateway.PaymentRequest("cus_1","PIX",500,LocalDate.of(2026,11,10),"T",REF)));
  response="{\"id\":\"not-a-payment\"}";
  assertThrows(AsaasGateway.Unavailable.class,()->gateway.payment("pay_1"));
 }
 @Test void errorsAreMappedWithoutEchoingTheProvider() {
  status=400; response="{\"errors\":[{\"code\":\"invalid_cpfCnpj\",\"description\":\"CPF inválido "+KEY+"\"}]}";
  var rejected=assertThrows(AsaasGateway.Rejected.class,()->gateway.createCustomer(new AsaasGateway.Customer("Nome","12345678909","a@b.co",REF)));
  assertEquals("invalid_request",rejected.code(),"Código fora de [a-z_] vira genérico");
  response="{\"errors\":[{\"code\":\"invalid_value\",\"description\":\"x\"}]}";
  assertEquals("invalid_value",assertThrows(AsaasGateway.Rejected.class,()->gateway.createCustomer(new AsaasGateway.Customer("Nome","12345678909","a@b.co",REF))).code());
  status=401; response="{}";
  var unavailable=assertThrows(AsaasGateway.Unavailable.class,()->gateway.findPayment(REF));
  assertFalse(String.valueOf(unavailable.getMessage()).contains(KEY));
  status=404;
  assertTrue(gateway.payment("pay_1").isEmpty());
  gateway.deletePayment("pay_1");
  assertThrows(IllegalArgumentException.class,()->gateway.payment("../customers"));
  assertTrue(seen.stream().noneMatch(line->line.contains(KEY)),"Chave nunca na URL ou no corpo");
 }
 @Test void secondCopyFieldsAndSearchByReference() {
  response="{\"identificationField\":\"23793381286000000000300000000000100000000015050\",\"nossoNumero\":\"1\",\"barCode\":\"2\"}";
  assertEquals("23793381286000000000300000000000100000000015050",gateway.identificationField("pay_1"));
  response="{\"encodedImage\":\"AAAA\",\"payload\":\"00020101021226800014br.gov.bcb.pix2558SINTETICO\",\"expirationDate\":\"2026-11-10 23:59:59\"}";
  assertEquals("00020101021226800014br.gov.bcb.pix2558SINTETICO",gateway.pixPayload("pay_1"));
  response="{\"object\":\"list\",\"hasMore\":false,\"data\":[{\"id\":\"cus_1\",\"externalReference\":\"outro\"},{\"id\":\"cus_2\",\"externalReference\":\""+REF+"\"}]}";
  assertEquals("cus_2",gateway.findCustomer(REF).orElseThrow());
  assertTrue(seen.getLast().contains("/v3/customers?limit=10&externalReference="+REF),seen.getLast());
  response="{\"object\":\"list\",\"data\":[]}";
  assertTrue(gateway.findPayment(REF).isEmpty());
 }
}
