package br.com.condpulse.finance.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.finance.application.FinanceModels;
import br.com.condpulse.finance.application.FinanceRules;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL do financeiro condominial (V036), sempre dentro do TenantTransactionExecutor; o banco repete papel e transições. */
public class FinanceStore {
 public static final int PAGE=200;
 private static final String CHARGE_SELECT="""
  select c.id,c.unit_id,u.label as unit_label,b.name as block_name,c.kind,c.description,c.amount_cents,c.due_date,c.billing_type,c.state,
   c.asaas_status,c.competence,c.invoice_url,c.bank_slip_url,c.paid_at,c.paid_cents,c.issue_error,c.cancel_reason,c.created_at,c.updated_at,
   c.version,c.asaas_payment_id,c.payer_id,c.cancelled_by,
   (c.issue_lease_until is not null and c.issue_lease_until>clock_timestamp()) as leased
  from cp.finance_charges c
  join cp.units u on u.tenant_id=c.tenant_id and u.id=c.unit_id
  left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
  """;
 private final JdbcTemplate jdbc;
 public FinanceStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Cobrança com os campos internos (pagamento do Asaas, pagador, reserva de emissão) que não vão ao navegador. */
 public record ChargeRow(FinanceModels.Charge view,String paymentId,UUID payerId,boolean leased,UUID cancelledBy) {}
 public record PayerRow(UUID id,UUID unitId,UUID membershipId,String legalName,String document,String email,String customerId,long version) {
  @Override public String toString() { return "PayerRow[id="+id+",redacted]"; }
 }
 public record Fee(UUID id,UUID unitId,String kind,String description,long amountCents,int dueDay,String billingType) {}

