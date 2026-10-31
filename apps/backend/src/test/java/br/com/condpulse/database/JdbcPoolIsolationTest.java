package br.com.condpulse.database;

import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.application.CurrentIdentityQuery;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import br.com.condpulse.tenancy.application.TenantUnitsQuery;
import java.util.concurrent.atomic.AtomicBoolean;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL + Hikari reais; uma única conexão força reutilização entre identidades. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class JdbcPoolIsolationTest {
 private static JdbcDisposableDatabase db;
 private static AnnotationConfigApplicationContext spring;
 private static CurrentIdentityQuery identities;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase();
  spring=new AnnotationConfigApplicationContext();
  spring.getEnvironment().setActiveProfiles("api");
  spring.registerBean(HikariDataSource.class,()->db.pool);
  spring.registerBean(JdbcTemplate.class,()->db.jdbc);
  spring.registerBean(PlatformTransactionManager.class,()->db.manager);
  spring.register(TransactionConfiguration.class,CurrentIdentityQuery.class);
  spring.refresh(); identities=spring.getBean(CurrentIdentityQuery.class);
 }
 @BeforeEach void reset() throws Exception { db.restoreActiveFixtures(); db.assertEmptyContext(); }
 @AfterAll static void cleanup() throws Exception {
  try { if(spring!=null) spring.close(); } finally { if(db!=null) db.close(); }
 }

 @Test void realTransactionalIdentityQueryAlternatesAccountsOnTheSameConnection() {
  int pid=db.backendPid();
  for(int i=0;i<3;i++) {
   var first=identities.execute(db.accountA);
   assertEquals(db.accountA,first.accountId()); assertEquals(1,first.memberships().size());
   assertEquals(db.tenantA,first.memberships().getFirst().tenantId());
   assertEquals(db.unitA,first.memberships().getFirst().unitId()); db.assertEmptyContext();
   var second=identities.execute(db.accountB);
   assertEquals(db.accountB,second.accountId()); assertEquals(1,second.memberships().size());
   assertEquals(db.tenantB,second.memberships().getFirst().tenantId());
   assertEquals(db.unitB,second.memberships().getFirst().unitId()); db.assertEmptyContext();
   assertEquals(pid,db.backendPid(),"O ensaio deve reutilizar a mesma conexão física.");
  }
 }
 @Test void rollbackAfterSuspendedAccountDoesNotLeakActorToNextRequest() throws Exception {
  int pid=db.backendPid();
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountA);
  var error=assertThrows(BusinessProblem.class,()->identities.execute(db.accountA));
  assertEquals("ACCOUNT_INACTIVE",error.code()); db.assertEmptyContext();
  var next=identities.execute(db.accountB);
  assertEquals(db.accountB,next.accountId()); assertEquals(db.tenantB,next.memberships().getFirst().tenantId());
  assertEquals(pid,db.backendPid()); db.assertEmptyContext();
 }
 @Test void revokedMembershipIsReevaluatedEvenOnAnAlreadyReusedConnection() throws Exception {
  assertEquals(1,identities.execute(db.accountA).memberships().size());
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=?",db.tenantA,db.accountA);
  assertTrue(identities.execute(db.accountA).memberships().isEmpty()); db.assertEmptyContext();
  assertEquals(1,identities.execute(db.accountB).memberships().size()); db.assertEmptyContext();
 }
 @Test void runtimeRlsRejectsCrossTenantUuidAndContextDisappearsAfterCommitAndRollback() {
  int pid=db.backendPid();
  assertEquals(0,db.jdbc.queryForObject("select count(*) from cp.units",Integer.class));
  assertEquals(1,db.context(db.accountA,db.tenantA,null,()->db.jdbc.queryForObject(
   "select count(*) from cp.units where id in (?,?)",Integer.class,db.unitA,db.unitB)));
  assertEquals(0,db.context(db.accountA,db.tenantA,null,()->db.jdbc.queryForObject(
   "select count(*) from cp.units where id=?",Integer.class,db.unitB)));
  db.assertEmptyContext();
  assertThrows(IllegalStateException.class,()->db.context(db.accountA,db.tenantA,"a".repeat(64),()->{
   assertEquals(1,db.jdbc.queryForObject("select count(*) from cp.units where id=?",Integer.class,db.unitA));
   throw new IllegalStateException("Rollback sintético do ensaio.");
  }));
  db.assertEmptyContext();
  assertEquals(1,db.context(db.accountB,db.tenantB,null,()->db.jdbc.queryForObject(
   "select count(*) from cp.units where id=?",Integer.class,db.unitB)));
  db.assertEmptyContext(); assertEquals(pid,db.backendPid());
  assertEquals(0,db.jdbc.queryForObject("select count(*) from cp.units",Integer.class));
  assertEquals(0,db.jdbc.queryForObject("""
   select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
   where n.nspname in ('cp','platform') and c.relkind='r' and c.relname<>'flyway_schema_history'
    and (not c.relrowsecurity or not c.relforcerowsecurity)
   """,Integer.class));
 }
 @Test void validatedTenantExecutorAlternatesTenantsAndRollsBackWithoutPoolContextLeak() {
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager); int pid=db.backendPid();
  assertEquals(2,transactions.<Integer>execute(db.accountA,db.tenantA,context->{
   assertEquals(db.accountA,context.accountId()); assertEquals(db.tenantA,context.tenantId());
   context.requireBookableUnit(db.unitA);
   assertEquals("",db.jdbc.queryForObject("select current_setting('app.session_hash',true)",String.class));
   return db.jdbc.queryForObject("select count(*) from cp.units",Integer.class);
  }));
  db.assertEmptyContext();
  assertThrows(IllegalStateException.class,()->transactions.execute(db.accountA,db.tenantA,context->{
   throw new IllegalStateException("Rollback sintético da fronteira tenant.");
  }));
  db.assertEmptyContext();
  assertEquals(1,transactions.<Integer>execute(db.accountB,db.tenantB,context->{
   assertEquals(db.accountB,context.accountId()); assertEquals(db.tenantB,context.tenantId());
   context.requireBookableUnit(db.unitB);
   return db.jdbc.queryForObject("select count(*) from cp.units",Integer.class);
  }));
  db.assertEmptyContext(); assertEquals(pid,db.backendPid());
 }
 @Test void tenantExecutorRechecksRevocationBeforeExecutingActionOrExposingAnotherTenant() throws Exception {
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager); var called=new AtomicBoolean();
  var denied=assertThrows(BusinessProblem.class,()->transactions.execute(db.accountA,db.tenantB,context->{
   called.set(true); return null;
  }));
  assertEquals("TENANT_ACCESS_DENIED",denied.code()); assertFalse(called.get()); db.assertEmptyContext();
  assertEquals(db.tenantA,transactions.execute(db.accountA,db.tenantA,context->context.tenantId()));
  db.adminUpdate("update cp.memberships set status='REVOKED' where tenant_id=? and account_id=?",db.tenantA,db.accountA);
  denied=assertThrows(BusinessProblem.class,()->transactions.execute(db.accountA,db.tenantA,context->{
   called.set(true); return null;
  }));
  assertEquals("TENANT_ACCESS_DENIED",denied.code()); assertFalse(called.get()); db.assertEmptyContext();
  assertEquals(db.tenantB,transactions.execute(db.accountB,db.tenantB,context->context.tenantId())); db.assertEmptyContext();
 }
 @Test void staffCapabilitiesReachTheDatabaseContextOnlyForAal2ManagersAndNeverLeak() throws Exception {
  var transactions=new TenantTransactionExecutor(db.jdbc,db.manager); int pid=db.backendPid();
  db.adminUpdate("insert into cp.memberships(tenant_id,account_id,display_name,role,status) values(?,?,'GESTOR FICTICIO JDBC','MANAGER','ACTIVE')",
   db.tenantB,db.accountC);
  try {
   String elevated=transactions.execute(db.accountC,db.tenantB,AssuranceLevel.AAL2,context->{
    context.requireCapability(StaffCapability.MANAGE_UNITS);
    return db.jdbc.queryForObject("select current_setting('app.capabilities',true)",String.class);
   });
   assertEquals("amenities.manage,announcements.publish,area.manage,audit.read,finance.manage,memberships.manage,policy.edit,policy.publish,requests.manage,reservations.decide,security.log.read.tenant,units.manage",elevated);
   db.assertEmptyContext();
   String weak=transactions.execute(db.accountC,db.tenantB,AssuranceLevel.AAL1,
    context->db.jdbc.queryForObject("select coalesce(current_setting('app.capabilities',true),'')",String.class));
   assertEquals("",weak); db.assertEmptyContext();
   // Mesmo AAL2, um morador nao recebe capacidade de equipe no contexto do banco.
   String resident=transactions.execute(db.accountA,db.tenantA,AssuranceLevel.AAL2,
    context->db.jdbc.queryForObject("select coalesce(current_setting('app.capabilities',true),'')",String.class));
   assertEquals("",resident); db.assertEmptyContext();
   assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->transactions.execute(db.accountC,db.tenantB,
    AssuranceLevel.AAL1,context->{context.requireCapability(StaffCapability.MANAGE_UNITS);return null;})).code());
   db.assertEmptyContext(); assertEquals(pid,db.backendPid());
  } finally {
   db.adminUpdate("delete from cp.memberships where tenant_id=? and account_id=? and role='MANAGER'",db.tenantB,db.accountC);
  }
 }
 @Test void unitQueryLimitsResidentToOwnUnitsWithinTheTenantAndRejectsInactiveUnit() throws Exception {
  var units=new TenantUnitsQuery(new TenantTransactionExecutor(db.jdbc,db.manager),db.jdbc);
  // O RLS permite as duas unidades do tenantA; a query de produto deve revelar somente o vínculo próprio.
  var mine=units.execute(db.accountA,db.tenantA);
  assertEquals(1,mine.size()); assertEquals(db.unitA,mine.getFirst().id());
  assertNotEquals(db.otherUnitA,mine.getFirst().id()); db.assertEmptyContext();
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->units.execute(db.accountA,db.tenantB)).code()); db.assertEmptyContext();
  db.adminUpdate("update cp.units set status='INACTIVE' where id=?",db.unitA);
  assertEquals("TENANT_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->units.execute(db.accountA,db.tenantA)).code()); db.assertEmptyContext();
  assertEquals(db.unitB,units.execute(db.accountB,db.tenantB).getFirst().id()); db.assertEmptyContext();
 }

 @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
 static class TransactionConfiguration {}
}
