package br.com.condpulse.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fixture exclusiva do banco descartável. Nunca aceita URL fornecida ou host remoto. */
final class JdbcDisposableDatabase implements AutoCloseable {
 final UUID accountA=UUID.randomUUID(),accountB=UUID.randomUUID(),accountC=UUID.randomUUID();
 final UUID tenantA=UUID.randomUUID(),tenantB=UUID.randomUUID();
 final UUID unitA=UUID.randomUUID(),unitB=UUID.randomUUID(),otherUnitA=UUID.randomUUID();
 /** Contas criadas pelo próprio fluxo testado (ex.: aceite de convite), removidas no encerramento. */
 final java.util.Set<UUID> extraAccounts=java.util.concurrent.ConcurrentHashMap.newKeySet();
 final HikariDataSource pool;
 final JdbcTemplate jdbc;
 final JdbcTransactionManager manager;
 final TransactionTemplate tx;
 private final String url,adminUser,adminPassword;

 JdbcDisposableDatabase() throws SQLException { this(1); }
 JdbcDisposableDatabase(int maximumPoolSize) throws SQLException {
  if(maximumPoolSize<1 || maximumPoolSize>4) throw new IllegalArgumentException("Pool de teste entre1e4 conexões.");
  if(!"DISPOSABLE".equals(System.getenv("CP_JDBC_TEST_OK")))
   throw new IllegalStateException("Ensaio JDBC exige confirmação DISPOSABLE.");
  String host=required("PGHOST"),database=required("PGDATABASE"),port=required("PGPORT");
  if(!Set.of("localhost","127.0.0.1","postgres").contains(host)
      || !(database.equals("condpulse_ci") || database.matches("cp_test_[A-Za-z0-9_]+"))
      || !port.matches("[0-9]{1,5}") || Integer.parseInt(port)<1 || Integer.parseInt(port)>65535)
   throw new IllegalStateException("Host, banco ou porta não permitido para fixtures JDBC.");
  String user=required("CP_TEST_DB_USER");
  if(!"cp_ci_login".equals(user))
   throw new IllegalStateException("Ensaio exige login runtime descartável cp_ci_login.");
  url="jdbc:postgresql://"+host+":"+port+"/"+database;
  adminUser=required("PGUSER"); adminPassword=required("PGPASSWORD");
  HikariConfig config=new HikariConfig();
  config.setJdbcUrl(url); config.setUsername(user); config.setPassword(required("CP_TEST_DB_PASSWORD"));
  config.setPoolName("condpulse-jdbc-disposable"); config.setMaximumPoolSize(maximumPoolSize); config.setMinimumIdle(0);
  config.setConnectionTimeout(5000); config.setInitializationFailTimeout(5000);
  pool=new HikariDataSource(config); jdbc=new JdbcTemplate(pool);
  manager=new JdbcTransactionManager(pool); tx=new TransactionTemplate(manager); tx.setTimeout(5);
  try {
   Boolean safe=jdbc.queryForObject("""
    select current_user='cp_ci_login' and pg_has_role(current_user,'cp_api','USAGE')
     and not(rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication)
    from pg_roles where rolname=current_user
    """,Boolean.class);
   if(!Boolean.TRUE.equals(safe)) throw new IllegalStateException("Login JDBC com privilégios incompatíveis.");
   if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('platform.web_sessions') is not null",Boolean.class)))
    throw new IllegalStateException("Aplicar migrations pelo Flyway antes do ensaio JDBC.");
   createFixtures();
  } catch(RuntimeException|SQLException ex) { pool.close(); throw ex; }
 }

