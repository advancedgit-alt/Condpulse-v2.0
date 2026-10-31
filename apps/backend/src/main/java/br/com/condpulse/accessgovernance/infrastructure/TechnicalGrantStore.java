package br.com.condpulse.accessgovernance.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalGrantModels;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL das concessões técnicas (V037), sempre dentro de uma fronteira transacional; o banco repete papel e transições. */
public class TechnicalGrantStore {
 private static final String COLUMNS="""
  id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,status,
  (status='ACTIVE' and valid_from<=now() and valid_until>now()) as current,revoked_at,version
  """;
 private final JdbcTemplate jdbc;
 public TechnicalGrantStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

 public boolean tenantActive(UUID tenantId) {
  Integer count=jdbc.queryForObject("select count(*) from platform.tenants where id=? and active",Integer.class,tenantId);
  return count!=null && count==1;
 }
 public TechnicalGrantModels.TechnicalGrant find(UUID grantId) {
  var rows=jdbc.query("select "+COLUMNS+" from cp.technical_grants where id=?",TechnicalGrantStore::grant,grantId);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public List<TechnicalGrantModels.TechnicalGrant> byTenant(UUID tenantId) {
  return jdbc.query("select "+COLUMNS+" from cp.technical_grants where tenant_id=? order by created_at desc,id limit 200",
   TechnicalGrantStore::grant,tenantId);
 }
 /** Um único instante (início da transação) para os dois limites e para a leitura de "current". */
 public void insert(UUID grantId,UUID tenantId,UUID accountId,String roleCode,List<String> scopes,int validDays,UUID grantedBy,String reason) {
  jdbc.update(connection->{
   var statement=connection.prepareStatement("""
    insert into cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,granted_by_account_id,reason)
    values(?,?,?,?,?,now(),now()+make_interval(days=>?),'PLATFORM_API',?,?)
    """);
   Array array=connection.createArrayOf("text",scopes.toArray());
   statement.setObject(1,grantId); statement.setObject(2,tenantId); statement.setObject(3,accountId); statement.setString(4,roleCode);
   statement.setArray(5,array); statement.setInt(6,validDays); statement.setObject(7,grantedBy); statement.setString(8,reason);
   return statement;
  });
 }
 /** Revogação pela versão lida; devolve falso se a concessão não estiver mais ativa nessa versão. */
 public boolean revoke(UUID tenantId,UUID grantId,long expectedVersion,UUID actor,String reason) {
  return jdbc.update("""
   update cp.technical_grants set status='REVOKED',revoked_by_account_id=?,revoke_reason=?,version=version+1
   where tenant_id=? and id=? and status='ACTIVE' and version=?
   """,actor,reason,tenantId,grantId,expectedVersion)==1;
 }
 private static TechnicalGrantModels.TechnicalGrant grant(ResultSet r,int n) throws SQLException {
  Timestamp revoked=r.getTimestamp("revoked_at");
  return new TechnicalGrantModels.TechnicalGrant(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),
   r.getObject("account_id",UUID.class),r.getString("role_code"),Arrays.asList((String[])r.getArray("scopes").getArray()),
   r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until").toInstant(),r.getString("granted_via"),r.getString("status"),
   r.getBoolean("current"),revoked==null?null:revoked.toInstant(),r.getLong("version"));
 }
}
