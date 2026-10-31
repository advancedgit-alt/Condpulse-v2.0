package br.com.condpulse.database;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.InvitationService;
import br.com.condpulse.tenancy.application.MembershipManagementService;
import br.com.condpulse.tenancy.application.MembershipModels;
import br.com.condpulse.tenancy.application.RentalModels;
import br.com.condpulse.tenancy.application.RentalService;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.tenancy.infrastructure.MembershipStore;
import br.com.condpulse.tenancy.infrastructure.RentalStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V034 com PostgreSQL real e o login da API: locação pela gestão, convite e remoção do inquilino pelo proprietário. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class RentalJdbcTest {
 private static JdbcDisposableDatabase db;
 private static RentalService rentals;
 private static InvitationService invitations;
 private static MembershipManagementService memberships;
 private static final AssuranceLevel AAL2=AssuranceLevel.AAL2;
 private static final UUID MANAGER=UUID.randomUUID(),OWNER=UUID.randomUUID();
 private static UUID ownerMembership;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new MembershipStore(db.jdbc); var audit=new AuditRecorder(db.jdbc);
  rentals=new RentalService(transactions,new RentalStore(db.jdbc),store,audit);
  invitations=new InvitationService(transactions,store,audit,db.jdbc,db.manager);
  memberships=new MembershipManagementService(transactions,store,audit);
  db.extraAccounts.add(MANAGER); db.extraAccounts.add(OWNER);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",MANAGER,OWNER);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTAO LOCACAO JDBC','MANAGER','ACTIVE')",
   db.tenantA,MANAGER);
  ownerMembership=UUID.randomUUID();
  db.adminUpdate("insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,?,'PROPRIETARIO JDBC','OWNER','ACTIVE')",
   ownerMembership,db.tenantA,OWNER,db.unitA);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterEach void endRentals() throws Exception { db.adminUpdate("delete from cp.unit_rentals where tenant_id=?",db.tenantA); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static RentalModels.RentalChange why(String reason) { return new RentalModels.RentalChange(reason); }
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }

 @Test void ownerOfRentedUnitOnlyInvitesAndRemovesTheTenant() throws Exception {
  assertFalse(rentals.view(OWNER,db.tenantA,db.unitA).rented(),"Sem locação o proprietário só vê a indicação");
  assertEquals("RENTAL_NOT_ACTIVE",code(()->rentals.invite(OWNER,db.tenantA,db.unitA,
   new RentalModels.TenantInvitation("RESIDENT","INQUILINO JDBC","inq@example.invalid",7))));
  assertEquals("RENTAL_REASON_REQUIRED",code(()->rentals.start(MANAGER,db.tenantA,AAL2,db.unitA,why(" "))));
  assertEquals("MFA_REQUIRED",code(()->rentals.start(MANAGER,db.tenantA,AssuranceLevel.AAL1,db.unitA,why("Contrato"))));
  assertEquals("STAFF_ACCESS_DENIED",code(()->rentals.start(OWNER,db.tenantA,AAL2,db.unitA,why("Contrato"))),"Proprietário não marca a locação");
  var started=rentals.start(MANAGER,db.tenantA,AAL2,db.unitA,why("Contrato de locação informado"));
  db.assertEmptyContext();
  assertEquals(java.util.List.of("PROPRIETARIO JDBC"),started.owners());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='UNIT_RENTAL_STARTED' and actor_id=?",db.unitA,MANAGER));
  assertEquals("RENTAL_ALREADY_ACTIVE",code(()->rentals.start(MANAGER,db.tenantA,AAL2,db.unitA,why("De novo"))));
  assertEquals(1,rentals.active(MANAGER,db.tenantA,AAL2).size());

  var view=rentals.view(OWNER,db.tenantA,db.unitA);
  assertTrue(view.rented()); assertEquals("JDBC A",view.unitLabel());
  var resident=view.occupants().stream().filter(o->"PESSOA FICTICIA JDBC A".equals(o.displayName())).findFirst().orElseThrow();
  assertEquals("UNIT_ACCESS_DENIED",code(()->rentals.view(OWNER,db.tenantA,db.otherUnitA)),"Outra unidade não é do proprietário");
  assertEquals("UNIT_ACCESS_DENIED",code(()->rentals.view(db.accountA,db.tenantA,db.unitA)),"Inquilino não administra a locação");
  assertEquals("INVITATION_ROLE_INVALID",code(()->rentals.invite(OWNER,db.tenantA,db.unitA,
   new RentalModels.TenantInvitation("OWNER","OUTRO DONO","dono@example.invalid",7))));
  assertEquals("UNIT_ACCESS_DENIED",code(()->rentals.invite(OWNER,db.tenantA,db.otherUnitA,
   new RentalModels.TenantInvitation("RESIDENT","INQUILINO","inq@example.invalid",7))));

  String email="inquilino."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  var created=rentals.invite(OWNER,db.tenantA,db.unitA,new RentalModels.TenantInvitation("RESPONSIBLE","INQUILINO JDBC",email,7));
  db.assertEmptyContext();
  assertEquals(OWNER.toString(),db.adminString("select created_by from cp.membership_invitations where id=?",created.invitationId()));
  UUID tenantAccount=UUID.randomUUID(); db.extraAccounts.add(tenantAccount);
  var accepted=invitations.accept(tenantAccount,email,new MembershipModels.AcceptInvitation(created.token()));
  assertEquals("PENDING",accepted.status(),"Vínculo do inquilino continua dependendo da gestão");
  var approved=memberships.decide(MANAGER,db.tenantA,AAL2,accepted.membershipId(),new MembershipModels.Decision("APPROVE",0L,null));
  assertEquals("ACTIVE",approved.status());
  assertTrue(rentals.view(OWNER,db.tenantA,db.unitA).occupants().stream().anyMatch(o->o.membershipId().equals(accepted.membershipId())));

  var second=rentals.invite(OWNER,db.tenantA,db.unitA,new RentalModels.TenantInvitation("RESIDENT","OUTRO INQUILINO","o@example.invalid",3));
  rentals.revokeInvitation(OWNER,db.tenantA,db.unitA,second.invitationId());
  assertEquals("INVITATION_NOT_PENDING",code(()->rentals.revokeInvitation(OWNER,db.tenantA,db.unitA,second.invitationId())));

  assertEquals("MEMBERSHIP_REASON_REQUIRED",code(()->rentals.remove(OWNER,db.tenantA,db.unitA,resident.membershipId(),
   new RentalModels.OccupantRemoval(resident.version(),null))));
  assertEquals("MEMBERSHIP_CHANGED",code(()->rentals.remove(OWNER,db.tenantA,db.unitA,resident.membershipId(),
   new RentalModels.OccupantRemoval(resident.version()+5,"Fim do contrato"))));
  var removed=rentals.remove(OWNER,db.tenantA,db.unitA,resident.membershipId(),new RentalModels.OccupantRemoval(resident.version(),"Fim do contrato"));
  db.assertEmptyContext();
  assertEquals("REVOKED",removed.status());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->new TenantTransactionExecutor(db.jdbc,db.manager).execute(db.accountA,db.tenantA,c->c.tenantId())).code(),"Removido perde o acesso");
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='MEMBERSHIP_REVOKED' and actor_id=?",
   resident.membershipId(),OWNER));
  assertEquals("1",db.adminString("select count(*) from cp.outbox where aggregate_id=? and event_type='MEMBERSHIP_REVOKED'",resident.membershipId()));
  UUID other=db.jdbc.queryForObject("select gen_random_uuid()",UUID.class);
  assertEquals("MEMBERSHIP_NOT_FOUND",code(()->rentals.remove(OWNER,db.tenantA,db.unitA,other,new RentalModels.OccupantRemoval(0L,"x"))));
  UUID elsewhere=UUID.fromString(db.adminString("select id from cp.memberships where tenant_id=? and account_id=? and unit_id=?",
   db.tenantA,db.accountC,db.otherUnitA));
  assertEquals("MEMBERSHIP_NOT_FOUND",code(()->rentals.remove(OWNER,db.tenantA,db.unitA,elsewhere,new RentalModels.OccupantRemoval(0L,"x"))),
   "Ocupante de outra unidade fica fora do alcance");
  assertEquals("ACTIVE",db.adminString("select status from cp.memberships where id=?",elsewhere));

  rentals.end(MANAGER,db.tenantA,AAL2,db.unitA,why("Contrato encerrado"));
  assertEquals("RENTAL_NOT_ACTIVE",code(()->rentals.end(MANAGER,db.tenantA,AAL2,db.unitA,why("De novo"))));
  assertFalse(rentals.view(OWNER,db.tenantA,db.unitA).rented());
  assertEquals("1",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='UNIT_RENTAL_ENDED'",db.unitA));
  db.assertEmptyContext();
 }

 @Test void rentalNeedsAnOwnerWhoDoesNotLiveThere() throws Exception {
  assertEquals("RENTAL_OWNER_REQUIRED",code(()->rentals.start(MANAGER,db.tenantA,AAL2,db.otherUnitA,why("Contrato"))));
  UUID dual=UUID.randomUUID();
  db.adminUpdate("insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,?,'DONO MORADOR JDBC','OWNER','ACTIVE')",
   dual,db.tenantA,db.accountC,db.otherUnitA);
  try {
   assertEquals("RENTAL_OWNER_IS_OCCUPANT",code(()->rentals.start(MANAGER,db.tenantA,AAL2,db.otherUnitA,why("Contrato"))));
   assertEquals("0",db.adminString("select count(*) from cp.unit_rentals where unit_id=?",db.otherUnitA));
  } finally {
   db.adminUpdate("update cp.memberships set status='REVOKED' where id=?",dual);
  }
  // Durante a locação, a gestão não reativa vínculo de ocupante do proprietário na unidade alugada.
  rentals.start(MANAGER,db.tenantA,AAL2,db.unitA,why("Contrato"));
  UUID pending=UUID.randomUUID();
  db.adminUpdateWithoutTriggers("insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) values(?,?,?,?,'DONO PENDENTE JDBC','RESIDENT','PENDING')",
   pending,db.tenantA,OWNER,db.unitA);
  try {
   assertEquals("MEMBERSHIP_RENTAL_CONFLICT",code(()->memberships.decide(MANAGER,db.tenantA,AAL2,pending,new MembershipModels.Decision("APPROVE",0L,null))));
   assertEquals("PENDING",db.adminString("select status from cp.memberships where id=?",pending));
  } finally {
   db.adminUpdateWithoutTriggers("update cp.memberships set status='REVOKED' where id=?",pending);
  }
  db.assertEmptyContext();
 }
}
