package br.com.condpulse.realtime.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Impressões digitais por assunto (CP-036), lidas como cp_api dentro do contexto do condomínio: as políticas de leitura
 * do banco decidem o que entra na conta, então mudança que o leitor não pode ver não altera nada. Só contagens, versões
 * e horários; nenhum identificador ou conteúdo sai daqui.
 */
@Repository @Profile("api")
public class RealtimeStore {
 public static final java.util.List<String> TOPICS=java.util.List.of("reservations","parcels","arrivals","notifications","announcements","requests");
 private final JdbcTemplate jdbc;
 public RealtimeStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 /** Vínculo residencial vigente na unidade ativa: a mesma regra da caixa da unidade. */
 public boolean resident(UUID tenant,UUID unit) {
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   select exists(select 1 from cp.memberships m join cp.units u on u.tenant_id=m.tenant_id and u.id=m.unit_id and u.status='ACTIVE'
    where m.tenant_id=? and m.unit_id=? and m.account_id=platform.current_actor_id() and m.status='ACTIVE'
     and m.role in('RESIDENT','RESPONSIBLE','DEPENDENT') and m.valid_from<=now() and(m.valid_until is null or m.valid_until>now()))
   """,Boolean.class,tenant,unit));
 }

 /** Uma instrução, um instantâneo: cada assunto vira "quantidade:soma das versões:última alteração". */
 public Map<String,String> fingerprints(UUID tenant,UUID unit) {
  return jdbc.queryForObject("""
   select
    (select count(*)||':'||coalesce(sum(version),0)||':'||coalesce(max(updated_at)::text,'')
      from cp.reservations where tenant_id=? and unit_id=?) as reservations,
    (select count(*)||':'||coalesce(sum(version),0)||':'||coalesce(max(updated_at)::text,'')
      from cp.parcels where tenant_id=? and unit_id=?) as parcels,
    (select count(*)||':'||coalesce(sum(version),0)||':'||coalesce(max(updated_at)::text,'')
      from cp.arrival_requests where tenant_id=? and unit_id=?) as arrivals,
    (select count(*)||':'||count(*) filter(where state='AVAILABLE')||':'||coalesce(max(created_at)::text,'')
      from cp.notifications where tenant_id=? and unit_id=? and account_id=platform.current_actor_id()) as notifications,
    (select count(*)||':'||coalesce(sum(a.version),0)||':'||coalesce(max(a.updated_at)::text,'')
      from cp.announcements a join cp.units u on u.tenant_id=a.tenant_id and u.id=?
      where a.tenant_id=? and a.state='PUBLISHED'
       and(a.audience='TENANT' or(a.audience='UNITS' and u.id=any(a.audience_ids)) or(a.audience='BLOCKS' and u.block_id=any(a.audience_ids)))) as announcements,
    (select count(*)||':'||coalesce(sum(version),0)||':'||coalesce(max(updated_at)::text,'')
      from cp.service_requests where tenant_id=? and unit_id=?) as requests
   """,(r,n)->{
    var result=new LinkedHashMap<String,String>();
    for(String topic:TOPICS) result.put(topic,r.getString(topic));
    return result;
   },tenant,unit,tenant,unit,tenant,unit,tenant,unit,unit,tenant,tenant,unit);
 }
}
