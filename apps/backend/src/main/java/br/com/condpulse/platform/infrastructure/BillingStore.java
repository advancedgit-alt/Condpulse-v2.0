package br.com.condpulse.platform.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import br.com.condpulse.platform.application.BillingModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL do financeiro da plataforma (V011). Executado somente dentro do PlatformTransactionExecutor: as políticas
 * exigem billing.read/billing.manage publicadas no contexto local; a consistência PAID ⇔ pagamento é conferida no
 * commit por gatilho diferido.
 */
@Repository @Profile("api")
public class BillingStore {
 public static final int CHARGE_PAGE=200;
 private final JdbcTemplate jdbc;
 public BillingStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Data de hoje no fuso de operação do projeto, pelo relógio do banco. */
 public LocalDate today() {
  return jdbc.queryForObject("select (clock_timestamp() at time zone 'America/Sao_Paulo')::date",LocalDate.class);
 }

 private static final String PLAN_SELECT="select id,name,price_cents,period,active,version,created_at,updated_at from platform.billing_plans";
 private static final RowMapper<BillingModels.Plan> PLAN=(r,n)->new BillingModels.Plan(r.getObject("id",UUID.class),
  r.getString("name"),r.getLong("price_cents"),r.getString("period"),r.getBoolean("active"),r.getLong("version"),
  r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
 public List<BillingModels.Plan> plans() {
  return jdbc.query(PLAN_SELECT+" order by active desc,lower(name),id",PLAN);
 }
 public BillingModels.Plan plan(UUID id,boolean lock) {
  var rows=jdbc.query(PLAN_SELECT+" where id=?"+(lock?" for update":""),PLAN,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertPlan(UUID id,String name,long priceCents,String period,UUID actor) {
  jdbc.update("insert into platform.billing_plans(id,name,price_cents,period,created_by) values(?,?,?,?,?)",id,name,priceCents,period,actor);
 }
 public boolean updatePlan(UUID id,String name,long priceCents,boolean active,long expectedVersion) {
  return jdbc.update("""
   update platform.billing_plans set name=?,price_cents=?,active=?,version=version+1,updated_at=clock_timestamp()
   where id=? and version=?
   """,name,priceCents,active,id,expectedVersion)==1;
 }

 private static final String SUBSCRIPTION_SELECT="""
  select s.id,s.tenant_id,t.name as tenant_name,s.plan_id,p.name as plan_name,s.price_cents,s.period,s.starts_on,s.ends_on,
    s.due_day,s.status,s.version,s.created_at
  from platform.billing_subscriptions s
  join platform.tenants t on t.id=s.tenant_id
  join platform.billing_plans p on p.id=s.plan_id
  """;
 private static final RowMapper<BillingModels.Subscription> SUBSCRIPTION=(r,n)->new BillingModels.Subscription(
  r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("tenant_name"),r.getObject("plan_id",UUID.class),
  r.getString("plan_name"),r.getLong("price_cents"),r.getString("period"),r.getObject("starts_on",LocalDate.class),
  r.getObject("ends_on",LocalDate.class),r.getInt("due_day"),r.getString("status"),r.getLong("version"),r.getTimestamp("created_at").toInstant());
 public List<BillingModels.Subscription> subscriptions(UUID tenant) {
  return tenant==null
   ?jdbc.query(SUBSCRIPTION_SELECT+" order by s.status,lower(t.name),s.starts_on desc,s.id limit 500",SUBSCRIPTION)
   :jdbc.query(SUBSCRIPTION_SELECT+" where s.tenant_id=? order by s.status,s.starts_on desc,s.id limit 500",SUBSCRIPTION,tenant);
 }
 public BillingModels.Subscription subscription(UUID id,boolean lock) {
  var rows=jdbc.query(SUBSCRIPTION_SELECT+" where s.id=?"+(lock?" for update of s":""),SUBSCRIPTION,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertSubscription(UUID id,UUID tenant,UUID plan,long priceCents,String period,LocalDate startsOn,int dueDay,UUID actor) {
  jdbc.update("""
   insert into platform.billing_subscriptions(id,tenant_id,plan_id,price_cents,period,starts_on,due_day,status,created_by)
   values(?,?,?,?,?,?,?,'ACTIVE',?)
   """,id,tenant,plan,priceCents,period,Date.valueOf(startsOn),dueDay,actor);
 }
 public boolean endSubscription(UUID id,LocalDate endsOn,long expectedVersion) {
  return jdbc.update("""
   update platform.billing_subscriptions set ends_on=?,status='ENDED',ended_at=clock_timestamp(),version=version+1
   where id=? and version=? and status='ACTIVE'
   """,Date.valueOf(endsOn),id,expectedVersion)==1;
 }
 /** Assinaturas do condomínio bloqueadas na ordem do id: há ativa? qual o último encerramento? */
 public record TenantSubscriptions(boolean active,LocalDate latestEnd) {}
 private record SubscriptionState(String status,LocalDate endsOn) {}
 public TenantSubscriptions lockTenantSubscriptions(UUID tenant) {
  var rows=jdbc.query("select status,ends_on from platform.billing_subscriptions where tenant_id=? order by id for update",
   (r,n)->new SubscriptionState(r.getString("status"),r.getObject("ends_on",LocalDate.class)),tenant);
  return new TenantSubscriptions(rows.stream().anyMatch(row->"ACTIVE".equals(row.status())),
   rows.stream().map(SubscriptionState::endsOn).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
 }
 /** Cobrança não cancelada que passa do fim pedido impede encerrar a assinatura nessa data. */
 public boolean chargesBeyond(UUID subscription,LocalDate endsOn) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from platform.billing_charges where subscription_id=? and status<>'CANCELLED' and period_end>?)
   """,Boolean.class,subscription,Date.valueOf(endsOn)));
 }

 private static final String CHARGE_SELECT="""
  select c.id,c.subscription_id,c.tenant_id,t.name as tenant_name,c.period_start,c.period_end,c.amount_cents,c.due_on,c.status,
    c.status='OPEN' and c.due_on<(clock_timestamp() at time zone 'America/Sao_Paulo')::date as overdue,
    c.cancel_reason,c.version,c.created_at,
    p.id as payment_id,p.amount_cents as payment_amount,p.paid_on,p.method,p.reference,p.recorded_by,p.recorded_at
  from platform.billing_charges c
  join platform.tenants t on t.id=c.tenant_id
  left join platform.billing_payments p on p.charge_id=c.id and p.status='RECORDED'
  """;
 private static final RowMapper<BillingModels.Charge> CHARGE=(r,n)->new BillingModels.Charge(r.getObject("id",UUID.class),
  r.getObject("subscription_id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("tenant_name"),
  r.getObject("period_start",LocalDate.class),r.getObject("period_end",LocalDate.class),r.getLong("amount_cents"),
  r.getObject("due_on",LocalDate.class),r.getString("status"),r.getBoolean("overdue"),r.getString("cancel_reason"),
  r.getLong("version"),r.getTimestamp("created_at").toInstant(),payment(r));
 private static BillingModels.Payment payment(ResultSet r) throws SQLException {
  UUID id=r.getObject("payment_id",UUID.class);
  return id==null?null:new BillingModels.Payment(id,r.getLong("payment_amount"),r.getObject("paid_on",LocalDate.class),
   r.getString("method"),r.getString("reference"),r.getObject("recorded_by",UUID.class),r.getTimestamp("recorded_at").toInstant());
 }
 /** Filtro por situação (OPEN, OVERDUE, PAID, CANCELLED) e condomínio; trechos fixos, valores sempre por parâmetro. */
 public BillingModels.ChargePage charges(String status,UUID tenant) {
  var where=new ArrayList<String>(); var args=new ArrayList<Object>();
  if("OVERDUE".equals(status)) where.add("c.status='OPEN' and c.due_on<(clock_timestamp() at time zone 'America/Sao_Paulo')::date");
  else if(status!=null) { where.add("c.status=?"); args.add(status); }
  if(tenant!=null) { where.add("c.tenant_id=?"); args.add(tenant); }
  args.add(CHARGE_PAGE+1);
  var rows=jdbc.query(CHARGE_SELECT+(where.isEmpty()?"":" where "+String.join(" and ",where))
   +" order by c.due_on desc,c.id desc limit ?",CHARGE,args.toArray());
  return new BillingModels.ChargePage(rows.stream().limit(CHARGE_PAGE).toList(),rows.size()>CHARGE_PAGE);
 }
 public BillingModels.Charge charge(UUID id,boolean lock) {
  var rows=jdbc.query(CHARGE_SELECT+" where c.id=?"+(lock?" for update of c":""),CHARGE,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insertCharge(UUID id,UUID subscription,UUID tenant,LocalDate start,LocalDate end,long amountCents,LocalDate due,UUID actor) {
  jdbc.update("""
   insert into platform.billing_charges(id,subscription_id,tenant_id,period_start,period_end,amount_cents,due_on,status,created_by)
   values(?,?,?,?,?,?,?,'OPEN',?)
   """,id,subscription,tenant,Date.valueOf(start),Date.valueOf(end),amountCents,Date.valueOf(due),actor);
 }
 public boolean cancelCharge(UUID id,String reason,long expectedVersion) {
  return jdbc.update("""
   update platform.billing_charges set status='CANCELLED',cancel_reason=?,version=version+1 where id=? and version=? and status='OPEN'
   """,reason,id,expectedVersion)==1;
 }
 public void insertPayment(UUID id,UUID charge,long amountCents,LocalDate paidOn,String method,String reference,UUID actor) {
  jdbc.update("""
   insert into platform.billing_payments(id,charge_id,amount_cents,paid_on,method,reference,status,recorded_by)
   values(?,?,?,?,?,?,'RECORDED',?)
   """,id,charge,amountCents,Date.valueOf(paidOn),method,reference,actor);
 }
 /** Mesmo comprovante (forma e referência, sem diferença de caixa ou espaços nas pontas) já quita outra cobrança. */
 public boolean referenceRecorded(String method,String reference) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from platform.billing_payments
    where method=? and lower(btrim(reference))=lower(btrim(?)) and status='RECORDED')
   """,Boolean.class,method,reference));
 }
 public boolean markPaid(UUID charge,long expectedVersion) {
  return jdbc.update("update platform.billing_charges set status='PAID',version=version+1 where id=? and version=? and status='OPEN'",
   charge,expectedVersion)==1;
 }
 public record PaymentRow(UUID id,UUID chargeId,long amountCents,String status) {}
 public PaymentRow payment(UUID id,boolean lock) {
  var rows=jdbc.query("select id,charge_id,amount_cents,status from platform.billing_payments where id=?"+(lock?" for update":""),
   (r,n)->new PaymentRow(r.getObject("id",UUID.class),r.getObject("charge_id",UUID.class),r.getLong("amount_cents"),r.getString("status")),id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public boolean voidPayment(UUID id,UUID actor,String reason) {
  return jdbc.update("""
   update platform.billing_payments set status='VOIDED',voided_by=?,voided_at=clock_timestamp(),void_reason=? where id=? and status='RECORDED'
   """,actor,reason,id)==1;
 }
 public boolean reopen(UUID charge,long expectedVersion) {
  return jdbc.update("update platform.billing_charges set status='OPEN',version=version+1 where id=? and version=? and status='PAID'",
   charge,expectedVersion)==1;
 }
}
