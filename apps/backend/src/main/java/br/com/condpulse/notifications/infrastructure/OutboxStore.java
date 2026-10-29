package br.com.condpulse.notifications.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.notifications.application.OutboxModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Fila do outbox para o worker (cp_worker). Arrendamento com SKIP LOCKED: duas instâncias não pegam a mesma tarefa, e
 * tarefa travada por outra transação fica para a próxima passada. Toda conclusão é cercada por estado LEASED e pela
 * tentativa arrendada: quem perdeu o arrendamento (outra instância arrendou de novo) não confirma nada. O gatilho do
 * banco confere cada transição e grava os horários de conclusão.
 */
@Repository @Profile("worker")
public class OutboxStore {
 static final String EXHAUSTED="Tentativas esgotadas: o arrendamento venceu sem confirmação do processamento.";
 private final JdbcTemplate jdbc;
 public OutboxStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Condomínios ativos (política worker_tenant_catalog), sem nenhum outro dado deles. */
 public List<UUID> activeTenants() {
  return jdbc.query("select id from platform.tenants where active order by id",(r,n)->r.getObject("id",UUID.class));
 }
 /** Arrendamento vencido na última tentativa permitida vira falha visível em vez de nova tentativa. */
 public int failExhausted(UUID tenant,int maxAttempts) {
  return jdbc.update("""
   with picked as(
    select id from cp.outbox where tenant_id=? and state='LEASED' and lease_until<=clock_timestamp() and attempts>=?
    order by lease_until,id limit 100 for update skip locked)
   update cp.outbox o set state='FAILED',last_error=? from picked where o.tenant_id=? and o.id=picked.id
   """,tenant,maxAttempts,EXHAUSTED,tenant);
 }
 /** Pendentes já disponíveis e arrendamentos vencidos com tentativas restantes, na ordem de disponibilidade. */
 public List<OutboxModels.LeasedEvent> lease(UUID tenant,int batch,int leaseSeconds,int maxAttempts) {
  if(batch<1 || batch>100 || leaseSeconds<5 || leaseSeconds>600) throw new IllegalArgumentException("Lote ou arrendamento fora do limite.");
  return jdbc.query("""
   with picked as(
    select id from cp.outbox where tenant_id=? and attempts<?
     and((state='PENDING' and available_at<=clock_timestamp()) or(state='LEASED' and lease_until<=clock_timestamp()))
    order by available_at,created_at,id limit ? for update skip locked)
   update cp.outbox o set state='LEASED',attempts=o.attempts+1,lease_until=clock_timestamp()+make_interval(secs=>?)
   from picked where o.tenant_id=? and o.id=picked.id
   returning o.id,o.event_type,o.aggregate_id,o.aggregate_version,o.payload->>'unitId' as unit_id,o.attempts
   """,(r,n)->new OutboxModels.LeasedEvent(r.getObject("id",UUID.class),r.getString("event_type"),
    r.getObject("aggregate_id",UUID.class),r.getLong("aggregate_version"),uuid(r.getString("unit_id")),r.getInt("attempts")),
   tenant,maxAttempts,batch,(double)leaseSeconds,tenant);
 }
 public boolean complete(UUID tenant,OutboxModels.LeasedEvent event,OutboxModels.Outcome outcome) {
  return jdbc.update("update cp.outbox set state=? where tenant_id=? and id=? and state='LEASED' and attempts=?",
   outcome.name(),tenant,event.id(),event.attempts())==1;
 }
 public boolean retry(UUID tenant,OutboxModels.LeasedEvent event,Duration delay,String error) {
  return jdbc.update("""
   update cp.outbox set state='PENDING',available_at=clock_timestamp()+make_interval(secs=>?),last_error=?
   where tenant_id=? and id=? and state='LEASED' and attempts=?
   """,delay.toMillis()/1000.0,error,tenant,event.id(),event.attempts())==1;
 }
 public boolean fail(UUID tenant,OutboxModels.LeasedEvent event,String error) {
  return jdbc.update("update cp.outbox set state='FAILED',last_error=? where tenant_id=? and id=? and state='LEASED' and attempts=?",
   error,tenant,event.id(),event.attempts())==1;
 }
 private static UUID uuid(String value) {
  if(value==null) return null;
  // Só a forma canônica: a política do banco compara o texto do payload com a unidade gravada no aviso.
  try { UUID parsed=UUID.fromString(value); return parsed.toString().equals(value)?parsed:null; }
  catch(IllegalArgumentException invalid) { return null; }
 }
}
