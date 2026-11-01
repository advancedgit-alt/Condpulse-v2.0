package br.com.condpulse.platform.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.platform.application.DemoTenantModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Leitura das gerações (política demo_generations_platform_read) e chamada da função platform.demo_provision (dono
 * cp_demo, V053). A API nunca grava diretamente nas tabelas do demo.
 */
@Repository @Profile("api")
public class DemoTenantStore {
 private static final String SELECT="""
  select g.id,g.generation,g.tenant_id,t.name as tenant_name,g.partner_id,p.name as partner_name,g.status,g.created_at,g.created_by,
   g.retired_at,g.counts::text as counts
  from platform.demo_generations g
  left join platform.tenants t on t.id=g.tenant_id
  left join platform.partners p on p.id=g.partner_id
  """;
 private final JdbcTemplate jdbc;
 public DemoTenantStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 private static final RowMapper<DemoTenantModels.Generation> ROW=(r,n)->new DemoTenantModels.Generation(
  r.getObject("id",UUID.class),r.getInt("generation"),r.getObject("tenant_id",UUID.class),r.getString("tenant_name"),
  r.getObject("partner_id",UUID.class),r.getString("partner_name"),r.getString("status"),r.getTimestamp("created_at").toInstant(),
  r.getObject("created_by",UUID.class),r.getTimestamp("retired_at")==null?null:r.getTimestamp("retired_at").toInstant(),counts(r));

 public DemoTenantModels.Generation current() {
  var rows=jdbc.query(SELECT+" where g.status='ACTIVE'",ROW);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public List<DemoTenantModels.Generation> retired(int limit) {
  return jdbc.query(SELECT+" where g.status='RETIRED' order by g.generation desc limit ?",ROW,limit);
 }
 public DemoTenantModels.Generation generation(UUID id) {
  var rows=jdbc.query(SELECT+" where g.id=?",ROW,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /** Resultado da função: id da geração, demo aposentado e se a chamada aplicou algo novo. */
 public record Provisioned(UUID generationId,UUID retiredTenantId,boolean applied) {}
 public Provisioned provision(UUID expectedTenant,String reason,String key) {
  return jdbc.queryForObject("select generation_id,retired_tenant_id,applied from platform.demo_provision(?,?,?)",
   (r,n)->new Provisioned(r.getObject("generation_id",UUID.class),r.getObject("retired_tenant_id",UUID.class),r.getBoolean("applied")),
   expectedTenant,reason,key);
 }

 /** Contagens só numéricas, em ordem estável. */
 private static Map<String,Long> counts(ResultSet r) throws SQLException {
  var text=r.getString("counts");
  var result=new LinkedHashMap<String,Long>();
  if(text==null) return result;
  var matcher=java.util.regex.Pattern.compile("\"([A-Za-z]{1,40})\"\\s*:\\s*(\\d{1,9})").matcher(text);
  while(matcher.find()) result.put(matcher.group(1),Long.parseLong(matcher.group(2)));
  return new java.util.TreeMap<>(result);
 }
}
