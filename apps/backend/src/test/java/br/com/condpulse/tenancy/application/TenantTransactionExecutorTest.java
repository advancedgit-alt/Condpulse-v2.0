package br.com.condpulse.tenancy.application;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TenantTransactionExecutorTest {
 private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 private final UUID account=UUID.randomUUID(),tenant=UUID.randomUUID(),unit=UUID.randomUUID();
 private final List<String> events=new ArrayList<>();
 private final AbstractPlatformTransactionManager manager=new AbstractPlatformTransactionManager() {
  @Override protected Object doGetTransaction() { return new Object(); }
  @Override protected void doBegin(Object transaction,TransactionDefinition definition) {
   assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
   assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,definition.getIsolationLevel());
   assertEquals(5,definition.getTimeout()); assertFalse(definition.isReadOnly());
   events.add("begin");
  }
  @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
  @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
 };
 @SuppressWarnings("unchecked")
 private void allow(String timeZone,List<TenantContext.Membership> memberships) {
  when(jdbc.queryForObject(contains("count(*)"),eq(Integer.class),eq(account))).thenReturn(1);
  doReturn(List.of(timeZone)).when(jdbc).query(startsWith("select time_zone"),any(RowMapper.class),eq(tenant));
  doReturn(memberships).when(jdbc).query(contains("from cp.memberships"),any(RowMapper.class),eq(tenant),eq(account));
 }
 private List<TenantContext.Membership> memberships() {
  return List.of(new TenantContext.Membership(UUID.randomUUID(),unit,"RESIDENT",false,false));
 }
 @Test void validatesAndSetsLocalContextBeforeInvokingBusinessCallback() {
  allow("America/Sao_Paulo",memberships());
  var executor=new TenantTransactionExecutor(jdbc,manager);
  String value=executor.execute(account,tenant,context->{
   assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
   assertEquals(account,context.accountId()); assertEquals(tenant,context.tenantId());
   assertEquals(ZoneId.of("America/Sao_Paulo"),context.timeZone());
   context.requireBookableUnit(unit); return "done";
  });
  assertEquals("done",value); assertEquals(List.of("begin","commit"),events);
  var order=inOrder(jdbc);
  order.verify(jdbc).queryForObject("select set_config('app.session_hash','',true)",String.class);
  order.verify(jdbc).queryForObject("select set_config('app.tenant_id','',true)",String.class);
  order.verify(jdbc).queryForObject("select set_config('app.actor_id',?,true)",String.class,account.toString());
  order.verify(jdbc).queryForObject(contains("count(*)"),eq(Integer.class),eq(account));
  order.verify(jdbc).query(startsWith("select time_zone"),any(RowMapper.class),eq(tenant));
  order.verify(jdbc).queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  order.verify(jdbc).query(contains("from cp.memberships"),any(RowMapper.class),eq(tenant),eq(account));
 }
 private List<TenantContext.Membership> manager() {
  return List.of(new TenantContext.Membership(UUID.randomUUID(),null,"MANAGER",false,false));
 }
 @Test void aal2ManagerPublishesSortedCapabilitiesOnlyAfterMembershipValidation() {
  allow("America/Sao_Paulo",manager());
  var executor=new TenantTransactionExecutor(jdbc,manager);
  executor.execute(account,tenant,AssuranceLevel.AAL2,context->{
   assertEquals(AssuranceLevel.AAL2,context.assurance());
   context.requireCapability(StaffCapability.MANAGE_UNITS); return null;
  });
  var order=inOrder(jdbc);
  order.verify(jdbc).queryForObject("select set_config('app.capabilities','',true)",String.class);
  order.verify(jdbc).query(contains("from cp.memberships"),any(RowMapper.class),eq(tenant),eq(account));
  order.verify(jdbc).queryForObject("select set_config('app.capabilities',?,true)",String.class,
   "amenities.manage,announcements.publish,area.manage,audit.read,finance.manage,memberships.manage,requests.manage,reservations.decide,units.manage");
  assertEquals(List.of("begin","commit"),events);
 }
 @Test void aal1ManagerNeverPublishesCapabilitiesToTheDatabaseContext() {
  allow("America/Sao_Paulo",manager());
  var error=assertThrows(BusinessProblem.class,()->new TenantTransactionExecutor(jdbc,manager)
   .execute(account,tenant,AssuranceLevel.AAL1,context->{context.requireCapability(StaffCapability.MANAGE_UNITS);return null;}));
  assertEquals("MFA_REQUIRED",error.code()); assertEquals(List.of("begin","rollback"),events);
  verify(jdbc).queryForObject("select set_config('app.capabilities','',true)",String.class);
  verify(jdbc,never()).queryForObject(eq("select set_config('app.capabilities',?,true)"),eq(String.class),any(Object[].class));
 }
 @Test void residentialCallsDefaultToAal1WithoutCapabilities() {
  allow("America/Sao_Paulo",memberships());
  new TenantTransactionExecutor(jdbc,manager).execute(account,tenant,context->{
   assertEquals(AssuranceLevel.AAL1,context.assurance()); assertTrue(context.effectiveCapabilities().isEmpty()); return null;
  });
  verify(jdbc,never()).queryForObject(eq("select set_config('app.capabilities',?,true)"),eq(String.class),any(Object[].class));
 }
 @Test void inactiveAccountStopsBeforeTenantReadAndRollsBack() {
  when(jdbc.queryForObject(contains("count(*)"),eq(Integer.class),eq(account))).thenReturn(0);
  var callback=new AtomicBoolean();
  var error=assertThrows(BusinessProblem.class,()->new TenantTransactionExecutor(jdbc,manager)
   .execute(account,tenant,context->{callback.set(true);return null;}));
  assertEquals("ACCOUNT_INACTIVE",error.code()); assertFalse(callback.get());
  assertEquals(List.of("begin","rollback"),events);
  verify(jdbc,never()).query(startsWith("select time_zone"),any(RowMapper.class),eq(tenant));
 }
 @Test void tenantWithoutActiveMembershipDoesNotInvokeBusinessCallback() {
  allow("America/Sao_Paulo",List.of());
  var callback=new AtomicBoolean();
  var error=assertThrows(BusinessProblem.class,()->new TenantTransactionExecutor(jdbc,manager)
   .execute(account,tenant,context->{callback.set(true);return null;}));
  assertEquals("TENANT_ACCESS_DENIED",error.code()); assertEquals(404,error.status());
  assertFalse(callback.get()); assertEquals(List.of("begin","rollback"),events);
 }
 @Test void invalidTenantTimeZoneFailsClosedBeforeBusinessCallback() {
  allow("invalid/time-zone",memberships());
  var callback=new AtomicBoolean();
  var error=assertThrows(BusinessProblem.class,()->new TenantTransactionExecutor(jdbc,manager)
   .execute(account,tenant,context->{callback.set(true);return null;}));
  assertEquals("TENANT_CONFIGURATION_INVALID",error.code()); assertEquals(503,error.status());
  assertFalse(callback.get()); assertEquals(List.of("begin","rollback"),events);
 }
 @Test void businessFailureRollsBackAndDoesNotCommit() {
  allow("America/Sao_Paulo",memberships());
  assertThrows(IllegalStateException.class,()->new TenantTransactionExecutor(jdbc,manager)
   .execute(account,tenant,context->{throw new IllegalStateException("synthetic");}));
  assertEquals(List.of("begin","rollback"),events);
 }
 @Test void missingIdentifiersAreRejectedWithoutStartingTransaction() {
  var error=assertThrows(BusinessProblem.class,()->new TenantTransactionExecutor(jdbc,manager)
   .execute(account,null,context->null));
  assertEquals(404,error.status()); assertTrue(events.isEmpty()); verifyNoInteractions(jdbc);
 }
}
