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

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  var executor=new PlatformTransactionExecutor(db.jdbc,db.manager);
  var store=new PlatformStore(db.jdbc);
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
  var reopened=billing.voidPayment(db.accountA,AAL2,paid.payment().paymentId(),new BillingModels.VoidPayment(paid.version(),"Comprovante de outro condomínio"));
  assertEquals("OPEN",reopened.status()); assertNull(reopened.payment());
  assertEquals("VOIDED",db.adminString("select status from platform.billing_payments where id=?",paid.payment().paymentId()));
  var cancelled=billing.cancelCharge(db.accountA,AAL2,charge.chargeId(),new BillingModels.CancelCharge(reopened.version(),"Período cobrado por engano"));
  assertEquals("CANCELLED",cancelled.status()); assertEquals("Período cobrado por engano",cancelled.cancelReason());
  var actions=platform.audit(db.accountA,AAL2,null,20).events().stream().map(e->e.action()).toList();
  assertTrue(actions.containsAll(List.of("BILLING_PLAN_CREATED","BILLING_SUBSCRIPTION_CREATED","BILLING_CHARGE_CREATED",
   "BILLING_PAYMENT_RECORDED","BILLING_PAYMENT_VOIDED","BILLING_CHARGE_CANCELLED")),actions.toString());
  db.assertEmptyContext();
 }
 @Test void planPriceChangeDoesNotRewriteSubscriptionsAndOnlyOneActivePerTenant() throws Exception {
  var plan=plan("Plano Preço "+UUID.randomUUID().toString().substring(0,8),20000);
  var subscription=subscription(plan,LocalDate.of(2030,1,1));
  var changed=billing.updatePlan(db.accountA,AAL2,plan.planId(),new BillingModels.UpdatePlan(plan.name(),25000L,true,plan.version(),"Reajuste aprovado"));
  assertTrue(changed.applied()); assertEquals(25000,changed.plan().priceCents());
  var replay=billing.updatePlan(db.accountA,AAL2,plan.planId(),new BillingModels.UpdatePlan(plan.name(),25000L,true,plan.version(),"Repetição"));
  assertFalse(replay.applied());
  assertEquals("BILLING_PLAN_CHANGED",assertThrows(BusinessProblem.class,()->billing.updatePlan(db.accountA,AAL2,plan.planId(),
   new BillingModels.UpdatePlan(plan.name(),30000L,true,plan.version(),"Versão antiga"))).code());
  var listed=billing.subscriptions(db.accountA,AAL2,db.tenantB).getFirst();
  assertEquals(20000,listed.priceCents(),"Assinatura mantém o valor contratado");
  assertEquals("BILLING_SUBSCRIPTION_EXISTS",assertThrows(BusinessProblem.class,()->subscription(plan,LocalDate.of(2030,2,1))).code());
  var charge=billing.createCharge(db.accountA,AAL2,subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,3,1)));
  assertEquals(20000,charge.amountCents());
  assertEquals("BILLING_CHARGES_AFTER_END",assertThrows(BusinessProblem.class,()->billing.endSubscription(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.EndSubscription(LocalDate.of(2030,3,15),subscription.version(),"Encerramento"))).code());
  var ended=billing.endSubscription(db.accountA,AAL2,subscription.subscriptionId(),
   new BillingModels.EndSubscription(LocalDate.of(2030,4,1),subscription.version(),"Contrato encerrado"));
  assertEquals("ENDED",ended.status()); assertEquals(LocalDate.of(2030,4,1),ended.endsOn());
  assertEquals("BILLING_SUBSCRIPTION_ENDED",assertThrows(BusinessProblem.class,()->billing.createCharge(db.accountA,AAL2,
   subscription.subscriptionId(),new BillingModels.CreateCharge(LocalDate.of(2030,4,1)))).code());
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
