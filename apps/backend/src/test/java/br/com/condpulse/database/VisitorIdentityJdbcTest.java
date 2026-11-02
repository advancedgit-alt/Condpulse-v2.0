package br.com.condpulse.database;

import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.stays.application.VisitorIdentityService;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL real: identidade canônica entre convites, sem dedução de nome e sem permissões administrativas no runtime. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class VisitorIdentityJdbcTest {
 private static JdbcDisposableDatabase db;
 private static TenantTransactionExecutor tx;
 private static VisitorIdentityService identity;
 private static VisitService visits;
 private static UUID concierge,manager;
 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(2);tx=new TenantTransactionExecutor(db.jdbc,db.manager);
  identity=new VisitorIdentityService(tx,db.jdbc,new SensitiveViewRecorder(db.jdbc,true));
  visits=new VisitService(tx,new VisitStore(db.jdbc),new AuditRecorder(db.jdbc));
  concierge=UUID.randomUUID();manager=UUID.randomUUID();db.extraAccounts.add(concierge);db.extraAccounts.add(manager);
  db.adminUpdate("insert into platform.accounts(id,status) values(?,'ACTIVE'),(?,'ACTIVE')",concierge,manager);
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'PORTARIA IDENTIDADE JDBC','CONCIERGE','ACTIVE'),(?,?,'GESTAO IDENTIDADE JDBC','MANAGER','ACTIVE')",db.tenantA,concierge,db.tenantA,manager);
 }
 @BeforeEach void reset() throws Exception { db.clearReservationFixtures();db.restoreActiveFixtures(); }
 @AfterAll static void close() throws Exception { if(db!=null) db.close(); }
 private static UUID permit(String name) {
  return visits.createPermit(db.accountA,db.tenantA,db.unitA,"identity-jdbc-"+UUID.randomUUID(),new VisitModels.CreatePermit(name,"7788",null,Instant.now().minusSeconds(1),Instant.now().plusSeconds(3600))).permit().permitId();
 }
 private static VisitorIdentityService.Check check(UUID permit,UUID canonical) {
  return identity.verify(concierge,db.tenantA,AssuranceLevel.AAL2,new VisitorIdentityService.Verify(UUID.randomUUID(),null,permit,canonical,"Documento conferido pessoalmente"));
 }
 @Test void newInvitationCanLinkToVerifiedIdentityWithoutMatchingNamesAndReplayIsAuditedOnce() throws Exception {
  UUID first=permit("Pessoa Identidade Ficticia"),second=permit("Outro Nome Ficticio");
  var root=check(first,null); UUID request=UUID.randomUUID();
  var body=new VisitorIdentityService.Verify(request,null,second,root.visitorId(),"Mesma pessoa: documento conferido");
  var alias=identity.verify(concierge,db.tenantA,AssuranceLevel.AAL2,body);
  assertNotEquals(root.visitorId(),alias.visitorId());assertEquals(root.visitorId(),alias.canonicalVisitorId());
  assertEquals(alias,identity.verify(concierge,db.tenantA,AssuranceLevel.AAL2,body));
  assertEquals(alias,identity.source(concierge,db.tenantA,AssuranceLevel.AAL2,null,second).check());
  assertEquals("1",db.adminString("select count(*) from cp.security_log where subject_id=? and event_type='VISITOR_IDENTITY_CHECKED'",alias.checkId()));
  assertEquals("IDENTITY_REQUEST_CONFLICT",assertThrows(BusinessProblem.class,()->identity.verify(concierge,db.tenantA,AssuranceLevel.AAL2,
   new VisitorIdentityService.Verify(request,null,second,root.visitorId(),"Motivo alterado"))).code());
  assertTrue(identity.search(concierge,db.tenantA,AssuranceLevel.AAL2,"Pessoa").candidates().stream().anyMatch(v->v.visitorId().equals(root.visitorId())));
  assertTrue(identity.search(concierge,db.tenantA,AssuranceLevel.AAL2,"Pes%_").candidates().isEmpty(),"% e _ são literais, não busca geral");
 }
 @Test void aliasChainsRootRetargetAndUnverifiedTargetsAreDeniedAndCorrectionPreservesHistory() throws Exception {
  UUID a=permit("Raiz Ficticia"),b=permit("Alias Ficticio"),c=permit("Terceiro Ficticio");
  var root=check(a,null);var alias=check(b,root.visitorId());
  assertThrows(BusinessProblem.class,()->check(c,alias.visitorId()),"Alias não vira raiz de outro alias");
  assertThrows(BusinessProblem.class,()->identity.voidCheck(manager,db.tenantA,AssuranceLevel.AAL2,root.checkId(),new VisitorIdentityService.VoidCheck(0L,"Raiz ainda associada")));
  var voided=identity.voidCheck(manager,db.tenantA,AssuranceLevel.AAL2,alias.checkId(),new VisitorIdentityService.VoidCheck(0L,"Associação feita por engano"));
  assertEquals("VOIDED",voided.state());assertNull(identity.source(concierge,db.tenantA,AssuranceLevel.AAL2,null,b).check());
  assertThrows(BusinessProblem.class,()->identity.voidCheck(manager,db.tenantA,AssuranceLevel.AAL2,alias.checkId(),new VisitorIdentityService.VoidCheck(0L,"Versão antiga")));
  var own=check(b,null);assertEquals(own.visitorId(),own.canonicalVisitorId());
  assertEquals("1",db.adminString("select count(*) from cp.security_log where subject_id=? and event_type='VISITOR_IDENTITY_VOIDED'",alias.checkId()));
 }
 @Test void wrongAccountTenantAndAssuranceCannotCheckOrCorrectAndRawIdentityIsImmutable() throws Exception {
  UUID p=permit("Protecao Ficticia");
  var body=new VisitorIdentityService.Verify(UUID.randomUUID(),null,p,null,"Conferência fictícia");
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->identity.verify(concierge,db.tenantA,AssuranceLevel.AAL1,body)).code());
  assertThrows(BusinessProblem.class,()->identity.verify(db.accountA,db.tenantA,AssuranceLevel.AAL2,body));
  assertThrows(BusinessProblem.class,()->identity.verify(concierge,db.tenantB,AssuranceLevel.AAL2,body));
  var root=check(p,null);
  assertThrows(BusinessProblem.class,()->identity.voidCheck(concierge,db.tenantA,AssuranceLevel.AAL2,root.checkId(),new VisitorIdentityService.VoidCheck(0L,"Sem poder de gestão")));
  assertThrows(org.springframework.dao.DataAccessException.class,()->tx.execute(concierge,db.tenantA,AssuranceLevel.AAL2,c->
   db.jdbc.update("update cp.visitor_identity_checks set canonical_visitor_id=? where tenant_id=? and id=?",UUID.randomUUID(),db.tenantA,root.checkId())));
  assertThrows(org.springframework.dao.DataAccessException.class,()->tx.execute(concierge,db.tenantA,AssuranceLevel.AAL2,c->
   db.jdbc.update("delete from cp.visitor_identity_checks where tenant_id=? and id=?",db.tenantA,root.checkId())));
  assertEquals("0",db.context(db.accountA,db.tenantA,null,()->db.jdbc.queryForObject("select count(*)::text from cp.visitor_identity_checks",String.class)));
  assertEquals("ACTIVE",identity.source(concierge,db.tenantA,AssuranceLevel.AAL2,null,p).check().state());db.assertEmptyContext();
 }
}