 private static String required(String name) {
  String value=System.getenv(name);
  if(value==null || value.isBlank()) throw new IllegalStateException("Variável de teste ausente: "+name);
  return value;
 }
 private Connection admin() throws SQLException { return DriverManager.getConnection(url,adminUser,adminPassword); }
 /** Sessão administrativa própria, para segurar travas de linha em ensaios de concorrência. */
 Connection adminSession() throws SQLException { return admin(); }
 private static void update(Connection connection,String sql,Object...args) throws SQLException {
  try(PreparedStatement statement=connection.prepareStatement(sql)) {
   for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
   statement.executeUpdate();
  }
 }
 private void createFixtures() throws SQLException {
  try(Connection connection=admin()) {
   connection.setAutoCommit(false);
   update(connection,"insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE'),(?,'ACTIVE')",accountA,accountB,accountC);
   update(connection,"insert into platform.tenants(id,name,active) values(?,'FICTICIO JDBC A',true),(?,'FICTICIO JDBC B',true)",tenantA,tenantB);
   update(connection,"insert into cp.units(id,tenant_id,label) values(?,?,'JDBC A'),(?,?,'JDBC B'),(?,?,'OUTRA UNIDADE JDBC A')",
    unitA,tenantA,unitB,tenantB,otherUnitA,tenantA);
   update(connection,"""
    insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
    values(?,?,?,'PESSOA FICTICIA JDBC A','RESIDENT','ACTIVE'),(?,?,?,'PESSOA FICTICIA JDBC B','RESIDENT','ACTIVE'),
     (?,?,?,'PESSOA FICTICIA JDBC C','RESIDENT','ACTIVE')
    """,tenantA,accountA,unitA,tenantB,accountB,unitB,tenantA,accountC,otherUnitA);
   connection.commit();
  }
 }
 void restoreActiveFixtures() throws SQLException {
  adminUpdate("update platform.accounts set status='ACTIVE' where id in (?,?,?)",accountA,accountB,accountC);
  // Somente vínculos-base das contas fixas: vínculos criados por convite ou revogados por um teste não voltam.
  adminUpdate("update cp.memberships set status='ACTIVE' where tenant_id in (?,?) and account_id in (?,?,?) and invitation_id is null",
   tenantA,tenantB,accountA,accountB,accountC);
  adminUpdate("update cp.units set status='ACTIVE' where tenant_id in (?,?)",tenantA,tenantB);
  adminUpdate("update platform.tenants set active=true where id in (?,?)",tenantA,tenantB);
 }
 void adminUpdate(String sql,Object...args) throws SQLException {
  try(Connection connection=admin()) { update(connection,sql,args); }
 }
 /** Ajuste de fixture sem gatilhos (só no banco descartável): simula prazo vencido sem esperar o relógio. */
 void adminUpdateWithoutTriggers(String sql,Object...args) throws SQLException {
  try(Connection connection=admin()) {
   update(connection,"set session_replication_role=replica");
   update(connection,sql,args);
  }
 }
 long sessionCount(String hash) throws SQLException {
  try(Connection connection=admin();PreparedStatement statement=connection.prepareStatement(
      "select count(*) from platform.web_sessions where token_hash=? and account_id in (?,?)")) {
   statement.setString(1,hash); statement.setObject(2,accountA); statement.setObject(3,accountB);
   try(var result=statement.executeQuery()) { result.next(); return result.getLong(1); }
  }
 }
 void deleteSessions() throws SQLException {
  adminUpdate("delete from platform.web_sessions where account_id in (?,?,?)",accountA,accountB,accountC);
 }
 void deleteRecoveryAttempts() throws SQLException {
  adminUpdate("delete from platform.recovery_attempts where account_id in (?,?,?)",accountA,accountB,accountC);
 }
 long recoveryCount(String hash) throws SQLException {
  return Long.parseLong(adminString("select count(*) from platform.recovery_attempts where token_hash=?",hash));
 }
 /** Papel de plataforma concedido como o procedimento operacional faria (fora da role runtime). */
 void grantPlatformAdmin(UUID account) throws SQLException {
  adminUpdate("insert into platform.platform_roles(account_id,role,status,reason) values(?,'PLATFORM_ADMIN','ACTIVE','ensaio JDBC ficticio')",
   account);
 }
 void clearPlatformFixtures() throws SQLException {
  clearBillingFixtures();
  adminUpdate("delete from platform.platform_audit_log where actor_id in (?,?,?) or tenant_id in (?,?)",
   accountA,accountB,accountC,tenantA,tenantB);
  adminUpdate("delete from platform.platform_roles where account_id in (?,?,?)",accountA,accountB,accountC);
  adminUpdate("delete from platform.platform_role_events where account_id in (?,?,?)",accountA,accountB,accountC);
 }
 /** Financeiro (V011) das contas e condomínios desta fixture; ordem respeita as chaves estrangeiras. */
 void clearBillingFixtures() throws SQLException {
  try(Connection connection=admin()) { deleteBilling(connection); }
 }
 private void deleteBilling(Connection connection) throws SQLException {
  update(connection,"delete from platform.billing_payments where charge_id in (select id from platform.billing_charges where tenant_id in (?,?))",
   tenantA,tenantB);
  update(connection,"delete from platform.billing_charges where tenant_id in (?,?)",tenantA,tenantB);
  update(connection,"delete from platform.billing_subscriptions where tenant_id in (?,?)",tenantA,tenantB);
  update(connection,"delete from platform.billing_plans where created_by in (?,?,?)",accountA,accountB,accountC);
 }
 void clearReservationFixtures() throws SQLException {
  try(Connection connection=admin()) {
   connection.setAutoCommit(false);
   for(String table:new String[]{"technical_grants","finance_webhook_events","finance_charges","finance_recurring_fees","finance_payers","concierge_occurrences","device_events","device_event_cursors","access_device_health","access_connector_health","credential_syncs","access_credentials","subject_contexts","access_subjects","access_devices","access_connectors","import_rows","import_batches","attachments","request_messages","service_requests","notifications","announcement_reads","announcements","parcel_handoffs","parcel_delegations","parcels","access_events","arrival_requests","permit_links","visit_permits","visit_rules","visitors","outbox","audit_log","idempotency_keys","move_requests","move_rules","reservation_waitlist","reservations","amenities"})
    update(connection,"delete from cp."+table+" where tenant_id in (?,?)",tenantA,tenantB);
   connection.commit();
  }
 }
 long businessCount(String table) throws SQLException {
  if(!Set.of("outbox","audit_log","idempotency_keys","reservations").contains(table))
   throw new IllegalArgumentException("Tabela de contagem não permitida.");
  try(Connection connection=admin();PreparedStatement statement=connection.prepareStatement(
    "select count(*) from cp."+table+" where tenant_id in (?,?)")) {
   statement.setObject(1,tenantA); statement.setObject(2,tenantB);
   try(var result=statement.executeQuery()) { result.next(); return result.getLong(1); }
  }
 }
 String adminString(String sql,Object...args) throws SQLException {
  try(Connection connection=admin();PreparedStatement statement=connection.prepareStatement(sql)) {
   for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
   try(var result=statement.executeQuery()) { return result.next()?result.getString(1):null; }
  }
 }
 <T> T context(UUID actor,UUID tenant,String hash,Supplier<T> action) {
  return tx.execute(status->{
   jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,actor==null?"":actor.toString());
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant==null?"":tenant.toString());
   jdbc.queryForObject("select set_config('app.session_hash',?,true)",String.class,hash==null?"":hash);
   return action.get();
  });
 }
 int backendPid() { return jdbc.queryForObject("select pg_backend_pid()",Integer.class); }
 void assertEmptyContext() {
  for(String setting:new String[]{"app.actor_id","app.tenant_id","app.session_hash","app.capabilities","app.invitation_hash",
    "app.recovery_hash","app.recovery_purge","app.platform_capabilities","app.occupancy_scan","app.finance_webhook_charge","app.technical_scopes","app.subject_context_scan"}) {
   String value=jdbc.queryForObject("select coalesce(current_setting(?,true),'')",String.class,setting);
   org.junit.jupiter.api.Assertions.assertEquals("",value,"Contexto não pode sobreviver à devolução ao pool: "+setting);
  }
 }
 @Override public void close() throws SQLException {
  try(Connection connection=admin()) {
   connection.setAutoCommit(false);
   update(connection,"delete from platform.web_sessions where account_id in (?,?,?)",accountA,accountB,accountC);
   update(connection,"delete from platform.recovery_attempts where account_id in (?,?,?)",accountA,accountB,accountC);
   deleteBilling(connection);
   update(connection,"delete from platform.platform_audit_log where actor_id in (?,?,?) or tenant_id in (?,?)",
    accountA,accountB,accountC,tenantA,tenantB);
   update(connection,"delete from platform.platform_roles where account_id in (?,?,?)",accountA,accountB,accountC);
   update(connection,"delete from platform.platform_role_events where account_id in (?,?,?)",accountA,accountB,accountC);
   for(String table:new String[]{"technical_grants","finance_webhook_events","finance_charges","finance_recurring_fees","finance_payers","concierge_occurrences","device_events","device_event_cursors","access_device_health","access_connector_health","credential_syncs","access_credentials","subject_contexts","access_subjects","access_devices","access_connectors","import_rows","import_batches","attachments","request_messages","service_requests","notifications","announcement_reads","announcements","parcel_handoffs","parcel_delegations","parcels","access_events","arrival_requests","permit_links","visit_permits","visit_rules","visitors","outbox","audit_log","idempotency_keys","move_requests","move_rules","reservation_waitlist","reservations","amenities"})
    update(connection,"delete from cp."+table+" where tenant_id in (?,?)",tenantA,tenantB);
   update(connection,"delete from cp.unit_rentals where tenant_id in (?,?)",tenantA,tenantB);
   // Convites e vínculos se referenciam (membership_id/invitation_id): apagar os dois na mesma instrução.
   update(connection,"with invitations as (delete from cp.membership_invitations where tenant_id in (?,?)) "
    +"delete from cp.memberships where tenant_id in (?,?)",tenantA,tenantB,tenantA,tenantB);
   update(connection,"delete from cp.units where tenant_id in (?,?)",tenantA,tenantB);
   update(connection,"delete from cp.blocks where tenant_id in (?,?)",tenantA,tenantB);
   update(connection,"delete from platform.tenants where id in (?,?)",tenantA,tenantB);
   update(connection,"delete from platform.accounts where id in (?,?,?)",accountA,accountB,accountC);
   for(UUID extra:extraAccounts) update(connection,"delete from platform.accounts where id=?",extra);
   connection.commit();
  } finally { pool.close(); }
 }
}
