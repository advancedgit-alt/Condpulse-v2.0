package br.com.condpulse.finance.application;

import java.time.LocalDate;
import java.util.Set;

/** Regras puras do financeiro condominial: situação a partir do Asaas, CPF/CNPJ e máscaras exibidas. */
public final class FinanceRules {
 private FinanceRules() {}
 /** Recebido (dinheiro entrou), inclusive em dinheiro. */
 static final Set<String> PAID_STATUSES=Set.of("RECEIVED","CONFIRMED","RECEIVED_IN_CASH");
 /** Depois do recebimento: estorno pedido ou em andamento, contestação, negativação já recebida. Continua paga. */
 static final Set<String> AFTER_PAYMENT=Set.of("REFUND_REQUESTED","REFUND_IN_PROGRESS","CHARGEBACK_REQUESTED","CHARGEBACK_DISPUTE",
  "AWAITING_CHARGEBACK_REVERSAL","DUNNING_RECEIVED");

 /** Situação sincronizada; {@code paidAt}/{@code paidCents} só para PAID e REFUNDED. */
 public record Target(String state,String asaasStatus,LocalDate paidAt,Long paidCents) {}

 /**
  * Situação lógica de um pagamento do Asaas: OPEN (PENDING), OVERDUE, PAID (recebido ou com estorno/contestação ainda não
  * concluídos), REFUNDED, CANCELLED (excluído ou inexistente) ou null quando o status não tem equivalente (negativação
  * solicitada, análise de risco, desconhecido): só o status bruto muda.
  */
 static String logical(AsaasGateway.Payment payment) {
  if(payment==null || payment.deleted()) return "CANCELLED";
  String raw=payment.status();
  if("PENDING".equals(raw)) return "OPEN";
  if("OVERDUE".equals(raw)) return "OVERDUE";
  if(PAID_STATUSES.contains(raw) || AFTER_PAYMENT.contains(raw)) return "PAID";
  if("REFUNDED".equals(raw)) return "REFUNDED";
  return null;
 }

 /**
  * Próxima situação, respeitando as transições do banco (V036), para emissão/adoção (current = PENDING_ISSUE), webhook e
  * conciliação. PENDING_ISSUE vai para OPEN, OVERDUE, PAID ou REFUNDED (nunca CANCELLED: excluído não é adotado);
  * OPEN/OVERDUE vão para OPEN, OVERDUE, PAID, REFUNDED ou CANCELLED; PAID só vai para REFUNDED (leitura antiga nunca apaga
  * o pagamento; desfazer recebimento em dinheiro fica só no status bruto); CANCELLED e REFUNDED são finais.
  * {@code payment} nulo significa pagamento inexistente no Asaas (excluído).
  */
 public static Target target(String current,LocalDate currentPaidAt,Long currentPaidCents,AsaasGateway.Payment payment,LocalDate today) {
  String raw=payment==null || payment.deleted()?"DELETED":payment.status();
  String status=raw!=null && raw.matches("[A-Z][A-Z_]{2,39}")?raw:null;
  Target keep=new Target(current,status,currentPaidAt,currentPaidCents);
  if(terminal(current)) return keep;
  String next=logical(payment);
  boolean pending="PENDING_ISSUE".equals(current);
  boolean open="OPEN".equals(current) || "OVERDUE".equals(current);
  if(next==null) return pending?new Target("OPEN",status,null,null):keep;
  switch(next) {
   case "OPEN","OVERDUE" -> { return "PAID".equals(current)?keep:new Target(next,status,null,null); }
   case "PAID" -> {
    if("PAID".equals(current)) return keep;
    return new Target("PAID",status,payment.paymentDate()==null?today:payment.paymentDate(),payment.valueCents());
   }
   case "REFUNDED" -> {
    if("PAID".equals(current)) return new Target("REFUNDED",status,currentPaidAt,currentPaidCents);
    return new Target("REFUNDED",status,payment.paymentDate()==null?today:payment.paymentDate(),payment.valueCents());
   }
   default -> { // CANCELLED
    if(open) return new Target("CANCELLED",status,null,null);
    if(pending) throw new AsaasGateway.Unavailable(); // excluído nunca é adotado: a emissão cria outro
    return keep;
   }
  }
 }
 /** Cancelada e estornada não mudam mais (gatilho da V036). */
 public static boolean terminal(String state) { return "CANCELLED".equals(state) || "REFUNDED".equals(state); }
 /** Situação inicial depois da emissão (ou da adoção do pagamento já criado): mesma regra de {@link #target}. */
 public static Target issued(AsaasGateway.Payment payment,LocalDate today) {
  return target("PENDING_ISSUE",null,null,payment,today);
 }

 /** Somente dígitos; CPF (11) ou CNPJ (14) com dígitos verificadores válidos; null quando inválido. */
 public static String document(String value) {
  if(value==null) return null;
  String digits=value.replaceAll("[\\s.\\-/]","");
  if(!digits.matches("[0-9]{11}|[0-9]{14}") || digits.chars().distinct().count()==1) return null;
  return digits.length()==11?(cpf(digits)?digits:null):(cnpj(digits)?digits:null);
 }
 private static boolean cpf(String d) {
  for(int size=9;size<=10;size++) {
   int sum=0;
   for(int i=0;i<size;i++) sum+=(d.charAt(i)-'0')*(size+1-i);
   int check=(sum*10)%11%10;
   if(check!=d.charAt(size)-'0') return false;
  }
  return true;
 }
 private static boolean cnpj(String d) {
  int[] first={5,4,3,2,9,8,7,6,5,4,3,2},second={6,5,4,3,2,9,8,7,6,5,4,3,2};
  return cnpjDigit(d,first)==d.charAt(12)-'0' && cnpjDigit(d,second)==d.charAt(13)-'0';
 }
 private static int cnpjDigit(String d,int[] weights) {
  int sum=0;
  for(int i=0;i<weights.length;i++) sum+=(d.charAt(i)-'0')*weights[i];
  int rest=sum%11;
  return rest<2?0:11-rest;
 }
 /** CPF ***.456.789-** e CNPJ **.***.678/0001-**: o suficiente para conferir, sem expor o documento inteiro. */
 public static String maskDocument(String digits) {
  if(digits==null) return null;
  if(digits.length()==11) return "***."+digits.substring(3,6)+"."+digits.substring(6,9)+"-**";
  if(digits.length()==14) return "**.***."+digits.substring(5,8)+"/"+digits.substring(8,12)+"-**";
  return "***";
 }
 public static String maskEmail(String email) {
  if(email==null) return null;
  int at=email.indexOf('@');
  if(at<1) return "***";
  return email.charAt(0)+"***"+email.substring(at);
 }
 /** E-mail simples aceito pelo Asaas e pelo banco (sem espaço, um @, domínio com ponto). */
 public static String email(String value) {
  if(value==null) return null;
  String trimmed=value.strip().toLowerCase(java.util.Locale.ROOT);
  if(trimmed.length()<6 || trimmed.length()>254 || !trimmed.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) return null;
  return trimmed;
 }
}
