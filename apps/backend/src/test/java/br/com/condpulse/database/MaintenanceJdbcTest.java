package br.com.condpulse.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
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

 /** Conversa longa no pedido atribuído: a zeladoria pagina as anteriores como a gestão; pedido não atribuído fica fora. */
 @Test void maintenancePagesOlderMessagesOnlyOnTheRequestAssignedToItsOwnMembership() throws Exception {
  UUID zelador=hireMaintenance();
  UUID zeladorMembership=membershipOf(zelador);
  var assigned=open("Vazamento recorrente no hall");
  var other=open("Interfone da portaria");
  requests.update(manager,db.tenantA,AAL2,assigned.requestId(),new RequestModels.Update("TRIAGED",zeladorMembership,null,null,0L));
  requests.update(manager,db.tenantA,AAL2,other.requestId(),new RequestModels.Update("TRIAGED",null,null,null,0L));
  // 249 mensagens no atribuído (a 50ª é comentário interno) e 3 no outro pedido.
  db.adminUpdateWithoutTriggers("""
   insert into cp.request_messages(tenant_id,request_id,author_id,author_role,author_name,body,visibility,created_at)
   select ?,?,case when n%2=0 or n=50 then ? else ? end,case when n%2=0 or n=50 then 'STAFF' else 'UNIT' end,'Autor',
    'Mensagem '||n,case when n=50 then 'INTERNAL' else 'RESIDENT' end,timestamptz '2030-01-01 00:00:00+00'+make_interval(secs=>n)
   from generate_series(1,249) n
   """,db.tenantA,assigned.requestId(),manager,responsible);
  db.adminUpdateWithoutTriggers("""
   insert into cp.request_messages(tenant_id,request_id,author_id,author_role,author_name,body,visibility,created_at)
   select ?,?,?,'UNIT','Autor','Outro '||n,'RESIDENT',timestamptz '2030-01-01 00:00:00+00'+make_interval(secs=>n)
   from generate_series(1,3) n
   """,db.tenantA,other.requestId(),responsible);
  var detail=requests.maintenanceRequest(zelador,db.tenantA,AAL2,assigned.requestId());
  assertEquals(200,detail.messages().size()); assertTrue(detail.messagesTruncated());
  var seen=new ArrayList<>(detail.messages());
  var page=requests.maintenanceOlderMessages(zelador,db.tenantA,AAL2,assigned.requestId(),seen.getFirst().messageId());
  assertEquals(49,page.messages().size()); assertFalse(page.truncated());
  seen.addAll(0,page.messages());
  assertEquals(db.adminString("""
   select string_agg(body,'|' order by created_at,id) from cp.request_messages where tenant_id=? and request_id=?
   """,db.tenantA,assigned.requestId()),seen.stream().map(RequestModels.Message::body).collect(Collectors.joining("|")),
   "Conversa completa, sem lacuna nem repetição, com o comentário interno");
  assertTrue(seen.stream().anyMatch(m->"INTERNAL".equals(m.visibility())));

  UUID otherMessage=UUID.fromString(db.adminString(
   "select id from cp.request_messages where request_id=? order by created_at desc limit 1",other.requestId()));
  UUID first=seen.getLast().messageId();
  assertEquals("SERVICE_REQUEST_NOT_FOUND",code(()->requests.maintenanceOlderMessages(zelador,db.tenantA,AAL2,other.requestId(),otherMessage)),
   "Pedido não atribuído não pagina, mesmo com IDs conhecidos");
  assertEquals("SERVICE_REQUEST_NOT_FOUND",code(()->requests.maintenanceOlderMessages(zelador,db.tenantA,AAL2,assigned.requestId(),otherMessage)),
   "Referência de outro pedido");
  assertEquals("SERVICE_REQUEST_NOT_FOUND",code(()->requests.maintenanceOlderMessages(zelador,db.tenantA,AAL2,assigned.requestId(),UUID.randomUUID())));
  assertEquals("MFA_REQUIRED",code(()->requests.maintenanceOlderMessages(zelador,db.tenantA,AAL1,assigned.requestId(),first)));
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.maintenanceOlderMessages(responsible,db.tenantA,AAL2,assigned.requestId(),first)),
   "Morador não usa a rota da zeladoria");
  assertEquals("STAFF_ACCESS_DENIED",code(()->requests.maintenanceOlderMessages(manager,db.tenantA,AAL2,assigned.requestId(),first)),
   "Gestão usa a própria rota");
  // Banco repete a regra: com a capacidade publicada, o zelador lê só a conversa do atribuído, mesmo com SQL direto.
  assertEquals(Integer.valueOf(0),transactions.<Integer>execute(zelador,db.tenantA,AAL2,context->db.jdbc.queryForObject(
   "select count(*) from cp.request_messages where request_id=?",Integer.class,other.requestId())));
  assertEquals(Integer.valueOf(249),transactions.<Integer>execute(zelador,db.tenantA,AAL2,context->db.jdbc.queryForObject(
   "select count(*) from cp.request_messages where request_id=?",Integer.class,assigned.requestId())));
  // Retirada a atribuição pela gestão, a paginação deixa de responder.
  requests.update(manager,db.tenantA,AAL2,assigned.requestId(),new RequestModels.Update("TRIAGED",null,null,null,1L));
  assertEquals("SERVICE_REQUEST_NOT_FOUND",code(()->requests.maintenanceOlderMessages(zelador,db.tenantA,AAL2,assigned.requestId(),first)));
  db.assertEmptyContext();
 }

 /**
  * A trava do gatilho vale só para quem atua pela zeladoria: a gestão continua atribuindo, dando prazo e encerrando, e a
  * manutenção do dono do schema (sem capacidade publicada) continua encerrando pedido, como antes da V046.
  */
 @Test void maintenanceGuardDoesNotReachManagementNorSchemaOwnerFlows() throws Exception {
  UUID zelador=hireMaintenance();
  UUID zeladorMembership=membershipOf(zelador);
  var managed=open("Bomba da cisterna");
  var updated=requests.update(manager,db.tenantA,AAL2,managed.requestId(),
   new RequestModels.Update("TRIAGED",zeladorMembership,java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(3),null,0L));
  assertEquals(zeladorMembership,updated.assigneeMembershipId());
  updated=requests.update(manager,db.tenantA,AAL2,managed.requestId(),new RequestModels.Update("CLOSED",null,null,null,1L));
  assertEquals("CLOSED",updated.state());
  var owner=open("Portão social");
  db.adminUpdate("update cp.service_requests set state='CLOSED',closed_at=clock_timestamp(),version=version+1 where tenant_id=? and id=?",
   db.tenantA,owner.requestId());
  assertEquals("CLOSED",db.adminString("select state from cp.service_requests where id=?",owner.requestId()));
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
