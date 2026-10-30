package br.com.condpulse.database;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import br.com.condpulse.accesscredentials.application.CredentialModels;
import br.com.condpulse.accesscredentials.application.CredentialReconciler;
import br.com.condpulse.accesscredentials.application.CredentialService;
import br.com.condpulse.accesscredentials.application.DeviceEventIngestor;
import br.com.condpulse.accesscredentials.infrastructure.CredentialStore;
import br.com.condpulse.accesscredentials.infrastructure.CredentialSyncStore;
import br.com.condpulse.accesscredentials.infrastructure.DeviceEventStore;
import br.com.condpulse.accessintegration.application.AccessAdapter;
import br.com.condpulse.accessintegration.application.AccessModels;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * V027 com o login do worker e o adaptador simulado: eventos gravados no condomínio do conector, sem duplicar, com
 * quarentena de equipamento desconhecido, e confirmação por evento só para a versão desejada atual.
 */
@EnabledIfEnvironmentVariable(named="CP_TEST_WORKER_DB_USER",matches="cp_ci_worker")
class DeviceEventWorkerJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static JdbcWorkerPool workerDb;
 private static CredentialService credentials;
 private static WorkerTransactions worker;
 private static CredentialSyncStore syncs;
 private static UUID manager;
 private UUID connector,connectorB,entrance,garage;
 private SimulatedAccessAdapter simulator,simulatorB;
 private CredentialReconciler reconciler;
 private DeviceEventIngestor ingestor;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  workerDb=new JdbcWorkerPool();
  credentials=new CredentialService(new TenantTransactionExecutor(db.jdbc,db.manager),new CredentialStore(db.jdbc),new AuditRecorder(db.jdbc));
  worker=new WorkerTransactions(workerDb.jdbc,workerDb.manager);
  syncs=new CredentialSyncStore(workerDb.jdbc);
  manager=UUID.randomUUID(); db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO EVENTOS WORKER','MANAGER','ACTIVE')",
   db.tenantA,manager);
 }
 @BeforeEach void fixtures() throws Exception {
  db.clearReservationFixtures(); db.restoreActiveFixtures(); db.assertEmptyContext(); workerDb.assertEmptyContext();
  connector=UUID.randomUUID(); connectorB=UUID.randomUUID(); entrance=UUID.randomUUID(); garage=UUID.randomUUID();
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Simulador A'),(?,?,'SIMULATED','Simulador B')",
   connector,db.tenantA,connectorB,db.tenantB);
  db.adminUpdate("""
   insert into cp.access_devices(id,tenant_id,connector_id,external_id,label,direction)
   values(?,?,?,'leitor-entrada','Entrada social','ENTRY'),(?,?,?,'leitor-garagem','Garagem','BOTH'),(gen_random_uuid(),?,?,'leitor-entrada','Entrada B','ENTRY')
   """,entrance,db.tenantA,connector,garage,db.tenantA,connector,db.tenantB,connectorB);
  simulator=new SimulatedAccessAdapter(connector.toString(),Clock.systemUTC(),false,List.of("leitor-entrada","leitor-garagem","leitor-fantasma"));
  simulatorB=new SimulatedAccessAdapter(connectorB.toString(),Clock.systemUTC(),false,List.of("leitor-entrada"));
  CredentialReconciler.Adapters adapters=(tenant,id,provider)->id.equals(connector)?Optional.<AccessAdapter>of(simulator)
   :id.equals(connectorB)?Optional.<AccessAdapter>of(simulatorB):Optional.empty();
  reconciler=new CredentialReconciler(worker,syncs,adapters);
  ingestor=new DeviceEventIngestor(worker,syncs,new DeviceEventStore(workerDb.jdbc),adapters);
 }
 @AfterAll static void cleanup() throws Exception {
  try { if(workerDb!=null) workerDb.close(); } finally { if(db!=null) db.close(); }
 }
 private static String key() { return "device-event-worker-"+UUID.randomUUID(); }
 private CredentialModels.Credential create(String label) throws Exception {
  UUID holder=UUID.fromString(db.adminString("select id from cp.memberships where account_id=? and unit_id=?",db.accountA,db.unitA));
  return credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"CARD",label)).credential();
 }
 private String status(UUID id) {
  return credentials.managed(manager,db.tenantA,AAL2,null,false).credentials().stream().filter(c->c.credentialId().equals(id)).findFirst().orElseThrow().status();
 }
 private String externalRef(UUID credential) throws Exception { return db.adminString("select external_ref from cp.access_credentials where id=?",credential); }

 @Test void lateApplicationEventConfirmsTheCurrentVersionAndRepeatsDoNotDuplicate() throws Exception {
  simulator.scenario("leitor-garagem",Scenario.ACK_ONLY);
  var card=create("Cartão eventos");
  reconciler.runOnce();
  assertEquals("PARTIAL",status(card.credentialId()));
  assertEquals(1,simulator.deliverPending("leitor-garagem"));
  var pass=ingestor.runOnce();
  assertTrue(pass.confirmations()>=1,"A confirmação chegou por evento");
  assertEquals("ACTIVE",status(card.credentialId()),"Sem nova consulta ao parceiro");
  long events=Long.parseLong(db.adminString("select count(*) from cp.device_events where tenant_id=?",db.tenantA));
  var again=ingestor.runOnce();
  assertEquals(0,again.stored(),"Mesmos eventos de novo: nada gravado");
  assertEquals(String.valueOf(events),db.adminString("select count(*) from cp.device_events where tenant_id=?",db.tenantA));
  assertEquals("CREDENTIAL_APPLIED:"+db.unitA,db.adminString(
   "select kind||':'||unit_id from cp.device_events where device_id=? and kind='CREDENTIAL_APPLIED'",garage));
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void passagesKeepWhatTheDeviceSaidAndUnknownDevicesAreQuarantined() throws Exception {
  var card=create("Cartão passagem");
  reconciler.runOnce();
  simulator.emitPassage("leitor-entrada",externalRef(card.credentialId()),AccessModels.Direction.ENTRY,true);
  simulator.emitPassage("leitor-garagem",externalRef(card.credentialId()),null,false);
  simulator.scenario("leitor-fantasma",Scenario.OFFLINE);
  ingestor.runOnce();
  assertEquals("1",db.adminString("select count(*) from cp.device_events where device_id=? and kind='PASSAGE_DETECTED'",entrance),"Evento repetido grava uma vez");
  assertEquals("ENTRY:TURNSTILE_SENSOR:"+db.unitA,db.adminString(
   "select direction||':'||evidence||':'||unit_id from cp.device_events where device_id=? and kind='PASSAGE_DETECTED'",entrance));
  assertEquals("-",db.adminString("select coalesce(direction,'-') from cp.device_events where device_id=? and kind='PASSAGE_DETECTED'",garage),
   "Direção ausente continua ausente");
  assertEquals("QUARANTINED:UNKNOWN_DEVICE:-",db.adminString(
   "select state||':'||quarantine_reason||':'||coalesce(unit_id::text,'-') from cp.device_events where tenant_id=? and kind='DEVICE_OFFLINE'",db.tenantA));
  assertEquals("0",db.adminString("select count(*) from cp.device_events where tenant_id=?",db.tenantB),"Eventos do conector A nunca vão para o condomínio B");
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void anOldApplicationEventAfterRevocationIsKeptButConfirmsNothing() throws Exception {
  simulator.scenario("leitor-entrada",Scenario.TIMEOUT_APPLIED);
  var card=create("Cartão revogado");
  reconciler.runOnce();
  credentials.revoke(manager,db.tenantA,AAL2,card.credentialId(),new CredentialModels.Revoke("Perdido",card.version()));
  var pass=ingestor.runOnce();
  assertEquals("1",db.adminString("select count(*) from cp.device_events where device_id=? and kind='CREDENTIAL_APPLIED' and applied_version=1",entrance),
   "O evento fica no histórico");
  assertEquals("-",db.adminString("select coalesce(observed_version||observed_action,'-') from cp.credential_syncs where credential_id=? and device_id=?",
   card.credentialId(),entrance),"Ativação antiga não confirma nada depois da revogação");
  assertEquals("REVOKING",status(card.credentialId()));
  assertTrue(pass.stored()>=1);
  simulator.scenario("leitor-entrada",Scenario.CONFIRM);
  reconciler.runOnce();
  assertEquals("REVOKED",status(card.credentialId()));
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }
 @Test void aPassThatHitsThePageLimitResumesWhereItStopped() throws Exception {
  var card=create("Cartão muitas passagens");
  reconciler.runOnce();
  String ref=externalRef(card.credentialId());
  for(int i=0;i<5;i++) simulator.emitPassage("leitor-entrada",ref,AccessModels.Direction.ENTRY,false);
  // Página de 1 evento e no máximo 2 páginas por passada: a continuação precisa ser guardada entre passadas.
  var small=new DeviceEventIngestor(worker,syncs,new DeviceEventStore(workerDb.jdbc),
   (tenant,id,provider)->id.equals(connector)?Optional.<AccessAdapter>of(simulator):Optional.empty(),1,2);
  for(int pass=0;pass<10;pass++) small.runOnce();
  assertEquals("5",db.adminString("select count(*) from cp.device_events where device_id=? and kind='PASSAGE_DETECTED'",entrance),
   "Todas as passagens chegam, sem repetir o começo para sempre");
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }

 @Test void aFailedWriteDoesNotMoveThePositionPastTheEvent() throws Exception {
  var card=create("Cartão falha");
  simulator.emitPassage("leitor-entrada",externalRef(card.credentialId()),AccessModels.Direction.ENTRY,false);
  db.adminUpdate("revoke insert on cp.device_events from cp_worker");
  try { ingestor.runOnce(); }
  finally {
   db.adminUpdate("grant insert(tenant_id,connector_id,external_event_id,device_id,credential_id,kind,direction,evidence,applied_version,occurred_at,"
    +"received_at,state,quarantine_reason) on cp.device_events to cp_worker");
  }
  assertEquals("0",db.adminString("select count(*) from cp.device_events where tenant_id=?",db.tenantA));
  assertEquals("0",db.adminString("select count(*) from cp.device_event_cursors where connector_id=?",connector),"Posição não avança sobre evento não gravado");
  ingestor.runOnce();
  assertEquals("1",db.adminString("select count(*) from cp.device_events where device_id=? and kind='PASSAGE_DETECTED'",entrance));
  db.assertEmptyContext(); workerDb.assertEmptyContext();
 }
}
