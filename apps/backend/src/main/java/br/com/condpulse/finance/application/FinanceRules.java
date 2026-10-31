package br.com.condpulse.finance.application;

import java.time.LocalDate;
import java.util.Set;

/** Regras puras do financeiro condominial: situação a partir do Asaas, CPF/CNPJ e máscaras exibidas. */
public final class FinanceRules {
 private FinanceRules() {}
 static final Set<String> PAID_STATUSES=Set.of("RECEIVED","CONFIRMED","RECEIVED_IN_CASH");

 /** Situação sincronizada; {@code paidAt}/{@code paidCents} só para PAID e REFUNDED. */
 public record Target(String state,String asaasStatus,LocalDate paidAt,Long paidCents) {}

 /**
  * Próxima situação de uma cobrança já emitida, respeitando as transições do banco (V036): OPEN/OVERDUE vão para
  * OPEN, OVERDUE, PAID, REFUNDED ou CANCELLED; PAID só vai para REFUNDED (nunca regride por leitura); CANCELLED e REFUNDED são finais. Status
  * do Asaas sem equivalente (estorno solicitado, chargeback, negativação...) só atualiza o status bruto.
  * {@code payment} nulo significa pagamento inexistente no Asaas (tratado como excluído).
  */
 public static Target target(String current,LocalDate currentPaidAt,Long currentPaidCents,AsaasGateway.Payment payment,LocalDate today) {
  String raw=payment==null?"DELETED":payment.deleted()?"DELETED":payment.status();
  String status=raw!=null && raw.matches("[A-Z][A-Z_]{2,39}")?raw:null;
  if("CANCELLED".equals(current) || "REFUNDED".equals(current) || "PENDING_ISSUE".equals(current))
   return new Target(current,status,currentPaidAt,currentPaidCents);
  boolean open="OPEN".equals(current) || "OVERDUE".equals(current);
  if("DELETED".equals(raw)) return open?new Target("CANCELLED",status,null,null):new Target(current,status,currentPaidAt,currentPaidCents);
  if(PAID_STATUSES.contains(raw)) {
   if("PAID".equals(current)) return new Target(current,status,currentPaidAt,currentPaidCents);
   LocalDate paid=payment.paymentDate()==null?today:payment.paymentDate();
   return new Target("PAID",status,paid,payment.valueCents());
  }
  // Paga não volta a aberta/vencida por leitura do Asaas: uma resposta antiga (lida antes do pagamento) nunca apaga o
  // pagamento. Desfazer recebimento em dinheiro no Asaas fica só no status bruto, para a gestão conferir.
  if("PAID".equals(current) && ("PENDING".equals(raw) || "OVERDUE".equals(raw))) return new Target(current,status,currentPaidAt,currentPaidCents);
  if("PENDING".equals(raw)) return new Target("OPEN",status,null,null);
  if("OVERDUE".equals(raw)) return new Target("OVERDUE",status,null,null);
  if("REFUNDED".equals(raw) && "PAID".equals(current)) return new Target("REFUNDED",status,currentPaidAt,currentPaidCents);
  // Recebido e estornado antes de o recebimento chegar aqui: vai direto a REFUNDED com os dados de pagamento do Asaas.
  if("REFUNDED".equals(raw) && open)
   return new Target("REFUNDED",status,payment.paymentDate()==null?today:payment.paymentDate(),payment.valueCents());
  return new Target(current,status,currentPaidAt,currentPaidCents);
 }
 /** Cancelada e estornada não mudam mais (gatilho da V036). */
 public static boolean terminal(String state) { return "CANCELLED".equals(state) || "REFUNDED".equals(state); }
 /** Situação inicial depois da emissão (ou da adoção do pagamento já criado). */
 public static Target issued(AsaasGateway.Payment payment,LocalDate today) {
  if(payment.deleted()) throw new AsaasGateway.Unavailable();
  String status=payment.status()!=null && payment.status().matches("[A-Z][A-Z_]{2,39}")?payment.status():null;
  if(PAID_STATUSES.contains(payment.status()))
   return new Target("PAID",status,payment.paymentDate()==null?today:payment.paymentDate(),payment.valueCents());
  if("OVERDUE".equals(payment.status())) return new Target("OVERDUE",status,null,null);
  return new Target("OPEN",status,null,null);
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
