package br.com.condpulse.finance.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import br.com.condpulse.finance.infrastructure.FinanceStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.application.DisplayText;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Financeiro por condomínio (ADR-0008). Gestão (finance.manage, AAL2): pagador por unidade, cobranças avulsas,
 * taxas recorrentes, cancelamento, sincronização, inadimplência e relatório, sempre com motivo na auditoria quando a
 * ação muda dados. Unidade (morador, responsável ou proprietário com vínculo vigente; nunca proprietário de unidade
 * alugada): cobranças emitidas e segunda via. Chamadas ao Asaas sempre fora da transação de negócio; a emissão é
 * idempotente pela referência externa (identificador da cobrança) e por uma reserva curta no banco.
 */
public class FinanceService {
 static final Set<String> KINDS=Set.of("CONDO_FEE","RESERVATION","FINE","OTHER");
 static final Set<String> FEE_KINDS=Set.of("CONDO_FEE","OTHER");
 static final Set<String> BILLING=Set.of("BOLETO","PIX");
 static final Set<String> STATES=Set.of("PENDING_ISSUE","OPEN","OVERDUE","PAID","CANCELLED","REFUNDED");
 static final int BATCH=20;
 private final TenantTransactionExecutor transactions;
 private final FinanceStore store;
 private final AsaasGateway asaas;
 private final AuditRecorder audit;
 public FinanceService(TenantTransactionExecutor transactions,FinanceStore store,AsaasGateway asaas,AuditRecorder audit) {
  this.transactions=transactions; this.store=store; this.asaas=asaas; this.audit=audit;
 }

