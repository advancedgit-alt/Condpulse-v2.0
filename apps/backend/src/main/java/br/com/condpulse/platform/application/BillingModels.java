package br.com.condpulse.platform.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Financeiro da plataforma (fase 5): relação comercial da assinatura CondPulse. Valores em centavos de real;
 * nenhum preço é presumido. Pagamento só é registrado manualmente, com referência do comprovante.
 */
public final class BillingModels {
 private BillingModels() {}
 public record Plan(UUID planId,String name,long priceCents,String period,boolean active,long version,Instant createdAt,Instant updatedAt) {}
 public record CreatePlan(String name,Long priceCents,String period,String reason) {}
 public record UpdatePlan(String name,Long priceCents,Boolean active,Long expectedVersion,String reason) {}
 public record PlanChange(Plan plan,boolean applied) {}
 public record Subscription(UUID subscriptionId,UUID tenantId,String tenantName,UUID planId,String planName,long priceCents,
   String period,LocalDate startsOn,LocalDate endsOn,int dueDay,String status,long version,Instant createdAt) {}
 public record CreateSubscription(UUID tenantId,UUID planId,LocalDate startsOn,Integer dueDay,String reason) {}
 public record EndSubscription(LocalDate endsOn,Long expectedVersion,String reason) {}
 public record Payment(UUID paymentId,long amountCents,LocalDate paidOn,String method,String reference,UUID recordedBy,Instant recordedAt) {}
 public record Charge(UUID chargeId,UUID subscriptionId,UUID tenantId,String tenantName,LocalDate periodStart,LocalDate periodEnd,
   long amountCents,LocalDate dueOn,String status,boolean overdue,String cancelReason,long version,Instant createdAt,Payment payment) {}
 public record ChargePage(List<Charge> charges,boolean truncated) {
  public ChargePage { charges=List.copyOf(charges); }
 }
 public record CreateCharge(LocalDate periodStart) {}
 public record CancelCharge(Long expectedVersion,String reason) {}
 public record RecordPayment(Long amountCents,LocalDate paidOn,String method,String reference,Long expectedVersion) {}
 public record VoidPayment(Long expectedChargeVersion,String reason) {}
}
