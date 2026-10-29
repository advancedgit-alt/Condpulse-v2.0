package br.com.condpulse.database;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.InvitationService;
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

/** Convite, aceite, aprovação e revogação com PostgreSQL real, RLS e contexto local no pool. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class MembershipInvitationJdbcTest {
 private static JdbcDisposableDatabase db;
 private static TenantTransactionExecutor transactions;
 private static MembershipManagementService memberships;
 private static InvitationService invitations;
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;

 @BeforeAll static void setup() throws Exception {
  // Duas conexões: a revogação cruzada entre gestores precisa de transações concorrentes reais.
  db=new JdbcDisposableDatabase(2);
  transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new MembershipStore(db.jdbc); var audit=new AuditRecorder(db.jdbc);
  memberships=new MembershipManagementService(transactions,store,audit);
  invitations=new InvitationService(transactions,store,audit,db.jdbc,db.manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTOR FICTICIO JDBC','MANAGER','ACTIVE')",
   db.tenantA,db.accountC);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private UUID newcomer() { UUID id=UUID.randomUUID(); db.extraAccounts.add(id); return id; }
 private MembershipModels.InvitationCreated invite(String email) {
  return invitations.create(db.accountC,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(db.unitA,"RESIDENT","CONVIDADO FICTICIO JDBC",email,7));
 }

 @Test void invitedPersonCreatesOnlyPendingMembershipAndManagerActivatesIt() throws Exception {
  String email="convidado."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  var created=invite(email); db.assertEmptyContext();
  assertTrue(created.token().matches("[A-Za-z0-9_-]{43}"));
  assertEquals("0",db.adminString("select count(*) from cp.membership_invitations where token_hash=?",created.token()),
   "Token bruto nunca é persistido");
  UUID person=newcomer();
  var accepted=invitations.accept(person," "+email.toUpperCase()+" ",new MembershipModels.AcceptInvitation(created.token()));
  db.assertEmptyContext();
  assertEquals(db.tenantA,accepted.tenantId()); assertEquals("PENDING",accepted.status());
  assertEquals("ACTIVE",db.adminString("select status from platform.accounts where id=?",person));
  assertEquals("ACCEPTED",db.adminString("select status from cp.membership_invitations where id=?",created.invitationId()));
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->transactions.execute(person,db.tenantA,context->context.tenantId())).code(),"Pendente não libera o condomínio");
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()))).code(),"Convite não é reutilizado");
  var pending=memberships.memberships(db.accountC,db.tenantA,AAL2,"PENDING").memberships().stream()
   .filter(m->m.membershipId().equals(accepted.membershipId())).findFirst().orElseThrow();
  assertEquals("RESIDENT",pending.role()); assertEquals(db.unitA,pending.unitId());
  var active=memberships.decide(db.accountC,db.tenantA,AAL2,pending.membershipId(),
   new MembershipModels.Decision("APPROVE",pending.version(),null));
  assertEquals("ACTIVE",active.status());
  assertEquals(db.tenantA,transactions.execute(person,db.tenantA,context->{
   context.requireBookableUnit(db.unitA); return context.tenantId();
  }));
  assertEquals("MEMBERSHIP_CHANGED",assertThrows(BusinessProblem.class,()->memberships.decide(db.accountC,db.tenantA,AAL2,
   pending.membershipId(),new MembershipModels.Decision("APPROVE",pending.version(),null))).code(),"Decisão repetida não duplica");
  var revoked=memberships.revoke(db.accountC,db.tenantA,AAL2,active.membershipId(),
   new MembershipModels.Revocation(active.version(),"Mudou-se"));
  assertEquals("REVOKED",revoked.status());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->transactions.execute(person,db.tenantA,context->context.tenantId())).code());
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='MEMBERSHIP_REVOKED'",active.membershipId()));
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='MEMBERSHIP_REVOKED' and reason='Mudou-se'",active.membershipId()));
  db.assertEmptyContext();
 }

 @Test void inactiveLocalAccountCannotAcceptAndLeavesTheInvitationPending() throws Exception {
  for(String status:new String[]{"SUSPENDED","PENDING","CLOSED"}) {
   String email="inativa."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
   var created=invite(email);
   UUID person=newcomer();
   db.adminUpdate("insert into platform.accounts(id,status) values(?,?)",person,status);
   assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,
    ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()))).code(),status);
   db.assertEmptyContext();
   assertEquals("PENDING",db.adminString("select status from cp.membership_invitations where id=?",created.invitationId()),status);
   assertEquals("0",db.adminString("select count(*) from cp.memberships where account_id=?",person),status);
   assertEquals(status,db.adminString("select status from platform.accounts where id=?",person));
   invitations.revoke(db.accountC,db.tenantA,AAL2,created.invitationId());
  }
 @Test void suspendedTenantDoesNotReceiveNewMembershipsUntilReactivated() throws Exception {
  String email="suspenso."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  var created=invite(email);
  UUID person=newcomer();
  db.adminUpdate("update platform.tenants set active=false where id=?",db.tenantA);
  try {
   assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
    ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()))).code());
   db.assertEmptyContext();
   assertEquals("0",db.adminString("select count(*) from platform.accounts where id=?",person),"Recusa desfaz o espelho da conta");
   assertEquals("PENDING",db.adminString("select status from cp.membership_invitations where id=?",created.invitationId()));
   assertEquals("0",db.adminString("select count(*) from cp.memberships where invitation_id=?",created.invitationId()));
  } finally {
   db.adminUpdate("update platform.tenants set active=true where id=?",db.tenantA);
  }
  var accepted=invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()));
  assertEquals("PENDING",accepted.status()); assertEquals(db.tenantA,accepted.tenantId());
  db.assertEmptyContext();
 }
 @Test void wrongEmailExpiredRevokedOrForgedInvitationsCreateNothing() throws Exception {
  String email="destino."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  var created=invite(email); UUID person=newcomer();
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,"outra.pessoa@example.invalid",new MembershipModels.AcceptInvitation(created.token()))).code());
  assertNull(db.adminString("select status from platform.accounts where id=?",person),"Recusa não deixa conta parcial");
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation("x".repeat(43)))).code());
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,null,new MembershipModels.AcceptInvitation(created.token()))).code());
  db.adminUpdate("update cp.membership_invitations set created_at=now()-interval '2 days',expires_at=now()-interval '1 second' where id=?",
   created.invitationId());
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()))).code(),"Convite expirado não cria vínculo");
  var second=invite(email);
  invitations.revoke(db.accountC,db.tenantA,AAL2,second.invitationId());
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(second.token()))).code());
  assertEquals("INVITATION_NOT_PENDING",assertThrows(BusinessProblem.class,
   ()->invitations.revoke(db.accountC,db.tenantA,AAL2,second.invitationId())).code());
  assertEquals("0",db.adminString("select count(*) from cp.memberships where account_id=?",person));
  var listed=invitations.invitations(db.accountC,db.tenantA,AAL2);
  assertTrue(listed.stream().anyMatch(i->i.invitationId().equals(created.invitationId()) && "EXPIRED".equals(i.status())));
  assertTrue(listed.stream().anyMatch(i->i.invitationId().equals(second.invitationId()) && "REVOKED".equals(i.status())));
  db.assertEmptyContext();
 }

 @Test void staffRulesProtectRolesUnitsAndTheLastManager() throws Exception {
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->invitations.create(db.accountA,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(db.unitA,"RESIDENT","X","x@example.invalid",7))).code());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->invitations.create(db.accountC,db.tenantA,AssuranceLevel.AAL1,
   new MembershipModels.CreateInvitation(db.unitA,"RESIDENT","X","x@example.invalid",7))).code());
  assertEquals("INVITATION_ROLE_INVALID",assertThrows(BusinessProblem.class,()->invitations.create(db.accountC,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(null,"MANAGER","X","x@example.invalid",7))).code());
  assertEquals("INVITATION_UNIT_REQUIRED",assertThrows(BusinessProblem.class,()->invitations.create(db.accountC,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(null,"RESIDENT","X","x@example.invalid",7))).code());
  assertEquals("UNIT_NOT_FOUND",assertThrows(BusinessProblem.class,()->invitations.create(db.accountC,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(db.unitB,"RESIDENT","X","x@example.invalid",7))).code(),"Unidade de outro tenant");
  assertEquals("INVITATION_VALIDITY_INVALID",assertThrows(BusinessProblem.class,()->invitations.create(db.accountC,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(db.unitA,"RESIDENT","X","x@example.invalid",30))).code());
  var managers=memberships.memberships(db.accountC,db.tenantA,AAL2,"ACTIVE").memberships().stream()
   .filter(m->"MANAGER".equals(m.role())).toList();
  assertEquals(1,managers.size());
  var self=managers.getFirst();
  assertEquals("MEMBERSHIP_SELF_REVOCATION_DENIED",assertThrows(BusinessProblem.class,()->memberships.revoke(db.accountC,db.tenantA,AAL2,
   self.membershipId(),new MembershipModels.Revocation(self.version(),"Teste"))).code());
  assertEquals("MEMBERSHIP_REASON_REQUIRED",assertThrows(BusinessProblem.class,()->memberships.decide(db.accountC,db.tenantA,AAL2,
   self.membershipId(),new MembershipModels.Decision("REJECT",self.version(),null))).code());
  var all=memberships.memberships(db.accountC,db.tenantA,AAL2,null).memberships();
  assertTrue(all.stream().anyMatch(m->db.unitA.equals(m.unitId())),"Lista os vínculos do próprio condomínio");
  assertTrue(all.stream().noneMatch(m->db.unitB.equals(m.unitId())),"Nunca lista vínculo de outro condomínio");
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->memberships.memberships(db.accountC,db.tenantB,AAL2,null)).code());
  db.assertEmptyContext();
 }

 @Test void revokedOrRejectedMembershipCanBeInvitedAgainWithoutDatabaseIntervention() throws Exception {
  String email="reconvite."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  UUID person=newcomer();
  var first=invitations.accept(person,email,new MembershipModels.AcceptInvitation(invite(email).token()));
  assertEquals("MEMBERSHIP_ALREADY_EXISTS",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(invite(email).token()))).code(),
   "Enquanto houver vínculo não revogado, novo aceite não duplica");
  var pending=memberships.memberships(db.accountC,db.tenantA,AAL2,"PENDING").memberships().stream()
   .filter(m->m.membershipId().equals(first.membershipId())).findFirst().orElseThrow();
  memberships.decide(db.accountC,db.tenantA,AAL2,pending.membershipId(),new MembershipModels.Decision("REJECT",pending.version(),"Unidade errada"));
  var second=invitations.accept(person,email,new MembershipModels.AcceptInvitation(invite(email).token()));
  assertNotEquals(first.membershipId(),second.membershipId());
  var current=memberships.memberships(db.accountC,db.tenantA,AAL2,"PENDING").memberships().stream()
   .filter(m->m.membershipId().equals(second.membershipId())).findFirst().orElseThrow();
  var active=memberships.decide(db.accountC,db.tenantA,AAL2,current.membershipId(),new MembershipModels.Decision("APPROVE",current.version(),null));
  memberships.revoke(db.accountC,db.tenantA,AAL2,active.membershipId(),new MembershipModels.Revocation(active.version(),"Mudou-se"));
  var third=invitations.accept(person,email,new MembershipModels.AcceptInvitation(invite(email).token()));
  assertEquals("PENDING",third.status(),"Revogação também permite novo convite");
  assertEquals("2",db.adminString("select count(*) from cp.memberships where account_id=? and status='REVOKED'",person));
  db.assertEmptyContext();
 }

 @Test void crossRevocationBetweenManagersKeepsAnActiveManager() throws Exception {
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'SEGUNDO GESTOR FICTICIO','MANAGER','ACTIVE')",
   db.tenantA,db.accountA);
  try {
   var managers=memberships.memberships(db.accountC,db.tenantA,AAL2,"ACTIVE").memberships().stream()
    .filter(m->"MANAGER".equals(m.role())).toList();
   assertEquals(2,managers.size());
   UUID managerA=UUID.fromString(db.adminString("select id::text from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantA,db.accountA));
   UUID managerC=UUID.fromString(db.adminString("select id::text from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantA,db.accountC));
   var start=new java.util.concurrent.CountDownLatch(1);
   java.util.List<String> results;
   try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
    var byC=pool.submit(()->{ start.await(); return outcome(()->memberships.revoke(db.accountC,db.tenantA,AAL2,managerA,
     new MembershipModels.Revocation(0L,"Teste concorrente"))); });
    var byA=pool.submit(()->{ start.await(); return outcome(()->memberships.revoke(db.accountA,db.tenantA,AAL2,managerC,
     new MembershipModels.Revocation(0L,"Teste concorrente"))); });
    start.countDown();
    results=java.util.List.of(byC.get(30,java.util.concurrent.TimeUnit.SECONDS),byA.get(30,java.util.concurrent.TimeUnit.SECONDS));
   }
   assertEquals(1,results.stream().filter("OK"::equals).count(),results.toString());
   assertEquals("1",db.adminString("select count(*) from cp.memberships where tenant_id=? and role='MANAGER' and status='ACTIVE'",db.tenantA),
    "Revogação cruzada nunca deixa o condomínio sem gestor");
   assertEquals("1",db.adminString("select count(*) from cp.outbox where tenant_id=? and event_type='MEMBERSHIP_REVOKED' and aggregate_id in (?,?)",
    db.tenantA,managerA,managerC));
  } finally {
   db.adminUpdate("delete from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantA,db.accountA);
   db.adminUpdate("update cp.memberships set status='ACTIVE' where tenant_id=? and account_id=? and role='MANAGER'",db.tenantA,db.accountC);
  }
  db.assertEmptyContext();
 }

 @Test void managerCannotDecideOwnPendingMembershipAndInactiveUnitBlocksAcceptance() throws Exception {
  UUID own=UUID.randomUUID();
  db.adminUpdate("insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,?,'GESTOR COMO MORADOR','RESIDENT','PENDING')",
   own,db.tenantA,db.accountC,db.unitA);
  try {
   assertEquals("MEMBERSHIP_SELF_DECISION_DENIED",assertThrows(BusinessProblem.class,()->memberships.decide(db.accountC,db.tenantA,AAL2,own,
    new MembershipModels.Decision("APPROVE",0L,null))).code());
   assertEquals("PENDING",db.adminString("select status from cp.memberships where id=?",own));
  } finally { db.adminUpdate("delete from cp.memberships where id=?",own); }
  String email="unidade.inativa."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  var created=invite(email); UUID person=newcomer();
  db.adminUpdate("update cp.units set status='INACTIVE' where id=?",db.unitA);
  assertEquals("INVITATION_NOT_AVAILABLE",assertThrows(BusinessProblem.class,
   ()->invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()))).code());
  assertEquals("0",db.adminString("select count(*) from cp.memberships where account_id=?",person));
  db.restoreActiveFixtures();
  db.assertEmptyContext();
 }

 private static String outcome(java.util.function.Supplier<MembershipModels.Membership> action) {
  try { action.get(); return "OK"; } catch(BusinessProblem problem) { return problem.code(); }
 }
}
