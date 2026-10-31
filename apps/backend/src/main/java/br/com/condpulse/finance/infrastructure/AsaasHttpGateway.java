package br.com.condpulse.finance.infrastructure;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import br.com.condpulse.finance.application.AsaasGateway;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP do Asaas (API v3), fixo no sandbox. A chave vai só no cabeçalho access_token; nunca em URL, log,
 * exceção ou resposta (toString redigido). Respostas são validadas campo a campo; qualquer coisa fora do esperado vira
 * {@link AsaasGateway.Unavailable}, nunca sucesso. Sem redirecionamento.
 */
public final class AsaasHttpGateway implements AsaasGateway {
 /** Único endereço aceito em execução (docs.asaas.com, "Autenticação": sandbox https://api-sandbox.asaas.com/v3). */
 public static final URI SANDBOX=URI.create("https://api-sandbox.asaas.com/v3");
 /** Chave do sandbox: prefixo $aact_hmlg_ (a de produção, $aact_prod_, é recusada). */
 public static final Pattern SANDBOX_KEY=Pattern.compile("\\$aact_hmlg_[A-Za-z0-9_:$.=+/-]{20,4096}");
 private static final Pattern CUSTOMER=Pattern.compile("cus_[A-Za-z0-9]{1,64}");
 private static final Pattern PAYMENT=Pattern.compile("pay_[A-Za-z0-9]{1,64}");
 private static final Pattern STATUS=Pattern.compile("[A-Z][A-Z_]{2,39}");
 private static final Pattern LINK=Pattern.compile("https://([a-z]+\\.)?asaas\\.com/[A-Za-z0-9/_.-]{1,200}");
 private static final Pattern LINE=Pattern.compile("[0-9 .]{20,64}");
 private static final Pattern PIX=Pattern.compile("[\\x20-\\x7e]{20,1024}");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final int MAX_BODY=256*1024;
 private final HttpClient http;
 private final URI base;
 private final String apiKey;

