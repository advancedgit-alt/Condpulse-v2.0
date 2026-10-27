package br.com.condpulse.tenancy.application;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("api")
public class TenantUnitsQuery {
 private final TenantTransactionExecutor transactions;
 private final JdbcTemplate jdbc;
 public TenantUnitsQuery(TenantTransactionExecutor transactions,JdbcTemplate jdbc) {
  this.transactions=transactions; this.jdbc=jdbc;
 }
 public List<Unit> execute(UUID accountId,UUID tenantId) {
  return transactions.execute(accountId,tenantId,context->List.copyOf(jdbc.query("""
   select u.id,u.label,u.block_id,b.name as block_name
   from cp.units u
   left join cp.blocks b on b.tenant_id=u.tenant_id and b.id=u.block_id
   where u.tenant_id=? and u.status='ACTIVE' and exists(
    select 1 from cp.memberships m where m.tenant_id=u.tenant_id and m.unit_id=u.id
      and m.account_id=? and m.status='ACTIVE' and m.valid_from<=now()
      and(m.valid_until is null or m.valid_until>now()))
   order by b.name nulls first,u.label,u.id
   """,(r,n)->new Unit(r.getObject("id",UUID.class),r.getString("label"),
     r.getObject("block_id",UUID.class),r.getString("block_name")),tenantId,accountId)));
 }
 public record Unit(UUID id,String label,UUID blockId,String blockName) {}
}
