package br.com.condpulse.platform.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** platform.tenant_modules (V045): leitura com tenants.read/tenant_modules.manage e gravação com tenant_modules.manage. */
@Repository @Profile("api")
public class TenantModuleStore {
 private final JdbcTemplate jdbc;
 public TenantModuleStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public record Row(String module,boolean enabled,long version,String reason,UUID updatedBy,Instant updatedAt) {}

 private static Row row(ResultSet r,int n) throws SQLException {
  return new Row(r.getString("module"),r.getBoolean("enabled"),r.getLong("version"),r.getString("reason"),
   r.getObject("updated_by",UUID.class),r.getTimestamp("updated_at").toInstant());
 }
 public List<Row> byTenant(UUID tenant) {
  return jdbc.query("select module,enabled,version,reason,updated_by,updated_at from platform.tenant_modules where tenant_id=? order by module",
   TenantModuleStore::row,tenant);
 }
 /** Leitura com trava da linha existente: decisão de repetição e gravação veem o mesmo estado. */
 public Row lock(UUID tenant,String module) {
  var rows=jdbc.query("select module,enabled,version,reason,updated_by,updated_at from platform.tenant_modules "
   +"where tenant_id=? and module=? for update",TenantModuleStore::row,tenant,module);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public void insert(UUID tenant,String module,boolean enabled,String reason,UUID actor) {
  jdbc.update("insert into platform.tenant_modules(tenant_id,module,enabled,version,reason,updated_by) values(?,?,?,1,?,?)",
   tenant,module,enabled,reason,actor);
 }
 public boolean update(UUID tenant,String module,boolean enabled,long expectedVersion,String reason,UUID actor) {
  return jdbc.update("update platform.tenant_modules set enabled=?,version=version+1,reason=?,updated_by=? "
   +"where tenant_id=? and module=? and version=?",enabled,reason,actor,tenant,module,expectedVersion)==1;
 }
 public Row find(UUID tenant,String module) {
  var rows=jdbc.query("select module,enabled,version,reason,updated_by,updated_at from platform.tenant_modules where tenant_id=? and module=?",
   TenantModuleStore::row,tenant,module);
  return rows.isEmpty()?null:rows.getFirst();
 }
}
