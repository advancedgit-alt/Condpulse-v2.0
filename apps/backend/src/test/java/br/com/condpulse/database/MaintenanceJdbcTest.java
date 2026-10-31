package br.com.condpulse.database;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.requests.application.RequestService;
import br.com.condpulse.requests.infrastructure.RequestStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SqlStates;
import br.com.condpulse.tenancy.application.InvitationService;
import br.com.condpulse.tenancy.application.MembershipManagementService;
import br.com.condpulse.tenancy.application.MembershipModels;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.tenancy.infrastructure.MembershipStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V046 com PostgreSQL real e o login da API: zelador (MAINTENANCE) convidado e aprovado pela gestão pelos fluxos reais,
 * com capacidade própria (maintenance.operate, AAL2), que lê e registra o andamento somente dos pedidos atribuídos ao
 * próprio vínculo; não muda responsável nem prazo, não encerra e não enxerga cadastro nem pedidos de terceiros.
 */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class MaintenanceJdbcTest {
 private static final AssuranceLevel AAL1=AssuranceLevel.AAL1,AAL2=AssuranceLevel.AAL2;
 private static JdbcDisposableDatabase db;
 private static TenantTransactionExecutor transactions;
 private static MembershipManagementService memberships;
 private static InvitationService invitations;
 private static RequestService requests;
 private static UUID manager,responsible;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(2);
  transactions=new TenantTransactionExecutor(db.jdbc,db.manager);
  var store=new MembershipStore(db.jdbc); var audit=new AuditRecorder(db.jdbc);
  memberships=new MembershipManagementService(transactions,store,audit);
  invitations=new InvitationService(transactions,store,audit,db.jdbc,db.manager);
  requests=new RequestService(transactions,new RequestStore(db.jdbc),audit);
  manager=UUID.randomUUID(); responsible=UUID.randomUUID();
  db.extraAccounts.add(manager); db.extraAccounts.add(responsible);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",manager,responsible);
  db.adminUpdate("""
   insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) values
    (?,?,null,'GESTAO ZELADORIA JDBC','MANAGER','ACTIVE'),(?,?,?,'RESPONSAVEL ZELADORIA JDBC','RESPONSIBLE','ACTIVE')
   """,db.tenantA,manager,db.tenantA,responsible,db.unitA);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 @FunctionalInterface private interface Call { Object run() throws Exception; }
 private static String code(Call action) { return assertThrows(BusinessProblem.class,action::run).code(); }
 private static String key() { return "maintenance-jdbc-"+UUID.randomUUID(); }

 /** Zelador pelos fluxos reais: convite da gestão (sem unidade), aceite com o mesmo e-mail e aprovação. */
 private static UUID hireMaintenance() {
  String email="zelador."+UUID.randomUUID().toString().substring(0,8)+"@example.invalid";
  assertEquals("INVITATION_UNIT_INVALID",code(()->invitations.create(manager,db.tenantA,AAL2,
   new MembershipModels.CreateInvitation(db.unitA,"MAINTENANCE","ZELADOR FICTICIO JDBC",email,7))),"Equipe não tem unidade");
  var created=invitations.create(manager,db.tenantA,AAL2,new MembershipModels.CreateInvitation(null,"MAINTENANCE","ZELADOR FICTICIO JDBC",email,7));
  UUID person=UUID.randomUUID(); db.extraAccounts.add(person);
  var accepted=invitations.accept(person,email,new MembershipModels.AcceptInvitation(created.token()));
  assertEquals("PENDING",accepted.status());
  assertEquals("TENANT_ACCESS_DENIED",code(()->requests.maintenance(person,db.tenantA,AAL2)),"Pendente não opera");
  var pending=memberships.memberships(manager,db.tenantA,AAL2,"PENDING").memberships().stream()
   .filter(m->m.membershipId().equals(accepted.membershipId())).findFirst().orElseThrow();
  assertEquals("MAINTENANCE",pending.role()); assertNull(pending.unitId());
  memberships.decide(manager,db.tenantA,AAL2,pending.membershipId(),new MembershipModels.Decision("APPROVE",pending.version(),null));
  return person;
 }
 private static UUID membershipOf(UUID account) throws Exception {
  return UUID.fromString(db.adminString("select id from cp.memberships where tenant_id=? and account_id=?",db.tenantA,account));
 }
 private static RequestModels.UnitRequest open(String title) {
  return requests.open(responsible,db.tenantA,db.unitA,key(),new RequestModels.Open("MAINTENANCE",title,"Lâmpada queimada no hall.")).request();
 }

 @Test void maintenanceSeesAndUpdatesOnlyTheRequestsAssignedToItsOwnMembership() throws Exception {
  UUID zelador=hireMaintenance();
  UUID zeladorMembership=membershipOf(zelador);
  assertTrue(requests.maintenance(zelador,db.tenantA,AAL2).requests().isEmpty());
  assertEquals("MFA_REQUIRED",code(()->requests.maintenance(zelador,db.tenantA,AAL1)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.maintenance(responsible,db.tenantA,AAL2)),"Morador não é zeladoria");
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.maintenance(manager,db.tenantA,AAL2)),"Gestão usa a própria tela");
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.managed(zelador,db.tenantA,AAL2,null)),"Zelador não gere pedidos");

  var assigned=open("Lâmpada do hall");
  var other=open("Portão da garagem");
  // A gestão vê o zelador na equipe e atribui um dos pedidos.
  var managed=requests.managedRequest(manager,db.tenantA,AAL2,assigned.requestId());
  assertTrue(managed.team().stream().anyMatch(t->t.membershipId().equals(zeladorMembership) && "MAINTENANCE".equals(t.role())));
  requests.update(manager,db.tenantA,AAL2,assigned.requestId(),new RequestModels.Update("TRIAGED",zeladorMembership,null,null,0L));

  var page=requests.maintenance(zelador,db.tenantA,AAL2);
  assertEquals(List.of(assigned.requestId()),page.requests().stream().map(RequestModels.MaintenanceSummary::requestId).toList());
  assertEquals("SERVICE_REQUEST_NOT_FOUND",code(()->requests.maintenanceRequest(zelador,db.tenantA,AAL2,other.requestId())),
   "Pedido não atribuído não aparece, mesmo com o ID");
  var detail=requests.maintenanceRequest(zelador,db.tenantA,AAL2,assigned.requestId());
  assertEquals("TRIAGED",detail.state()); assertEquals(1,detail.version());

  assertEquals("SERVICE_REQUEST_STATE_INVALID",code(()->requests.maintenanceUpdate(zelador,db.tenantA,AAL2,assigned.requestId(),
   new RequestModels.MaintenanceUpdate("CLOSED",null,1L))),"Zelador não encerra");
  assertEquals("SERVICE_REQUEST_CHANGED",code(()->requests.maintenanceUpdate(zelador,db.tenantA,AAL2,assigned.requestId(),
   new RequestModels.MaintenanceUpdate("IN_PROGRESS",null,0L))));
  assertEquals("SERVICE_REQUEST_NOT_FOUND",code(()->requests.maintenanceUpdate(zelador,db.tenantA,AAL2,other.requestId(),
   new RequestModels.MaintenanceUpdate("IN_PROGRESS",null,0L))));
  var working=requests.maintenanceUpdate(zelador,db.tenantA,AAL2,assigned.requestId(),new RequestModels.MaintenanceUpdate("IN_PROGRESS",null,1L));
  assertEquals("IN_PROGRESS",working.state()); assertEquals(2,working.version());
  var note=requests.maintenanceMessage(zelador,db.tenantA,AAL2,assigned.requestId(),key(),
   new RequestModels.StaffMessage("Troca agendada para amanhã.","RESIDENT"));
  assertEquals("ZELADOR FICTICIO JDBC",note.message().authorName());
  assertEquals("SERVICE_REQUEST_RESOLUTION_REQUIRED",code(()->requests.maintenanceUpdate(zelador,db.tenantA,AAL2,assigned.requestId(),
   new RequestModels.MaintenanceUpdate("RESOLVED",null,2L))));
  var resolved=requests.maintenanceUpdate(zelador,db.tenantA,AAL2,assigned.requestId(),
   new RequestModels.MaintenanceUpdate("RESOLVED","Lâmpada trocada.",2L));
  assertEquals("RESOLVED",resolved.state()); assertEquals("Lâmpada trocada.",resolved.resolution());
  assertEquals("SERVICE_REQUEST_TRANSITION_INVALID",code(()->requests.maintenanceUpdate(zelador,db.tenantA,AAL2,assigned.requestId(),
   new RequestModels.MaintenanceUpdate("IN_PROGRESS",null,3L))),"Zelador não reabre");
  var seen=requests.unitRequest(responsible,db.tenantA,assigned.requestId());
  assertTrue(seen.messages().stream().anyMatch(m->m.body().equals("Resolução: Lâmpada trocada.")),"Unidade vê a resolução");
  assertEquals("RESOLVED",seen.state());
  db.assertEmptyContext();
 }

 @Test void databaseKeepsMaintenanceAwayFromAssignmentRegistryAndOtherCapabilities() throws Exception {
  UUID zelador=hireMaintenance();
  UUID zeladorMembership=membershipOf(zelador);
  var assigned=open("Infiltração na garagem");
  requests.update(manager,db.tenantA,AAL2,assigned.requestId(),new RequestModels.Update("TRIAGED",zeladorMembership,null,null,0L));
  assertEquals("STAFF_ACCESS_DENIED",code(()->transactions.execute(zelador,db.tenantA,AAL2,context->{
   context.requireCapability(StaffCapability.MANAGE_FINANCE); return null; })),"Sem financeiro");
  assertEquals("STAFF_ACCESS_DENIED",code(()->transactions.execute(zelador,db.tenantA,AAL2,context->{
   context.requireCapability(StaffCapability.OPERATE_CONCIERGE); return null; })),"Sem portaria");
  // Cadastro: só o próprio vínculo é legível.
  assertEquals(Integer.valueOf(1),transactions.<Integer>execute(zelador,db.tenantA,AAL2,context->
   db.jdbc.queryForObject("select count(*) from cp.memberships",Integer.class)));
  // Responsável e prazo não mudam pela zeladoria, mesmo com SQL direto.
  var reassign=assertThrows(DataAccessException.class,()->transactions.execute(zelador,db.tenantA,AAL2,context->db.jdbc.update(
   "update cp.service_requests set state='IN_PROGRESS',assignee_membership_id=null,version=version+1 where id=?",assigned.requestId())));
  assertEquals("42501",SqlStates.of(reassign));
  var close=assertThrows(DataAccessException.class,()->transactions.execute(zelador,db.tenantA,AAL2,context->db.jdbc.update(
   "update cp.service_requests set state='CLOSED',version=version+1 where id=?",assigned.requestId())));
  assertEquals("42501",SqlStates.of(close));
  // Revogado o vínculo pela gestão, o pedido sai da zeladoria na próxima requisição.
  var active=memberships.memberships(manager,db.tenantA,AAL2,"ACTIVE").memberships().stream()
   .filter(m->m.membershipId().equals(zeladorMembership)).findFirst().orElseThrow();
  memberships.revoke(manager,db.tenantA,AAL2,zeladorMembership,new MembershipModels.Revocation(active.version(),"Fim do contrato ficticio"));
  assertEquals("TENANT_ACCESS_DENIED",code(()->requests.maintenance(zelador,db.tenantA,AAL2)));
  db.assertEmptyContext();
 }
}
