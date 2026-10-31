package br.com.condpulse.finance.application;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Porta do Asaas (ADR-0008, somente sandbox nesta entrega). Implementação só no servidor, com a chave fora do
 * navegador, de logs e de respostas. Toda chamada acontece fora da transação de negócio. Falha de rede, resposta
 * inesperada ou recusa de credencial vira {@link Unavailable}; recusa de validação do Asaas vira {@link Rejected}.
 */
public interface AsaasGateway {
 /** Cliente (pagador): referência externa = identificador do pagador no CondPulse. */
 record Customer(String name,String document,String email,String externalReference) {
  @Override public String toString() { return "AsaasGateway.Customer[redacted]"; }
 }
 /** Cobrança: referência externa = identificador da cobrança no CondPulse (idempotência da emissão). */
 record PaymentRequest(String customerId,String billingType,long amountCents,LocalDate dueDate,String description,String externalReference) {}
 /** Pagamento como o Asaas o devolve, já validado; valores em centavos. */
 record Payment(String id,String status,String externalReference,String billingType,long valueCents,LocalDate paymentDate,
     String invoiceUrl,String bankSlipUrl,boolean deleted) {}

 Optional<String> findCustomer(String externalReference);
 String createCustomer(Customer customer);
 /** Pagamento não excluído com a referência externa exata, se existir. */
 Optional<Payment> findPayment(String externalReference);
 Payment createPayment(PaymentRequest request);
 /** Pagamento pelo identificador; vazio quando o Asaas responde 404. */
 Optional<Payment> payment(String id);
 /** Exclui (cancela) o pagamento; 404 conta como já excluído. */
 void deletePayment(String id);
 /** Linha digitável do boleto. */
 String identificationField(String id);
 /** PIX copia e cola. */
 String pixPayload(String id);

 final class Unavailable extends RuntimeException {
  public Unavailable() { super("Asaas indisponível."); }
 }
 final class Rejected extends RuntimeException {
  private final String code;
  public Rejected(String code) { super("Asaas recusou a operação."); this.code=code; }
  /** Código do Asaas saneado (letras minúsculas, dígitos e sublinhado), nunca a descrição. */
  public String code() { return code; }
 }
}
