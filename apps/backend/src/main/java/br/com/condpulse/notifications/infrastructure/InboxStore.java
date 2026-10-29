package br.com.condpulse.notifications.infrastructure;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lado do worker da caixa interna: situação mínima do registro que gerou o evento (unidade e se ainda está aberto) e
 * gravação dos avisos para os destinatários aptos, uma vez por tarefa, destinatário e canal.
 */
@Repository @Profile("worker")
public class InboxStore {
 /** Registro do evento: unidade gravada e se ainda faz sentido avisar (chegada aguardando, encomenda a retirar). */
 public record Resource(UUID unitId,boolean open) {}
 public record Delivery(int eligible,int inserted) {}
 private final JdbcTemplate jdbc;
 public InboxStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public Resource arrival(UUID tenant,UUID arrival) {
  var rows=jdbc.query("""
   select unit_id,state='WAITING' and expires_at>clock_timestamp() as open from cp.arrival_requests where tenant_id=? and id=?
   """,(r,n)->new Resource(r.getObject("unit_id",UUID.class),r.getBoolean("open")),tenant,arrival);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public Resource parcel(UUID tenant,UUID parcel) {
  var rows=jdbc.query("select unit_id,status='RECEIVED' as open from cp.parcels where tenant_id=? and id=?",
   (r,n)->new Resource(r.getObject("unit_id",UUID.class),r.getBoolean("open")),tenant,parcel);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /**
  * Um aviso por destinatário apto (vínculo residencial vigente, conta e unidade ativas; dependente só com a permissão
  * do tipo). Reprocessar a mesma tarefa não duplica: a chave tarefa/destinatário/canal absorve a repetição.
  */
 public Delivery deliver(UUID tenant,UUID unit,UUID outbox,String kind,UUID resource) {
  return jdbc.queryForObject("""
   with eligible as(
    select distinct m.account_id from cp.memberships m
    where m.tenant_id=? and m.unit_id=? and cp.notification_recipient(m.tenant_id,m.unit_id,m.account_id,?)),
   inserted as(
    insert into cp.notifications(tenant_id,account_id,unit_id,outbox_id,kind,resource_id)
    select ?,account_id,?,?,?,? from eligible
    on conflict(tenant_id,outbox_id,account_id,channel) do nothing returning 1)
   select(select count(*) from eligible) as eligible,(select count(*) from inserted) as inserted
   """,(r,n)->new Delivery(r.getInt("eligible"),r.getInt("inserted")),tenant,unit,kind,tenant,unit,outbox,kind,resource);
 }
}
