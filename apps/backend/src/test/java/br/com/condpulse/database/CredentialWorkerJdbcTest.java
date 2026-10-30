package br.com.condpulse.database;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import br.com.condpulse.accesscredentials.application.CredentialModels;
import br.com.condpulse.accesscredentials.application.CredentialReconciler;
import br.com.condpulse.accesscredentials.application.CredentialService;
import br.com.condpulse.accesscredentials.infrastructure.CredentialStore;
import br.com.condpulse.accesscredentials.infrastructure.CredentialSyncStore;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.simulated.SimulatedAccessAdapter;
import br.com.condpulse.accessintegration.simulated.SimulatedAccessAdapter.Scenario;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V026 com o login do worker (cp_ci_worker) e o adaptador simulado (CP-043): a reconciliação leva o estado desejado
 * aos equipamentos com revogação primeiro, consulta antes de reenviar e nenhuma confirmação antiga reativa credencial
 * revogada. Nenhum equipamento real é contatado.
 */
@EnabledIfEnvironmentVariable(named="CP_TEST_WORKER_DB_USER",matches="cp_ci_worker")
class CredentialWorkerJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;
 private static CredentialService credentials;
 private static WorkerTransactions worker;
 private static CredentialSyncStore syncs;
 private static UUID manager;
 private UUID connector,entrance,garage;
 private SimulatedAccessAdapter simulator;
 private CredentialReconciler reconciler;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  workerDb=new JdbcWorkerPool();
  credentials=new CredentialService(new TenantTransactionExecutor(db.jdbc,db.manager),new CredentialStore(db.jdbc),new AuditRecorder(db.jdbc));
  worker=new WorkerTransactions(workerDb.jdbc,workerDb.manager);
  syncs=new CredentialSyncStore(workerDb.jdbc);
  manager=UUID.randomUUID(); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO CREDENCIAL WORKER','MANAGER','ACTIVE')",
   db.tenantA,manager);
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext(); workerDb.assertEmptyContext();
  connector=UUID.randomUUID(); entrance=UUID.randomUUID(); garage=UUID.randomUUID();
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Simulador worker')",connector,db.tenantA);
  db.adminUpdate("""
   insert into cp.access_devices(id,tenant_id,connector_id,external_id,label,direction)
   values(?,?,?,'leitor-entrada','Entrada social','ENTRY'),(?,?,?,'leitor-garagem','Garagem','BOTH')
   """,entrance,db.tenantA,connector,garage,db.tenantA,connector);
  simulator=new SimulatedAccessAdapter(connector.toString(),Clock.systemUTC(),false,List.of("leitor-entrada","leitor-garagem"));
  CredentialReconciler.Adapters adapters=(tenant,id,provider)->id.equals(connector)?Optional.<AccessAdapter>of(simulator):Optional.empty();
  reconciler=new CredentialReconciler(worker,syncs,adapters);
 }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }
 private static String key() { return "credential-worker-"+UUID.randomUUID(); }
 private CredentialModels.Credential create(String label) throws Exception {
  UUID holder=UUID.fromString(db.adminString("select id from cp.memberships where account_id=? and unit_id=?",db.accountA,db.unitA));
  return credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"CARD",label)).credential();
 }
 private CredentialModels.Credential current(UUID id) {
  return credentials.managed(manager,db.tenantA,AAL2,null,false).credentials().stream().filter(c->c.credentialId().equals(id)).findFirst().orElseThrow();
 }
 private String sync(UUID credential,UUID device) throws Exception {
  return db.adminString("select attempted_version||attempted_action||':'||outcome||':'||coalesce(observed_version||observed_action,'-') "
   +"from cp.credential_syncs where credential_id=? and device_id=?",credential,device);
 }

 @Test void revocationWinsOverALateConfirmationOfTheOldActivation() throws Exception {
  simulator.scenario("leitor-garagem",Scenario.ACK_ONLY);
  var card=create("Cartão worker");
  reconciler.runOnce();
  assertEquals("1APPLY:APPLIED:1APPLY",sync(card.credentialId(),entrance));
  assertEquals("1APPLY:ACCEPTED:-",sync(card.credentialId(),garage),"Recebimento não é aplicação");
  assertEquals("PARTIAL",current(card.credentialId()).status());

  var revoked=credentials.revoke(manager,db.tenantA,AAL2,card.credentialId(),new CredentialModels.Revoke("Cartão perdido",card.version()));
  assertEquals("REVOKING",revoked.status());
  // O worker grava tarde o resultado do comando antigo: o banco recusa (versão 1 não é mais a desejada).
  var stale=assertThrows(DataAccessException.class,()->worker.inTenant(db.tenantA,()->{
   syncs.record(db.tenantA,card.credentialId(),garage,1,"APPLY","APPLIED",null,0); return null; }));
  assertNotNull(stale);
  reconciler.runOnce();
  assertEquals("2REVOKE:APPLIED:2REVOKE",sync(card.credentialId(),entrance),"Revogação vai antes, mesmo com espera da versão anterior");
  assertEquals("2REVOKE:ACCEPTED:-",sync(card.credentialId(),garage));
  // O equipamento entrega o que ficou na fila: só a revogação (a ativação antiga foi substituída, nunca aplicada).
  assertEquals(1,simulator.deliverPending("leitor-garagem"));
  assertEquals(1,simulator.applications("leitor-garagem"),"A ativação antiga nunca foi aplicada na garagem");
  db.adminUpdate("update cp.credential_syncs set next_attempt_at=clock_timestamp() where credential_id=?",card.credentialId());
  var pass=reconciler.runOnce();
  assertEquals(0,pass.sent(),"Recebido é consultado, não reenviado");
  assertEquals("2REVOKE:APPLIED:2REVOKE",sync(card.credentialId(),garage));
  assertEquals("REVOKED",current(card.credentialId()).status());
  assertEquals(0,reconciler.runOnce().sent(),"Estado final alcançado: nada a reenviar");
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void uncertainResultsAreQueriedBeforeResendingAndOfflineDevicesBackOff() throws Exception {
  simulator.scenario("leitor-entrada",Scenario.TIMEOUT_APPLIED);
  simulator.scenario("leitor-garagem",Scenario.OFFLINE);
  var card=create("Cartão incerto");
  reconciler.runOnce();
  assertEquals("1APPLY:UNKNOWN:-",sync(card.credentialId(),entrance));
  assertEquals("1APPLY:UNAVAILABLE:-",sync(card.credentialId(),garage));
  assertEquals("t",db.adminString("select (next_attempt_at>clock_timestamp()+interval '50 seconds')::text from cp.credential_syncs where device_id=?",garage).substring(0,1),
   "Fora do ar espera antes de tentar de novo");
  simulator.scenario("leitor-entrada",Scenario.CONFIRM);
  simulator.scenario("leitor-garagem",Scenario.CONFIRM);
  db.adminUpdate("update cp.credential_syncs set next_attempt_at=clock_timestamp() where credential_id=?",card.credentialId());
  reconciler.runOnce();
  assertEquals("1APPLY:APPLIED:1APPLY",sync(card.credentialId(),entrance),"Consulta mostra que a versão chegou");
  assertEquals(1,simulator.applications("leitor-entrada"),"Consulta antes de reenviar: aplicado uma vez só");
  assertEquals("1APPLY:APPLIED:1APPLY",sync(card.credentialId(),garage));
  assertEquals("ACTIVE",current(card.credentialId()).status());
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void revocationsComeFirstAndConnectorsWithoutAdapterSendNothing() throws Exception {
  var active=create("Cartão ativo");
  var lost=create("Cartão perdido");
  credentials.revoke(manager,db.tenantA,AAL2,lost.credentialId(),new CredentialModels.Revoke("Perdido",lost.version()));
  var due=worker.inTenant(db.tenantA,()->syncs.due(db.tenantA,connector));
  assertEquals(List.of(lost.credentialId(),active.credentialId()),due.stream().map(CredentialSyncStore.Work::credentialId).toList(),
   "Revogação tem prioridade");
  var none=new CredentialReconciler(worker,syncs,CredentialReconciler.NONE).runOnce();
  assertTrue(none.skippedConnectors()>=1); assertEquals(0,none.sent());
  assertEquals("0",db.adminString("select count(*) from cp.credential_syncs where credential_id in (?,?)",active.credentialId(),lost.credentialId()));
  assertEquals("REVOKING",current(lost.credentialId()).status(),"Sem integração, a revogação fica pendente e visível");
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }
}
