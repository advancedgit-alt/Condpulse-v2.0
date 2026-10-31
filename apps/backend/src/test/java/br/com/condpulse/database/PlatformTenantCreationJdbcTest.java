package br.com.condpulse.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.ManagerInvitationService;
import br.com.condpulse.platform.application.PlatformModels;
import br.com.condpulse.platform.application.PlatformService;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V035 com PostgreSQL real: criação de condomínio pelo painel master, convite do primeiro gestor e isolamento. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class PlatformTenantCreationJdbcTest {
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static final String EMAIL="gestora.ficticia@example.invalid";
 private static JdbcDisposableDatabase db;
 private static PlatformService platform;
 private static ManagerInvitationService invitations;
 private static TenantTransactionExecutor tenants;
 private final List<UUID> created=new ArrayList<>();

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  platform=new PlatformService(new PlatformTransactionExecutor(db.jdbc,db.manager),new PlatformStore(db.jdbc));
  invitations=new ManagerInvitationService(db.jdbc,new AuditRecorder(db.jdbc),db.manager);
  tenants=new TenantTransactionExecutor(db.jdbc,db.manager);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.clearPlatformFixtures(); db.assertEmptyContext();
 }
 @AfterEach void removeCreated() throws Exception {
  for(UUID tenant:created) {
   db.adminUpdate("delete from cp.audit_log where tenant_id=?",tenant);
   db.adminUpdate("delete from platform.manager_invitations where tenant_id=?",tenant);
   db.adminUpdate("delete from cp.memberships where tenant_id=?",tenant);
   db.adminUpdate("delete from cp.amenities where tenant_id=?",tenant);
   db.adminUpdate("delete from cp.units where tenant_id=?",tenant);
   db.adminUpdate("delete from platform.platform_audit_log where tenant_id=?",tenant);
   db.adminUpdate("delete from platform.tenant_creations where tenant_id=?",tenant);
   db.adminUpdate("delete from platform.tenants where id=?",tenant);
  }
  created.clear();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static String key() { return "jdbc-"+UUID.randomUUID(); }
 private static PlatformModels.CreateTenant request(String name,AmenityManagementModels.AmenityInput amenity) {
  return new PlatformModels.CreateTenant(name,"America/Sao_Paulo",List.of("101","102"),
   new PlatformModels.ManagerInput("GESTORA FICTICIA JDBC",EMAIL,7),amenity,"Contrato fictício JDBC");
 }
 private PlatformModels.TenantCreated create(String key,PlatformModels.CreateTenant request) {
  var result=platform.createTenant(db.accountA,AAL2,key,request);
  if(!created.contains(result.tenant().tenantId())) created.add(result.tenant().tenantId());
  return result;
 }
 private static UUID newAccount() { UUID id=UUID.randomUUID(); db.extraAccounts.add(id); return id; }
 private static PlatformModels.AcceptManagerInvitation token(String value) { return new PlatformModels.AcceptManagerInvitation(value); }

 @Test void platformAdminCreatesTenantUnitsAmenityAndInvitationWithAuditAndIdempotency() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var policy=new AmenityManagementModels.PolicyInput(1,List.of(1,2,3,4,5,6,7),"08:00","22:00",60,30,240,2,120);
  var amenity=new AmenityManagementModels.AmenityInput("SALAO FICTICIO",30,false,0L,30,null,policy,null,null);
  String key=key();
  var request=request("  CONDOMINIO   FICTICIO JDBC  ",amenity);
  var result=create(key,request);
  UUID tenant=result.tenant().tenantId();
  assertTrue(result.applied()); assertEquals("CONDOMINIO FICTICIO JDBC",result.tenant().name());
  assertTrue(result.tenant().active()); assertEquals(0,result.tenant().version()); assertEquals("PLATFORM",result.tenant().origin());
  assertEquals(2,result.tenant().usage().activeUnits()); assertEquals(0,result.tenant().usage().activeManagers());
  assertNotNull(result.tenant().billing(),"Financeiro ligado e billing.read: situação da assinatura presente");
  assertFalse(result.tenant().billing().activeSubscription());
  assertTrue(result.invitationToken().matches("[A-Za-z0-9_-]{43}")); assertNotNull(result.invitationExpiresAt());
  assertEquals("1",db.adminString("select count(*) from cp.amenities where tenant_id=? and policy_version=1 and active",tenant));
  assertEquals("PENDING",db.adminString("select status from platform.manager_invitations where tenant_id=?",tenant));
  assertEquals("0",db.adminString("select count(*) from platform.manager_invitations where tenant_id=? and (email_hash=? or token_hash=?)",
   tenant,EMAIL,result.invitationToken()),"Banco guarda só hashes do e-mail e do token");
  assertEquals("TENANT_CREATED,TENANT_MANAGER_INVITED",db.adminString(
   "select string_agg(action,',' order by created_at,action) from platform.platform_audit_log where tenant_id=? and actor_id=?",tenant,db.accountA));
  // Repetição: mesma chave e mesmo pedido devolvem o mesmo condomínio, sem token e sem gravar de novo.
  var replay=create(key,request);
  assertFalse(replay.applied()); assertEquals(tenant,replay.tenant().tenantId()); assertNull(replay.invitationToken());
  assertEquals("2",db.adminString("select count(*) from platform.platform_audit_log where tenant_id=?",tenant));
  assertEquals("1",db.adminString("select count(*) from platform.tenant_creations where created_by=? and idempotency_key=?",db.accountA,key));
  assertEquals("TENANT_CREATION_KEY_REUSED",assertThrows(BusinessProblem.class,()->create(key,request("OUTRO NOME FICTICIO",null))).code());
  var detail=platform.tenant(db.accountA,AAL2,tenant);
  assertEquals(1,detail.managerInvitations().size()); assertEquals("PENDING",detail.managerInvitations().getFirst().status());
  assertTrue(detail.managers().isEmpty()); assertEquals(2,detail.recentEvents().size());
  db.assertEmptyContext();
 }
 @Test void nonPlatformAccountAndAal1AreDeniedWithoutWriting() throws Exception {
  String key=key();
  assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->platform.createTenant(db.accountB,AAL2,key,
   request("NEGADO FICTICIO",null))).code());
  // Gestor local não ganha poder de plataforma.
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values(?,?,null,'GESTAO FICTICIA','MANAGER','ACTIVE')",
   db.tenantA,db.accountC);
  try {
   assertEquals("PLATFORM_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->platform.createTenant(db.accountC,AAL2,key,
    request("NEGADO FICTICIO",null))).code());
  } finally {
   db.adminUpdate("delete from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantA,db.accountC);
  }
  db.grantPlatformAdmin(db.accountA);
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->platform.createTenant(db.accountA,AssuranceLevel.AAL1,key,
   request("NEGADO FICTICIO",null))).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->platform.tenant(db.accountA,AssuranceLevel.AAL1,db.tenantA)).code());
  assertEquals("0",db.adminString("select count(*) from platform.tenant_creations where idempotency_key=?",key));
  assertEquals("0",db.adminString("select count(*) from platform.tenants where name='NEGADO FICTICIO'"));
  // A role runtime sem a criação registrada nesta transação não insere condomínio nem unidade em condomínio existente.
  int denied=0;
  for(String sql:new String[]{"insert into platform.tenants(id,name,time_zone,active) values(gen_random_uuid(),'DIRETO','UTC',true)",
    "insert into cp.units(tenant_id,label) values('"+db.tenantB+"','PLATAFORMA')"}) {
   try {
    db.tx.execute(status->{
     db.jdbc.queryForObject("select set_config('app.actor_id',?,true)",String.class,db.accountA.toString());
     db.jdbc.queryForObject("select set_config('app.platform_capabilities','platform.audit.read,tenants.manage,tenants.read',true)",String.class);
     db.jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,db.tenantB.toString());
     return db.jdbc.update(sql);
    });
   } catch(org.springframework.dao.DataAccessException expected) { denied++; }
  }
  assertEquals(2,denied,"Condomínio e unidade só entram pela criação registrada na mesma transação");
  db.assertEmptyContext();
 }
 @Test void invitedManagerAcceptsAndEntersOnlyTheNewTenant() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var result=create(key(),request("CONDOMINIO GESTOR JDBC",null));
  UUID tenant=result.tenant().tenantId();
  UUID manager=newAccount();
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(manager,"outra.pessoa@example.invalid",token(result.invitationToken()))).code(),"Outro e-mail não aceita");
  assertNull(db.adminString("select status from platform.accounts where id=? and exists(select 1 from cp.memberships where account_id=?)",manager,manager));
  var accepted=invitations.accept(manager," Gestora.Ficticia@EXAMPLE.invalid ",token(result.invitationToken()));
  assertEquals(tenant,accepted.tenantId()); assertEquals("ACTIVE",accepted.status());
  assertEquals("MANAGER:ACTIVE",db.adminString("select role||':'||status from cp.memberships where tenant_id=? and account_id=?",tenant,manager));
  assertEquals("ACCEPTED",db.adminString("select status from platform.manager_invitations where tenant_id=?",tenant));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where tenant_id=? and action='MANAGER_INVITATION_ACCEPTED'",tenant));
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(manager,EMAIL,token(result.invitationToken()))).code(),"Convite é de uso único");
  // O gestor entra no condomínio novo, com as próprias unidades, e não alcança os demais.
  int units=tenants.execute(manager,tenant,AAL2,context->db.jdbc.queryForObject("select count(*) from cp.units",Integer.class));
  assertEquals(2,units);
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->tenants.execute(manager,db.tenantA,AAL2,context->1)).code());
  int residentUnits=tenants.execute(db.accountB,db.tenantB,context->db.jdbc.queryForObject("select count(*) from cp.units",Integer.class));
  assertEquals(1,residentUnits,"Isolamento entre condomínios inalterado");
  var detail=platform.tenant(db.accountA,AAL2,tenant);
  assertEquals(List.of("GESTORA FICTICIA JDBC"),detail.managers().stream().map(PlatformModels.Manager::displayName).toList());
  assertEquals(1,detail.tenant().usage().activeManagers());
  assertEquals("TENANT_MANAGER_EXISTS",assertThrows(BusinessProblem.class,()->platform.issueManagerInvitation(db.accountA,AAL2,tenant,
   new PlatformModels.IssueManagerInvitation("OUTRO GESTOR","outro@example.invalid",3,"Ensaio"))).code());
  db.assertEmptyContext();
 }
 @Test void reissuedRevokedOrSuspendedInvitationDoesNotCreateMembership() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var result=create(key(),request("CONDOMINIO CONVITE JDBC",null));
  UUID tenant=result.tenant().tenantId();
  var second=platform.issueManagerInvitation(db.accountA,AAL2,tenant,
   new PlatformModels.IssueManagerInvitation("GESTORA FICTICIA JDBC",EMAIL,3,"Link perdido"));
  UUID manager=newAccount();
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(manager,EMAIL,token(result.invitationToken()))).code(),"Novo convite revoga o anterior");
  platform.revokeManagerInvitation(db.accountA,AAL2,tenant,second.invitationId(),new PlatformModels.RevokeManagerInvitation("Enviado por engano"));
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,()->invitations.accept(manager,EMAIL,token(second.token()))).code());
  assertEquals("MANAGER_INVITATION_NOT_PENDING",assertThrows(BusinessProblem.class,()->platform.revokeManagerInvitation(db.accountA,AAL2,tenant,
   second.invitationId(),new PlatformModels.RevokeManagerInvitation("De novo"))).code());
  var third=platform.issueManagerInvitation(db.accountA,AAL2,tenant,new PlatformModels.IssueManagerInvitation("GESTORA FICTICIA JDBC",EMAIL,3,"Terceiro"));
  platform.updateTenant(db.accountA,AAL2,tenant,new PlatformModels.UpdateTenant(null,false,0L,"Suspensão de ensaio"));
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,()->invitations.accept(manager,EMAIL,token(third.token()))).code(),
   "Condomínio suspenso não recebe gestor");
  assertEquals("TENANT_SUSPENDED",assertThrows(BusinessProblem.class,()->platform.issueManagerInvitation(db.accountA,AAL2,tenant,
   new PlatformModels.IssueManagerInvitation("GESTORA FICTICIA JDBC",EMAIL,3,"Suspenso"))).code());
  assertEquals("0",db.adminString("select count(*) from cp.memberships where tenant_id=?",tenant));
  assertEquals("REVOKED,REVOKED,PENDING",db.adminString(
   "select string_agg(status,',' order by created_at,status desc) from platform.manager_invitations where tenant_id=?",tenant));
  db.assertEmptyContext();
 }
 @Test void catalogShowsAggregatedUsageWithoutResidentialContent() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var tenantA=platform.tenants(db.accountA,AAL2,null).tenants().stream().filter(t->t.tenantId().equals(db.tenantA)).findFirst().orElseThrow();
  assertEquals(2,tenantA.usage().activeUnits()); assertEquals(2,tenantA.usage().activeMemberships());
  assertEquals(0,tenantA.usage().activeManagers()); assertNull(tenantA.origin());
  assertNotNull(tenantA.usage().lastActivityAt());
  assertEquals("TENANT_NOT_FOUND",assertThrows(BusinessProblem.class,()->platform.tenant(db.accountA,AAL2,UUID.randomUUID())).code());
  db.assertEmptyContext();
 }
 @Test void reissueWaitingOnAnAcceptanceInProgressSeesTheNewManagerAndRefuses() throws Exception {
  db.grantPlatformAdmin(db.accountA);
  var result=create(key(),request("CONDOMINIO CORRIDA JDBC",null));
  UUID tenant=result.tenant().tenantId();
  UUID manager=newAccount();
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE')",manager);
  // Aceite em andamento (sessão administrativa própria): trava o convite, cria o gestor e só confirma depois.
  try(var accept=db.adminSession()) {
   accept.setAutoCommit(false);
   try(var lock=accept.prepareStatement("select id from platform.manager_invitations where tenant_id=? and status='PENDING' for update")) {
    lock.setObject(1,tenant); lock.executeQuery().close();
   }
   var reissue=java.util.concurrent.CompletableFuture.supplyAsync(()->{
    try {
     platform.issueManagerInvitation(db.accountA,AAL2,tenant,new PlatformModels.IssueManagerInvitation("OUTRA PESSOA","outra@example.invalid",3,"Corrida"));
     return "ISSUED";
    } catch(BusinessProblem problem) { return problem.code(); }
   });
   Thread.sleep(500);
   assertFalse(reissue.isDone(),"A emissão espera o aceite que trava o convite");
   UUID membership=UUID.randomUUID();
   try(var insert=accept.prepareStatement("insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,null,'GESTORA FICTICIA JDBC','MANAGER','ACTIVE')")) {
    insert.setObject(1,membership); insert.setObject(2,tenant); insert.setObject(3,manager); insert.executeUpdate();
   }
   try(var done=accept.prepareStatement("update platform.manager_invitations set status='ACCEPTED',accepted_by=?,accepted_at=now(),membership_id=? where tenant_id=? and status='PENDING'")) {
    done.setObject(1,manager); done.setObject(2,membership); done.setObject(3,tenant); done.executeUpdate();
   }
   accept.commit();
   assertEquals("TENANT_MANAGER_EXISTS",reissue.get(10,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertEquals("0",db.adminString("select count(*) from platform.manager_invitations where tenant_id=? and status='PENDING'",tenant),
   "Nenhum convite novo depois do aceite concorrente");
  db.assertEmptyContext();
 }
}
