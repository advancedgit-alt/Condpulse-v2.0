package br.com.condpulse.tenancy.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.UnitManagementModels;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Somente dentro da transação do TenantTransactionExecutor, com capacidade já publicada no contexto. */
@Repository @Profile("api")
public class UnitManagementStore {
 private static final int PAGE_LIMIT=1000;
 private final JdbcTemplate jdbc;
 public UnitManagementStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public List<UnitManagementModels.Block> blocks(UUID tenant) {
  return jdbc.query("select id,name from cp.blocks where tenant_id=? order by name,id limit 1000",
   (r,n)->new UnitManagementModels.Block(r.getObject("id",UUID.class),r.getString("name")),tenant);
 }
 public UnitManagementModels.Block insertBlock(UUID tenant,String name) {
  UUID id=UUID.randomUUID();
  jdbc.update("insert into cp.blocks(id,tenant_id,name) values(?,?,?)",id,tenant,name);
  return new UnitManagementModels.Block(id,name);
 }
 public boolean blockExists(UUID tenant,UUID block) {
  Integer count=jdbc.queryForObject("select count(*) from cp.blocks where tenant_id=? and id=?",Integer.class,tenant,block);
  return count!=null && count==1;
 }
 public UnitManagementModels.UnitPage units(UUID tenant) {
  var rows=jdbc.query("""
   select u.id,u.label,u.block_id,b.name as block_name,u.status,u.version
   from cp.units u left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
   where u.tenant_id=? order by b.name nulls first,u.label,u.id limit ?
   """,(r,n)->unit(r),tenant,PAGE_LIMIT+1);
  return new UnitManagementModels.UnitPage(rows.stream().limit(PAGE_LIMIT).toList(),rows.size()>PAGE_LIMIT);
 }
 public UnitManagementModels.Unit insertUnit(UUID tenant,String label,UUID block) {
  UUID id=UUID.randomUUID();
  jdbc.update("insert into cp.units(id,tenant_id,block_id,label,status) values(?,?,?,?,'ACTIVE')",id,tenant,block,label);
  return find(tenant,id);
 }
 public UnitManagementModels.Unit find(UUID tenant,UUID unit) {
  var rows=jdbc.query("""
   select u.id,u.label,u.block_id,b.name as block_name,u.status,u.version
   from cp.units u left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
   where u.tenant_id=? and u.id=?
   """,(r,n)->unit(r),tenant,unit);
  return rows.size()==1?rows.getFirst():null;
 }
 /** Atualização condicional pela versão lida; zero linhas significa inexistente ou alterada. */
 public boolean updateUnit(UUID tenant,UUID unit,String label,UUID block,String status,long expectedVersion) {
  return jdbc.update("""
   update cp.units set label=?,block_id=?,status=?,updated_at=clock_timestamp(),version=version+1
   where tenant_id=? and id=? and version=?
   """,label,block,status,tenant,unit,expectedVersion)==1;
 }
 private static UnitManagementModels.Unit unit(ResultSet r) throws SQLException {
  return new UnitManagementModels.Unit(r.getObject("id",UUID.class),r.getString("label"),
   r.getObject("block_id",UUID.class),r.getString("block_name"),r.getString("status"),r.getLong("version"));
 }
}
