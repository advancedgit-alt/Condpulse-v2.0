package br.com.condpulse.platform.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.BillingStore;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SqlStates;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Financeiro da plataforma (fase 5): planos, assinaturas dos condomínios, cobranças por período e pagamentos
 * registrados manualmente. "Pago" só existe com pagamento registrado do mesmo valor, com referência e responsável
 * (o banco confere no commit). Sem gateway, cobrança automática ou emissão fiscal; atraso não bloqueia acesso.
 */
@Service @Profile("api")
public class BillingService {
 static final Set<String> PERIODS=Set.of("MONTHLY","YEARLY");
 static final Set<String> METHODS=Set.of("PIX","TRANSFERENCIA","BOLETO","OUTRO");
 static final Set<String> CHARGE_FILTERS=Set.of("OPEN","OVERDUE","PAID","CANCELLED");
 static final long MAX_CENTS=100_000_000L;
 private static final LocalDate EARLIEST=LocalDate.of(2000,1,1);
 /** Limites das datas aceitas (iguais aos CHECKs do V011): início até o dia 28 e períodos dentro do século. */
 private static final LocalDate LATEST_START=LocalDate.of(2100,12,28);
 private static final LocalDate LATEST_PERIOD=LocalDate.of(2100,12,31);
 private static final LocalDate LATEST_END=LocalDate.of(2101,12,31);
 private final PlatformTransactionExecutor transactions;
 private final BillingStore store;
 private final PlatformStore platform;
 public BillingService(PlatformTransactionExecutor transactions,BillingStore store,PlatformStore platform) {
  this.transactions=transactions; this.store=store; this.platform=platform;
 }

 public List<BillingModels.Plan> plans(UUID actor,AssuranceLevel assurance) {
  return read(actor,assurance,context->store.plans());
 }
 public BillingModels.Plan createPlan(UUID actor,AssuranceLevel assurance,BillingModels.CreatePlan request) {
  if(request==null) throw invalid("BILLING_INPUT_INVALID","Informe os dados do plano.");
  String name=PlatformService.text(request.name(),120,"BILLING_PLAN_NAME_INVALID","Informe o nome do plano com até 120 caracteres.");
  long price=cents(request.priceCents());
  String period=oneOf(request.period(),PERIODS,"BILLING_PERIOD_INVALID","Use período mensal ou anual.");
  String reason=reason(request.reason());
  UUID id=UUID.randomUUID();
  return manage(actor,assurance,Map.of("23505","BILLING_PLAN_NAME_TAKEN"),context->{
   store.insertPlan(id,name,price,period,actor);
   platform.audit(actor,"BILLING_PLAN_CREATED",null,reason,details("planId",id,"name",name,"priceCents",price,"period",period));
   return store.plan(id,false);
  });
 }
 /** Pedido igual ao plano atual é repetição natural (applied=false). Preço novo vale só para assinaturas novas. */
 public BillingModels.PlanChange updatePlan(UUID actor,AssuranceLevel assurance,UUID planId,BillingModels.UpdatePlan request) {
  if(planId==null) throw notFound("BILLING_PLAN_NOT_FOUND","Plano não encontrado.");
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("BILLING_VERSION_REQUIRED","Recarregue os dados antes de salvar.");
  String name=PlatformService.text(request.name(),120,"BILLING_PLAN_NAME_INVALID","Informe o nome do plano com até 120 caracteres.");
  long price=cents(request.priceCents());
  if(request.active()==null) throw invalid("BILLING_INPUT_INVALID","Informe se o plano fica disponível.");
  boolean active=request.active();
  String reason=reason(request.reason());
  return manage(actor,assurance,Map.of("23505","BILLING_PLAN_NAME_TAKEN"),context->{
   var current=store.plan(planId,true);
   if(current==null) throw notFound("BILLING_PLAN_NOT_FOUND","Plano não encontrado.");
   if(current.name().equals(name) && current.priceCents()==price && current.active()==active)
    return new BillingModels.PlanChange(current,false);
   if(current.version()!=request.expectedVersion() || !store.updatePlan(planId,name,price,active,request.expectedVersion()))
    throw changed("BILLING_PLAN_CHANGED","O plano foi alterado por outra pessoa. Recarregue os dados antes de salvar.");
   platform.audit(actor,"BILLING_PLAN_UPDATED",null,reason,details("planId",planId,"previousName",current.name(),"name",name,
    "previousPriceCents",current.priceCents(),"priceCents",price,"active",active));
   return new BillingModels.PlanChange(store.plan(planId,false),true);
  });
 }

