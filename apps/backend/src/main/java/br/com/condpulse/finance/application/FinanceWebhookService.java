package br.com.condpulse.finance.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.finance.infrastructure.FinanceWebhookStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook de cobranças do Asaas. Autentica pelo cabeçalho asaas-access-token (comparação em tempo constante com
 * CP_ASAAS_WEBHOOK_TOKEN) antes de qualquer leitura; não confia na situação do corpo: relê o pagamento no Asaas (fora da
 * transação) e, numa única transação, grava o evento (idempotente pelo id do evento), a situação e o outbox.
 */
public class FinanceWebhookService {
 public enum Outcome { PROCESSED, DUPLICATE, IGNORED }
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final int MAX_BODY=64*1024;
 private final byte[] tokenDigest;
 private final AsaasGateway asaas;
 private final FinanceWebhookStore store;
 public FinanceWebhookService(String token,AsaasGateway asaas,FinanceWebhookStore store) {
  if(token==null || token.length()<32 || token.length()>255 || !token.matches("[A-Za-z0-9_-]+"))
   throw new IllegalArgumentException("Configure o token do webhook do Asaas (32 a 255 caracteres).");
  this.tokenDigest=sha256(token); this.asaas=asaas; this.store=store;
 }

 public Outcome handle(String token,String body) {
  if(token==null || token.length()>255 || !MessageDigest.isEqual(tokenDigest,sha256(token)))
   throw new BusinessProblem("FINANCE_WEBHOOK_UNAUTHORIZED",401,"Webhook não autenticado.");
  if(body==null || body.isEmpty() || body.length()>MAX_BODY) throw invalid();
  Map<?,?> event;
  try { event=JSON.readValue(body,Object.class) instanceof Map<?,?> map?map:null; } catch(RuntimeException malformed) { event=null; }
  if(event==null || !(event.get("id") instanceof String eventId) || !eventId.matches("[A-Za-z0-9_-]{4,128}")
     || !(event.get("event") instanceof String type) || !type.matches("[A-Z][A-Z_]{2,63}")) throw invalid();
  if(!type.startsWith("PAYMENT_") || !(event.get("payment") instanceof Map<?,?> payment)
     || !(payment.get("id") instanceof String paymentId) || !paymentId.matches("pay_[A-Za-z0-9]{1,64}")) return Outcome.IGNORED;
  AsaasGateway.Payment current;
  try { current=asaas.payment(paymentId).orElse(null); }
  catch(AsaasGateway.Unavailable | AsaasGateway.Rejected unavailable) {
   throw new BusinessProblem("FINANCE_ASAAS_UNAVAILABLE",503,"Asaas indisponível; o evento será reenviado.");
  }
  String reference=current!=null?current.externalReference():payment.get("externalReference") instanceof String r?r:null;
  UUID chargeId;
  try { chargeId=reference==null || !reference.matches("[0-9a-f-]{36}")?null:UUID.fromString(reference); }
  catch(IllegalArgumentException notOurs) { chargeId=null; }
  if(chargeId==null) return Outcome.IGNORED;
  try { return store.apply(eventId,type,chargeId,paymentId,current); }
  catch(DataAccessException | TransactionException failure) {
   throw new BusinessProblem("FINANCE_DEPENDENCY_UNAVAILABLE",503,"Não foi possível registrar o evento; ele será reenviado.");
  }
 }
 private static BusinessProblem invalid() { return new BusinessProblem("FINANCE_WEBHOOK_INVALID",400,"Evento inválido."); }
 private static byte[] sha256(String value) {
  try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
 @Override public String toString() { return "FinanceWebhookService[token=REDACTED]"; }
}
