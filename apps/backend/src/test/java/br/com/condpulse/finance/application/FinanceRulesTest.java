package br.com.condpulse.finance.application;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceRulesTest {
 private static final LocalDate TODAY=LocalDate.of(2026,10,30);
 private static AsaasGateway.Payment payment(String status,boolean deleted) {
  return new AsaasGateway.Payment("pay_1",status,"00000000-0000-4000-8000-000000000001","PIX",15000,LocalDate.of(2026,10,29),null,null,deleted);
 }
 @Test void documentsAreValidatedAndMasked() {
  assertEquals("12345678909",FinanceRules.document("123.456.789-09"));
  assertNull(FinanceRules.document("123.456.789-00")); assertNull(FinanceRules.document("111.111.111-11")); assertNull(FinanceRules.document("abc"));
  assertEquals("11222333000181",FinanceRules.document("11.222.333/0001-81"));
  assertNull(FinanceRules.document("11.222.333/0001-82"));
  assertEquals("***.456.789-**",FinanceRules.maskDocument("12345678909"));
  assertEquals("**.***.333/0001-**",FinanceRules.maskDocument("11222333000181"));
  assertEquals("a***@example.invalid",FinanceRules.maskEmail("ana@example.invalid"));
  assertEquals("ana@example.invalid",FinanceRules.email(" Ana@Example.invalid "));
  assertNull(FinanceRules.email("sem-arroba")); assertNull(FinanceRules.email("a b@c.d"));
 }
 @Test void asaasStatusesFollowTheDatabaseTransitions() {
  assertEquals("PAID",FinanceRules.target("OPEN",null,null,payment("RECEIVED",false),TODAY).state());
  assertEquals(15000L,FinanceRules.target("OVERDUE",null,null,payment("CONFIRMED",false),TODAY).paidCents());
  assertEquals("OVERDUE",FinanceRules.target("OPEN",null,null,payment("OVERDUE",false),TODAY).state());
  assertEquals("CANCELLED",FinanceRules.target("OPEN",null,null,payment("PENDING",true),TODAY).state());
  assertEquals("CANCELLED",FinanceRules.target("OVERDUE",null,null,null,TODAY).state(),"Pagamento inexistente conta como excluído");
  assertEquals("PAID",FinanceRules.target("PAID",TODAY,15000L,payment("PENDING",true),TODAY).state(),"Pago não é cancelado pelo Asaas");
  assertEquals("REFUNDED",FinanceRules.target("PAID",TODAY,15000L,payment("REFUNDED",false),TODAY).state());
  var stale=FinanceRules.target("PAID",TODAY,15000L,payment("PENDING",false),TODAY);
  assertEquals("PAID",stale.state()); assertEquals(TODAY,stale.paidAt()); assertEquals(15000L,stale.paidCents(),"Leitura antiga não apaga o pagamento");
  assertEquals("PAID",FinanceRules.target("PAID",TODAY,15000L,payment("OVERDUE",false),TODAY).state());
  var refunded=FinanceRules.target("OVERDUE",null,null,payment("REFUNDED",false),TODAY);
  assertEquals("REFUNDED",refunded.state(),"Recebido e estornado antes do webhook de recebimento não fica aberto");
  assertEquals(LocalDate.of(2026,10,29),refunded.paidAt()); assertEquals(15000L,refunded.paidCents());
  assertEquals(TODAY,FinanceRules.target("OPEN",null,null,new AsaasGateway.Payment("pay_1","REFUNDED",null,"PIX",15000,null,null,null,false),TODAY).paidAt());
  var chargeback=FinanceRules.target("PAID",TODAY,15000L,payment("CHARGEBACK_REQUESTED",false),TODAY);
  assertEquals("PAID",chargeback.state()); assertEquals("CHARGEBACK_REQUESTED",chargeback.asaasStatus());
  assertEquals("CANCELLED",FinanceRules.target("CANCELLED",null,null,payment("RECEIVED",false),TODAY).state(),"Cancelada é final");
  assertEquals("OPEN",FinanceRules.issued(payment("PENDING",false),TODAY).state());
  assertThrows(AsaasGateway.Unavailable.class,()->FinanceRules.issued(payment("PENDING",true),TODAY));
 }
 @Test void everyAsaasStatusMapsConsistentlyForAdoptionWebhookAndSync() {
  // Adoção (pagamento já criado com a referência): mesma regra do webhook, nunca "aberta" para dinheiro recebido.
  for(String paid:new String[]{"RECEIVED","CONFIRMED","RECEIVED_IN_CASH","REFUND_REQUESTED","REFUND_IN_PROGRESS","CHARGEBACK_REQUESTED",
    "CHARGEBACK_DISPUTE","AWAITING_CHARGEBACK_REVERSAL","DUNNING_RECEIVED"}) {
   var adopted=FinanceRules.issued(payment(paid,false),TODAY);
   assertEquals("PAID",adopted.state(),paid); assertEquals(15000L,adopted.paidCents(),paid);
   assertEquals("PAID",FinanceRules.target("OPEN",null,null,payment(paid,false),TODAY).state(),paid);
  }
  var refunded=FinanceRules.issued(payment("REFUNDED",false),TODAY);
  assertEquals("REFUNDED",refunded.state()); assertEquals(LocalDate.of(2026,10,29),refunded.paidAt());
  assertEquals("OVERDUE",FinanceRules.issued(payment("OVERDUE",false),TODAY).state());
  for(String unknown:new String[]{"DUNNING_REQUESTED","AWAITING_RISK_ANALYSIS","ALGO_NOVO"}) {
   assertEquals("OPEN",FinanceRules.issued(payment(unknown,false),TODAY).state(),unknown);
   var kept=FinanceRules.target("OVERDUE",null,null,payment(unknown,false),TODAY);
   assertEquals("OVERDUE",kept.state()); assertEquals(unknown,kept.asaasStatus());
  }
  // Transições produzidas sempre aceitas pelo gatilho da V036.
  var allowed=java.util.Map.of("PENDING_ISSUE",java.util.Set.of("PENDING_ISSUE","OPEN","OVERDUE","PAID","REFUNDED"),
   "OPEN",java.util.Set.of("OPEN","OVERDUE","PAID","REFUNDED","CANCELLED"),"OVERDUE",java.util.Set.of("OPEN","OVERDUE","PAID","REFUNDED","CANCELLED"),
   "PAID",java.util.Set.of("PAID","REFUNDED"),"CANCELLED",java.util.Set.of("CANCELLED"),"REFUNDED",java.util.Set.of("REFUNDED"));
  for(var current:allowed.keySet()) for(String raw:new String[]{"PENDING","OVERDUE","RECEIVED","CONFIRMED","RECEIVED_IN_CASH","REFUNDED",
    "REFUND_REQUESTED","CHARGEBACK_REQUESTED","DUNNING_REQUESTED","DELETED",null}) {
   AsaasGateway.Payment payment=raw==null?null:"DELETED".equals(raw)?payment("PENDING",true):payment(raw,false);
   String next;
   try { next=FinanceRules.target(current,TODAY,15000L,payment,TODAY).state(); }
   catch(AsaasGateway.Unavailable notAdopted) { assertEquals("PENDING_ISSUE",current); continue; }
   assertTrue(allowed.get(current).contains(next),current+" -> "+next+" ("+raw+")");
  }
 }
}
