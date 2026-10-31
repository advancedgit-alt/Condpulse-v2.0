package br.com.condpulse.database;

import java.sql.DriverManager;
import java.util.Set;
import br.com.condpulse.shared.infrastructure.DatabaseRoleGuard;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guarda de inicialização com PostgreSQL real: logins da API (cp_ci_login) e do worker (cp_ci_worker) passam; um grant
 * por coluna que daria à aplicação o poder de alterar registro de acesso, escolher estado da tarefa ou mexer no aviso
 * impede a inicialização. Cada grant de teste é desfeito no próprio teste (banco descartável). Um segundo banco
 * descartável, migrado pelo Flyway só até a V005 (estado do banco remoto), prova que a guarda não derruba API nem worker
 * antes das migrations novas e continua passando depois de todas aplicadas.
 */
@EnabledIfEnvironmentVariable(named="CP_TEST_WORKER_DB_USER",matches="cp_ci_worker")
class DatabaseRoleGuardJdbcTest {
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;

 @BeforeAll static void setup() throws Exception { db=new JdbcDisposableDatabase(1); workerDb=new JdbcWorkerPool(); }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }

 private static void assertRefusedWith(String grant,String revoke,boolean worker) throws Exception {
  db.adminUpdate(grant);
  try {
   var guard=new DatabaseRoleGuard(worker?workerDb.jdbc:db.jdbc);
   var refused=assertThrows(IllegalStateException.class,()->guard.verify(),grant);
   assertEquals("Role de execução com privilégios incompatíveis.",refused.getMessage());
  } finally { db.adminUpdate(revoke); }
 }

 private static final String PRE_V015_DATABASE="cp_test_guard_v005";
 private static String env(String name) {
  String value=System.getenv(name);
  if(value==null || value.isBlank()) throw new IllegalStateException("Variável de teste ausente: "+name);
  return value;
 }
 private static void migrate(String url,MigrationVersion target) {
  Flyway.configure().dataSource(url,env("PGUSER"),env("PGPASSWORD")).defaultSchema("platform").schemas("platform","cp")
   .table("flyway_schema_history").locations("classpath:db/migration").validateMigrationNaming(true).cleanDisabled(true)
   .baselineOnMigrate(false).target(target).load().migrate();
 }
 private static void assertRuntimeRolesStart(String url,String label) {
  for(String[] login:new String[][]{{env("CP_TEST_DB_USER"),env("CP_TEST_DB_PASSWORD")},
   {env("CP_TEST_WORKER_DB_USER"),env("CP_TEST_WORKER_DB_PASSWORD")}}) {
   var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,login[0],login[1]));
   assertDoesNotThrow(()->new DatabaseRoleGuard(jdbc).verify(),label+": "+login[0]);
  }
 }

 @Test void runtimeRolesStartOnADatabaseStillAtV005AndAfterEveryMigration() throws Exception {
  String host=env("PGHOST"),port=env("PGPORT");
  if(!Set.of("localhost","127.0.0.1","postgres").contains(host) || !port.matches("[0-9]{1,5}"))
   throw new IllegalStateException("Host ou porta não permitido para o banco descartável da guarda.");
  String server="jdbc:postgresql://"+host+":"+port+"/",url=server+PRE_V015_DATABASE;
  try(var admin=DriverManager.getConnection(server+env("PGDATABASE"),env("PGUSER"),env("PGPASSWORD"));var statement=admin.createStatement()) {
   statement.execute("drop database if exists "+PRE_V015_DATABASE+" with (force)");
   statement.execute("create database "+PRE_V015_DATABASE);
  }
  try {
   migrate(url,MigrationVersion.fromVersion("5"));
   assertEquals("5",new JdbcTemplate(new DriverManagerDataSource(url,env("PGUSER"),env("PGPASSWORD"))).queryForObject(
    "select max(version::int)::text from platform.flyway_schema_history where success and version is not null",String.class));
   assertRuntimeRolesStart(url,"Banco na V005");
   migrate(url,MigrationVersion.LATEST);
   assertRuntimeRolesStart(url,"Banco com todas as migrations");
  } finally {
   try(var admin=DriverManager.getConnection(server+env("PGDATABASE"),env("PGUSER"),env("PGPASSWORD"));var statement=admin.createStatement()) {
    statement.execute("drop database if exists "+PRE_V015_DATABASE+" with (force)");
   }
  }
 }

 @Test void runtimeRolesStartAndColumnGrantDriftIsRefused() throws Exception {
  assertDoesNotThrow(()->new DatabaseRoleGuard(db.jdbc).verify(),"Login da API sem privilégio a mais");
  assertDoesNotThrow(()->new DatabaseRoleGuard(workerDb.jdbc).verify(),"Login do worker sem privilégio a mais");
  assertRefusedWith("GRANT UPDATE(note) ON cp.access_events TO cp_api","REVOKE UPDATE(note) ON cp.access_events FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(unit_id) ON cp.access_events TO cp_api","REVOKE INSERT(unit_id) ON cp.access_events FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(state) ON cp.outbox TO cp_api","REVOKE INSERT(state) ON cp.outbox FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(payload) ON cp.outbox TO cp_api","REVOKE UPDATE(payload) ON cp.outbox FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(event_type) ON cp.outbox TO cp_worker","REVOKE UPDATE(event_type) ON cp.outbox FROM cp_worker",true);
  assertRefusedWith("GRANT UPDATE(kind) ON cp.notifications TO cp_api","REVOKE UPDATE(kind) ON cp.notifications FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(published_at) ON cp.announcements TO cp_api","REVOKE UPDATE(published_at) ON cp.announcements FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(read_at) ON cp.announcement_reads TO cp_api","REVOKE INSERT(read_at) ON cp.announcement_reads FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.import_rows TO cp_api","REVOKE DELETE ON cp.import_rows FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(source) ON cp.import_batches TO cp_api","REVOKE UPDATE(source) ON cp.import_batches FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(created_by) ON cp.import_batches TO cp_api","REVOKE INSERT(created_by) ON cp.import_batches FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(unit_label) ON cp.import_rows TO cp_api","REVOKE UPDATE(unit_label) ON cp.import_rows FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(object_key) ON cp.attachments TO cp_api","REVOKE UPDATE(object_key) ON cp.attachments FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(state) ON cp.attachments TO cp_api","REVOKE INSERT(state) ON cp.attachments FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(token_hash) ON cp.permit_links TO cp_api","REVOKE UPDATE(token_hash) ON cp.permit_links FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(revoked_at) ON cp.permit_links TO cp_api","REVOKE INSERT(revoked_at) ON cp.permit_links FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.permit_links TO cp_api","REVOKE DELETE ON cp.permit_links FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(week_days) ON cp.visit_rules TO cp_api","REVOKE UPDATE(week_days) ON cp.visit_rules FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.visit_rules TO cp_api","REVOKE DELETE ON cp.visit_rules FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(starts_at) ON cp.reservation_waitlist TO cp_api","REVOKE UPDATE(starts_at) ON cp.reservation_waitlist FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(state) ON cp.reservation_waitlist TO cp_api","REVOKE INSERT(state) ON cp.reservation_waitlist FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.reservation_waitlist TO cp_api","REVOKE DELETE ON cp.reservation_waitlist FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(code_hmac) ON cp.parcel_delegations TO cp_api","REVOKE UPDATE(code_hmac) ON cp.parcel_delegations FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(attempts) ON cp.parcel_delegations TO cp_api","REVOKE INSERT(attempts) ON cp.parcel_delegations FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.parcel_delegations TO cp_api","REVOKE DELETE ON cp.parcel_delegations FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(starts_at) ON cp.move_requests TO cp_api","REVOKE UPDATE(starts_at) ON cp.move_requests FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(state) ON cp.move_requests TO cp_api","REVOKE INSERT(state) ON cp.move_requests FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.move_rules TO cp_api","REVOKE DELETE ON cp.move_rules FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(label) ON cp.access_credentials TO cp_api","REVOKE UPDATE(label) ON cp.access_credentials FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(observed_version) ON cp.credential_syncs TO cp_worker","REVOKE UPDATE(observed_version) ON cp.credential_syncs FROM cp_worker",true);
  assertRefusedWith("GRANT UPDATE(external_id) ON cp.access_devices TO cp_api","REVOKE UPDATE(external_id) ON cp.access_devices FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(configuration_hash) ON cp.point_bindings TO cp_api","REVOKE UPDATE(configuration_hash) ON cp.point_bindings FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.installation_acceptances TO cp_api","REVOKE DELETE ON cp.installation_acceptances FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(document) ON cp.access_policy_versions TO cp_api","REVOKE UPDATE(document) ON cp.access_policy_versions FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(status) ON cp.access_policy_validations TO cp_api","REVOKE UPDATE(status) ON cp.access_policy_validations FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(valid_until) ON cp.access_grants TO cp_api","REVOKE UPDATE(valid_until) ON cp.access_grants FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(reason_code) ON cp.revocation_markers TO cp_api","REVOKE UPDATE(reason_code) ON cp.revocation_markers FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.revocation_markers TO cp_api","REVOKE DELETE ON cp.revocation_markers FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(document) ON cp.rights_publications TO cp_api","REVOKE UPDATE(document) ON cp.rights_publications FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(state) ON cp.rights_publications TO cp_api","REVOKE INSERT(state) ON cp.rights_publications FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(outcome) ON cp.edge_reports TO cp_api","REVOKE UPDATE(outcome) ON cp.edge_reports FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(lease_until) ON cp.edge_leases TO cp_api","REVOKE UPDATE(lease_until) ON cp.edge_leases FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(connector_id) ON cp.edge_agents TO cp_api","REVOKE UPDATE(connector_id) ON cp.edge_agents FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.edge_agents TO cp_api","REVOKE DELETE ON cp.edge_agents FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON platform.partners TO cp_api","REVOKE DELETE ON platform.partners FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(name) ON platform.partners TO cp_api","REVOKE UPDATE(name) ON platform.partners FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(role) ON platform.partner_members TO cp_api","REVOKE UPDATE(role) ON platform.partner_members FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(status) ON platform.partner_members TO cp_api","REVOKE INSERT(status) ON platform.partner_members FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(starts_at) ON platform.partner_tenant_contracts TO cp_api",
   "REVOKE UPDATE(starts_at) ON platform.partner_tenant_contracts FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON platform.partner_tenant_contracts TO cp_api","REVOKE DELETE ON platform.partner_tenant_contracts FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(ends_at) ON cp.operator_schedules TO cp_api","REVOKE UPDATE(ends_at) ON cp.operator_schedules FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.operator_schedules TO cp_api","REVOKE DELETE ON cp.operator_schedules FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.rights_publications TO cp_worker","REVOKE DELETE ON cp.rights_publications FROM cp_worker",true);
  assertRefusedWith("GRANT UPDATE(direction) ON cp.device_events TO cp_worker","REVOKE UPDATE(direction) ON cp.device_events FROM cp_worker",true);
  assertRefusedWith("GRANT INSERT(unit_id) ON cp.device_events TO cp_worker","REVOKE INSERT(unit_id) ON cp.device_events FROM cp_worker",true);
  assertRefusedWith("GRANT DELETE ON cp.device_events TO cp_api","REVOKE DELETE ON cp.device_events FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(observed_at) ON cp.access_device_health TO cp_worker","REVOKE UPDATE(observed_at) ON cp.access_device_health FROM cp_worker",true);
  assertRefusedWith("GRANT INSERT(checked_at) ON cp.access_connector_health TO cp_worker","REVOKE INSERT(checked_at) ON cp.access_connector_health FROM cp_worker",true);
  assertRefusedWith("GRANT DELETE ON cp.access_device_health TO cp_api","REVOKE DELETE ON cp.access_device_health FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.access_credentials TO cp_api","REVOKE DELETE ON cp.access_credentials FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.outbox TO cp_worker","REVOKE DELETE ON cp.outbox FROM cp_worker",true);
  assertRefusedWith("GRANT UPDATE(account_id) ON cp.notifications TO cp_worker","REVOKE UPDATE(account_id) ON cp.notifications FROM cp_worker",true);
  // Depois de desfeitos os grants, as duas roles voltam a iniciar.
  assertDoesNotThrow(()->new DatabaseRoleGuard(db.jdbc).verify());
  assertDoesNotThrow(()->new DatabaseRoleGuard(workerDb.jdbc).verify());
 }
}