 public AsaasHttpGateway(HttpClient http,String apiKey) { this(http,SANDBOX,apiKey); }
 /** Endereço local só para testes (http://127.0.0.1); em execução, apenas o sandbox. */
 AsaasHttpGateway(HttpClient http,URI base,String apiKey) {
  if(base==null || !(SANDBOX.equals(base) || "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && "/v3".equals(base.getPath())))
   throw new IllegalArgumentException("Endereço do Asaas inválido.");
  if(apiKey==null || !SANDBOX_KEY.matcher(apiKey).matches()) throw new IllegalArgumentException("Chave do Asaas ausente ou fora do sandbox.");
  this.http=http; this.base=base; this.apiKey=apiKey;
 }
 public static AsaasHttpGateway forTest(HttpClient http,URI base,String apiKey) { return new AsaasHttpGateway(http,base,apiKey); }

 @Override public Optional<String> findCustomer(String externalReference) {
  var page=send("GET","/customers?limit=10&externalReference="+encode(reference(externalReference)),null,false);
  for(Map<?,?> item:data(page)) {
   if(Boolean.TRUE.equals(item.get("deleted")) || !externalReference.equals(item.get("externalReference"))) continue;
   return Optional.of(id(item,CUSTOMER));
  }
  return Optional.empty();
 }
 @Override public String createCustomer(Customer customer) {
  var body=new LinkedHashMap<String,Object>();
  body.put("name",customer.name()); body.put("cpfCnpj",customer.document()); body.put("email",customer.email());
  body.put("externalReference",reference(customer.externalReference()));
  // Sem e-mail/SMS do Asaas nesta entrega (sandbox): a segunda via fica na área do morador.
  body.put("notificationDisabled",true);
  return id(send("POST","/customers",body,false),CUSTOMER);
 }
 @Override public Optional<Payment> findPayment(String externalReference) {
  var page=send("GET","/payments?limit=10&externalReference="+encode(reference(externalReference)),null,false);
  for(Map<?,?> item:data(page)) {
   if(Boolean.TRUE.equals(item.get("deleted")) || !externalReference.equals(item.get("externalReference"))) continue;
   return Optional.of(payment(item));
  }
  return Optional.empty();
 }
 @Override public Payment createPayment(PaymentRequest request) {
  if(request.amountCents()<1 || !CUSTOMER.matcher(request.customerId()).matches()) throw new IllegalArgumentException("Cobrança inválida.");
  var body=new LinkedHashMap<String,Object>();
  body.put("customer",request.customerId()); body.put("billingType",request.billingType());
  body.put("value",BigDecimal.valueOf(request.amountCents()).movePointLeft(2)); body.put("dueDate",request.dueDate().toString());
  body.put("description",request.description()); body.put("externalReference",reference(request.externalReference()));
  var created=payment(send("POST","/payments",body,false));
  if(!request.externalReference().equals(created.externalReference())) throw new Unavailable();
  return created;
 }
 @Override public Optional<Payment> payment(String id) {
  var body=send("GET","/payments/"+path(id),null,true);
  return body==null?Optional.empty():Optional.of(payment(body));
 }
 @Override public void deletePayment(String id) {
  var body=send("DELETE","/payments/"+path(id),null,true);
  if(body!=null && !Boolean.TRUE.equals(body.get("deleted"))) throw new Unavailable();
 }
 @Override public String identificationField(String id) {
  var body=send("GET","/payments/"+path(id)+"/identificationField",null,false);
  return text(body,"identificationField",LINE);
 }
 @Override public String pixPayload(String id) {
  var body=send("GET","/payments/"+path(id)+"/pixQrCode",null,false);
  return text(body,"payload",PIX);
 }

 private Map<?,?> send(String method,String path,Map<String,Object> json,boolean notFoundIsEmpty) {
  var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15))
   .header("access_token",apiKey).header("User-Agent","CondPulse/0.1 (homologacao)").header("Accept","application/json");
  if(json!=null) {
   String text;
   try { text=JSON.writeValueAsString(json); } catch(RuntimeException error) { throw new IllegalArgumentException("Corpo inválido."); }
   builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(text));
  } else builder.method(method,HttpRequest.BodyPublishers.noBody());
  HttpResponse<String> response;
  try { response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
  catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Unavailable(); }
  catch(IOException | RuntimeException error) { throw new Unavailable(); }
  int status=response.statusCode();
  String body=response.body();
  if(body!=null && body.length()>MAX_BODY) throw new Unavailable();
  if(status==404 && notFoundIsEmpty) return null;
  if(status==400) throw new Rejected(errorCode(body));
  if(status!=200) throw new Unavailable();
  try {
   Object parsed=JSON.readValue(body,Object.class);
   if(parsed instanceof Map<?,?> map) return map;
  } catch(RuntimeException invalid) { /* abaixo */ }
  throw new Unavailable();
 }
 private static String errorCode(String body) {
  try {
   if(JSON.readValue(body,Object.class) instanceof Map<?,?> map && map.get("errors") instanceof List<?> errors && !errors.isEmpty()
      && errors.getFirst() instanceof Map<?,?> first && first.get("code") instanceof String code && code.matches("[a-z_]{1,60}"))
    return code;
  } catch(RuntimeException invalid) { /* código genérico */ }
  return "invalid_request";
 }
 private static List<Map<?,?>> data(Map<?,?> page) {
  if(!(page.get("data") instanceof List<?> items) || items.size()>100) throw new Unavailable();
  var result=new java.util.ArrayList<Map<?,?>>();
  for(Object item:items) {
   if(!(item instanceof Map<?,?> map)) throw new Unavailable();
   result.add(map);
  }
  return result;
 }
 private static String id(Map<?,?> body,Pattern pattern) {
  if(body.get("id") instanceof String id && pattern.matcher(id).matches()) return id;
  throw new Unavailable();
 }
 private static String text(Map<?,?> body,String field,Pattern pattern) {
  if(body.get(field) instanceof String value && pattern.matcher(value).matches()) return value;
  throw new Unavailable();
 }
 static Payment payment(Map<?,?> body) {
  String id=id(body,PAYMENT);
  String status=body.get("status") instanceof String s && STATUS.matcher(s).matches()?s:null;
  boolean deleted=Boolean.TRUE.equals(body.get("deleted"));
  if(status==null && !deleted) throw new Unavailable();
  String reference=body.get("externalReference") instanceof String r?r:null;
  String billing=body.get("billingType") instanceof String b && STATUS.matcher(b).matches()?b:null;
  long cents;
  if(body.get("value") instanceof Number value) {
   try { cents=new BigDecimal(value.toString()).movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact(); }
   catch(ArithmeticException | NumberFormatException invalid) { throw new Unavailable(); }
  } else throw new Unavailable();
  LocalDate paid=date(body.get("paymentDate"));
  if(paid==null) paid=date(body.get("clientPaymentDate"));
  return new Payment(id,status,reference,billing,cents,paid,link(body.get("invoiceUrl")),link(body.get("bankSlipUrl")),deleted);
 }
 private static LocalDate date(Object value) {
  if(!(value instanceof String text) || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
  try { return LocalDate.parse(text); } catch(DateTimeParseException invalid) { return null; }
 }
 private static String link(Object value) {
  return value instanceof String text && LINK.matcher(text).matches()?text:null;
 }
 private static String reference(String value) {
  if(value==null || !value.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("Referência externa inválida.");
  return value;
 }
 private static String path(String id) {
  if(id==null || !PAYMENT.matcher(id).matches()) throw new IllegalArgumentException("Pagamento inválido.");
  return id;
 }
 private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
 @Override public String toString() { return "AsaasHttpGateway[base="+base+",credentials=REDACTED]"; }
}
