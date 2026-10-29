package br.com.condpulse.database;

import br.com.condpulse.shared.infrastructure.DatabaseRoleGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guarda de inicialização com PostgreSQL real: logins da API (cp_ci_login) e do worker (cp_ci_worker) passam; um grant
 * por coluna que daria à aplicação o poder de alterar registro de acesso, escolher estado da tarefa ou mexer no aviso
 * impede a inicialização. Cada grant de teste é desfeito no próprio teste (banco descartável).
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
   var refused=assertThrows(IllegalStateException.class,()->guard.run(null),grant);
   assertEquals("Role de execução com privilégios incompatíveis.",refused.getMessage());
  } finally { db.adminUpdate(revoke); }
 }

 @Test void runtimeRolesStartAndColumnGrantDriftIsRefused() throws Exception {
  assertDoesNotThrow(()->new DatabaseRoleGuard(db.jdbc).run(null),"Login da API sem privilégio a mais");
  assertDoesNotThrow(()->new DatabaseRoleGuard(workerDb.jdbc).run(null),"Login do worker sem privilégio a mais");
  assertRefusedWith("GRANT UPDATE(note) ON cp.access_events TO cp_api","REVOKE UPDATE(note) ON cp.access_events FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(unit_id) ON cp.access_events TO cp_api","REVOKE INSERT(unit_id) ON cp.access_events FROM cp_api",false);
  assertRefusedWith("GRANT INSERT(state) ON cp.outbox TO cp_api","REVOKE INSERT(state) ON cp.outbox FROM cp_api",false);
  assertRefusedWith("GRANT UPDATE(kind) ON cp.notifications TO cp_api","REVOKE UPDATE(kind) ON cp.notifications FROM cp_api",false);
  assertRefusedWith("GRANT DELETE ON cp.outbox TO cp_worker","REVOKE DELETE ON cp.outbox FROM cp_worker",true);
  assertRefusedWith("GRANT UPDATE(account_id) ON cp.notifications TO cp_worker","REVOKE UPDATE(account_id) ON cp.notifications FROM cp_worker",true);
  // Depois de desfeitos os grants, as duas roles voltam a iniciar.
  assertDoesNotThrow(()->new DatabaseRoleGuard(db.jdbc).run(null));
  assertDoesNotThrow(()->new DatabaseRoleGuard(workerDb.jdbc).run(null));
 }
}