 // Unidades e pagadores.
 public List<FinanceModels.UnitFinance> units(UUID tenant) {
  record UnitRow(UUID id,String label,String block) {}
  var units=jdbc.query("""
   select u.id,u.label,b.name as block_name from cp.units u left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
   where u.tenant_id=? and u.status='ACTIVE' order by b.name nulls first,u.label,u.id limit 2000
   """,(r,n)->new UnitRow(r.getObject("id",UUID.class),r.getString("label"),r.getString("block_name")),tenant);
  Map<UUID,FinanceModels.Payer> payers=new HashMap<>();
  jdbc.query("""
   select p.id,p.unit_id,p.membership_id,m.display_name,m.role,p.legal_name,p.document,p.email,p.asaas_customer_id,p.created_at,p.version
   from cp.finance_payers p join cp.memberships m on m.tenant_id=p.tenant_id and m.id=p.membership_id
   where p.tenant_id=? and p.state='ACTIVE'
   """,r->{ payers.put(r.getObject("unit_id",UUID.class),payer(r)); },tenant);
  Map<UUID,List<FinanceModels.Candidate>> candidates=new HashMap<>();
  jdbc.query("""
   select m.id,m.unit_id,m.display_name,m.role from cp.memberships m
   where m.tenant_id=? and m.unit_id is not null and m.role in ('OWNER','RESPONSIBLE') and m.status='ACTIVE'
   order by m.role,m.display_name,m.id
   """,r->{ candidates.computeIfAbsent(r.getObject("unit_id",UUID.class),k->new ArrayList<>())
     .add(new FinanceModels.Candidate(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("role"))); },tenant);
  var rented=new HashSet<UUID>(jdbc.query("select distinct unit_id from cp.unit_rentals where tenant_id=? and ended_at is null",
   (r,n)->r.getObject(1,UUID.class),tenant));
  return units.stream().map(u->new FinanceModels.UnitFinance(u.id(),u.label(),u.block(),rented.contains(u.id()),payers.get(u.id()),
   candidates.getOrDefault(u.id(),List.of()))).toList();
 }
 public PayerRow activePayer(UUID tenant,UUID unit,boolean lock) {
  var rows=jdbc.query("""
   select id,unit_id,membership_id,legal_name,document,email,asaas_customer_id,version from cp.finance_payers
   where tenant_id=? and unit_id=? and state='ACTIVE'"""+(lock?" for update":""),(r,n)->payerRow(r),tenant,unit);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PayerRow payer(UUID tenant,UUID id) {
  var rows=jdbc.query("""
   select id,unit_id,membership_id,legal_name,document,email,asaas_customer_id,version from cp.finance_payers where tenant_id=? and id=?
   """,(r,n)->payerRow(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public FinanceModels.Payer payerView(UUID tenant,UUID id) {
  var rows=jdbc.query("""
   select p.id,p.unit_id,p.membership_id,m.display_name,m.role,p.legal_name,p.document,p.email,p.asaas_customer_id,p.created_at,p.version
   from cp.finance_payers p join cp.memberships m on m.tenant_id=p.tenant_id and m.id=p.membership_id where p.tenant_id=? and p.id=?
   """,(r,n)->payer(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertPayer(UUID id,UUID tenant,UUID unit,UUID membership,String name,String document,String email) {
  jdbc.update("insert into cp.finance_payers(id,tenant_id,unit_id,membership_id,legal_name,document,email) values(?,?,?,?,?,?,?)",
   id,tenant,unit,membership,name,document,email);
 }
 public boolean replacePayer(UUID tenant,UUID id,long version) {
  return jdbc.update("update cp.finance_payers set state='REPLACED',version=version+1 where tenant_id=? and id=? and state='ACTIVE' and version=?",
   tenant,id,version)==1;
 }
 public boolean setCustomer(UUID tenant,UUID payer,String customer) {
  return jdbc.update("""
   update cp.finance_payers set asaas_customer_id=?,version=version+1 where tenant_id=? and id=? and asaas_customer_id is null
   """,customer,tenant,payer)==1;
 }

 // Cobranças.
 public FinanceModels.ChargePage charges(UUID tenant,String state,UUID unit) {
  var sql=new StringBuilder(CHARGE_SELECT).append(" where c.tenant_id=?");
  var args=new ArrayList<Object>(); args.add(tenant);
  if(state!=null) { sql.append(" and c.state=?"); args.add(state); }
  if(unit!=null) { sql.append(" and c.unit_id=?"); args.add(unit); }
  sql.append(" order by c.due_date desc,c.created_at desc,c.id limit ?"); args.add(PAGE+1);
  var rows=jdbc.query(sql.toString(),(r,n)->row(r).view(),args.toArray());
  return new FinanceModels.ChargePage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public FinanceModels.ChargePage unitCharges(UUID tenant,UUID unit) {
  var rows=jdbc.query(CHARGE_SELECT+" where c.tenant_id=? and c.unit_id=? and c.state<>'PENDING_ISSUE' order by c.due_date desc,c.id limit ?",
   (r,n)->row(r).view(),tenant,unit,PAGE+1);
  return new FinanceModels.ChargePage(rows.stream().limit(PAGE).toList(),rows.size()>PAGE);
 }
 public ChargeRow charge(UUID tenant,UUID id,boolean lock) {
  var rows=jdbc.query(CHARGE_SELECT+" where c.tenant_id=? and c.id=?"+(lock?" for update of c":""),(r,n)->row(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertCharge(UUID id,UUID tenant,UUID unit,UUID payer,String kind,String description,long amount,LocalDate due,String billing) {
  jdbc.update("""
   insert into cp.finance_charges(id,tenant_id,unit_id,payer_id,kind,description,amount_cents,due_date,billing_type) values(?,?,?,?,?,?,?,?,?)
   """,id,tenant,unit,payer,kind,description,amount,Date.valueOf(due),billing);
 }
 /** Reserva curta de emissão: duas emissões simultâneas da mesma cobrança nunca chamam o Asaas ao mesmo tempo. */
 public boolean claimIssue(UUID tenant,UUID id,long version) {
  return jdbc.update("""
   update cp.finance_charges set issue_lease_until=clock_timestamp()+interval '90 seconds',version=version+1
   where tenant_id=? and id=? and state='PENDING_ISSUE' and version=? and (issue_lease_until is null or issue_lease_until<=clock_timestamp())
   """,tenant,id,version)==1;
 }
 /** Devolve a reserva de emissão tomada (por exemplo, por um cancelamento que não conseguiu falar com o Asaas). */
 public boolean releaseIssue(UUID tenant,UUID id,long version) {
  return jdbc.update("""
   update cp.finance_charges set issue_lease_until=null,version=version+1
   where tenant_id=? and id=? and state='PENDING_ISSUE' and version=? and issue_lease_until is not null
   """,tenant,id,version)==1;
 }
 public boolean markIssued(UUID tenant,UUID id,long version,String paymentId,FinanceRules.Target target,String invoiceUrl,String slipUrl) {
  return jdbc.update("""
   update cp.finance_charges set state=?,asaas_payment_id=?,asaas_status=?,invoice_url=?,bank_slip_url=?,paid_at=?,paid_cents=?,
    issue_error=null,issue_lease_until=null,version=version+1
   where tenant_id=? and id=? and state='PENDING_ISSUE' and version=?
   """,target.state(),paymentId,target.asaasStatus(),invoiceUrl,slipUrl,date(target.paidAt()),target.paidCents(),tenant,id,version)==1;
 }
 public boolean markIssueFailed(UUID tenant,UUID id,long version,String code) {
  return jdbc.update("""
   update cp.finance_charges set issue_error=?,issue_lease_until=null,version=version+1
   where tenant_id=? and id=? and state='PENDING_ISSUE' and version=?
   """,code,tenant,id,version)==1;
 }
 public boolean cancel(UUID tenant,UUID id,long version,UUID actor,String reason) {
  return jdbc.update("""
   update cp.finance_charges set state='CANCELLED',cancelled_by=?,cancel_reason=?,issue_lease_until=null,version=version+1
   where tenant_id=? and id=? and state in ('PENDING_ISSUE','OPEN','OVERDUE') and version=?
   """,actor,reason,tenant,id,version)==1;
 }
 /** Motivo gravado quando a conciliação da gestão encontra a cobrança excluída no Asaas. */
 public static final String DELETED_IN_ASAAS="Excluída no Asaas (conciliação)";
 /**
  * Conciliação pela gestão. Cancelamento fora do webhook exige autoria e motivo (gatilho da V036): quem sincroniza fica
  * como autor, com o motivo fixo de exclusão no Asaas.
  */
 public boolean sync(UUID tenant,UUID id,long version,FinanceRules.Target target,UUID actor) {
  boolean cancelled="CANCELLED".equals(target.state());
  return jdbc.update("""
   update cp.finance_charges set state=?,asaas_status=?,paid_at=?,paid_cents=?,cancelled_by=?,cancel_reason=?,version=version+1
   where tenant_id=? and id=? and version=?
   """,target.state(),target.asaasStatus(),date(target.paidAt()),target.paidCents(),cancelled?actor:null,cancelled?DELETED_IN_ASAAS:null,
   tenant,id,version)==1;
 }
 public List<UUID> pendingIssue(UUID tenant,int limit) {
  return jdbc.query("""
   select id from cp.finance_charges where tenant_id=? and state='PENDING_ISSUE'
    and (issue_lease_until is null or issue_lease_until<=clock_timestamp()) order by due_date,created_at,id limit ?
   """,(r,n)->r.getObject(1,UUID.class),tenant,limit);
 }
 public int pendingCount(UUID tenant) {
  Integer count=jdbc.queryForObject("select count(*) from cp.finance_charges where tenant_id=? and state='PENDING_ISSUE'",Integer.class,tenant);
  return count==null?0:count;
 }
 public void event(UUID tenant,String type,FinanceModels.Charge charge) {
  jdbc.update("""
   insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   values(?,?,?,?,jsonb_build_object('chargeId',?::uuid,'unitId',?::uuid,'state',?::text,'version',?::bigint))
   """,tenant,type,charge.chargeId(),charge.version(),charge.chargeId(),charge.unitId(),charge.state(),charge.version());
 }

 // Taxas recorrentes.
 public List<FinanceModels.RecurringFee> recurringFees(UUID tenant) {
  return jdbc.query(FEE_SELECT+" where f.tenant_id=? order by f.state,f.created_at desc,f.id limit 500",(r,n)->fee(r),tenant);
 }
 private static final String FEE_SELECT="""
  select f.id,f.unit_id,u.label as unit_label,f.kind,f.description,f.amount_cents,f.due_day,f.billing_type,f.state,f.created_at,f.version
  from cp.finance_recurring_fees f left join cp.units u on u.tenant_id=f.tenant_id and u.id=f.unit_id
  """;
 private static FinanceModels.RecurringFee fee(ResultSet r) throws SQLException {
  return new FinanceModels.RecurringFee(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("unit_label"),
   r.getString("kind"),r.getString("description"),r.getLong("amount_cents"),r.getInt("due_day"),r.getString("billing_type"),r.getString("state"),
   r.getTimestamp("created_at").toInstant(),r.getLong("version"));
 }
 /** Taxa pelo identificador, sem depender do limite da listagem. */
 public FinanceModels.RecurringFee recurringFee(UUID tenant,UUID id) {
  var rows=jdbc.query(FEE_SELECT+" where f.tenant_id=? and f.id=?",(r,n)->fee(r),tenant,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertRecurringFee(UUID id,UUID tenant,UUID unit,String kind,String description,long amount,int dueDay,String billing) {
  jdbc.update("""
   insert into cp.finance_recurring_fees(id,tenant_id,unit_id,kind,description,amount_cents,due_day,billing_type) values(?,?,?,?,?,?,?,?)
   """,id,tenant,unit,kind,description,amount,dueDay,billing);
 }
 public boolean endRecurringFee(UUID tenant,UUID id) {
  return jdbc.update("update cp.finance_recurring_fees set state='ENDED',version=version+1 where tenant_id=? and id=? and state='ACTIVE'",
   tenant,id)==1;
 }
 public List<Fee> activeFees(UUID tenant) {
  return jdbc.query("""
   select id,unit_id,kind,description,amount_cents,due_day,billing_type from cp.finance_recurring_fees
   where tenant_id=? and state='ACTIVE' order by created_at,id
   """,(r,n)->new Fee(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("kind"),r.getString("description"),
    r.getLong("amount_cents"),r.getInt("due_day"),r.getString("billing_type")),tenant);
 }
 /** Unidade ativa → pagador ativo. */
 public Map<UUID,UUID> payersByUnit(UUID tenant) {
  Map<UUID,UUID> result=new HashMap<>();
  jdbc.query("""
   select p.unit_id,p.id from cp.finance_payers p join cp.units u on u.tenant_id=p.tenant_id and u.id=p.unit_id and u.status='ACTIVE'
   where p.tenant_id=? and p.state='ACTIVE'
   """,r->{ result.put(r.getObject("unit_id",UUID.class),r.getObject("id",UUID.class)); },tenant);
  return result;
 }
 /** Cobrança da competência; null quando a mesma taxa já gerou a cobrança da unidade nessa competência. */
 public UUID insertRecurringCharge(UUID tenant,UUID unit,UUID payer,Fee fee,LocalDate competence,LocalDate due) {
  var ids=jdbc.query("""
   insert into cp.finance_charges(id,tenant_id,unit_id,payer_id,kind,description,amount_cents,due_date,billing_type,recurring_fee_id,competence)
   values(?,?,?,?,?,?,?,?,?,?,?)
   on conflict (tenant_id,recurring_fee_id,unit_id,competence) where recurring_fee_id is not null do nothing returning id
   """,(r,n)->r.getObject(1,UUID.class),UUID.randomUUID(),tenant,unit,payer,fee.kind(),fee.description(),fee.amountCents(),Date.valueOf(due),fee.billingType(),
   fee.id(),Date.valueOf(competence));
  return ids.isEmpty()?null:ids.getFirst();
 }

 // Inadimplência e relatórios.
 public FinanceModels.DelinquencyReport delinquency(UUID tenant,LocalDate today) {
  var rows=jdbc.query("""
   select c.unit_id,u.label as unit_label,b.name as block_name,count(*) as charges,sum(c.amount_cents) as total,min(c.due_date) as oldest
   from cp.finance_charges c join cp.units u on u.tenant_id=c.tenant_id and u.id=c.unit_id
   left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
   where c.tenant_id=? and c.state in ('OPEN','OVERDUE') and c.due_date<?
   group by c.unit_id,u.label,b.name order by min(c.due_date),u.label,c.unit_id limit 2000
   """,(r,n)->new FinanceModels.Delinquency(r.getObject("unit_id",UUID.class),r.getString("unit_label"),r.getString("block_name"),
    r.getInt("charges"),r.getLong("total"),r.getDate("oldest").toLocalDate()),tenant,Date.valueOf(today));
  return new FinanceModels.DelinquencyReport(today,rows,rows.stream().mapToLong(FinanceModels.Delinquency::totalCents).sum());
 }
 public FinanceModels.Report report(UUID tenant,LocalDate from,LocalDate until,LocalDate today) {
  return new FinanceModels.Report(jdbc.query("""
   select to_char(date_trunc('month',c.due_date),'YYYY-MM') as month,
    coalesce(sum(c.amount_cents) filter(where c.state not in ('PENDING_ISSUE','CANCELLED')),0) as issued,
    coalesce(sum(coalesce(c.paid_cents,c.amount_cents)) filter(where c.state='PAID'),0) as paid,
    coalesce(sum(c.amount_cents) filter(where c.state in ('OPEN','OVERDUE') and c.due_date>=?),0) as open,
    coalesce(sum(c.amount_cents) filter(where c.state in ('OPEN','OVERDUE') and c.due_date<?),0) as overdue,
    count(*) filter(where c.state='CANCELLED') as cancelled
   from cp.finance_charges c where c.tenant_id=? and c.due_date>=? and c.due_date<?
   group by 1 order by 1
   """,(r,n)->new FinanceModels.MonthSummary(r.getString("month"),r.getLong("issued"),r.getLong("paid"),r.getLong("open"),r.getLong("overdue"),
    r.getInt("cancelled")),Date.valueOf(today),Date.valueOf(today),tenant,Date.valueOf(from),Date.valueOf(until)));
 }
 /** Proprietário com locação em vigor nesta unidade (V034): a unidade some para ele. */
 public boolean rentedOwner(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("select cp.rental_owner_unit(?,?)",Boolean.class,tenant,unit));
 }
 public Instant now() { return jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant(); }

 // Idempotência da criação (mesmo protocolo dos demais módulos).
 public record IdempotentReply(boolean fresh,UUID resource,int responseStatus) {}
 public IdempotentReply begin(UUID tenant,UUID actor,String route,String key,String hash) {
  int inserted=jdbc.update("""
   insert into cp.idempotency_keys(tenant_id,actor_id,request_key,method,route,body_hash,expires_at)
   values(?,?,?,'POST',?,?,clock_timestamp()+interval '24 hours')
   on conflict(tenant_id,actor_id,method,route,request_key) do nothing
   """,tenant,actor,key,route,hash);
  if(inserted==1) return new IdempotentReply(true,null,0);
  var rows=jdbc.query("""
   select body_hash,response_status,expires_at<=clock_timestamp() as expired,(response_body->>'id')::uuid as resource
   from cp.idempotency_keys where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? for update
   """,(r,n)->{
    if(r.getBoolean("expired")) throw new BusinessProblem("IDEMPOTENCY_KEY_EXPIRED",409,"Use uma nova chave para esta operação.");
    if(!hash.equals(r.getString("body_hash"))) throw new BusinessProblem("IDEMPOTENCY_KEY_REUSED",409,"Esta chave já foi usada com outros dados.");
    Integer status=r.getObject("response_status",Integer.class);
    if(status==null) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
    return new IdempotentReply(false,r.getObject("resource",UUID.class),status);
   },tenant,actor,route,key);
  if(rows.size()!=1) throw new BusinessProblem("REQUEST_IN_PROGRESS",409,"A operação ainda está em processamento.");
  return rows.getFirst();
 }
 public void finish(UUID tenant,UUID actor,String route,String key,UUID resource,int status) {
  int changed=jdbc.update("""
   update cp.idempotency_keys set response_status=?,response_body=jsonb_build_object('id',?::uuid)
   where tenant_id=? and actor_id=? and method='POST' and route=? and request_key=? and response_status is null
   """,status,resource,tenant,actor,route,key);
  if(changed!=1) throw new IllegalStateException("Resultado de idempotência não persistido.");
 }

 private static Date date(LocalDate value) { return value==null?null:Date.valueOf(value); }
 private static LocalDate localDate(ResultSet r,String column) throws SQLException {
  var value=r.getDate(column);
  return value==null?null:value.toLocalDate();
 }
 private static PayerRow payerRow(ResultSet r) throws SQLException {
  return new PayerRow(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getObject("membership_id",UUID.class),
   r.getString("legal_name"),r.getString("document"),r.getString("email"),r.getString("asaas_customer_id"),r.getLong("version"));
 }
 private static FinanceModels.Payer payer(ResultSet r) throws SQLException {
  return new FinanceModels.Payer(r.getObject("id",UUID.class),r.getObject("membership_id",UUID.class),r.getString("display_name"),
   r.getString("role"),r.getString("legal_name"),FinanceRules.maskDocument(r.getString("document")),FinanceRules.maskEmail(r.getString("email")),
   r.getString("asaas_customer_id")!=null,r.getTimestamp("created_at").toInstant(),r.getLong("version"));
 }
 private static ChargeRow row(ResultSet r) throws SQLException {
  Long paid=r.getObject("paid_cents")==null?null:r.getLong("paid_cents");
  var view=new FinanceModels.Charge(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("unit_label"),
   r.getString("block_name"),r.getString("kind"),r.getString("description"),r.getLong("amount_cents"),localDate(r,"due_date"),
   r.getString("billing_type"),r.getString("state"),r.getString("asaas_status"),localDate(r,"competence"),r.getString("invoice_url"),
   r.getString("bank_slip_url"),localDate(r,"paid_at"),paid,r.getString("issue_error"),r.getString("cancel_reason"),
   r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("version"));
  return new ChargeRow(view,r.getString("asaas_payment_id"),r.getObject("payer_id",UUID.class),r.getBoolean("leased"),
   r.getObject("cancelled_by",UUID.class));
 }
}
