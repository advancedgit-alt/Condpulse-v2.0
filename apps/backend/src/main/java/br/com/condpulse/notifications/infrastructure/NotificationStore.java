package br.com.condpulse.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import br.com.condpulse.notifications.application.NotificationModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lado da API da caixa interna (cp_api): o banco só mostra ao destinatário os próprios avisos, e só enquanto ele mantém
 * o vínculo que justificou o aviso. A situação do registro passa pelas mesmas políticas de leitura de chegadas e
 * encomendas: o que o leitor não pode ver volta null.
 */
@Repository @Profile("api")
public class NotificationStore {
 static final int LIMIT=50;
 private static final String COLUMNS="""
  select n.id,n.unit_id,n.kind,n.resource_id,n.state,n.created_at,n.read_at,
   case n.kind
    when 'ARRIVAL_WAITING' then(select case when a.state='WAITING' and a.expires_at<=now() then 'EXPIRED' else a.state end
     from cp.arrival_requests a where a.tenant_id=n.tenant_id and a.id=n.resource_id)
    when 'PARCEL_RECEIVED' then(select p.status from cp.parcels p where p.tenant_id=n.tenant_id and p.id=n.resource_id)
    when 'WAITLIST_OFFERED' then(select case when w.state='OFFERED' and w.offer_expires_at<=clock_timestamp() then 'EXPIRED' else w.state end
     from cp.reservation_waitlist w where w.tenant_id=n.tenant_id and w.id=n.resource_id)
   end as resource_state
  """;
 private static final String SELECT=COLUMNS+" from cp.notifications n\n";
 private final JdbcTemplate jdbc;
 public NotificationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Vínculo residencial vigente na unidade ativa (qualquer papel residencial; o tipo do aviso filtra o resto). */
 public boolean resident(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.memberships m join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id and u.status='ACTIVE'
    where m.tenant_id=? and m.unit_id=? and m.account_id=platform.current_actor_id() and m.status='ACTIVE'
     and m.role in('RESIDENT','RESPONSIBLE','DEPENDENT') and m.valid_from<=now() and(m.valid_until is null or m.valid_until>now()))
   """,Boolean.class,tenant,unit));
 }
 /**
  * Página e contagem de não abertos na mesma instrução (mesmo instantâneo): abertura em outra aba entre duas consultas
  * não deixa a contagem menor que os itens disponíveis da página. Sem itens não há aviso algum, então nada a abrir.
  */
 public NotificationModels.Page page(UUID tenant,UUID unit) {
  int[] unread={0};
  var items=jdbc.query(COLUMNS+"""
   ,(select count(*) from cp.notifications c where c.tenant_id=? and c.unit_id=? and c.account_id=platform.current_actor_id()
     and c.state='AVAILABLE') as unread
   from cp.notifications n
   where n.tenant_id=? and n.unit_id=? and n.account_id=platform.current_actor_id()
   order by n.created_at desc,n.id desc limit ?
   """,(r,n)->{ unread[0]=r.getInt("unread"); return notification(r); },tenant,unit,tenant,unit,LIMIT);
  return new NotificationModels.Page(items,unread[0]);
 }
 /** Abertura pelo destinatário: AVAILABLE vira READ_IN_APP uma vez, com horário do banco; repetir devolve a leitura gravada. */
 public NotificationModels.Notification open(UUID tenant,UUID notification) {
  jdbc.update("update cp.notifications set state='READ_IN_APP' where tenant_id=? and id=? and state='AVAILABLE'",tenant,notification);
  var rows=jdbc.query(SELECT+" where n.tenant_id=? and n.id=?",(r,n)->notification(r),tenant,notification);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private static NotificationModels.Notification notification(ResultSet r) throws SQLException {
  return new NotificationModels.Notification(r.getObject("id",UUID.class),r.getObject("unit_id",UUID.class),r.getString("kind"),
   r.getObject("resource_id",UUID.class),r.getString("resource_state"),r.getString("state"),instant(r.getTimestamp("created_at")),
   instant(r.getTimestamp("read_at")));
 }
 private static java.time.Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
