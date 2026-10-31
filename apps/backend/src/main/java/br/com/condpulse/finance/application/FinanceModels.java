package br.com.condpulse.finance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Financeiro condominial (ADR-0008). Valores em centavos; datas civis no fuso do condomínio. */
public final class FinanceModels {
 private FinanceModels() {}

 /** Pagador ativo, sempre com documento e e-mail mascarados. */
 public record Payer(UUID payerId,UUID membershipId,String displayName,String role,String legalName,String document,String email,
     boolean customerSynced,Instant createdAt,long version) {}
 public record Candidate(UUID membershipId,String displayName,String role) {}
 /** Unidade na gestão financeira: pagador atual, candidatos (proprietário ou responsável ativo) e locação em vigor. */
 public record UnitFinance(UUID unitId,String unitLabel,String blockName,boolean rented,Payer payer,List<Candidate> candidates) {
  public UnitFinance { candidates=List.copyOf(candidates); }
 }
 public record PayerChange(UUID membershipId,String legalName,String document,String email,String reason) {
  @Override public String toString() { return "PayerChange[membershipId="+membershipId+",redacted]"; }
 }

 /** state: PENDING_ISSUE, OPEN, OVERDUE, PAID, CANCELLED ou REFUNDED. */
 public record Charge(UUID chargeId,UUID unitId,String unitLabel,String blockName,String kind,String description,long amountCents,
     LocalDate dueDate,String billingType,String state,String asaasStatus,LocalDate competence,String invoiceUrl,String bankSlipUrl,
     LocalDate paidAt,Long paidCents,String issueError,String cancelReason,Instant createdAt,Instant updatedAt,long version) {}
 public record ChargePage(List<Charge> charges,boolean truncated) {
  public ChargePage { charges=List.copyOf(charges); }
 }
 public record ChargeCreate(UUID unitId,String kind,String description,Long amountCents,LocalDate dueDate,String billingType,String reason) {}
 public record Mutation(Charge charge,boolean replayed,int status) {}
 public record Cancel(String reason,Long expectedVersion) {}
 /** Segunda via: linha digitável (boleto) ou PIX copia e cola, mais os links do Asaas. */
 public record PaymentInfo(UUID chargeId,String billingType,String identificationField,String pixPayload,String invoiceUrl,
     String bankSlipUrl,LocalDate dueDate,long amountCents) {}

 public record RecurringFee(UUID feeId,UUID unitId,String unitLabel,String kind,String description,long amountCents,int dueDay,
     String billingType,String state,Instant createdAt,long version) {}
 public record FeeMutation(RecurringFee fee,boolean replayed,int status) {}
 public record RecurringCreate(UUID unitId,String kind,String description,Long amountCents,Integer dueDay,String billingType,String reason) {}
 public record Reason(String reason) {}
 /** competence: AAAA-MM. */
 public record Generate(String competence,String reason) {}
 public record GenerateResult(int created,int skipped,int issued,int pending) {}
 public record IssueResult(int issued,int failed,int pending) {}

 public record Delinquency(UUID unitId,String unitLabel,String blockName,int charges,long totalCents,LocalDate oldestDue) {}
 public record DelinquencyReport(LocalDate referenceDate,List<Delinquency> units,long totalCents) {
  public DelinquencyReport { units=List.copyOf(units); }
 }
 /** month: AAAA-MM, pelo vencimento. */
 public record MonthSummary(String month,long issuedCents,long paidCents,long openCents,long overdueCents,int cancelled) {}
 public record Report(List<MonthSummary> months) {
  public Report { months=List.copyOf(months); }
 }
}
