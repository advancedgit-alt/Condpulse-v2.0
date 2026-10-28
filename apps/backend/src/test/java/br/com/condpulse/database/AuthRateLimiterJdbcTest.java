package br.com.condpulse.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.identity.bff.AuthRateLimiter;
import br.com.condpulse.identity.bff.AuthRateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/** Quota/concorrencia/RLS reais. O helper so aceita o banco CI descartavel. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class AuthRateLimiterJdbcTest {
 private static JdbcDisposableDatabase db;
 private static final List<String> keys=new ArrayList<>();
 @BeforeAll static void setup() throws Exception {
  assertEquals("DISPOSABLE",System.getenv("CP_DB_TEST_OK"));
  assertEquals("condpulse_ci",System.getenv("PGDATABASE"));
  db=new JdbcDisposableDatabase(4);
 }
 @AfterAll static void cleanup() throws Exception {
  if(db==null) return;
  try { for(String key:keys) db.adminUpdate("delete from platform.auth_rate_limits where bucket_key=?",key); }
  finally { db.close(); }
 }
 private static String key() {
  String value=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
  keys.add(value); return value;
 }
 private static AuthRateLimiter.Bucket bucket(String key,int limit,int seconds) { return new AuthRateLimiter.Bucket(key,limit,seconds); }
 private static AuthRateStore store() { return new AuthRateStore(db.jdbc,db.manager); }
 private static java.sql.Connection admin() throws Exception {
  return java.sql.DriverManager.getConnection("jdbc:postgresql://"+System.getenv("PGHOST")+":"+System.getenv("PGPORT")+"/condpulse_ci",System.getenv("PGUSER"),System.getenv("PGPASSWORD"));
 }
 private static long count(String key) throws Exception {
  try(var connection=admin();
      var statement=connection.prepareStatement("select coalesce(sum(attempts),0) from platform.auth_rate_limits where bucket_key=?")) {
   statement.setString(1,key); try(var rows=statement.executeQuery()) { rows.next(); return rows.getLong(1); }
  }
 }
 @Test void concurrentInstancesShareExactlyFiveAdmissionsAndPersistAllDenials() throws Exception {
  String global=key(),subject=key(); var buckets=List.of(bucket(global,600,60),bucket(subject,5,900));
  var start=new CountDownLatch(1); var first=store(); var second=store();
  try(var workers=Executors.newFixedThreadPool(12)) {
   var futures=new ArrayList<java.util.concurrent.Future<AuthRateLimiter.Admission>>();
   for(int index=0;index<12;index++) {
    var limiter=index%2==0?first:second;
    futures.add(workers.submit(()->{ start.await(); return limiter.admit(buckets); }));
   }
   start.countDown(); var outcomes=new ArrayList<AuthRateLimiter.Admission>();
   for(var future:futures) outcomes.add(future.get(15,TimeUnit.SECONDS));
   assertEquals(5,outcomes.stream().filter(AuthRateLimiter.Admission::allowed).count());
   assertEquals(7,outcomes.stream().filter(value->!value.allowed()).count());
   assertTrue(outcomes.stream().filter(value->!value.allowed()).allMatch(value->value.retryAfterSeconds()>=1 && value.retryAfterSeconds()<=900));
  }
  assertEquals(12,count(global)); assertEquals(6,count(subject));
  assertEquals("",db.jdbc.queryForObject("select coalesce(current_setting('app.auth_rate_key',true),'')",String.class));
  db.assertEmptyContext();
 }
 @Test void globalGatePreventsCreatingUnlimitedSubjectBuckets() throws Exception {
  String global=key(); var limiter=store(); var subjects=new ArrayList<String>();
  for(int index=0;index<12;index++) {
   String subject=key(); subjects.add(subject);
   var result=limiter.admit(List.of(bucket(global,2,60),bucket(subject,5,900)));
   assertEquals(index<2,result.allowed());
  }
  assertEquals(3,count(global));
  long created=0; for(String subject:subjects) if(count(subject)>0) created++;
  assertEquals(2,created);
 }
 @Test void expiredWindowAdmitsAgainWithoutWaitingOrChangingActiveQuota() throws Exception {
  String global=key(),subject=key(); var limiter=store(); var buckets=List.of(bucket(global,600,60),bucket(subject,1,60));
  assertTrue(limiter.admit(buckets).allowed()); assertFalse(limiter.admit(buckets).allowed());
  db.adminUpdate("update platform.auth_rate_limits set window_started_at=clock_timestamp()-interval '61 seconds',expires_at=clock_timestamp()-interval '1 second' where bucket_key=?",subject);
  assertTrue(limiter.admit(buckets).allowed()); assertEquals(1,count(subject)); assertEquals(3,count(global));
 }
 @Test void denialCommitsOutsideRolledBackBusinessTransaction() throws Exception {
  String global=key(),subject=key(); var limiter=store(); var buckets=List.of(bucket(global,600,60),bucket(subject,1,60));
  assertTrue(limiter.admit(buckets).allowed());
  assertThrows(IllegalStateException.class,()->db.tx.execute(status->{
   assertFalse(limiter.admit(buckets).allowed()); throw new IllegalStateException("synthetic rollback");
  }));
  assertEquals(2,count(subject)); assertEquals(2,count(global)); db.assertEmptyContext();
 }
 @Test void runtimeCannotReadOrDeleteAnotherActiveBucketOrWriteWithoutContext() throws Exception {
  String global=key(),subject=key(); assertTrue(store().admit(List.of(bucket(global,600,60),bucket(subject,1,60))).allowed());
  assertEquals(0,db.jdbc.queryForObject("select count(*) from platform.auth_rate_limits where bucket_key=?",Integer.class,subject).intValue());
  assertEquals(0,db.jdbc.update("delete from platform.auth_rate_limits where bucket_key=?",subject));
  assertThrows(DataAccessException.class,()->db.jdbc.update("insert into platform.auth_rate_limits values(?,clock_timestamp(),1,clock_timestamp()+interval '60 seconds')",key()));
  assertThrows(DataAccessException.class,()->db.jdbc.update("update platform.auth_rate_limits set bucket_key=? where bucket_key=?",key(),subject));
  assertFalse(db.jdbc.queryForObject("select has_table_privilege('cp_worker','platform.auth_rate_limits','SELECT')",Boolean.class));
  assertFalse(db.jdbc.queryForObject("select exists(select 1 from pg_class c cross join lateral aclexplode(c.relacl) a where c.oid='platform.auth_rate_limits'::regclass and a.grantee=0)",Boolean.class));
  assertTrue(db.jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid='platform.auth_rate_limits'::regclass",Boolean.class));
  assertEquals(1,count(subject));
  String expired=key();
  db.adminUpdate("insert into platform.auth_rate_limits values(?,clock_timestamp()-interval '61 seconds',1,clock_timestamp()-interval '1 second')",expired);
  assertThrows(DataAccessException.class,()->db.jdbc.update("update platform.auth_rate_limits set attempts=2 where bucket_key=?",expired));
 }
 @Test void garbageCollectionDeletesAtMostSixteenExpiredRowsAndNeverAnActiveRow() throws Exception {
  // Isolar o inventario desta fixture de janelas vencidas de outro caso da classe.
  for(String previous:List.copyOf(keys)) db.adminUpdate("delete from platform.auth_rate_limits where bucket_key=?",previous);
  var expired=new ArrayList<String>();
  for(int index=0;index<40;index++) {
   String key=key(); expired.add(key);
   db.adminUpdate("insert into platform.auth_rate_limits values(?,clock_timestamp()-interval '61 seconds',1,clock_timestamp()-interval '1 second')",key);
  }
  String global=key(),subject=key(); assertTrue(store().admit(List.of(bucket(global,600,60),bucket(subject,5,60))).allowed());
  long retained=0; for(String key:expired) if(count(key)>0) retained++;
  assertEquals(24,retained); assertEquals(1,count(global)); assertEquals(1,count(subject));
 }
 @Test void garbageCollectionSkipsLockedExpiredBucketsInsteadOfBlockingQuota() throws Exception {
  String expired=key(),global=key(),subject=key();
  db.adminUpdate("insert into platform.auth_rate_limits values(?,clock_timestamp()-interval '61 seconds',1,clock_timestamp()-interval '1 second')",expired);
  try(var guard=admin()) {
   guard.setAutoCommit(false);
   try(var statement=guard.prepareStatement("select bucket_key from platform.auth_rate_limits where bucket_key=? for update")) {
    statement.setString(1,expired); try(var rows=statement.executeQuery()) { assertTrue(rows.next()); }
   }
   try(var executor=Executors.newSingleThreadExecutor()) {
    var result=executor.submit(()->store().admit(List.of(bucket(global,600,60),bucket(subject,5,60))));
    assertTrue(result.get(3,TimeUnit.SECONDS).allowed(),"GC nao pode esperar pela linha expirada travada.");
   } finally { guard.rollback(); }
  }
  assertEquals(1,count(expired)); assertEquals(1,count(global)); assertEquals(1,count(subject));
 }
}