 // Pagadores.
 public java.util.List<FinanceModels.UnitFinance> units(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{ manager(context); return store.units(tenant); });
 }
 public FinanceModels.Payer setPayer(UUID actor,UUID tenant,AssuranceLevel assurance,UUID unit,FinanceModels.PayerChange request) {
  if(unit==null) throw unitNotFound();
  if(request==null || request.membershipId()==null) throw invalid("FINANCE_PAYER_REQUIRED","Escolha o proprietário ou o responsável que paga.");
  String name=DisplayText.normalize(request.legalName(),120);
  if(name==null || name.codePointCount(0,name.length())<3) throw invalid("FINANCE_PAYER_NAME_INVALID","Informe o nome do pagador com 3 a 120 caracteres.");
  String document=FinanceRules.document(request.document());
  if(document==null) throw invalid("FINANCE_DOCUMENT_INVALID","Informe um CPF ou CNPJ válido.");
  String email=FinanceRules.email(request.email());
  if(email==null) throw invalid("FINANCE_EMAIL_INVALID","Informe um e-mail válido do pagador.");
  String reason=reason(request.reason());
  var payer=execute(actor,tenant,assurance,context->{
   manager(context);
   var current=store.activePayer(tenant,unit,true);
   if(current!=null && current.membershipId().equals(request.membershipId()) && current.legalName().equals(name)
      && current.document().equals(document) && current.email().equals(email)) return current;
   if(current!=null && !store.replacePayer(tenant,current.id(),current.version())) throw changed();
   UUID id=UUID.randomUUID();
   store.insertPayer(id,tenant,unit,request.membershipId(),name,document,email);
   audit.record(tenant,actor,"FINANCE_PAYER_SET","FINANCE_PAYER",id,reason);
   return store.payer(tenant,id);
  });
  ensureCustomer(actor,tenant,assurance,payer,false);
  return execute(actor,tenant,assurance,context->{ manager(context); return store.payerView(tenant,payer.id()); });
 }

 // Cobranças da gestão.
 public FinanceModels.ChargePage charges(UUID actor,UUID tenant,AssuranceLevel assurance,String state,UUID unit) {
  if(state!=null && !STATES.contains(state)) throw invalid("FINANCE_STATE_INVALID","Situação de cobrança inválida.");
  return execute(actor,tenant,assurance,context->{ manager(context); return store.charges(tenant,state,unit); });
 }
 public FinanceModels.Mutation create(UUID actor,UUID tenant,AssuranceLevel assurance,String key,FinanceModels.ChargeCreate request) {
  requireKey(key);
  if(request==null || request.unitId()==null) throw invalid("FINANCE_UNIT_REQUIRED","Escolha a unidade.");
  if(request.kind()==null || !KINDS.contains(request.kind())) throw invalid("FINANCE_KIND_INVALID","Escolha taxa condominial, reserva, multa ou outro.");
  if(request.billingType()==null || !BILLING.contains(request.billingType())) throw invalid("FINANCE_BILLING_INVALID","Escolha boleto ou PIX.");
  long amount=amount(request.amountCents());
  String description=description(request.description());
  String reason=reason(request.reason());
  if(request.dueDate()==null) throw invalid("FINANCE_DUE_DATE_INVALID","Informe o vencimento.");
  String hash=digest("CHARGE|"+request.unitId()+"|"+request.kind()+"|"+request.billingType()+"|"+amount+"|"+request.dueDate()+"|"
   +description.length()+":"+description+"|"+reason.length()+":"+reason);
  String route="/v1/tenants/"+tenant+"/management/finance/charges";
  var created=execute(actor,tenant,assurance,context->{
   manager(context);
   var previous=store.begin(tenant,actor,route,key,hash);
   if(!previous.fresh()) {
    var replay=store.charge(tenant,previous.resource(),false);
    if(replay==null) throw notFound();
    return new FinanceModels.Mutation(replay.view(),true,previous.responseStatus());
   }
   LocalDate today=LocalDate.now(context.timeZone());
   if(request.dueDate().isBefore(today) || request.dueDate().isAfter(today.plusYears(2)))
    throw invalid("FINANCE_DUE_DATE_INVALID","O vencimento vai de hoje a dois anos.");
   var payer=store.activePayer(tenant,request.unitId(),false);
   if(payer==null) throw new BusinessProblem("FINANCE_PAYER_MISSING",409,"Cadastre o pagador da unidade antes de cobrar.");
   UUID id=UUID.randomUUID();
   store.insertCharge(id,tenant,request.unitId(),payer.id(),request.kind(),description,amount,request.dueDate(),request.billingType());
   var charge=store.charge(tenant,id,false);
   if(charge==null) throw new IllegalStateException("Cobrança não encontrada na mesma transação.");
   audit.record(tenant,actor,"FINANCE_CHARGE_CREATED","FINANCE_CHARGE",id,reason);
   store.event(tenant,"FINANCE_CHARGE_CREATED",charge.view());
   store.finish(tenant,actor,route,key,id,201);
   return new FinanceModels.Mutation(charge.view(),false,201);
  });
  if(!"PENDING_ISSUE".equals(created.charge().state())) return created;
  return new FinanceModels.Mutation(issueQuietly(actor,tenant,assurance,created.charge().chargeId()),created.replayed(),created.status());
 }
 /** Emite (ou tenta de novo) uma cobrança pendente. */
 public FinanceModels.Charge issue(UUID actor,UUID tenant,AssuranceLevel assurance,UUID chargeId) {
  if(chargeId==null) throw notFound();
  return issueCharge(actor,tenant,assurance,chargeId);
 }
 /** Emite até 20 cobranças pendentes (por exemplo, depois de gerar uma competência). */
 public FinanceModels.IssueResult issuePending(UUID actor,UUID tenant,AssuranceLevel assurance) {
  var ids=execute(actor,tenant,assurance,context->{ manager(context); return store.pendingIssue(tenant,BATCH); });
  int issued=0,failed=0;
  for(UUID id:ids) {
   var charge=issueQuietly(actor,tenant,assurance,id);
   if("PENDING_ISSUE".equals(charge.state())) failed++; else issued++;
  }
  int pending=execute(actor,tenant,assurance,context->{ manager(context); return store.pendingCount(tenant); });
  return new FinanceModels.IssueResult(issued,failed,pending);
 }
 public FinanceModels.Charge cancel(UUID actor,UUID tenant,AssuranceLevel assurance,UUID chargeId,FinanceModels.Cancel request) {
  if(chargeId==null) throw notFound();
  if(request==null || request.expectedVersion()==null || request.expectedVersion()<0) throw invalid("FINANCE_VERSION_REQUIRED","Atualize a cobrança antes de continuar.");
  String reason=reason(request.reason());
  var current=execute(actor,tenant,assurance,context->{
   manager(context);
   var row=store.charge(tenant,chargeId,true);
   if(row==null) throw notFound();
   if("CANCELLED".equals(row.view().state())) return row;
   if(!Set.of("PENDING_ISSUE","OPEN","OVERDUE").contains(row.view().state())) throw closed();
   if(row.view().version()!=request.expectedVersion()) throw changed();
   if(row.leased()) throw issuing();
   return row;
  });
  if("CANCELLED".equals(current.view().state())) return current.view();
  try {
   if(current.paymentId()!=null) asaas.deletePayment(current.paymentId());
   else asaas.findPayment(chargeId.toString()).ifPresent(found->asaas.deletePayment(found.id()));
  } catch(AsaasGateway.Rejected rejected) {
   throw new BusinessProblem("FINANCE_ASAAS_REJECTED",409,"O Asaas não permitiu cancelar esta cobrança (por exemplo, já paga). Sincronize e confira.");
  } catch(AsaasGateway.Unavailable unavailable) { throw asaasUnavailable(); }
  return execute(actor,tenant,assurance,context->{
   manager(context);
   var row=store.charge(tenant,chargeId,true);
   if(row==null) throw notFound();
   if("CANCELLED".equals(row.view().state())) {
    audit.record(tenant,actor,"FINANCE_CHARGE_CANCELLED","FINANCE_CHARGE",chargeId,reason);
    return row.view();
   }
   if(!store.cancel(tenant,chargeId,row.view().version(),actor,reason)) throw changed();
   var cancelled=store.charge(tenant,chargeId,false).view();
   audit.record(tenant,actor,"FINANCE_CHARGE_CANCELLED","FINANCE_CHARGE",chargeId,reason);
   store.event(tenant,"FINANCE_CHARGE_CANCELLED",cancelled);
   return cancelled;
  });
 }
 /** Conciliação pontual: relê o pagamento no Asaas e aplica a situação. */
 public FinanceModels.Charge sync(UUID actor,UUID tenant,AssuranceLevel assurance,UUID chargeId) {
  if(chargeId==null) throw notFound();
  var current=execute(actor,tenant,assurance,context->{
   manager(context);
   var row=store.charge(tenant,chargeId,false);
   if(row==null) throw notFound();
   if(row.paymentId()==null) throw new BusinessProblem("FINANCE_CHARGE_NOT_ISSUED",409,"A cobrança ainda não foi emitida no Asaas.");
   return row;
  });
  AsaasGateway.Payment payment;
  try { payment=asaas.payment(current.paymentId()).orElse(null); }
  catch(AsaasGateway.Unavailable | AsaasGateway.Rejected unavailable) { throw asaasUnavailable(); }
  return execute(actor,tenant,assurance,context->{
   manager(context);
   var row=store.charge(tenant,chargeId,true);
   if(row==null) throw notFound();
   var view=row.view();
   var target=FinanceRules.target(view.state(),view.paidAt(),view.paidCents(),payment,LocalDate.now(context.timeZone()));
   if(same(view,target)) return view;
   if(!store.sync(tenant,chargeId,view.version(),target)) throw changed();
   var synced=store.charge(tenant,chargeId,false).view();
   audit.record(tenant,actor,"FINANCE_CHARGE_SYNCED","FINANCE_CHARGE",chargeId,null);
   if(!view.state().equals(synced.state())) store.event(tenant,"FINANCE_CHARGE_UPDATED",synced);
   return synced;
  });
 }

 // Taxas recorrentes.
 public java.util.List<FinanceModels.RecurringFee> recurringFees(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{ manager(context); return store.recurringFees(tenant); });
 }
 public FinanceModels.RecurringFee createRecurringFee(UUID actor,UUID tenant,AssuranceLevel assurance,FinanceModels.RecurringCreate request) {
  if(request==null) throw invalid("FINANCE_FEE_INVALID","Informe a taxa recorrente.");
  String kind=request.kind()==null?"CONDO_FEE":request.kind();
  if(!FEE_KINDS.contains(kind)) throw invalid("FINANCE_KIND_INVALID","Taxa recorrente é taxa condominial ou outro.");
  if(request.billingType()==null || !BILLING.contains(request.billingType())) throw invalid("FINANCE_BILLING_INVALID","Escolha boleto ou PIX.");
  if(request.dueDay()==null || request.dueDay()<1 || request.dueDay()>28) throw invalid("FINANCE_DUE_DAY_INVALID","O dia de vencimento vai de 1 a 28.");
  long amount=amount(request.amountCents());
  String description=description(request.description());
  String reason=reason(request.reason());
  return execute(actor,tenant,assurance,context->{
   manager(context);
   UUID id=UUID.randomUUID();
   store.insertRecurringFee(id,tenant,request.unitId(),kind,description,amount,request.dueDay(),request.billingType());
   audit.record(tenant,actor,"FINANCE_RECURRING_FEE_CREATED","FINANCE_RECURRING_FEE",id,reason);
   return store.recurringFee(tenant,id);
  });
 }
 public FinanceModels.RecurringFee endRecurringFee(UUID actor,UUID tenant,AssuranceLevel assurance,UUID feeId,FinanceModels.Reason request) {
  if(feeId==null) throw feeNotFound();
  String reason=reason(request==null?null:request.reason());
  return execute(actor,tenant,assurance,context->{
   manager(context);
   var fee=store.recurringFee(tenant,feeId);
   if(fee==null) throw feeNotFound();
   if("ENDED".equals(fee.state())) return fee;
   if(!store.endRecurringFee(tenant,feeId)) throw changed();
   audit.record(tenant,actor,"FINANCE_RECURRING_FEE_ENDED","FINANCE_RECURRING_FEE",feeId,reason);
   return store.recurringFee(tenant,feeId);
  });
 }
 /**
  * Gera as cobranças de uma competência para todas as taxas ativas (unidade da taxa ou todas com pagador). Repetir não
  * duplica. Vencimento já passado é pulado. Em seguida emite até 20; o restante fica pendente para "Emitir pendentes".
  */
 public FinanceModels.GenerateResult generate(UUID actor,UUID tenant,AssuranceLevel assurance,FinanceModels.Generate request) {
  YearMonth month;
  try { month=YearMonth.parse(request==null || request.competence()==null?"":request.competence()); }
  catch(DateTimeParseException invalid) { throw invalid("FINANCE_COMPETENCE_INVALID","Informe a competência no formato AAAA-MM."); }
  String reason=reason(request.reason());
  int[] counts=execute(actor,tenant,assurance,context->{
   manager(context);
   LocalDate today=LocalDate.now(context.timeZone());
   if(month.isBefore(YearMonth.from(today)) || month.isAfter(YearMonth.from(today).plusMonths(12)))
    throw invalid("FINANCE_COMPETENCE_INVALID","A competência vai do mês atual a 12 meses à frente.");
   var payers=store.payersByUnit(tenant);
   int created=0,skipped=0;
   for(var fee:store.activeFees(tenant)) {
    LocalDate due=month.atDay(fee.dueDay());
    var units=fee.unitId()==null?payers.keySet():Set.of(fee.unitId());
    for(UUID unit:units) {
     UUID payer=payers.get(unit);
     if(payer==null || due.isBefore(today)) { skipped++; continue; }
     if(store.insertRecurringCharge(tenant,unit,payer,fee,month.atDay(1),due)==null) skipped++; else created++;
    }
    audit.record(tenant,actor,"FINANCE_RECURRING_GENERATED","FINANCE_RECURRING_FEE",fee.id(),reason+" ("+month+")");
   }
   return new int[]{created,skipped};
  });
  var issued=issuePending(actor,tenant,assurance);
  return new FinanceModels.GenerateResult(counts[0],counts[1],issued.issued(),issued.pending());
 }

 public FinanceModels.DelinquencyReport delinquency(UUID actor,UUID tenant,AssuranceLevel assurance) {
  return execute(actor,tenant,assurance,context->{ manager(context); return store.delinquency(tenant,LocalDate.now(context.timeZone())); });
 }
 /** Resumo por mês de vencimento, de {@code from} a {@code until} (AAAA-MM, até 24 meses). */
 public FinanceModels.Report report(UUID actor,UUID tenant,AssuranceLevel assurance,String from,String until) {
  YearMonth start,end;
  try { start=YearMonth.parse(from); end=YearMonth.parse(until); }
  catch(DateTimeParseException | NullPointerException invalid) { throw invalid("FINANCE_PERIOD_INVALID","Informe o período no formato AAAA-MM."); }
  if(end.isBefore(start) || start.plusMonths(23).isBefore(end)) throw invalid("FINANCE_PERIOD_INVALID","O período tem de 1 a 24 meses.");
  return execute(actor,tenant,assurance,context->{
   manager(context);
   return store.report(tenant,start.atDay(1),end.plusMonths(1).atDay(1),LocalDate.now(context.timeZone()));
  });
 }

 // Unidade.
 public FinanceModels.ChargePage unitCharges(UUID actor,UUID tenant,UUID unit) {
  return execute(actor,tenant,AssuranceLevel.AAL1,context->{ requireUnit(context,unit); return store.unitCharges(tenant,unit); });
 }
 public FinanceModels.PaymentInfo paymentInfo(UUID actor,UUID tenant,UUID unit,UUID chargeId) {
  if(chargeId==null) throw notFound();
  var row=execute(actor,tenant,AssuranceLevel.AAL1,context->{
   requireUnit(context,unit);
   var current=store.charge(tenant,chargeId,false);
   if(current==null || !current.view().unitId().equals(unit)) throw notFound();
   if(!Set.of("OPEN","OVERDUE").contains(current.view().state()) || current.paymentId()==null)
    throw new BusinessProblem("FINANCE_CHARGE_NOT_PAYABLE",409,"Esta cobrança não está em aberto.");
   return current;
  });
  var view=row.view();
  try {
   String line="BOLETO".equals(view.billingType())?asaas.identificationField(row.paymentId()):null;
   String pix="PIX".equals(view.billingType())?asaas.pixPayload(row.paymentId()):null;
   return new FinanceModels.PaymentInfo(view.chargeId(),view.billingType(),line,pix,view.invoiceUrl(),view.bankSlipUrl(),view.dueDate(),view.amountCents());
  } catch(AsaasGateway.Unavailable | AsaasGateway.Rejected unavailable) { throw asaasUnavailable(); }
 }

 // Emissão.
 private record Claim(FinanceStore.ChargeRow charge,FinanceStore.PayerRow payer) {}
 private FinanceModels.Charge issueQuietly(UUID actor,UUID tenant,AssuranceLevel assurance,UUID chargeId) {
  try { return issueCharge(actor,tenant,assurance,chargeId); }
  catch(BusinessProblem problem) {
   if(!"FINANCE_CHARGE_ISSUING".equals(problem.code())) throw problem;
   return execute(actor,tenant,assurance,context->{ manager(context); return store.charge(tenant,chargeId,false).view(); });
  }
 }
 private FinanceModels.Charge issueCharge(UUID actor,UUID tenant,AssuranceLevel assurance,UUID chargeId) {
  var claim=execute(actor,tenant,assurance,context->{
   manager(context);
   var row=store.charge(tenant,chargeId,true);
   if(row==null) throw notFound();
   if(!"PENDING_ISSUE".equals(row.view().state())) return new Claim(row,null);
   if(!store.claimIssue(tenant,chargeId,row.view().version())) throw issuing();
   var payer=store.payer(tenant,row.payerId());
   if(payer==null) throw new IllegalStateException("Pagador da cobrança não encontrado.");
   return new Claim(store.charge(tenant,chargeId,false),payer);
  });
  if(claim.payer()==null) return claim.charge().view();
  var view=claim.charge().view();
  AsaasGateway.Payment payment;
  try {
   String customer=ensureCustomer(actor,tenant,assurance,claim.payer(),true);
   payment=asaas.findPayment(chargeId.toString()).orElse(null);
   if(payment==null) payment=asaas.createPayment(new AsaasGateway.PaymentRequest(customer,view.billingType(),view.amountCents(),view.dueDate(),
    view.description(),chargeId.toString()));
  } catch(AsaasGateway.Rejected | AsaasGateway.Unavailable failure) {
   String code=failure instanceof AsaasGateway.Rejected?"ASAAS_REJECTED":"ASAAS_UNAVAILABLE";
   return execute(actor,tenant,assurance,context->{
    manager(context);
    var row=store.charge(tenant,chargeId,true);
    if(row!=null && "PENDING_ISSUE".equals(row.view().state())) store.markIssueFailed(tenant,chargeId,row.view().version(),code);
    return store.charge(tenant,chargeId,false).view();
   });
  }
  var issued=payment;
  return execute(actor,tenant,assurance,context->{
   manager(context);
   var row=store.charge(tenant,chargeId,true);
   if(row==null) throw notFound();
   if(!"PENDING_ISSUE".equals(row.view().state())) return row.view();
   var target=FinanceRules.issued(issued,LocalDate.now(context.timeZone()));
   if(!store.markIssued(tenant,chargeId,row.view().version(),issued.id(),target,issued.invoiceUrl(),issued.bankSlipUrl())) throw changed();
   var opened=store.charge(tenant,chargeId,false).view();
   audit.record(tenant,actor,"FINANCE_CHARGE_ISSUED","FINANCE_CHARGE",chargeId,null);
   store.event(tenant,"FINANCE_CHARGE_ISSUED",opened);
   return opened;
  });
 }
 /** Cliente do Asaas do pagador: reaproveita o gravado, procura pela referência externa e só então cria. */
 private String ensureCustomer(UUID actor,UUID tenant,AssuranceLevel assurance,FinanceStore.PayerRow payer,boolean required) {
  if(payer.customerId()!=null) return payer.customerId();
  String customer;
  try {
   customer=asaas.findCustomer(payer.id().toString()).orElse(null);
   if(customer==null) customer=asaas.createCustomer(new AsaasGateway.Customer(payer.legalName(),payer.document(),payer.email(),payer.id().toString()));
  } catch(AsaasGateway.Rejected | AsaasGateway.Unavailable failure) {
   if(required) throw failure;
   return null;
  }
  String saved=customer;
  execute(actor,tenant,assurance,context->{ manager(context); store.setCustomer(tenant,payer.id(),saved); return null; });
  return customer;
 }

 private static boolean same(FinanceModels.Charge view,FinanceRules.Target target) {
  return view.state().equals(target.state()) && java.util.Objects.equals(view.asaasStatus(),target.asaasStatus())
   && java.util.Objects.equals(view.paidAt(),target.paidAt()) && java.util.Objects.equals(view.paidCents(),target.paidCents());
 }
 private void requireUnit(TenantContext context,UUID unit) {
  boolean member=unit!=null && context.memberships().stream().anyMatch(m->unit.equals(m.unitId())
   && ("RESIDENT".equals(m.role()) || "RESPONSIBLE".equals(m.role()) || "OWNER".equals(m.role())));
  if(!member || store.rentedOwner(context.tenantId(),unit)) throw new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação.");
 }
 private static void manager(TenantContext context) { context.requireCapability(StaffCapability.MANAGE_FINANCE); }
 private static long amount(Long cents) {
  if(cents==null || cents<500 || cents>100_000_000L) throw invalid("FINANCE_AMOUNT_INVALID","O valor vai de R$ 5,00 a R$ 1.000.000,00.");
  return cents;
 }
 private static String description(String value) {
  String text=DisplayText.normalize(value,200);
  if(text==null || text.codePointCount(0,text.length())<3) throw invalid("FINANCE_DESCRIPTION_INVALID","Descreva a cobrança com 3 a 200 caracteres.");
  return text;
 }
 private static String reason(String value) {
  String text=DisplayText.normalize(value,480);
  if(text==null || text.codePointCount(0,text.length())<3) throw invalid("FINANCE_REASON_REQUIRED","Informe o motivo, com 3 a 480 caracteres.");
  return text;
 }
 private static void requireKey(String key) {
  if(key==null || !key.matches("[A-Za-z0-9._:-]{16,200}"))
   throw invalid("IDEMPOTENCY_KEY_INVALID","Informe uma chave de operação válida de 16 a 200 caracteres.");
 }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.",impossible); }
 }
 private static BusinessProblem invalid(String code,String message) { return new BusinessProblem(code,400,message); }
 private static BusinessProblem notFound() { return new BusinessProblem("FINANCE_CHARGE_NOT_FOUND",404,"Cobrança não encontrada."); }
 private static BusinessProblem unitNotFound() { return new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação."); }
 private static BusinessProblem feeNotFound() { return new BusinessProblem("FINANCE_FEE_NOT_FOUND",404,"Taxa recorrente não encontrada."); }
 private static BusinessProblem changed() { return new BusinessProblem("FINANCE_CHANGED",409,"Os dados foram alterados. Atualize antes de continuar."); }
 private static BusinessProblem closed() { return new BusinessProblem("FINANCE_CHARGE_CLOSED",409,"Esta cobrança já foi paga, cancelada ou estornada."); }
 private static BusinessProblem issuing() { return new BusinessProblem("FINANCE_CHARGE_ISSUING",409,"A cobrança está sendo emitida. Atualize em instantes."); }
 private static BusinessProblem asaasUnavailable() {
  return new BusinessProblem("FINANCE_ASAAS_UNAVAILABLE",503,"O Asaas não respondeu. Atualize e confira antes de repetir.");
 }
 private <T> T execute(UUID actor,UUID tenant,AssuranceLevel assurance,Function<TenantContext,T> action) {
  try { return transactions.execute(actor,tenant,assurance,action); }
  catch(DataAccessException error) {
   String state=SqlStates.of(error);
   if("23514".equals(state)) throw new BusinessProblem("FINANCE_RULE_VIOLATION",409,"A operação não atende às regras do financeiro. Atualize e confira.");
   if("23505".equals(state)) throw changed();
   if("42501".equals(state)) throw new BusinessProblem("STAFF_ACCESS_DENIED",403,"Esta ação não está disponível para o seu vínculo.");
   throw new BusinessProblem("FINANCE_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  } catch(TransactionException error) {
   throw new BusinessProblem("FINANCE_DEPENDENCY_UNAVAILABLE",503,"Não foi possível concluir a operação. Atualize e confira antes de repetir.");
  }
 }
}
