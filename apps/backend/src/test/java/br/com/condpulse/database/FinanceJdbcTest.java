package br.com.condpulse.database;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import br.com.condpulse.finance.application.AsaasGateway;
import br.com.condpulse.finance.application.FinanceModels;
import br.com.condpulse.finance.application.FinanceService;
import br.com.condpulse.finance.application.FinanceWebhookService;
import br.com.condpulse.finance.infrastructure.FinanceStore;
import br.com.condpulse.finance.infrastructure.FinanceWebhookStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V036 com PostgreSQL real e o login da API; Asaas simulado em memória (nenhuma chamada de rede). */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class FinanceJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static final String TOKEN="SYNTHETIC_webhook_token_0123456789abcdef";
 private static final UUID MANAGER=UUID.randomUUID(),OWNER=UUID.randomUUID();
 private static JdbcDisposableDatabase db;
 private static FinanceService finance;
 private static FinanceWebhookService webhook;
 private static FakeAsaas asaas;
 private static UUID ownerMembership;

 /** Asaas em memória: guarda clientes e pagamentos; o teste muda a situação como o Asaas mudaria. */
 static final class FakeAsaas implements AsaasGateway {
  final Map<String,String> customers=new ConcurrentHashMap<>();
  final Map<String,Payment> payments=new ConcurrentHashMap<>();
  final AtomicInteger created=new AtomicInteger();
  volatile boolean down;
  /** Executado uma vez na próxima busca por referência externa: simula outra requisição concorrente no meio da chamada. */
  volatile Runnable onFind;
  /** Executado uma vez depois de ler um pagamento (a leitura já devolvida fica antiga). */
  volatile Runnable onPayment;
  private void check() { if(down) throw new Unavailable(); }
  @Override public Optional<String> findCustomer(String reference) { check(); return Optional.ofNullable(customers.get(reference)); }
  @Override public String createCustomer(Customer customer) {
   check(); return customers.computeIfAbsent(customer.externalReference(),k->"cus_"+(1000+customers.size()));
  }
  @Override public Optional<Payment> findPayment(String reference) {
   check();
   var hook=onFind; onFind=null; if(hook!=null) hook.run(); return payments.values().stream().filter(p->reference.equals(p.externalReference()) && !p.deleted()).findFirst();
  }
  @Override public Payment createPayment(PaymentRequest request) {
   check();
   String id="pay_"+(5000+created.incrementAndGet());
   var payment=new Payment(id,"PENDING",request.externalReference(),request.billingType(),request.amountCents(),null,
    "https://sandbox.asaas.com/i/"+id.substring(4),"BOLETO".equals(request.billingType())?"https://sandbox.asaas.com/b/pdf/"+id.substring(4):null,false);
   payments.put(id,payment); return payment;
  }
  @Override public Optional<Payment> payment(String id) {
   check(); var read=Optional.ofNullable(payments.get(id));
   var hook=onPayment; onPayment=null; if(hook!=null) hook.run();
   return read;
  }
  @Override public void deletePayment(String id) {
   check(); var p=payments.get(id);
   if(p!=null && "RECEIVED".equals(p.status())) throw new Rejected("invalid_action");
   if(p!=null) payments.put(id,new Payment(p.id(),p.status(),p.externalReference(),p.billingType(),p.valueCents(),p.paymentDate(),p.invoiceUrl(),p.bankSlipUrl(),true));
  }
  @Override public String identificationField(String id) { check(); return "23793381286000000000300000000000100000000015000"; }
  @Override public String pixPayload(String id) { check(); return "00020101021226800014br.gov.bcb.pix2558SINTETICO-NAO-REAL"; }
  void set(String id,String status,LocalDate paid) {
   var p=payments.get(id);
   payments.put(id,new Payment(p.id(),status,p.externalReference(),p.billingType(),p.valueCents(),paid,p.invoiceUrl(),p.bankSlipUrl(),p.deleted()));
  }
 }

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  asaas=new FakeAsaas();
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  finance=new FinanceService(transactions,new FinanceStore(db.jdbc),asaas,new AuditRecorder(db.jdbc));
  webhook=new FinanceWebhookService(TOKEN,asaas,new FinanceWebhookStore(db.jdbc,db.manager));
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(OWNER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,OWNER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO FINANCEIRO JDBC','MANAGER','ACTIVE')",
   db.tenantA,MANAGER);
  ownerMembership=UUID.randomUUID();
  db.adminUpdate("insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,?,'PROPRIETARIO JDBC','OWNER','ACTIVE')",
   ownerMembership,db.tenantA,OWNER,db.unitA);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearReservationFixtures();
  db.adminUpdate("delete from cp.unit_rentals where tenant_id=?",db.tenantA);
  asaas.down=false; asaas.onFind=null; asaas.onPayment=null; db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "finance-jdbc-"+UUID.randomUUID(); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static LocalDate today() { return LocalDate.now(ZoneId.of("America/Sao_Paulo")); }
 private static void payer() {
  finance.setPayer(MANAGER,db.tenantA,AAL2,db.unitA,new FinanceModels.PayerChange(ownerMembership,"Proprietário Fictício","123.456.789-09",
   "Pagador@Example.invalid","Pagador definido em assembleia fictícia"));
 }
 private static FinanceModels.Charge charge(String billing) {
  return finance.create(MANAGER,db.tenantA,AAL2,key(),new FinanceModels.ChargeCreate(db.unitA,"FINE","Multa por barulho",15000L,today().plusDays(5),
   billing,"Multa aprovada pelo conselho")).charge();
 }
 private static String event(String id,String type,String payment) {
  return "{\"id\":\""+id+"\",\"event\":\""+type+"\",\"dateCreated\":\"2026-10-30 10:00:00\",\"payment\":{\"id\":\""+payment+"\"}}";
 }
 private static String paymentId(UUID charge) throws Exception {
  return db.adminString("select asaas_payment_id from cp.finance_charges where id=?",charge);
 }

 @Test void managementIssuesAndUnitSeesSecondCopy() throws Exception {
  assertEquals("MFA_REQUIRED",code(()->finance.units(MANAGER,db.tenantA,AssuranceLevel.AAL1)),"Gestão com AAL1 é recusada");
  assertEquals("STAFF_ACCESS_DENIED",code(()->finance.units(db.accountA,db.tenantA,AAL2)),"Morador não acessa a gestão");
  assertEquals("FINANCE_DOCUMENT_INVALID",code(()->finance.setPayer(MANAGER,db.tenantA,AAL2,db.unitA,
   new FinanceModels.PayerChange(ownerMembership,"Nome","111.111.111-11","a@example.invalid","Motivo"))));
  assertEquals("FINANCE_PAYER_MISSING",code(()->charge("BOLETO")));
  payer();
  var unit=finance.units(MANAGER,db.tenantA,AAL2).stream().filter(u->u.unitId().equals(db.unitA)).findFirst().orElseThrow();
  assertEquals("***.456.789-**",unit.payer().document()); assertEquals("p***@example.invalid",unit.payer().email());
  assertTrue(unit.payer().customerSynced(),"Cliente criado no Asaas simulado");
  assertEquals("12345678909",db.adminString("select document from cp.finance_payers where tenant_id=? and state='ACTIVE'",db.tenantA));
  var issued=charge("BOLETO");
  assertEquals("OPEN",issued.state()); assertNotNull(issued.invoiceUrl());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='FINANCE_CHARGE_ISSUED' and aggregate_id=?",issued.chargeId()));
  assertEquals("Multa aprovada pelo conselho",db.adminString("select reason from cp.audit_log where action='FINANCE_CHARGE_CREATED' and resource_id=?",
   issued.chargeId()));
  // Unidade: morador vê e pega a segunda via; outra unidade e outro condomínio não.
  assertEquals(1,finance.unitCharges(db.accountA,db.tenantA,db.unitA).charges().size());
  var info=finance.paymentInfo(db.accountA,db.tenantA,db.unitA,issued.chargeId());
  assertNotNull(info.identificationField()); assertNull(info.pixPayload());
  assertEquals("UNIT_ACCESS_DENIED",code(()->finance.unitCharges(db.accountC,db.tenantA,db.unitA)),"Morador de outra unidade");
  assertEquals("FINANCE_CHARGE_NOT_FOUND",code(()->finance.paymentInfo(db.accountC,db.tenantA,db.otherUnitA,issued.chargeId())));
  assertEquals("TENANT_ACCESS_DENIED",code(()->finance.unitCharges(db.accountB,db.tenantA,db.unitA)),"Outro condomínio");
  // Proprietário vê enquanto a unidade não está alugada; alugada, a unidade some para ele.
  assertEquals(1,finance.unitCharges(OWNER,db.tenantA,db.unitA).charges().size());
  db.adminUpdate("insert into cp.unit_rentals(tenant_id,unit_id,owner_membership_id,owner_account_id,started_by) values(?,?,?,?,?)",
   db.tenantA,db.unitA,ownerMembership,OWNER,MANAGER);
  assertEquals("UNIT_ACCESS_DENIED",code(()->finance.unitCharges(OWNER,db.tenantA,db.unitA)),"Proprietário de unidade alugada");
  assertEquals("UNIT_ACCESS_DENIED",code(()->finance.paymentInfo(OWNER,db.tenantA,db.unitA,issued.chargeId())));
  assertEquals(1,finance.unitCharges(db.accountA,db.tenantA,db.unitA).charges().size(),"Ocupante continua vendo");
  db.assertEmptyContext();
 }

 @Test void webhookIsAuthenticatedIdempotentAndTrustsOnlyTheAsaasReadback() throws Exception {
  payer();
  var issued=charge("PIX");
  String payment=paymentId(issued.chargeId());
  assertEquals("FINANCE_WEBHOOK_UNAUTHORIZED",code(()->webhook.handle("token-errado-0000000000000000000000000",event("evt_a1","PAYMENT_RECEIVED",payment))));
  assertEquals("FINANCE_WEBHOOK_UNAUTHORIZED",code(()->webhook.handle(null,event("evt_a1","PAYMENT_RECEIVED",payment))));
  assertEquals("FINANCE_WEBHOOK_INVALID",code(()->webhook.handle(TOKEN,"{nao-json")));
  // Corpo diz "recebido", mas o Asaas ainda diz pendente: nada muda além do registro do evento.
  assertEquals(FinanceWebhookService.Outcome.PROCESSED,webhook.handle(TOKEN,event("evt_a1","PAYMENT_RECEIVED",payment)));
  assertEquals("OPEN",db.adminString("select state from cp.finance_charges where id=?",issued.chargeId()));
  asaas.set(payment,"RECEIVED",today());
  assertEquals(FinanceWebhookService.Outcome.PROCESSED,webhook.handle(TOKEN,event("evt_a2","PAYMENT_RECEIVED",payment)));
  assertEquals(FinanceWebhookService.Outcome.DUPLICATE,webhook.handle(TOKEN,event("evt_a2","PAYMENT_RECEIVED",payment)),"Evento repetido");
  assertEquals("PAID:15000",db.adminString("select state||':'||paid_cents from cp.finance_charges where id=?",issued.chargeId()));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='FINANCE_CHARGE_UPDATED' and aggregate_id=?",issued.chargeId()));
  assertEquals("2",db.adminString("select count(*) from cp.finance_webhook_events where charge_id=?",issued.chargeId()));
  assertEquals(FinanceWebhookService.Outcome.IGNORED,webhook.handle(TOKEN,"{\"id\":\"evt_a3\",\"event\":\"TRANSFER_DONE\"}"));
  assertEquals(FinanceWebhookService.Outcome.IGNORED,webhook.handle(TOKEN,event("evt_a4","PAYMENT_RECEIVED","pay_999999")),"Pagamento alheio");
  // Já pago: o Asaas recusa cancelar e nada muda.
  var paid=finance.charges(MANAGER,db.tenantA,AAL2,"PAID",null).charges().getFirst();
  assertEquals("FINANCE_CHARGE_CLOSED",code(()->finance.cancel(MANAGER,db.tenantA,AAL2,paid.chargeId(),new FinanceModels.Cancel("Erro de lançamento",paid.version()))));
  asaas.down=true;
  assertEquals("FINANCE_ASAAS_UNAVAILABLE",code(()->webhook.handle(TOKEN,event("evt_a5","PAYMENT_UPDATED",payment))),"Asaas fora: reenvio");
  db.assertEmptyContext();
 }

 @Test void cancelSyncRecurringAndDelinquency() throws Exception {
  payer();
  var issued=charge("BOLETO");
  assertEquals("FINANCE_REASON_REQUIRED",code(()->finance.cancel(MANAGER,db.tenantA,AAL2,issued.chargeId(),new FinanceModels.Cancel(" ",issued.version()))));
  var cancelled=finance.cancel(MANAGER,db.tenantA,AAL2,issued.chargeId(),new FinanceModels.Cancel("Lançada em duplicidade",issued.version()));
  assertEquals("CANCELLED",cancelled.state()); assertTrue(asaas.payments.get(paymentId(issued.chargeId())).deleted());
  // PAYMENT_DELETED depois do cancelamento pela gestão: evento registrado uma vez, cobrança final intacta (sem 503 em laço).
  String deletedPayment=paymentId(issued.chargeId());
  assertEquals(FinanceWebhookService.Outcome.PROCESSED,webhook.handle(TOKEN,event("evt_c1","PAYMENT_DELETED",deletedPayment)));
  assertEquals(FinanceWebhookService.Outcome.DUPLICATE,webhook.handle(TOKEN,event("evt_c1","PAYMENT_DELETED",deletedPayment)));
  assertEquals("CANCELLED:"+cancelled.version(),db.adminString("select state||':'||version from cp.finance_charges where id=?",issued.chargeId()));
  assertEquals("CANCELLED",db.adminString("select resulting_state from cp.finance_webhook_events where event_id='evt_c1'"));
  assertEquals("CANCELLED",finance.sync(MANAGER,db.tenantA,AAL2,issued.chargeId()).state(),"Sincronizar cobrança final não tenta alterá-la");
  // Falha do Asaas na emissão: fica pendente com o código; "emitir pendentes" conclui depois.
  asaas.down=true;
  var pending=charge("BOLETO");
  assertEquals("PENDING_ISSUE",pending.state()); assertEquals("ASAAS_UNAVAILABLE",pending.issueError());
  assertTrue(finance.unitCharges(db.accountA,db.tenantA,db.unitA).charges().stream().noneMatch(c->c.chargeId().equals(pending.chargeId())),
   "Não emitida não aparece para a unidade");
  asaas.down=false;
  assertEquals(new FinanceModels.IssueResult(1,0,0),finance.issuePending(MANAGER,db.tenantA,AAL2));
  int before=asaas.created.get();
  assertEquals("OPEN",finance.issue(MANAGER,db.tenantA,AAL2,pending.chargeId()).state());
  assertEquals(before,asaas.created.get(),"Reemitir não cria outro pagamento");
  // Sincronização: atraso vindo do Asaas.
  asaas.set(paymentId(pending.chargeId()),"OVERDUE",null);
  assertEquals("OVERDUE",finance.sync(MANAGER,db.tenantA,AAL2,pending.chargeId()).state());
  // Excluída direto no Asaas antes do webhook: a conciliação cancela com autoria de quem sincroniza e motivo fixo.
  var deleted=charge("PIX");
  var stored=asaas.payments.get(paymentId(deleted.chargeId()));
  asaas.payments.put(stored.id(),new AsaasGateway.Payment(stored.id(),stored.status(),stored.externalReference(),stored.billingType(),
   stored.valueCents(),null,stored.invoiceUrl(),stored.bankSlipUrl(),true));
  var reconciled=finance.sync(MANAGER,db.tenantA,AAL2,deleted.chargeId());
  assertEquals("CANCELLED",reconciled.state()); assertEquals("Excluída no Asaas (conciliação)",reconciled.cancelReason());
  assertEquals(MANAGER.toString(),db.adminString("select cancelled_by from cp.finance_charges where id=?",deleted.chargeId()));
  // Recorrente: gerar de novo a mesma competência não duplica.
  var fee=finance.createRecurringFee(MANAGER,db.tenantA,AAL2,new FinanceModels.RecurringCreate(null,"CONDO_FEE","Taxa condominial",45000L,28,"PIX",
   "Valor aprovado em assembleia fictícia"));
  var ended=finance.createRecurringFee(MANAGER,db.tenantA,AAL2,new FinanceModels.RecurringCreate(null,"OTHER","Fundo de obras",10000L,10,"PIX",
   "Valor aprovado em assembleia fictícia"));
  assertEquals("ENDED",finance.endRecurringFee(MANAGER,db.tenantA,AAL2,ended.feeId(),new FinanceModels.Reason("Obra concluída")).state());
  assertEquals("ACTIVE",fee.state());
  String month=YearMonth.from(today()).toString();
  var first=finance.generate(MANAGER,db.tenantA,AAL2,new FinanceModels.Generate(month,"Competência do mês"));
  var again=finance.generate(MANAGER,db.tenantA,AAL2,new FinanceModels.Generate(month,"Competência do mês"));
  assertEquals(today().getDayOfMonth()<=28?1:0,first.created()); assertEquals(0,again.created());
  assertEquals("FINANCE_COMPETENCE_INVALID",code(()->finance.generate(MANAGER,db.tenantA,AAL2,new FinanceModels.Generate("2020-01","Antiga"))));
  // Inadimplência: vencida em aberto (vencimento ajustado no banco descartável para simular o atraso).
  db.adminUpdateWithoutTriggers("update cp.finance_charges set due_date=current_date-10 where id=?",pending.chargeId());
  var report=finance.delinquency(MANAGER,db.tenantA,AAL2);
  assertEquals(1,report.units().size()); assertEquals(15000,report.totalCents());
  assertEquals("STAFF_ACCESS_DENIED",code(()->finance.delinquency(db.accountA,db.tenantA,AAL2)));
  assertFalse(finance.report(MANAGER,db.tenantA,AAL2,YearMonth.from(today()).minusMonths(1).toString(),month).months().isEmpty());
  db.assertEmptyContext();
 }

 @Test void cancelAndIssueOfAPendingChargeExcludeEachOther() throws Exception {
  payer();
  asaas.down=true;
  var pending=charge("PIX");
  assertEquals("PENDING_ISSUE",pending.state());
  asaas.down=false;
  // Enquanto o cancelamento consulta o Asaas, uma emissão concorrente encontra a reserva e não cria pagamento.
  var during=new java.util.concurrent.atomic.AtomicReference<String>();
  asaas.onFind=()->during.set(code(()->finance.issue(MANAGER,db.tenantA,AAL2,pending.chargeId())));
  int before=asaas.created.get();
  var cancelled=finance.cancel(MANAGER,db.tenantA,AAL2,pending.chargeId(),new FinanceModels.Cancel("Lançada por engano",pending.version()));
  assertEquals("CANCELLED",cancelled.state());
  assertEquals("FINANCE_CHARGE_ISSUING",during.get(),"A emissão concorrente encontra a reserva do cancelamento");
  assertEquals(before,asaas.created.get(),"Nenhum pagamento criado no Asaas");
  // Emissão em andamento (reserva ativa): o cancelamento é recusado sem falar com o Asaas.
  asaas.down=true;
  var other=charge("PIX");
  asaas.down=false;
  db.adminUpdateWithoutTriggers("update cp.finance_charges set issue_lease_until=clock_timestamp()+interval '60 seconds' where id=?",other.chargeId());
  assertEquals("FINANCE_CHARGE_ISSUING",code(()->finance.cancel(MANAGER,db.tenantA,AAL2,other.chargeId(),new FinanceModels.Cancel("Motivo",other.version()))));
  // Asaas fora durante o cancelamento: a reserva é devolvida e a emissão segue possível.
  db.adminUpdateWithoutTriggers("update cp.finance_charges set issue_lease_until=null where id=?",other.chargeId());
  asaas.down=true;
  assertEquals("FINANCE_ASAAS_UNAVAILABLE",code(()->finance.cancel(MANAGER,db.tenantA,AAL2,other.chargeId(),new FinanceModels.Cancel("Motivo",other.version()))));
  asaas.down=false;
  assertEquals("OPEN",finance.issue(MANAGER,db.tenantA,AAL2,other.chargeId()).state());
  db.assertEmptyContext();
 }

 @Test void staleReconciliationNeverOverwritesAPaymentRecordedMeanwhile() throws Exception {
  payer();
  var issued=charge("BOLETO");
  String payment=paymentId(issued.chargeId());
  // A sincronização lê PENDING; antes de gravar, o webhook registra o pagamento.
  asaas.onPayment=()->{ asaas.set(payment,"RECEIVED",today());
   assertEquals(FinanceWebhookService.Outcome.PROCESSED,webhook.handle(TOKEN,event("evt_s1","PAYMENT_RECEIVED",payment))); };
  assertEquals("FINANCE_CHANGED",code(()->finance.sync(MANAGER,db.tenantA,AAL2,issued.chargeId())));
  assertEquals("PAID:15000",db.adminString("select state||':'||paid_cents from cp.finance_charges where id=?",issued.chargeId()));
  // Mesmo uma leitura antiga aplicada à versão atual não desfaz o pagamento.
  asaas.set(payment,"PENDING",null);
  var synced=finance.sync(MANAGER,db.tenantA,AAL2,issued.chargeId());
  assertEquals("PAID",synced.state()); assertEquals(15000L,synced.paidCents());
  db.assertEmptyContext();
 }

 @Test void webhookDuringIssuanceIsRetriedAndTheLaterIssueAdoptsThePaidPayment() throws Exception {
  payer();
  asaas.down=true;
  var pending=charge("PIX");
  asaas.down=false;
  // Pagamento já criado no Asaas com a referência da cobrança, ainda não vinculado (emissão em andamento) e já pago.
  var created=asaas.createPayment(new AsaasGateway.PaymentRequest("cus_1000","PIX",15000,today().plusDays(5),"Multa",pending.chargeId().toString()));
  asaas.set(created.id(),"RECEIVED",today());
  db.adminUpdateWithoutTriggers("update cp.finance_charges set issue_lease_until=clock_timestamp()+interval '60 seconds' where id=?",pending.chargeId());
  var retry=assertThrows(BusinessProblem.class,()->webhook.handle(TOKEN,event("evt_i1","PAYMENT_RECEIVED",created.id())));
  assertEquals("FINANCE_CHARGE_ISSUING",retry.code()); assertEquals(503,retry.status());
  assertEquals("0",db.adminString("select count(*) from cp.finance_webhook_events where event_id='evt_i1'"),"Evento não fica registrado");
  // Sem emissão em andamento: o evento não vinculado é ignorado e a emissão seguinte adota o pagamento já pago.
  db.adminUpdateWithoutTriggers("update cp.finance_charges set issue_lease_until=null where id=?",pending.chargeId());
  assertEquals(FinanceWebhookService.Outcome.IGNORED,webhook.handle(TOKEN,event("evt_i2","PAYMENT_RECEIVED",created.id())));
  int before=asaas.created.get();
  var issued=finance.issue(MANAGER,db.tenantA,AAL2,pending.chargeId());
  assertEquals("PAID",issued.state()); assertEquals(before,asaas.created.get(),"Adota o pagamento existente");
  // Reenvio do Asaas depois do vínculo: processado normalmente.
  assertEquals(FinanceWebhookService.Outcome.PROCESSED,webhook.handle(TOKEN,event("evt_i1","PAYMENT_RECEIVED",created.id())));
  db.assertEmptyContext();
 }
}
