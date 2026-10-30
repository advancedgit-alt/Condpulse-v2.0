package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.accesscredentials.application.CredentialModels;
import br.com.condpulse.accesscredentials.application.CredentialService;
import br.com.condpulse.accesscredentials.infrastructure.CredentialStore;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.MembershipManagementService;
import br.com.condpulse.tenancy.application.MembershipModels;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.tenancy.infrastructure.MembershipStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V026 pela API (cp_api): cadastro e revogação pela gestão, leitura da portaria e do próprio vínculo, vínculo encerrado. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class CredentialJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static CredentialService credentials;
 private static MembershipManagementService memberships;
 private static UUID manager,concierge,accountD,unitD;
 private UUID connector,disabled,deviceIn,deviceOut;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  credentials=new CredentialService(transactions,new CredentialStore(db.jdbc),new AuditRecorder(db.jdbc));
  memberships=new MembershipManagementService(transactions,new MembershipStore(db.jdbc),new AuditRecorder(db.jdbc));
  manager=UUID.randomUUID(); concierge=UUID.randomUUID(); accountD=UUID.randomUUID(); unitD=UUID.randomUUID();
  db.extraAccounts.add(manager); db.extraAccounts.add(concierge); db.extraAccounts.add(accountD);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE'),(?,'ACTIVE')",manager,concierge,accountD);
  db.adminUpdate("insert into cp.units(id,tenant_id,label) values(?,?,'CREDENCIAL D')",unitD,db.tenantA);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
   values(?,?,null,'GESTAO CREDENCIAL JDBC','MANAGER','ACTIVE'),(?,?,null,'PORTARIA CREDENCIAL JDBC','CONCIERGE','ACTIVE')
   """,db.tenantA,manager,db.tenantA,concierge);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext();
  db.adminUpdate("delete from cp.memberships where account_id=?",accountD);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,'MORADOR CREDENCIAL D','RESIDENT','ACTIVE')",
   db.tenantA,accountD,unitD);
  connector=UUID.randomUUID(); disabled=UUID.randomUUID(); deviceIn=UUID.randomUUID(); deviceOut=UUID.randomUUID();
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label) values(?,?,'SIMULATED','Simulador JDBC')",connector,db.tenantA);
  db.adminUpdate("insert into cp.access_connectors(id,tenant_id,provider,label,status) values(?,?,'SIMULATED','Desligado JDBC','DISABLED')",disabled,db.tenantA);
  db.adminUpdate("""
   insert into cp.access_devices(id,tenant_id,connector_id,external_id,label,direction)
   values(?,?,?,'leitor-entrada','Entrada social','ENTRY'),(?,?,?,'leitor-garagem','Garagem','BOTH')
   """,deviceIn,db.tenantA,connector,deviceOut,db.tenantA,connector);
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "credential-jdbc-"+UUID.randomUUID(); }
 private UUID membershipOf(UUID account,UUID unit) throws Exception {
  return UUID.fromString(db.adminString("select id from cp.memberships where account_id=? and unit_id=?",account,unit));
 }
 private void confirm(UUID credential,UUID device,long version,String action) throws Exception {
  db.adminUpdate("""
   insert into cp.credential_syncs(tenant_id,credential_id,device_id,attempted_version,attempted_action,outcome) values(?,?,?,?,?,'APPLIED')
   on conflict(tenant_id,credential_id,device_id) do update set attempted_version=excluded.attempted_version,attempted_action=excluded.attempted_action,
    outcome='APPLIED'
   """,db.tenantA,credential,device,version,action);
 }
 private static String code(Runnable action) {
  return assertThrows(BusinessProblem.class,action::run).code();
 }

 @Test void managerCreatesAndRevokesConciergeSeesPendingRevocationsAndHolderSeesOwn() throws Exception {
  UUID holder=membershipOf(db.accountA,db.unitA);
  var request=new CredentialModels.Create(connector,holder,"CARD","Cartão final 1234");
  assertEquals("MFA_REQUIRED",code(()->credentials.create(manager,db.tenantA,AssuranceLevel.AAL1,key(),request)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->credentials.create(db.accountA,db.tenantA,AAL2,key(),request)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->credentials.create(concierge,db.tenantA,AAL2,key(),request)));
  assertEquals("CREDENTIAL_KIND_INVALID",code(()->credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"FACE","Rosto"))));
  assertEquals("CREDENTIAL_LABEL_INVALID",code(()->credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"CARD","  "))));
  assertEquals("CREDENTIAL_CONNECTOR_UNAVAILABLE",code(()->credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(disabled,holder,"CARD","x"))));
  UUID managerMembership=UUID.fromString(db.adminString("select id from cp.memberships where account_id=?",manager));
  assertEquals("CREDENTIAL_HOLDER_INVALID",code(()->credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,managerMembership,"CARD","x"))));

  String createKey=key();
  var created=credentials.create(manager,db.tenantA,AAL2,createKey,request);
  var replay=credentials.create(manager,db.tenantA,AAL2,createKey,request);
  assertFalse(created.replayed()); assertTrue(replay.replayed());
  var credential=created.credential();
  assertEquals(credential.credentialId(),replay.credential().credentialId());
  assertEquals("SYNCING",credential.status());
  assertEquals(List.of("PENDING","PENDING"),credential.devices().stream().map(CredentialModels.DeviceSync::state).toList());
  assertEquals(db.unitA,credential.unitId()); assertEquals("PESSOA FICTICIA JDBC A",credential.holderName());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where event_type='CREDENTIAL_CREATED' and aggregate_id=?",credential.credentialId()));

  confirm(credential.credentialId(),deviceIn,1,"APPLY");
  var partial=credentials.managed(manager,db.tenantA,AAL2,db.unitA,false).credentials().getFirst();
  assertEquals("PARTIAL",partial.status());
  assertEquals(List.of(credential.credentialId()),credentials.unit(db.accountA,db.tenantA,db.unitA).credentials().stream()
   .map(CredentialModels.Credential::credentialId).toList(),"Morador vê a própria credencial");
  assertTrue(credentials.unit(db.accountC,db.tenantA,db.otherUnitA).credentials().isEmpty(),"Outra unidade não vê");
  assertEquals("UNIT_ACCESS_DENIED",code(()->credentials.unit(db.accountC,db.tenantA,db.unitA)));
  assertTrue(credentials.pendingRevocations(concierge,db.tenantA,AAL2).credentials().isEmpty());
  assertEquals("STAFF_ACCESS_DENIED",code(()->credentials.pendingRevocations(db.accountA,db.tenantA,AAL2)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->credentials.managed(concierge,db.tenantA,AAL2,null,false)));
  var listed=credentials.connectors(concierge,db.tenantA,AAL2).connectors().stream().filter(c->c.connectorId().equals(connector)).findFirst().orElseThrow();
  assertEquals(2,listed.devices().size(),"Portaria vê os equipamentos da integração");

  assertEquals("CREDENTIAL_REASON_REQUIRED",code(()->credentials.revoke(manager,db.tenantA,AAL2,credential.credentialId(),new CredentialModels.Revoke(" ",0L))));
  assertEquals("CREDENTIAL_CHANGED",code(()->credentials.revoke(manager,db.tenantA,AAL2,credential.credentialId(),new CredentialModels.Revoke("Cartão perdido",7L))));
  var revoked=credentials.revoke(manager,db.tenantA,AAL2,credential.credentialId(),new CredentialModels.Revoke("Cartão perdido",0L));
  assertEquals("REVOKING",revoked.status()); assertEquals(2,revoked.desiredVersion()); assertEquals("REVOKED",revoked.desiredState());
  assertEquals(revoked,credentials.revoke(manager,db.tenantA,AAL2,credential.credentialId(),new CredentialModels.Revoke("Cartão perdido",0L)),
   "Repetir a mesma revogação devolve o estado");
  assertEquals("CREDENTIAL_ALREADY_REVOKED",code(()->credentials.revoke(manager,db.tenantA,AAL2,credential.credentialId(),new CredentialModels.Revoke("Outro motivo",0L))));
  assertEquals(List.of("PENDING","PENDING"),revoked.devices().stream().map(CredentialModels.DeviceSync::state).toList(),
   "Confirmação da ativação não conta como revogação");
  assertEquals(List.of(credential.credentialId()),credentials.pendingRevocations(concierge,db.tenantA,AAL2).credentials().stream()
   .map(CredentialModels.Credential::credentialId).toList(),"Portaria vê a revogação pendente");
  // Confirmação atrasada da ativação (versão 1) chega depois da revogação: o banco descarta.
  assertThrows(Exception.class,()->confirm(credential.credentialId(),deviceOut,1,"APPLY"));
  confirm(credential.credentialId(),deviceIn,2,"REVOKE");
  confirm(credential.credentialId(),deviceOut,2,"REVOKE");
  assertEquals("REVOKED",credentials.managed(manager,db.tenantA,AAL2,null,false).credentials().getFirst().status());
  assertTrue(credentials.pendingRevocations(concierge,db.tenantA,AAL2).credentials().isEmpty(),"Revogação confirmada sai da lista da portaria");
  assertTrue(credentials.managed(manager,db.tenantA,AAL2,null,true).credentials().isEmpty(),"Nada pendente");
  db.assertEmptyContext();
 }

 @Test void creationWaitsForAConcurrentMembershipEndingAndIsRefused() throws Exception {
  UUID holder=membershipOf(accountD,unitD);
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  try(var ending=db.adminSession()) {
   ending.setAutoCommit(false);
   try(var update=ending.prepareStatement("update cp.memberships set status='REVOKED',version=version+1 where id=?")) {
    update.setObject(1,holder); update.executeUpdate();
   }
   var creating=pool.submit(()->{
    try { credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"CARD","Cartão concorrente")); return "OK"; }
    catch(BusinessProblem problem) { return problem.code(); }
   });
   Thread.sleep(1500);
   assertFalse(creating.isDone(),"O cadastro espera o encerramento do vínculo em andamento");
   ending.commit();
   assertEquals("CREDENTIAL_HOLDER_INVALID",creating.get(20,java.util.concurrent.TimeUnit.SECONDS));
  } finally { pool.shutdownNow(); }
  assertEquals("0",db.adminString("select count(*) from cp.access_credentials where membership_id=?",holder),"Nenhuma credencial ativa para vínculo encerrado");
  db.assertEmptyContext();
 }

 @Test void endingTheMembershipRevokesItsCredentialsInTheSameTransaction() throws Exception {
  UUID holder=membershipOf(accountD,unitD);
  var first=credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"CARD","Cartão D")).credential();
  var second=credentials.create(manager,db.tenantA,AAL2,key(),new CredentialModels.Create(connector,holder,"QR","QR D")).credential();
  long version=Long.parseLong(db.adminString("select version from cp.memberships where id=?",holder));
  memberships.revoke(manager,db.tenantA,AAL2,holder,new MembershipModels.Revocation(version,"Mudou-se"));
  for(var credential:List.of(first,second)) {
   assertEquals("REVOKED:2",db.adminString("select desired_state||':'||desired_version from cp.access_credentials where id=?",credential.credentialId()));
   assertTrue(db.adminString("select revoke_reason from cp.access_credentials where id=?",credential.credentialId()).startsWith("Vínculo encerrado"));
  }
  assertEquals("2",db.adminString("select count(*) from cp.audit_log where action='CREDENTIAL_REVOKED' and tenant_id=?",db.tenantA));
  assertEquals(2,credentials.pendingRevocations(concierge,db.tenantA,AAL2).credentials().size(),"Revogação física pendente aparece na portaria");
  db.assertEmptyContext();
 }
}
