package br.com.condpulse.database;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.OutboxOperationsModels;
import br.com.condpulse.platform.application.OutboxOperationsService;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.OutboxOperationsStore;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V032 com PostgreSQL real e o login da API: a operação da plataforma vê a fila de todos os condomínios (sem conteúdo),
 * recebe o alerta de fila antiga e devolve FAILED/SKIPPED para a fila com motivo auditado; sem papel ou sem AAL2, nada.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class OutboxOperationsJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static OutboxOperationsService outbox;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  outbox=new OutboxOperationsService(new PlatformTransactionExecutor(db.jdbc,db.manager),new OutboxOperationsStore(db.jdbc),new PlatformStore(db.jdbc));
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearReservationFixtures(); db.clearPlatformFixtures(); db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static UUID task(UUID tenant,String state,String extra) throws Exception {
  UUID id=UUID.randomUUID();
  db.adminUpdateWithoutTriggers("insert into cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload,state,attempts,last_error,processed_at,available_at) "
   +"values(?,?,'PARCEL_RECEIVED',?,1,'{}'::jsonb,?,?,?,"+extra+")",id,tenant,UUID.randomUUID(),state,
   "FAILED".equals(state)?6:0,"FAILED".equals(state)?"Falha saneada":null);
  return id;
 }

 @Test void withoutPlatformRoleOrSecondFactorNothingIsVisible() throws Exception {
  assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->outbox.summary(db.accountA,AAL2)).code());
  db.grantPlatformAdmin(db.accountA);
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->outbox.summary(db.accountA,AssuranceLevel.AAL1)).code());
  db.assertEmptyContext();
 }

 @Test void summaryFlagsOldReadyTasksAndExpiredLeasesAcrossTenants() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  task(db.tenantA,"PENDING","null,clock_timestamp()-interval '5 minutes'");
  task(db.tenantA,"FAILED","clock_timestamp(),clock_timestamp()");
  task(db.tenantB,"PENDING","null,clock_timestamp()");
  var summary=outbox.summary(db.accountA,AAL2);
  assertEquals(60,summary.staleAfterSeconds());
  var a=summary.tenants().stream().filter(t->t.tenantId().equals(db.tenantA)).findFirst().orElseThrow();
  var b=summary.tenants().stream().filter(t->t.tenantId().equals(db.tenantB)).findFirst().orElseThrow();
  assertTrue(a.stale(),"Tarefa pronta há 5 minutos gera alerta"); assertTrue(a.oldestReadySeconds()>=290);
  assertEquals(1,a.failed()); assertEquals(1,a.pending()); assertNotNull(a.tenantName());
  assertFalse(b.stale(),"Tarefa recém-criada não gera alerta");
  assertTrue(summary.tenants().indexOf(a)<summary.tenants().indexOf(b),"Condomínio com alerta aparece antes");
  db.assertEmptyContext();
 }

 @Test void replayReturnsFailedOrSkippedTasksWithAuditAndRefusesTheRest() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  UUID failed=task(db.tenantA,"FAILED","clock_timestamp(),clock_timestamp()");
  UUID skipped=task(db.tenantB,"SKIPPED","clock_timestamp(),clock_timestamp()");
  UUID delivered=task(db.tenantA,"DELIVERED","clock_timestamp(),clock_timestamp()");
  var page=outbox.tasks(db.accountA,AAL2,null,"FAILED");
  assertTrue(page.tasks().stream().anyMatch(t->t.taskId().equals(failed) && "Falha saneada".equals(t.lastError()) && t.attempts()==6));
  assertTrue(outbox.tasks(db.accountA,AAL2,db.tenantB,"FAILED").tasks().isEmpty(),"Filtro por condomínio");
  assertEquals("OUTBOX_STATE_INVALID",assertThrows(BusinessProblem.class,()->outbox.tasks(db.accountA,AAL2,null,"DELIVERED")).code());
  assertEquals("AUDIT_REASON_REQUIRED",assertThrows(BusinessProblem.class,
   ()->outbox.replay(db.accountA,AAL2,failed,new OutboxOperationsModels.Replay("  "))).code());
  var replayed=outbox.replay(db.accountA,AAL2,failed,new OutboxOperationsModels.Replay("Consumidor corrigido no deploy fictício"));
  assertEquals("PENDING",replayed.state()); assertEquals(6,replayed.attempts(),"O número da tentativa não volta atrás"); assertNull(replayed.lastError()); assertNull(replayed.processedAt());
  assertEquals("6",db.adminString("select attempt_base from cp.outbox where id=?",failed),"Orçamento de tentativas renovado");
  assertEquals("OUTBOX_TASK_REPLAYED:FAILED:PARCEL_RECEIVED",db.adminString("""
   select action||':'||(details->>'previousState')||':'||(details->>'eventType') from platform.platform_audit_log
   where actor_id=? and details->>'taskId'=?
   """,db.accountA,failed.toString()));
  assertEquals("PENDING",outbox.replay(db.accountA,AAL2,skipped,new OutboxOperationsModels.Replay("Evento ganhou consumidor")).state());
  assertEquals("OUTBOX_TASK_NOT_REPLAYABLE",assertThrows(BusinessProblem.class,
   ()->outbox.replay(db.accountA,AAL2,failed,new OutboxOperationsModels.Replay("Repetição"))).code(),"Já devolvida não volta de novo");
  assertEquals("OUTBOX_TASK_NOT_REPLAYABLE",assertThrows(BusinessProblem.class,
   ()->outbox.replay(db.accountA,AAL2,delivered,new OutboxOperationsModels.Replay("Entregue"))).code());
  assertEquals("OUTBOX_TASK_NOT_FOUND",assertThrows(BusinessProblem.class,
   ()->outbox.replay(db.accountA,AAL2,UUID.randomUUID(),new OutboxOperationsModels.Replay("Inexistente"))).code());
  assertEquals("1",db.adminString("select count(*) from platform.platform_audit_log where actor_id=? and details->>'taskId'=?",db.accountA,failed.toString()),
   "Recusa não grava auditoria");
  db.assertEmptyContext();
 }
}