 public List<BillingModels.Subscription> subscriptions(UUID actor,AssuranceLevel assurance,UUID tenant) {
  return read(actor,assurance,context->store.subscriptions(tenant));
 }
 /** Valor e período vêm do plano ativo no momento da contratação e não mudam com o plano. */
 public BillingModels.Subscription createSubscription(UUID actor,AssuranceLevel assurance,BillingModels.CreateSubscription request) {
  if(request==null || request.tenantId()==null || request.planId()==null)
   throw invalid("BILLING_INPUT_INVALID","Informe o condomínio e o plano.");
  LocalDate startsOn=request.startsOn();
  if(startsOn==null || startsOn.getDayOfMonth()>28 || startsOn.isBefore(EARLIEST) || startsOn.isAfter(LATEST_START))
   throw invalid("BILLING_START_INVALID","Informe o início da assinatura entre os dias 1 e 28 do mês, a partir de 2000.");
  int dueDay=request.dueDay()==null?0:request.dueDay();
  if(dueDay<1 || dueDay>28) throw invalid("BILLING_DUE_DAY_INVALID","Informe o dia de vencimento entre 1 e 28.");
  String reason=reason(request.reason());
  UUID id=UUID.randomUUID();
  return manage(actor,assurance,Map.of("23505","BILLING_SUBSCRIPTION_EXISTS","23P01","BILLING_START_OVERLAP"),context->{
   // Bloqueia o plano (preço e período lidos são os gravados) e as assinaturas do condomínio (encerramento concorrente).
   var plan=store.plan(request.planId(),true);
   if(plan==null) throw notFound("BILLING_PLAN_NOT_FOUND","Plano não encontrado.");
   if(!plan.active()) throw changed("BILLING_PLAN_INACTIVE","O plano não está disponível para novas assinaturas.");
   var previous=store.lockTenantSubscriptions(request.tenantId());
   if(previous.active()) throw changed("BILLING_SUBSCRIPTION_EXISTS",duplicateMessage("BILLING_SUBSCRIPTION_EXISTS"));
   if(previous.latestEnd()!=null && startsOn.isBefore(previous.latestEnd()))
    throw changed("BILLING_START_OVERLAP",duplicateMessage("BILLING_START_OVERLAP"));
   store.insertSubscription(id,request.tenantId(),plan.planId(),plan.priceCents(),plan.period(),startsOn,dueDay,actor);
   platform.audit(actor,"BILLING_SUBSCRIPTION_CREATED",request.tenantId(),reason,details("subscriptionId",id,"planName",plan.name(),
    "priceCents",plan.priceCents(),"period",plan.period(),"startsOn",startsOn,"dueDay",dueDay));
   return store.subscription(id,false);
  });
 }
 /**
  * Encerramento com data posterior ao início; pode ser futura (encerramento agendado). Cobranças não canceladas não
  * podem passar da data, e nova assinatura do condomínio só começa a partir dela.
  */
 public BillingModels.Subscription endSubscription(UUID actor,AssuranceLevel assurance,UUID subscriptionId,BillingModels.EndSubscription request) {
  if(subscriptionId==null) throw notFound("BILLING_SUBSCRIPTION_NOT_FOUND","Assinatura não encontrada.");
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("BILLING_VERSION_REQUIRED","Recarregue os dados antes de salvar.");
  if(request.endsOn()==null || request.endsOn().isAfter(LATEST_END)) throw invalid("BILLING_END_INVALID","Informe a data de encerramento.");
  String reason=reason(request.reason());
  return manage(actor,assurance,Map.of(),context->{
   var current=store.subscription(subscriptionId,true);
   if(current==null) throw notFound("BILLING_SUBSCRIPTION_NOT_FOUND","Assinatura não encontrada.");
   if(!"ACTIVE".equals(current.status())) throw changed("BILLING_SUBSCRIPTION_ENDED","A assinatura já está encerrada.");
   if(current.version()!=request.expectedVersion())
    throw changed("BILLING_SUBSCRIPTION_CHANGED","A assinatura foi alterada por outra pessoa. Recarregue os dados.");
   if(!request.endsOn().isAfter(current.startsOn()))
    throw invalid("BILLING_END_INVALID","O encerramento precisa ser depois do início da assinatura.");
   if(store.chargesBeyond(subscriptionId,request.endsOn()))
    throw changed("BILLING_CHARGES_AFTER_END","Há cobrança de período depois desta data. Cancele-a ou escolha outra data.");
   if(!store.endSubscription(subscriptionId,request.endsOn(),request.expectedVersion()))
    throw changed("BILLING_SUBSCRIPTION_CHANGED","A assinatura foi alterada por outra pessoa. Recarregue os dados.");
   platform.audit(actor,"BILLING_SUBSCRIPTION_ENDED",current.tenantId(),reason,details("subscriptionId",subscriptionId,"endsOn",request.endsOn()));
   return store.subscription(subscriptionId,false);
  });
 }

