package br.com.condpulse.database;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.visits.application.PermitLinkPublicService;
import br.com.condpulse.visits.application.PermitLinkTokens;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
import br.com.condpulse.visits.infrastructure.PermitLinkPublicStore;
import br.com.condpulse.visits.infrastructure.VisitStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** V020 com PostgreSQL real e login da API: link do convite por hash, visão pública mínima, rotação e revogação. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class PermitLinkJdbcTest {
 private static JdbcDisposableDatabase db;
 private static VisitService visits;
 private static PermitLinkPublicService publicView;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(1);
  visits=new VisitService(new TenantTransactionExecutor(db.jdbc,db.manager),new VisitStore(db.jdbc),new AuditRecorder(db.jdbc));
  publicView=new PermitLinkPublicService(new PermitLinkPublicStore(db.jdbc,db.manager));
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.clearReservationFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
 private static VisitModels.Permit invite(String name) {
  return visits.createPermit(db.accountA,db.tenantA,db.unitA,"link-jdbc-"+UUID.randomUUID(),
   new VisitModels.CreatePermit(name,"12ab","Aniversário",now(),now().plus(Duration.ofHours(3)))).permit();
 }

 @Test void sharedLinkShowsOnlyTheMinimumAndTheDatabaseNeverSeesTheRawToken() throws Exception {
  var permit=invite("Visita Fictícia Sobrenome");
  var link=visits.shareLink(db.accountA,db.tenantA,permit.permitId());
  assertTrue(PermitLinkTokens.wellFormed(link.token()));
  assertEquals(permit.validUntil(),link.validUntil());
  assertFalse(link.toString().contains(link.token()),"Token fora do toString");
  assertEquals("0",db.adminString("select count(*) from cp.permit_links where token_hash=?",link.token()),"Token bruto não fica no banco");
  assertEquals("1",db.adminString("select count(*) from cp.permit_links where token_hash=?",PermitLinkTokens.hash(link.token())));
  assertEquals("0",db.adminString("select count(*) from cp.audit_log where coalesce(reason,'') like ?","%"+link.token()+"%"));
  assertTrue(visits.unitPermits(db.accountA,db.tenantA,db.unitA).permits().getFirst().shareLinkActive());
  var seen=publicView.view(link.token());
  assertEquals("Visita",seen.visitorFirstName(),"Só o primeiro nome");
  assertEquals("ACTIVE",seen.state()); assertEquals("Aniversário",seen.purpose());
  assertEquals(permit.validUntil(),seen.validUntil());
  assertNotNull(seen.tenantName()); assertEquals("America/Sao_Paulo",seen.timeZone());
  assertEquals("PERMIT_LINK_UNAVAILABLE",assertThrows(BusinessProblem.class,()->publicView.view(PermitLinkTokens.newToken())).code(),
   "Token desconhecido");
  assertEquals("PERMIT_LINK_UNAVAILABLE",assertThrows(BusinessProblem.class,()->publicView.view("curto")).code());
  db.assertEmptyContext();
 }

 @Test void newLinkRevokesThePreviousAndRevokingTheLinkOrThePermitClosesIt() throws Exception {
  var permit=invite("Pessoa Fictícia");
  var first=visits.shareLink(db.accountA,db.tenantA,permit.permitId());
  var second=visits.shareLink(db.accountA,db.tenantA,permit.permitId());
  assertNotEquals(first.token(),second.token());
  assertEquals("PERMIT_LINK_UNAVAILABLE",assertThrows(BusinessProblem.class,()->publicView.view(first.token())).code(),"Link anterior revogado");
  assertEquals("ACTIVE",publicView.view(second.token()).state());
  assertEquals("1",db.adminString("select count(*) from cp.permit_links where permit_id=? and revoked_at is null",permit.permitId()));
  var after=visits.revokeLink(db.accountA,db.tenantA,permit.permitId());
  assertFalse(after.shareLinkActive()); assertEquals("ACTIVE",after.state(),"Revogar o link não revoga o convite");
  assertThrows(BusinessProblem.class,()->publicView.view(second.token()));
  var third=visits.shareLink(db.accountA,db.tenantA,permit.permitId());
  visits.revokePermit(db.accountA,db.tenantA,permit.permitId(),new VisitModels.RevokePermit("Visita cancelada",0L));
  assertEquals("PERMIT_LINK_UNAVAILABLE",assertThrows(BusinessProblem.class,()->publicView.view(third.token())).code(),
   "Convite revogado fecha o link");
  assertFalse(visits.unitPermits(db.accountA,db.tenantA,db.unitA).permits().getFirst().shareLinkActive());
  assertEquals("VISIT_PERMIT_LINK_UNAVAILABLE",assertThrows(BusinessProblem.class,
   ()->visits.shareLink(db.accountA,db.tenantA,permit.permitId())).code(),"Convite revogado não ganha link");
  assertEquals("3",db.adminString("select count(*) from cp.audit_log where resource_id=? and action='VISIT_PERMIT_LINK_CREATED'",permit.permitId()));
  db.assertEmptyContext();
 }

 @Test void otherUnitOtherTenantAndConciergeCannotShare() throws Exception {
  var permit=invite("Pessoa Fictícia");
  assertEquals("VISIT_PERMIT_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.shareLink(db.accountC,db.tenantA,permit.permitId())).code());
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->visits.shareLink(db.accountB,db.tenantA,permit.permitId())).code());
  assertEquals("VISIT_PERMIT_NOT_FOUND",assertThrows(BusinessProblem.class,()->visits.revokeLink(db.accountC,db.tenantA,permit.permitId())).code());
  assertEquals("0",db.adminString("select count(*) from cp.permit_links where permit_id=?",permit.permitId()));
  db.assertEmptyContext();
 }

 @Test void expiredPermitClosesTheLinkWithTheSameAnswer() throws Exception {
  var permit=invite("Pessoa Fictícia");
  var link=visits.shareLink(db.accountA,db.tenantA,permit.permitId());
  db.adminUpdate("set session_replication_role=replica; update cp.visit_permits set valid_from=now()-interval '3 hours',"
   +"valid_until=now()-interval '1 minute' where id=?; set session_replication_role=origin",permit.permitId());
  assertEquals("PERMIT_LINK_UNAVAILABLE",assertThrows(BusinessProblem.class,()->publicView.view(link.token())).code());
  db.assertEmptyContext();
 }
}
