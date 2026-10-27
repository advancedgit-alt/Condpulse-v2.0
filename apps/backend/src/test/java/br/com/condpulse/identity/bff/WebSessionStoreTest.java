package br.com.condpulse.identity.bff;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebSessionStoreTest {
 private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 private final String hash="a".repeat(64);
 private final UUID account=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private final UUID lease=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private final List<String> events=new ArrayList<>();
 private final AbstractPlatformTransactionManager manager=new AbstractPlatformTransactionManager() {
  @Override protected Object doGetTransaction() { return new Object(); }
  @Override protected void doBegin(Object transaction,TransactionDefinition definition) { events.add("begin"); }
  @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
  @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
 };
 @SuppressWarnings("unchecked")
 private void candidate(UUID previousLease,boolean active) throws Exception {
  ResultSet r=mock(ResultSet.class);
  when(r.getObject("id",UUID.class)).thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
  when(r.getString("token_hash")).thenReturn(hash);
  when(r.getObject("account_id",UUID.class)).thenReturn(account);
  when(r.getString("encrypted_access_token")).thenReturn("synthetic-encrypted-access");
  when(r.getString("encrypted_refresh_token")).thenReturn("synthetic-encrypted-refresh");
  when(r.getTimestamp("access_expires_at")).thenReturn(Timestamp.from(Instant.parse("2030-01-01T01:00:00Z")));
  when(r.getTimestamp("expires_at")).thenReturn(Timestamp.from(Instant.parse("2030-01-01T08:00:00Z")));
  when(r.getLong("version")).thenReturn(0L);
  when(r.getObject("refresh_lease_id",UUID.class)).thenReturn(previousLease);
  when(r.getBoolean("lease_active")).thenReturn(active);
  doAnswer(invocation->{
   RowMapper<?> mapper=invocation.getArgument(1);
   return List.of(mapper.mapRow(r,0));
  }).when(jdbc).query(contains("lease_active"),any(RowMapper.class),eq(hash));
  when(jdbc.queryForObject(contains("count(*)"),eq(Integer.class),eq(account))).thenReturn(1);
 }
 @Test void expiredLeaseDeletionCommitsBeforeReauthenticationErrorIsThrown() throws Exception {
  candidate(lease,false);
  when(jdbc.update(startsWith("delete from platform.web_sessions"),eq(hash))).thenAnswer(invocation->{
   events.add("delete"); return 1;
  });
  var store=new WebSessionStore(jdbc,new TransactionTemplate(manager));
  var error=assertThrows(BusinessProblem.class,()->store.claimRefresh(hash,UUID.randomUUID()));
  assertEquals("SESSION_EXPIRED",error.code());
  assertEquals(List.of("begin","delete","commit"),events);
  verify(jdbc,never()).update(startsWith("update platform.web_sessions"),any(UUID.class),eq(hash),anyLong());
 }
 @Test void activeLeaseReturnsConflictAndDoesNotDeleteTheSession() throws Exception {
  candidate(lease,true);
  var store=new WebSessionStore(jdbc,new TransactionTemplate(manager));
  var error=assertThrows(BusinessProblem.class,()->store.claimRefresh(hash,UUID.randomUUID()));
  assertEquals(409,error.status()); assertEquals("SESSION_REFRESH_IN_PROGRESS",error.code());
  assertEquals(List.of("begin","rollback"),events);
  verify(jdbc,never()).update(startsWith("delete from platform.web_sessions"),eq(hash));
 }
 @Test void sessionWithoutPreviousLeaseCanBeClaimed() throws Exception {
  candidate(null,false);
  UUID newLease=UUID.randomUUID();
  when(jdbc.update(contains("refresh_lease_id is null"),eq(newLease),eq(hash),eq(0L))).thenReturn(1);
  var store=new WebSessionStore(jdbc,new TransactionTemplate(manager));
  var result=store.claimRefresh(hash,newLease);
  assertEquals(account,result.accountId()); assertEquals(List.of("begin","commit"),events);
  verify(jdbc,never()).update(startsWith("delete from platform.web_sessions"),eq(hash));
 }
}
