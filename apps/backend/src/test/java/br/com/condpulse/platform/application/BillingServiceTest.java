package br.com.condpulse.platform.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.BillingStore;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private BillingModels.Subscription subscription(String period,LocalDate startsOn) {
  return new BillingModels.Subscription(UUID.randomUUID(),UUID.randomUUID(),"Condomínio",UUID.randomUUID(),"Plano",1000,period,
   startsOn,null,10,"ACTIVE",0,Instant.EPOCH);
 }

 @Test void periodsFollowTheSubscriptionStartAndDueDateFallsInsideThePeriod() {
  var monthly=subscription("MONTHLY",LocalDate.of(2030,1,15));
  assertEquals(LocalDate.of(2030,2,15),BillingService.periodEnd(monthly,LocalDate.of(2030,1,15)));
  assertEquals(LocalDate.of(2031,1,15),BillingService.periodEnd(monthly,LocalDate.of(2030,12,15)));
  for(var invalid:new LocalDate[]{LocalDate.of(2030,1,14),LocalDate.of(2030,2,16),LocalDate.of(2029,12,15)})
   assertEquals("BILLING_PERIOD_START_INVALID",assertThrows(BusinessProblem.class,()->BillingService.periodEnd(monthly,invalid)).code());
  var yearly=subscription("YEARLY",LocalDate.of(2030,3,1));
  assertEquals(LocalDate.of(2032,3,1),BillingService.periodEnd(yearly,LocalDate.of(2031,3,1)));
  assertEquals("BILLING_PERIOD_START_INVALID",assertThrows(BusinessProblem.class,
   ()->BillingService.periodEnd(yearly,LocalDate.of(2030,4,1))).code());
  assertEquals(LocalDate.of(2030,1,20),BillingService.dueOn(LocalDate.of(2030,1,15),20));
  assertEquals(LocalDate.of(2030,1,15),BillingService.dueOn(LocalDate.of(2030,1,15),15));
  assertEquals(LocalDate.of(2030,2,5),BillingService.dueOn(LocalDate.of(2030,1,15),5));
  assertEquals(LocalDate.of(2031,1,5),BillingService.dueOn(LocalDate.of(2030,12,15),5));
 }
 @Test void invalidRequestsAreRefusedBeforeOpeningATransaction() {
  var transactions=mock(PlatformTransactionExecutor.class);
  var service=new BillingService(transactions,mock(BillingStore.class),mock(PlatformStore.class));
  UUID id=UUID.randomUUID();
  record Case(Runnable call,String code) {}
  for(var item:new Case[]{
    new Case(()->service.createPlan(ACCOUNT,AssuranceLevel.AAL2,new BillingModels.CreatePlan(" ",100L,"MONTHLY","Motivo")),"BILLING_PLAN_NAME_INVALID"),
    new Case(()->service.createPlan(ACCOUNT,AssuranceLevel.AAL2,new BillingModels.CreatePlan("Plano",-1L,"MONTHLY","Motivo")),"BILLING_PRICE_INVALID"),
    new Case(()->service.createPlan(ACCOUNT,AssuranceLevel.AAL2,new BillingModels.CreatePlan("Plano",100L,"WEEKLY","Motivo")),"BILLING_PERIOD_INVALID"),
    new Case(()->service.createPlan(ACCOUNT,AssuranceLevel.AAL2,new BillingModels.CreatePlan("Plano",100L,"MONTHLY",null)),"AUDIT_REASON_REQUIRED"),
    new Case(()->service.createSubscription(ACCOUNT,AssuranceLevel.AAL2,new BillingModels.CreateSubscription(id,id,LocalDate.of(2030,1,29),10,"Motivo")),
     "BILLING_START_INVALID"),
    new Case(()->service.createSubscription(ACCOUNT,AssuranceLevel.AAL2,new BillingModels.CreateSubscription(id,id,LocalDate.of(2030,1,1),29,"Motivo")),
     "BILLING_DUE_DAY_INVALID"),
    new Case(()->service.recordPayment(ACCOUNT,AssuranceLevel.AAL2,id,new BillingModels.RecordPayment(0L,LocalDate.of(2030,1,1),"PIX","REF-1",0L)),
     "BILLING_PAYMENT_AMOUNT_INVALID"),
    new Case(()->service.recordPayment(ACCOUNT,AssuranceLevel.AAL2,id,new BillingModels.RecordPayment(100L,LocalDate.of(2030,1,1),"CHEQUE","REF-1",0L)),
     "BILLING_PAYMENT_METHOD_INVALID"),
    new Case(()->service.recordPayment(ACCOUNT,AssuranceLevel.AAL2,id,new BillingModels.RecordPayment(100L,LocalDate.of(2030,1,1),"PIX","ab",0L)),
     "BILLING_PAYMENT_REFERENCE_REQUIRED"),
    new Case(()->service.recordPayment(ACCOUNT,AssuranceLevel.AAL2,id,new BillingModels.RecordPayment(100L,null,"PIX","REF-1",0L)),
     "BILLING_PAYMENT_DATE_INVALID"),
    new Case(()->service.voidPayment(ACCOUNT,AssuranceLevel.AAL2,id,new BillingModels.VoidPayment(0L," ")),"AUDIT_REASON_REQUIRED"),
    new Case(()->service.cancelCharge(ACCOUNT,AssuranceLevel.AAL2,id,new BillingModels.CancelCharge(null,"Motivo")),"BILLING_VERSION_REQUIRED"),
    new Case(()->service.charges(ACCOUNT,AssuranceLevel.AAL2,"LATE",null),"BILLING_FILTER_INVALID")}) {
   var error=assertThrows(BusinessProblem.class,item.call()::run);
   assertEquals(item.code(),error.code()); assertEquals(400,error.status());
  }
  verifyNoInteractions(transactions);
 }
}
