package br.com.condpulse.database;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import br.com.condpulse.accesscredentials.application.CredentialModels;
import br.com.condpulse.accesscredentials.application.CredentialReconciler;
import br.com.condpulse.accesscredentials.application.CredentialService;
import br.com.condpulse.accesscredentials.application.DeviceHealthMonitor;
import br.com.condpulse.accesscredentials.infrastructure.CredentialStore;
import br.com.condpulse.accesscredentials.infrastructure.CredentialSyncStore;
import br.com.condpulse.accesscredentials.infrastructure.DeviceHealthStore;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.application.AccessModels;
import br.com.condpulse.accessintegration.simulated.SimulatedAccessAdapter;
import br.com.condpulse.accessintegration.simulated.SimulatedAccessAdapter.Scenario;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.notifications.application.WorkerTransactions;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V028 com o login do worker e o adaptador simulado (CP-048): equipamento fora do ar aparece fora do ar, conector sem
 * resposta e observação antiga viram "sem informação recente", e a reconciliação leva o estado final sem reproduzir a
 * fila antiga.
 */
@EnabledIfEnvironmentVariable(named="CP_TEST_WORKER_DB_USER",matches="cp_ci_worker")
class DeviceHealthWorkerJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;
 private static CredentialService credentials;
 private static TenantTransactionExecutor tenants;
 private static WorkerTransactions worker;
 private static CredentialSyncStore syncs;
 private static UUID manager;
 private UUID connector,entrance,garage;
 private SimulatedAccessAdapter simulator;
 private CredentialReconciler reconciler;
 private DeviceHealthMonitor monitor;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  workerDb=new JdbcWorkerPool();
  tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
  credentials=new CredentialService(tenants,new CredentialStore(db.jdbc),new AuditRecorder(db.jdbc));
  worker=new WorkerTransactions(workerDb.jdbc,workerDb.manager);
  syncs=new CredentialSyncStore(workerDb.jdbc);
  manager=UUID.randomUUID(); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO SAUDE WORKER','MANAGER','ACTIVE')",
   db.tenantA,manager);
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext(); workerDb.assertEmptyContext();
  connector=UUID.randomUUID(); entrance=UUID.randomUUID(); garage=UUID.randomUUID();
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Simulador saúde')",connector,db.tenantA);
  db.adminUpdate("""
   insert into cp.access_devices(id,tenant_id,connector_id,external_id,label,direction)
   values(?,?,?,'leitor-entrada','Entrada social','ENTRY'),(?,?,?,'leitor-garagem','Garagem','BOTH')
   """,entrance,db.tenantA,connector,garage,db.tenantA,connector);
  simulator=new SimulatedAccessAdapter(connector.toString(),Clock.systemUTC(),false,List.of("leitor-entrada","leitor-garagem","leitor-fantasma"));
  // O banco do CI tem resíduos de outros ensaios: só o conector deste teste tem adaptador.
  CredentialReconciler.Adapters adapters=(tenant,id,provider)->id.equals(connector)?Optional.<AccessAdapter>of(simulator):Optional.empty();
  reconciler=new CredentialReconciler(worker,syncs,adapters);
  monitor=new DeviceHealthMonitor(worker,syncs,new DeviceHealthStore(workerDb.jdbc),adapters);
 }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }
 private CredentialModels.ConnectorHealth health() {
  return credentials.health(manager,db.tenantA,AAL2).connectors().stream().filter(c->c.connectorId().equals(connector)).findFirst().orElseThrow();
 }
 private static CredentialModels.DeviceHealth device(CredentialModels.ConnectorHealth health,UUID id) {
  return health.devices().stream().filter(d->d.deviceId().equals(id)).findFirst().orElseThrow();
 }
 private CredentialModels.Credential create(String label) throws Exception {
  UUID holder=UUID.fromString(db.adminString("select id from cp.memberships where account_id=? and unit_id=?",db.accountA,db.unitA));
  return credentials.create(manager,db.tenantA,AAL2,"device-health-"+UUID.randomUUID(),new CredentialModels.Create(connector,holder,"CARD",label)).credential();
 }

 @Test void anOfflineDeviceShowsOfflineAndClockDriftIsFlagged() throws Exception {
  assertEquals("UNKNOWN:NEVER_CHECKED",health().status()+":"+health().reason(),"Antes da primeira consulta nada aparece no ar");
  simulator.scenario("leitor-garagem",Scenario.OFFLINE);
  simulator.clockSkew("leitor-entrada",Duration.ofMinutes(2));
  var pass=monitor.runOnce();
  assertEquals(1,pass.connectors());
  assertEquals(2,pass.devices());
  assertEquals(1,pass.unknownDevices(),"Equipamento que não é deste conector é ignorado");
  var health=health();
  assertEquals("OK",health.status());
  var entry=device(health,entrance);
  assertEquals("ONLINE",entry.status());
  assertEquals(120L,entry.clockSkewSeconds());
  assertTrue(entry.clockDrift(),"Relógio dois minutos adiantado é sinalizado");
  var offline=device(health,garage);
  assertEquals("OFFLINE",offline.status());
  assertFalse(offline.clockDrift());
  assertNotNull(offline.stateSince());
  simulator.scenario("leitor-garagem",Scenario.CONFIRM);
  monitor.runOnce();
  assertEquals("ONLINE",device(health(),garage).status(),"Voltou ao ar na consulta seguinte");
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void anUnreachableConnectorNeverShowsDevicesOnline() throws Exception {
  monitor.runOnce();
  var before=health();
  simulator.unreachable(true);
  var pass=monitor.runOnce();
  assertEquals(1,pass.unreachable());
  var after=health();
  assertEquals("UNREACHABLE:ADAPTER_ERROR",after.status()+":"+after.reason());
  assertEquals(before.lastReachableAt(),after.lastReachableAt(),"A última resposta continua a anterior");
  assertTrue(after.checkedAt().isAfter(before.checkedAt()));
  assertEquals("UNKNOWN:CONNECTOR_UNREACHABLE",device(after,entrance).status()+":"+device(after,entrance).reason());
  simulator.unreachable(false);
  monitor.runOnce();
  assertEquals("OK",health().status());
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void anOldObservationIsShownAsUnknownInsteadOfOnline() throws Exception {
  monitor.runOnce();
  var stale=tenants.execute(manager,db.tenantA,AAL2,context->new CredentialStore(db.jdbc).health(db.tenantA,Duration.ZERO,
   CredentialService.HEARTBEAT_STALE,CredentialService.CLOCK_DRIFT)).connectors().stream().filter(c->c.connectorId().equals(connector)).findFirst().orElseThrow();
  assertEquals("UNKNOWN:STALE",stale.status()+":"+stale.reason());
  assertEquals("UNKNOWN",device(stale,entrance).status());
  db.assertEmptyContext();
 }

 @Test void reconciliationAfterAnOutageSendsOnlyTheFinalState() throws Exception {
  simulator.scenario("leitor-entrada",Scenario.OFFLINE);
  var card=create("Cartão queda");
  reconciler.runOnce();
  monitor.runOnce();
  var during=health();
  assertEquals("OFFLINE",device(during,entrance).status());
  assertEquals(1,device(during,entrance).pendingApply(),"Ativação pendente no equipamento fora do ar");
  assertEquals(0,device(during,garage).pendingApply(),"Garagem confirmou");
  credentials.revoke(manager,db.tenantA,AAL2,card.credentialId(),new CredentialModels.Revoke("Cartão perdido durante a queda",card.version()));
  var revoking=health();
  assertEquals(1,device(revoking,entrance).pendingRevoke());
  assertEquals(0,device(revoking,entrance).pendingApply(),"A ativação antiga deixa de ser pendência");
  assertNotNull(device(revoking,entrance).oldestPendingRevocation());
  simulator.scenario("leitor-entrada",Scenario.CONFIRM);
  reconciler.runOnce();
  assertEquals(1,simulator.applications("leitor-entrada"),"Só a revogação chegou: a ativação antiga não foi reproduzida");
  var ref=db.adminString("select external_ref from cp.access_credentials where id=?",card.credentialId());
  var applied=simulator.getApplication(new AccessModels.ApplicationQuery(connector.toString(),ref,2,AccessModels.Action.REVOKE,List.of("leitor-entrada")));
  assertEquals(AccessModels.Outcome.APPLIED,applied.destinations().getFirst().outcome());
  monitor.runOnce();
  var after=health();
  assertEquals("ONLINE",device(after,entrance).status());
  assertEquals(0,device(after,entrance).pendingRevoke()+device(after,garage).pendingRevoke(),"Estado final confirmado em todos");
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void residentsDoNotSeeDeviceHealth() {
  var refused=assertThrows(BusinessProblem.class,()->credentials.health(db.accountA,db.tenantA,AAL2));
  assertEquals("STAFF_ACCESS_DENIED",refused.code());
 }
}