 public BillingModels.ChargePage charges(UUID actor,AssuranceLevel assurance,String status,UUID tenant) {
  if(status!=null && !CHARGE_FILTERS.contains(status)) throw invalid("BILLING_FILTER_INVALID","Situação de cobrança inválida.");
  return read(actor,assurance,context->store.charges(status,tenant));
 }
 /**
  * Cobrança de um período alinhado ao início da assinatura (mensal ou anual), com o valor contratado. Vencimento no
  * primeiro dia de vencimento a partir do início do período. Um período por assinatura; sem rateio. Assinatura
  * encerrada ainda aceita período que termina até o encerramento; período cancelado pode ser cobrado de novo.
  */
 public BillingModels.Charge createCharge(UUID actor,AssuranceLevel assurance,UUID subscriptionId,BillingModels.CreateCharge request) {
  if(subscriptionId==null) throw notFound("BILLING_SUBSCRIPTION_NOT_FOUND","Assinatura não encontrada.");
  LocalDate start=request==null?null:request.periodStart();
  if(start==null || start.isBefore(EARLIEST) || start.isAfter(LATEST_PERIOD))
   throw invalid("BILLING_PERIOD_START_INVALID","Informe o início do período.");
  UUID id=UUID.randomUUID();
  return manage(actor,assurance,Map.of("23505","BILLING_CHARGE_EXISTS","23P01","BILLING_CHARGE_EXISTS"),context->{
   var subscription=store.subscription(subscriptionId,true);
   if(subscription==null) throw notFound("BILLING_SUBSCRIPTION_NOT_FOUND","Assinatura não encontrada.");
   // Assinatura encerrada ainda cobra períodos que terminam até a data de encerramento (cobrança esquecida).
   LocalDate end=periodEnd(subscription,start);
   if(subscription.endsOn()!=null && end.isAfter(subscription.endsOn()))
    throw invalid("BILLING_PERIOD_START_INVALID","O período passa do encerramento da assinatura.");
   LocalDate due=dueOn(start,subscription.dueDay());
   store.insertCharge(id,subscriptionId,subscription.tenantId(),start,end,subscription.priceCents(),due,actor);
   platform.audit(actor,"BILLING_CHARGE_CREATED",subscription.tenantId(),null,details("chargeId",id,"periodStart",start,
    "periodEnd",end,"amountCents",subscription.priceCents(),"dueOn",due));
   return store.charge(id,false);
  });
 }
 public BillingModels.Charge cancelCharge(UUID actor,AssuranceLevel assurance,UUID chargeId,BillingModels.CancelCharge request) {
  if(chargeId==null) throw notFound("BILLING_CHARGE_NOT_FOUND","Cobrança não encontrada.");
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("BILLING_VERSION_REQUIRED","Recarregue os dados antes de salvar.");
  String reason=reason(request.reason());
  return manage(actor,assurance,Map.of(),context->{
   var charge=store.charge(chargeId,true);
   if(charge==null) throw notFound("BILLING_CHARGE_NOT_FOUND","Cobrança não encontrada.");
   if("PAID".equals(charge.status())) throw changed("BILLING_CHARGE_PAID","A cobrança está paga. Estorne o pagamento antes de cancelar.");
   if("CANCELLED".equals(charge.status())) throw changed("BILLING_CHARGE_CANCELLED","A cobrança já está cancelada.");
   if(charge.version()!=request.expectedVersion() || !store.cancelCharge(chargeId,reason,request.expectedVersion()))
    throw changed("BILLING_CHARGE_CHANGED","A cobrança foi alterada por outra pessoa. Recarregue os dados.");
   platform.audit(actor,"BILLING_CHARGE_CANCELLED",charge.tenantId(),reason,details("chargeId",chargeId,"amountCents",charge.amountCents()));
   return store.charge(chargeId,false);
  });
 }
 /**
  * Registro manual de pagamento: valor igual ao da cobrança (sem pagamento parcial), data até hoje, forma e referência
  * do comprovante. A cobrança só passa a PAID junto com o pagamento, na mesma transação.
  */
 public BillingModels.Charge recordPayment(UUID actor,AssuranceLevel assurance,UUID chargeId,BillingModels.RecordPayment request) {
  if(chargeId==null) throw notFound("BILLING_CHARGE_NOT_FOUND","Cobrança não encontrada.");
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0)
   throw invalid("BILLING_VERSION_REQUIRED","Recarregue os dados antes de salvar.");
  if(request.amountCents()==null || request.amountCents()<1 || request.amountCents()>MAX_CENTS)
   throw invalid("BILLING_PAYMENT_AMOUNT_INVALID","Informe o valor pago.");
  if(request.paidOn()==null || request.paidOn().isBefore(EARLIEST))
   throw invalid("BILLING_PAYMENT_DATE_INVALID","Informe a data do pagamento.");
  String method=oneOf(request.method(),METHODS,"BILLING_PAYMENT_METHOD_INVALID","Forma de pagamento inválida.");
  String reference=PlatformService.text(request.reference(),200,"BILLING_PAYMENT_REFERENCE_REQUIRED",
   "Informe a referência do comprovante (de 3 a 200 caracteres).");
  if(reference.length()<3) throw invalid("BILLING_PAYMENT_REFERENCE_REQUIRED","Informe a referência do comprovante (de 3 a 200 caracteres).");
  UUID id=UUID.randomUUID();
  // A cobrança é bloqueada antes do pagamento: duplicidade restante é o mesmo comprovante em outra cobrança.
  return manage(actor,assurance,Map.of("23505","BILLING_PAYMENT_REFERENCE_USED"),context->{
   var charge=store.charge(chargeId,true);
   if(charge==null) throw notFound("BILLING_CHARGE_NOT_FOUND","Cobrança não encontrada.");
   if(!"OPEN".equals(charge.status())) throw changed("BILLING_CHARGE_NOT_OPEN","A cobrança não está em aberto.");
   if(charge.version()!=request.expectedVersion())
    throw changed("BILLING_CHARGE_CHANGED","A cobrança foi alterada por outra pessoa. Recarregue os dados.");
   if(request.amountCents()!=charge.amountCents())
    throw new BusinessProblem("BILLING_PAYMENT_AMOUNT_MISMATCH",422,"O valor pago precisa ser igual ao da cobrança. Pagamento parcial não é registrado.");
   if(request.paidOn().isAfter(store.today()))
    throw invalid("BILLING_PAYMENT_DATE_INVALID","A data do pagamento não pode ser futura.");
   if(store.referenceRecorded(method,reference))
    throw changed("BILLING_PAYMENT_REFERENCE_USED",duplicateMessage("BILLING_PAYMENT_REFERENCE_USED"));
   store.insertPayment(id,chargeId,request.amountCents(),request.paidOn(),method,reference,actor);
   if(!store.markPaid(chargeId,request.expectedVersion()))
    throw changed("BILLING_CHARGE_CHANGED","A cobrança foi alterada por outra pessoa. Recarregue os dados.");
   platform.audit(actor,"BILLING_PAYMENT_RECORDED",charge.tenantId(),null,details("chargeId",chargeId,"paymentId",id,
    "amountCents",request.amountCents(),"method",method,"paidOn",request.paidOn()));
   return store.charge(chargeId,false);
  });
 }
 /** Estorno de registro errado: o pagamento fica VOIDED com motivo e responsável e a cobrança volta a OPEN. */
 public BillingModels.Charge voidPayment(UUID actor,AssuranceLevel assurance,UUID paymentId,BillingModels.VoidPayment request) {
  if(paymentId==null) throw notFound("BILLING_PAYMENT_NOT_FOUND","Pagamento não encontrado.");
  if(request==null || request.expectedChargeVersion()==null || request.expectedChargeVersion()<0)
   throw invalid("BILLING_VERSION_REQUIRED","Recarregue os dados antes de salvar.");
  String reason=reason(request.reason());
  return manage(actor,assurance,Map.of(),context->{
   var found=store.payment(paymentId,false);
   if(found==null) throw notFound("BILLING_PAYMENT_NOT_FOUND","Pagamento não encontrado.");
   // Mesma ordem de bloqueio do registro: cobrança primeiro, depois o pagamento.
   var charge=store.charge(found.chargeId(),true);
   var payment=store.payment(paymentId,true);
   if(charge==null || payment==null) throw notFound("BILLING_PAYMENT_NOT_FOUND","Pagamento não encontrado.");
   if(!"RECORDED".equals(payment.status())) throw changed("BILLING_PAYMENT_VOIDED","O pagamento já foi estornado.");
   if(charge.version()!=request.expectedChargeVersion())
    throw changed("BILLING_CHARGE_CHANGED","A cobrança foi alterada por outra pessoa. Recarregue os dados.");
   if(!store.voidPayment(paymentId,actor,reason) || !store.reopen(charge.chargeId(),request.expectedChargeVersion()))
    throw changed("BILLING_CHARGE_CHANGED","A cobrança foi alterada por outra pessoa. Recarregue os dados.");
   platform.audit(actor,"BILLING_PAYMENT_VOIDED",charge.tenantId(),reason,details("chargeId",charge.chargeId(),"paymentId",paymentId,
    "amountCents",payment.amountCents()));
   return store.charge(charge.chargeId(),false);
  });
 }

 static LocalDate periodEnd(BillingModels.Subscription subscription,LocalDate start) {
  boolean yearly="YEARLY".equals(subscription.period());
  long steps=yearly?ChronoUnit.YEARS.between(subscription.startsOn(),start):ChronoUnit.MONTHS.between(subscription.startsOn(),start);
  LocalDate aligned=yearly?subscription.startsOn().plusYears(steps):subscription.startsOn().plusMonths(steps);
  if(steps<0 || !aligned.equals(start))
   throw invalid("BILLING_PERIOD_START_INVALID","O período precisa começar no mesmo dia do início da assinatura, a partir dele.");
  return yearly?start.plusYears(1):start.plusMonths(1);
 }
 static LocalDate dueOn(LocalDate start,int dueDay) {
  return dueDay>=start.getDayOfMonth()?start.withDayOfMonth(dueDay):start.plusMonths(1).withDayOfMonth(dueDay);
 }
 private static long cents(Long value) {
  if(value==null || value<1 || value>MAX_CENTS)
   throw invalid("BILLING_PRICE_INVALID","Informe o valor em centavos, de R$ 0,01 até R$ 1.000.000,00.");
  return value;
 }
 private static String oneOf(String value,Set<String> allowed,String code,String message) {
  if(value==null || !allowed.contains(value)) throw invalid(code,message);
  return value;
 }
 private static String reason(String value) {
  return PlatformService.text(value,500,"AUDIT_REASON_REQUIRED","Informe o motivo, com até 500 caracteres.");
 }
 private static Map<String,String> details(Object... pairs) {
  var result=new LinkedHashMap<String,String>();
  for(int i=0;i+1<pairs.length;i+=2) result.put(String.valueOf(pairs[i]),String.valueOf(pairs[i+1]));
  return result;
 }
 private <T> T read(UUID actor,AssuranceLevel assurance,Function<PlatformContext,T> action) {
  return execute(actor,assurance,Map.of(),context->{ context.require(PlatformCapability.READ_BILLING); return action.apply(context); });
 }
 private <T> T manage(UUID actor,AssuranceLevel assurance,Map<String,String> conflicts,Function<PlatformContext,T> action) {
  return execute(actor,assurance,conflicts,context->{
   context.require(PlatformCapability.READ_BILLING); context.require(PlatformCapability.MANAGE_BILLING);
   return action.apply(context);
  });
 }
 /** Unicidade (23505) e exclusão de períodos (23P01) viram o código de conflito da operação; o resto é genérico. */
 private <T> T execute(UUID actor,AssuranceLevel assurance,Map<String,String> conflicts,Function<PlatformContext,T> action) {
  Objects.requireNonNull(action);
  try {
   return transactions.execute(actor,assurance,action);
  } catch(DataAccessException error) {
   // Constraints e o gatilho diferido abortam a transação; traduzir somente depois do rollback do executor.
   String state=SqlStates.of(error);
   String conflict=state==null?null:conflicts.get(state);
   if(conflict!=null) throw changed(conflict,duplicateMessage(conflict));
   if("23503".equals(state)) throw notFound("BILLING_REFERENCE_NOT_FOUND","Condomínio, plano ou cobrança não encontrado.");
   if("23514".equals(state)) throw changed("BILLING_STATE_CONFLICT","Os dados financeiros mudaram ou não atendem às regras. Recarregue e confira.");
   if("42501".equals(state)) throw new BusinessProblem("PLATFORM_ACCESS_DENIED",403,"Esta área é exclusiva da administração da plataforma.");
   throw new BusinessProblem("BILLING_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação financeira. Atualize e confira antes de repetir.");
  }
 }
 private static String duplicateMessage(String code) {
  return switch(code) {
   case "BILLING_PLAN_NAME_TAKEN" -> "Já existe um plano com este nome.";
   case "BILLING_SUBSCRIPTION_EXISTS" -> "O condomínio já tem uma assinatura ativa.";
   case "BILLING_CHARGE_EXISTS" -> "Já existe cobrança para este período.";
   case "BILLING_START_OVERLAP" -> "O início precisa ser depois do encerramento da assinatura anterior do condomínio.";
   case "BILLING_PAYMENT_REFERENCE_USED" -> "Este comprovante já quita outra cobrança. Confira a referência e a forma de pagamento.";
   default -> "Registro duplicado.";
  };
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound(String code,String message) { return new BusinessProblem(code,404,message); }
 private static BusinessProblem changed(String code,String message) { return new BusinessProblem(code,409,message); }
}
