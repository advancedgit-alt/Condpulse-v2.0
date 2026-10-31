package br.com.condpulse.finance.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import br.com.condpulse.finance.application.AsaasGateway;
import br.com.condpulse.finance.application.FinanceRules;
import br.com.condpulse.finance.application.FinanceWebhookService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transação dedicada do webhook (V036): sem ator e sem capacidade; o contexto app.finance_webhook_charge só deixa ler e
 * sincronizar a cobrança identificada. O condomínio vem da própria cobrança (identificador + pagamento do Asaas). Evento,
 * situação e outbox na mesma transação; evento repetido não muda nada.
 */
public class FinanceWebhookStore {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public FinanceWebhookStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  this.jdbc=jdbc;
  tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setTimeout(5);
 }

 private record Current(UUID tenant,UUID unit,String state,LocalDate paidAt,Long paidCents,String asaasStatus,long version) {}
 /** Sem ator o catálogo de condomínios não é legível; o fuso só serve de data de pagamento quando o Asaas não a informa. */
 private static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");

 public FinanceWebhookService.Outcome apply(String eventId,String eventType,UUID chargeId,String paymentId,AsaasGateway.Payment payment) {
  return tx.execute(status->{
   for(String setting:new String[]{"app.session_hash","app.capabilities","app.invitation_hash","app.tenant_id","app.platform_capabilities",
     "app.permit_link_hash","app.occupancy_scan","app.actor_id"})
    jdbc.queryForObject("select set_config(?,'',true)",String.class,setting);
   jdbc.queryForObject("select set_config('app.finance_webhook_charge',?,true)",String.class,chargeId.toString());
   var tenants=jdbc.query("select tenant_id from cp.finance_charges where id=? and asaas_payment_id=?",
    (r,n)->r.getObject(1,UUID.class),chargeId,paymentId);
   if(tenants.size()!=1) return FinanceWebhookService.Outcome.IGNORED;
   UUID tenant=tenants.getFirst();
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
   var current=jdbc.query("""
    select c.tenant_id,c.unit_id,c.state,c.paid_at,c.paid_cents,c.asaas_status,c.version
    from cp.finance_charges c where c.tenant_id=? and c.id=? for update
    """,(r,n)->new Current(r.getObject("tenant_id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("state"),
     r.getDate("paid_at")==null?null:r.getDate("paid_at").toLocalDate(),r.getObject("paid_cents")==null?null:r.getLong("paid_cents"),
     r.getString("asaas_status"),r.getLong("version")),tenant,chargeId);
   if(current.size()!=1) return FinanceWebhookService.Outcome.IGNORED;
   var row=current.getFirst();
   LocalDate today=LocalDate.now(ZONE);
   var target=FinanceRules.target(row.state(),row.paidAt(),row.paidCents(),payment,today);
   String resulting=FinanceRules.terminal(row.state())?row.state():target.state();
   var inserted=jdbc.query("""
    insert into cp.finance_webhook_events(tenant_id,event_id,event_type,charge_id,asaas_status,resulting_state)
    values(?,?,?,?,?,?) on conflict (event_id) do nothing returning id
    """,(r,n)->r.getObject(1,UUID.class),tenant,eventId,eventType,chargeId,target.asaasStatus(),resulting);
   if(inserted.isEmpty()) return FinanceWebhookService.Outcome.DUPLICATE;
   // Cancelada ou estornada é final: o evento fica registrado (idempotente), a cobrança não muda (o gatilho recusaria).
   if(FinanceRules.terminal(row.state())) return FinanceWebhookService.Outcome.PROCESSED;
   boolean unchanged=row.state().equals(target.state()) && java.util.Objects.equals(row.asaasStatus(),target.asaasStatus())
    && java.util.Objects.equals(row.paidAt(),target.paidAt()) && java.util.Objects.equals(row.paidCents(),target.paidCents());
   if(unchanged) return FinanceWebhookService.Outcome.PROCESSED;
   int updated=jdbc.update("""
    update cp.finance_charges set state=?,asaas_status=?,paid_at=?,paid_cents=?,version=version+1 where tenant_id=? and id=? and version=?
    """,target.state(),target.asaasStatus(),target.paidAt()==null?null:Date.valueOf(target.paidAt()),target.paidCents(),tenant,chargeId,row.version());
   if(updated!=1) throw new IllegalStateException("Cobrança alterada durante o webhook.");
   if(!row.state().equals(target.state()))
    jdbc.update("""
     insert into cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
     values(?,'FINANCE_CHARGE_UPDATED',?,?,jsonb_build_object('chargeId',?::uuid,'unitId',?::uuid,'state',?::text,'version',?::bigint))
     """,tenant,chargeId,row.version()+1,chargeId,row.unit(),target.state(),row.version()+1);
   return FinanceWebhookService.Outcome.PROCESSED;
  });
 }
}
