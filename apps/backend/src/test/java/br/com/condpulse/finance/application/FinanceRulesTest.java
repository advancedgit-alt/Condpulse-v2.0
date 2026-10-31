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
  assertEquals("OPEN",FinanceRules.target("OPEN",null,null,payment("REFUNDED",false),TODAY).state(),"Estorno de não pago não muda");
  var chargeback=FinanceRules.target("PAID",TODAY,15000L,payment("CHARGEBACK_REQUESTED",false),TODAY);
  assertEquals("PAID",chargeback.state()); assertEquals("CHARGEBACK_REQUESTED",chargeback.asaasStatus());
  assertEquals("CANCELLED",FinanceRules.target("CANCELLED",null,null,payment("RECEIVED",false),TODAY).state(),"Cancelada é final");
  assertEquals("OPEN",FinanceRules.issued(payment("PENDING",false),TODAY).state());
  assertThrows(AsaasGateway.Unavailable.class,()->FinanceRules.issued(payment("PENDING",true),TODAY));
 }
}
