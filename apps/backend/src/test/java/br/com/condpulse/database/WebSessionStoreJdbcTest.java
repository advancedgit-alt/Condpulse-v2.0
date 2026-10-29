package br.com.condpulse.database;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import br.com.condpulse.identity.bff.SessionSecrets;
import br.com.condpulse.identity.bff.StoredWebSession;
import br.com.condpulse.identity.bff.TokenCipher;
import br.com.condpulse.identity.bff.WebSessionStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Store de produção com transações JDBC reais; sem cliente Auth nem tokens reais. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class WebSessionStoreJdbcTest {
 private static JdbcDisposableDatabase db;
 private static WebSessionStore sessions;
 private static TokenCipher cipher;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(); sessions=new WebSessionStore(db.jdbc,db.tx);
  byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
  cipher=new TokenCipher(Base64.getEncoder().encodeToString(key)); java.util.Arrays.fill(key,(byte)0);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.deleteSessions(); db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private StoredWebSession create(UUID account) {
  String hash=SessionSecrets.hash(SessionSecrets.newToken());
  String context=account+"/"+hash+"/";
  var session=new StoredWebSession(UUID.randomUUID(),hash,account,
   cipher.seal("SYNTHETIC_ACCESS_FOR_JDBC_TEST_ONLY",context+"access"),
   cipher.seal("SYNTHETIC_REFRESH_FOR_JDBC_TEST_ONLY",context+"refresh"),
   Instant.now().plusSeconds(300),Instant.now().plusSeconds(3600),0);
  sessions.create(session); db.assertEmptyContext(); return session;
 }
 @Test void sessionsFromDifferentAccountsRemainSeparatedOnOnePhysicalConnection() {
  int pid=db.backendPid(); var a=create(db.accountA); var b=create(db.accountB);
  assertEquals(db.accountA,sessions.load(a.tokenHash()).accountId()); db.assertEmptyContext();
  assertEquals(db.accountB,sessions.load(b.tokenHash()).accountId()); db.assertEmptyContext();
  var missing=assertThrows(BusinessProblem.class,()->sessions.load(SessionSecrets.hash(SessionSecrets.newToken())));
  assertEquals("SESSION_EXPIRED",missing.code()); db.assertEmptyContext();
  assertEquals(0,db.jdbc.queryForObject("select count(*) from platform.web_sessions",Integer.class));
  assertEquals(pid,db.backendPid());
 }
 @Test void activeLeaseRejectsSecondRefreshWhileOriginalCasCanComplete() {
  var a=create(db.accountA); UUID lease=UUID.randomUUID();
  assertEquals(0,sessions.claimRefresh(a.tokenHash(),lease).version()); db.assertEmptyContext();
  var conflict=assertThrows(BusinessProblem.class,()->sessions.claimRefresh(a.tokenHash(),UUID.randomUUID()));
  assertEquals(409,conflict.status()); assertEquals("SESSION_REFRESH_IN_PROGRESS",conflict.code()); db.assertEmptyContext();
  String access=cipher.seal("SYNTHETIC_ROTATED_ACCESS",a.cipherContext("access"));
  String refresh=cipher.seal("SYNTHETIC_ROTATED_REFRESH",a.cipherContext("refresh"));
  assertTrue(sessions.completeRefresh(a.tokenHash(),lease,0,access,refresh,Instant.now().plusSeconds(600)));
  var changed=sessions.load(a.tokenHash()); assertEquals(1,changed.version());
  assertEquals("SYNTHETIC_ROTATED_ACCESS",cipher.open(changed.encryptedAccessToken(),a.cipherContext("access")));
  assertFalse(sessions.completeRefresh(a.tokenHash(),lease,0,access,refresh,Instant.now().plusSeconds(600)));
  db.assertEmptyContext();
 }
 @Test void crashWithExpiredLeaseCommitsDeletionBeforeReturning401AndCannotResurrect() throws Exception {
  var a=create(db.accountA); var b=create(db.accountB); UUID lease=UUID.randomUUID();
  sessions.claimRefresh(a.tokenHash(),lease);
  db.adminUpdate("update platform.web_sessions set refresh_lease_until=now()-interval '1 second' where id=?",a.id());
  var error=assertThrows(BusinessProblem.class,()->sessions.claimRefresh(a.tokenHash(),UUID.randomUUID()));
  assertEquals(401,error.status()); assertEquals("SESSION_EXPIRED",error.code()); db.assertEmptyContext();
  // Esta leitura usa outra conexão e outra transação: um DELETE revertido não passa.
  assertEquals(0,db.sessionCount(a.tokenHash()));
  assertFalse(sessions.completeRefresh(a.tokenHash(),lease,0,a.encryptedAccessToken(),a.encryptedRefreshToken(),Instant.now().plusSeconds(600)));
  assertThrows(BusinessProblem.class,()->sessions.load(a.tokenHash()));
  assertEquals(db.accountB,sessions.load(b.tokenHash()).accountId()); db.assertEmptyContext();
 }
 @Test void delayedCompletionAfterLogoutDoesNotRecreateSession() throws Exception {
  var a=create(db.accountA); UUID lease=UUID.randomUUID(); sessions.claimRefresh(a.tokenHash(),lease);
  sessions.remove(a.tokenHash()); db.assertEmptyContext();
  assertFalse(sessions.completeRefresh(a.tokenHash(),lease,0,a.encryptedAccessToken(),a.encryptedRefreshToken(),Instant.now().plusSeconds(600)));
  assertEquals(0,db.sessionCount(a.tokenHash())); db.assertEmptyContext();
 }
 @Test void absoluteExpiryPreventsLoadClaimAndDelayedCompletion() throws Exception {
  var a=create(db.accountA); UUID lease=UUID.randomUUID(); sessions.claimRefresh(a.tokenHash(),lease);
  db.adminUpdate("""
   update platform.web_sessions set created_at=now()-interval '2 days',expires_at=now()-interval '1 day'
   where id=?
   """,a.id());
  assertEquals("SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->sessions.load(a.tokenHash())).code());
  assertEquals("SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->sessions.claimRefresh(a.tokenHash(),UUID.randomUUID())).code());
  assertFalse(sessions.completeRefresh(a.tokenHash(),lease,0,a.encryptedAccessToken(),a.encryptedRefreshToken(),Instant.now().plusSeconds(600)));
  db.assertEmptyContext();
 }
 @Test void suspendedAccountFailsClosedAndNextAccountDoesNotInheritItsContext() throws Exception {
  var a=create(db.accountA); var b=create(db.accountB); int pid=db.backendPid();
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountA);
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,()->sessions.load(a.tokenHash())).code());
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,()->sessions.claimRefresh(a.tokenHash(),UUID.randomUUID())).code());
  db.assertEmptyContext(); assertEquals(db.accountB,sessions.load(b.tokenHash()).accountId());
  db.assertEmptyContext(); assertEquals(pid,db.backendPid());
 }
 private StoredWebSession next(UUID account,java.time.Instant expiresAt) {
  String hash=SessionSecrets.hash(SessionSecrets.newToken());
  return new StoredWebSession(UUID.randomUUID(),hash,account,
   cipher.seal("SYNTHETIC_AAL2_ACCESS",account+"/"+hash+"/access"),cipher.seal("SYNTHETIC_AAL2_REFRESH",account+"/"+hash+"/refresh"),
   Instant.now().plusSeconds(300),expiresAt,0);
 }
 @Test void replaceSwapsLeasedSessionAtomicallyAndRejectsLostLease() {
  var a=create(db.accountA); UUID lease=UUID.randomUUID();
  sessions.claimRefresh(a.tokenHash(),lease); db.assertEmptyContext();
  var elevated=next(db.accountA,a.expiresAt());
  var lost=assertThrows(BusinessProblem.class,()->sessions.replace(a.tokenHash(),UUID.randomUUID(),elevated));
  assertEquals("SESSION_EXPIRED",lost.code()); db.assertEmptyContext();
  assertEquals("SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->sessions.load(elevated.tokenHash())).code());
  assertEquals(db.accountA,sessions.load(a.tokenHash()).accountId());
  sessions.replace(a.tokenHash(),lease,elevated); db.assertEmptyContext();
  assertEquals("SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->sessions.load(a.tokenHash())).code());
  var loaded=sessions.load(elevated.tokenHash());
  assertEquals(db.accountA,loaded.accountId()); assertEquals(a.expiresAt().getEpochSecond(),loaded.expiresAt().getEpochSecond());
  db.assertEmptyContext();
 }
 @Test void failedInsertDuringReplaceRollsBackRemovalOfTheLeasedSession() {
  var a=create(db.accountA); var other=create(db.accountA); UUID lease=UUID.randomUUID();
  sessions.claimRefresh(a.tokenHash(),lease);
  // Hash repetido viola UNIQUE depois do DELETE; a transação inteira precisa ser desfeita.
  var clash=new StoredWebSession(UUID.randomUUID(),other.tokenHash(),db.accountA,other.encryptedAccessToken(),
   other.encryptedRefreshToken(),other.accessExpiresAt(),other.expiresAt(),0);
  assertThrows(RuntimeException.class,()->sessions.replace(a.tokenHash(),lease,clash)); db.assertEmptyContext();
  assertEquals(db.accountA,sessions.load(a.tokenHash()).accountId());
  assertEquals(409,assertThrows(BusinessProblem.class,()->sessions.claimRefresh(a.tokenHash(),UUID.randomUUID())).status());
  db.assertEmptyContext();
 }
 @Test void releaseRequiresTheOwnLeaseAndKeepsTokensUntouched() {
  var a=create(db.accountA); UUID lease=UUID.randomUUID();
  sessions.claimRefresh(a.tokenHash(),lease);
  sessions.releaseRefresh(a.tokenHash(),UUID.randomUUID()); db.assertEmptyContext();
  assertEquals(409,assertThrows(BusinessProblem.class,()->sessions.claimRefresh(a.tokenHash(),UUID.randomUUID())).status());
  sessions.releaseRefresh(a.tokenHash(),lease); db.assertEmptyContext();
  var again=sessions.claimRefresh(a.tokenHash(),UUID.randomUUID());
  assertEquals(a.encryptedAccessToken(),again.encryptedAccessToken()); assertEquals(0,again.version());
  db.assertEmptyContext();
 }
}
