package br.com.condpulse.reservations.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Passada da fila de espera pelo worker (cp_worker, sem conta). O worker só enxerga condomínio ativo, id/atividade das
 * áreas e, dentro de cp.waitlist_offer, a fila da área sob a marca da oferta (V023).
 */
@Repository @Profile("worker")
public class WaitlistSweepStore {
 public static final int AMENITY_LIMIT=200;
 private final JdbcTemplate jdbc;
 public WaitlistSweepStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 public List<UUID> activeTenants() {
  return jdbc.query("select id from platform.tenants where active order by id",(r,n)->r.getObject("id",UUID.class));
 }
 /** Todas as áreas, ativas ou não: área inativa ainda precisa vencer retenção e pedido parados. */
 public List<UUID> amenities(UUID tenant) {
  return jdbc.query("select id from cp.amenities where tenant_id=? order by id limit ?",(r,n)->r.getObject("id",UUID.class),tenant,AMENITY_LIMIT);
 }
 public int offer(UUID tenant,UUID amenity) {
  Integer created=jdbc.queryForObject("select cp.waitlist_offer(?,?)",Integer.class,tenant,amenity);
  return created==null?0:created;
 }
}
