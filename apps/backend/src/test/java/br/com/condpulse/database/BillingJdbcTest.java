package br.com.condpulse.database;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.BillingModels;
import br.com.condpulse.platform.application.BillingService;
import br.com.condpulse.platform.application.PlatformService;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.BillingStore;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V011 com PostgreSQL real: plano, assinatura, cobrança, pagamento manual, estorno e cancelamento. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class BillingJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static BillingService billing;
 private static PlatformService platform;
 private static PlatformTransactionExecutor executor;
 private static PlatformStore store;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  executor=new PlatformTransactionExecutor(db.jdbc,db.manager);
  store=new PlatformStore(db.jdbc);
  billing=new BillingService(executor,new BillingStore(db.jdbc),store);
  platform=new PlatformService(executor,store);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.grantPlatformAdmin(db.accountA); db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private BillingModels.Plan plan(String name,long cents) {
  return billing.createPlan(db.accountA,AAL2,new BillingModels.CreatePlan(name,cents,"MONTHLY","Tabela aprovada em ensaio"));
 }
 private BillingModels.Subscription subscription(BillingModels.Plan plan,LocalDate startsOn) {
  return billing.createSubscription(db.accountA,AAL2,new BillingModels.CreateSubscription(db.tenantB,plan.planId(),startsOn,10,"Contrato de ensaio"));
 }

 @Test void paymentIsRecordedOnlyForTheExactAmountAndVoidReopensTheCharge() throws Exception {
  var plan=plan("Plano Ensaio "+UUID.randomUUID().toString().substring(0,8),15000);
  var subscription=subscription(plan,LocalDate.of(2030,1,5));
  assertEquals(15000,subscription.priceCents()); assertEquals("ACTIVE",subscription.status());
  var charge=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,2,5)));
  assertEquals(LocalDate.of(2030,3,5),charge.periodEnd()); assertEquals(LocalDate.of(2030,2,10),charge.dueOn());
  assertEquals("OPEN",charge.status()); assertNull(charge.payment());
  assertEquals("BILLING_CHARGE_EXISTS",assertThrows(BusinessProblem.class,()->billing.createCharge(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,2,5)))).code());
  assertEquals("BILLING_PERIOD_START_INVALID",assertThrows(BusinessProblem.class,()->billing.createCharge(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,2,6)))).code());
  LocalDate today=LocalDate.now(ZoneId.of("America/Sao_Paulo"));
  assertEquals("BILLING_PAYMENT_AMOUNT_MISMATCH",assertThrows(BusinessProblem.class,()->billing.recordPayment(db.accountA,AAL2,
   charge.chargeId(),new BillingModels.RecordPayment(14999L,today,"PIX","E2E-ENSAIO-001",charge.version()))).code());
  assertEquals("BILLING_PAYMENT_DATE_INVALID",assertThrows(BusinessProblem.class,()->billing.recordPayment(db.accountA,AAL2,
   charge.chargeId(),new BillingModels.RecordPayment(15000L,today.plusDays(2),"PIX","E2E-ENSAIO-001",charge.version()))).code());
  var paid=billing.recordPayment(db.accountA,AAL2,charge.chargeId(),new BillingModels.RecordPayment(15000L,today,"PIX","E2E-ENSAIO-001",charge.version()));
  assertEquals("PAID",paid.status()); assertNotNull(paid.payment()); assertEquals("E2E-ENSAIO-001",paid.payment().reference());
  assertEquals(db.accountA,paid.payment().recordedBy());
  assertEquals("BILLING_CHARGE_NOT_OPEN",assertThrows(BusinessProblem.class,()->billing.recordPayment(db.accountA,AAL2,
   charge.chargeId(),new BillingModels.RecordPayment(15000L,today,"PIX","E2E-ENSAIO-002",paid.version()))).code());
  assertEquals("BILLING_CHARGE_PAID",assertThrows(BusinessProblem.class,()->billing.cancelCharge(db.accountA,AAL2,
   charge.chargeId(),new BillingModels.CancelCharge(paid.version(),"Cancelar paga"))).code());
  assertEquals("BILLING_CHARGE_CHANGED",assertThrows(BusinessProblem.class,()->billing.voidPayment(db.accountA,AAL2,
   paid.payment().paymentId(),new BillingModels.VoidPayment(charge.version(),"Versão antiga"))).code());
  var reopened=billing.voidPayment(db.accountA,AAL2,paid.payment().paymentId(),new BillingModels.VoidPayment(paid.version(),"Comprovante de outro período"));
  assertEquals("OPEN",reopened.status()); assertNull(reopened.payment());
  assertEquals("VOIDED",db.adminString("select status from platform.billing_payments where id=?",paid.payment().paymentId()));
  // O comprovante estornado quita a cobrança certa; registrado, não quita uma segunda (caixa e espaços não importam).
  var next=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,3,5)));
  var nextPaid=billing.recordPayment(db.accountA,AAL2,next.chargeId(),new BillingModels.RecordPayment(15000L,today,"PIX","E2E-ENSAIO-001",next.version()));
  assertEquals("PAID",nextPaid.status());
  assertEquals("BILLING_PAYMENT_REFERENCE_USED",assertThrows(BusinessProblem.class,()->billing.recordPayment(db.accountA,AAL2,
   charge.chargeId(),new BillingModels.RecordPayment(15000L,today,"PIX"," e2e-ensaio-001 ",reopened.version()))).code());
  var otherMethod=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,4,5)));
  assertEquals("PAID",billing.recordPayment(db.accountA,AAL2,otherMethod.chargeId(),
   new BillingModels.RecordPayment(15000L,today,"BOLETO","E2E-ENSAIO-001",otherMethod.version())).status(),"Outra forma é outro comprovante");
  var cancelled=billing.cancelCharge(db.accountA,AAL2,charge.chargeId(),new BillingModels.CancelCharge(reopened.version(),"Período cobrado por engano"));
  assertEquals("CANCELLED",cancelled.status()); assertEquals("Período cobrado por engano",cancelled.cancelReason());
  var again=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,2,5)));
  assertEquals("OPEN",again.status(),"Período cancelado pode ser cobrado de novo");
  var actions=platform.audit(db.accountA,AAL2,null,20).events().stream().map(e->e.action()).toList();
  assertTrue(actions.containsAll(List.of("BILLING_PLAN_CREATED","BILLING_SUBSCRIPTION_CREATED","BILLING_CHARGE_CREATED",
   "BILLING_PAYMENT_RECORDED","BILLING_PAYMENT_VOIDED","BILLING_CHARGE_CANCELLED")),actions.toString());
  db.assertEmptyContext();
 }
 @Test void planPriceChangeDoesNotRewriteSubscriptionsAndOnlyOneActivePerTenant() throws Exception {
  assertEquals("BILLING_PRICE_INVALID",assertThrows(BusinessProblem.class,()->plan("Plano Sem Valor",0)).code());
  var plan=plan("Plano Preço "+UUID.randomUUID().toString().substring(0,8),20000);
  // Início no dia 20 com vencimento no dia 10: o vencimento cai no mês seguinte ao início do período (Java e banco).
  var subscription=subscription(plan,LocalDate.of(2030,1,20));
  var changed=billing.updatePlan(db.accountA,AAL2,plan.planId(),new BillingModels.UpdatePlan(plan.name(),25000L,true,plan.version(),"Reajuste aprovado"));
  assertTrue(changed.applied()); assertEquals(25000,changed.plan().priceCents());
  var replay=billing.updatePlan(db.accountA,AAL2,plan.planId(),new BillingModels.UpdatePlan(plan.name(),25000L,true,plan.version(),"Repetição"));
  assertFalse(replay.applied());
  assertEquals("BILLING_PLAN_CHANGED",assertThrows(BusinessProblem.class,()->billing.updatePlan(db.accountA,AAL2,plan.planId(),
   new BillingModels.UpdatePlan(plan.name(),30000L,true,plan.version(),"Versão antiga"))).code());
  var listed=billing.subscriptions(db.accountA,AAL2,db.tenantB).getFirst();
  assertEquals(20000,listed.priceCents(),"Assinatura mantém o valor contratado");
  assertEquals("BILLING_SUBSCRIPTION_EXISTS",assertThrows(BusinessProblem.class,()->subscription(plan,LocalDate.of(2030,2,1))).code());
  var charge=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,3,20)));
  assertEquals(20000,charge.amountCents()); assertEquals(LocalDate.of(2030,4,10),charge.dueOn()); assertEquals(LocalDate.of(2030,4,20),charge.periodEnd());
  assertEquals("BILLING_CHARGES_AFTER_END",assertThrows(BusinessProblem.class,()->billing.endSubscription(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.EndSubscription(LocalDate.of(2030,4,1),subscription.version(),"Encerramento"))).code());
  assertEquals("BILLING_END_INVALID",assertThrows(BusinessProblem.class,()->billing.endSubscription(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.EndSubscription(LocalDate.of(2030,1,20),subscription.version(),"Antes do início"))).code());
  // Encerramento agendado (data futura) é aceito: a assinatura fica ENDED com a data.
  var ended=billing.endSubscription(db.accountA,AAL2,subscription.subscriptionId(),
   new BillingModels.EndSubscription(LocalDate.of(2030,4,20),subscription.version(),"Contrato encerrado"));
  assertEquals("ENDED",ended.status()); assertEquals(LocalDate.of(2030,4,20),ended.endsOn());
  var late=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,2,20)));
  assertEquals(LocalDate.of(2030,3,10),late.dueOn(),"Encerrada ainda cobra período esquecido dentro do contrato");
  assertEquals("BILLING_PERIOD_START_INVALID",assertThrows(BusinessProblem.class,()->billing.createCharge(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,4,20)))).code());
  assertEquals("BILLING_START_OVERLAP",assertThrows(BusinessProblem.class,()->subscription(plan,LocalDate.of(2030,4,1))).code());
  var deactivated=billing.updatePlan(db.accountA,AAL2,plan.planId(),new BillingModels.UpdatePlan(plan.name(),25000L,false,changed.plan().version(),"Plano descontinuado"));
  assertFalse(deactivated.plan().active());
  assertEquals("BILLING_PLAN_INACTIVE",assertThrows(BusinessProblem.class,()->subscription(plan,LocalDate.of(2030,5,1))).code());
  db.assertEmptyContext();
 }
 @Test void overdueIsComputedButNeverBlocksTheTenant() throws Exception {
  var plan=plan("Plano Atraso "+UUID.randomUUID().toString().substring(0,8),5000);
  LocalDate today=LocalDate.now(ZoneId.of("America/Sao_Paulo"));
  LocalDate start=today.minusMonths(3).withDayOfMonth(1);
  var subscription=subscription(plan,start);
  billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(start));
  var overdue=billing.charges(db.accountA,AAL2,"OVERDUE",db.tenantB).charges();
  assertEquals(1,overdue.size()); assertTrue(overdue.getFirst().overdue());
  assertTrue(billing.charges(db.accountA,AAL2,"PAID",db.tenantB).charges().isEmpty());
  int open=new TenantTransactionExecutor(db.jdbc,db.manager).execute(db.accountB,db.tenantB,context->1);
  assertEquals(1,open,"Atraso não bloqueia o contexto do condomínio");
  assertEquals("BILLING_FILTER_INVALID",assertThrows(BusinessProblem.class,()->billing.charges(db.accountA,AAL2,"LATE",null)).code());
  db.assertEmptyContext();
 }
 @Test void sessionHidesBillingWhileTheAreaIsDisabled() throws Exception {
  var hidden=new PlatformService(executor,store,false).session(db.accountA,AAL2).capabilities();
  assertEquals(List.of("outbox.operate","platform.audit.read","tenants.manage","tenants.read"),hidden);
  assertTrue(new PlatformService(executor,store,true).session(db.accountA,AAL2).capabilities().containsAll(List.of("billing.read","billing.manage")));
  db.assertEmptyContext();
 }
 @Test void secondFactorRoleAndLocalManagementAreRequiredBoundaries() throws Exception {
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->billing.plans(db.accountA,AssuranceLevel.AAL1)).code());
  assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->billing.plans(db.accountB,AAL2)).code());
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO FICTICIA','MANAGER','ACTIVE')",
   db.tenantB,db.accountC);
  try {
   int visible=new TenantTransactionExecutor(db.jdbc,db.manager).execute(db.accountC,db.tenantB,AAL2,
    context->db.jdbc.queryForObject("select count(*) from platform.billing_charges",Integer.class));
   assertEquals(0,visible,"Gestão local não vê o financeiro da plataforma");
  } finally {
   db.adminUpdate("delete from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantB,db.accountC);
  }
  db.assertEmptyContext();
 }
}
