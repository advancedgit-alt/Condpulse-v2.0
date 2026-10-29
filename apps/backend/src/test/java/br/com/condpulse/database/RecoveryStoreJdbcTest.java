package br.com.condpulse.database;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import br.com.condpulse.identity.bff.RecoveryStore;
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
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/** V009 com PostgreSQL real: tentativa de recuperação ligada ao hash do cookie e encerramento das sessões da conta. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK",matches="DISPOSABLE")
class RecoveryStoreJdbcTest {
 private static JdbcDisposableDatabase db;
 private static RecoveryStore recovery;
 private static WebSessionStore sessions;
 private static TokenCipher cipher;

 @BeforeAll static void setup() throws Exception {
  db=new JdbcDisposableDatabase(); recovery=new RecoveryStore(db.jdbc,db.tx); sessions=new WebSessionStore(db.jdbc,db.tx);
  byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
  cipher=new TokenCipher(Base64.getEncoder().encodeToString(key)); java.util.Arrays.fill(key,(byte)0);
 }
 @BeforeEach void reset() throws Exception {
  db.restoreActiveFixtures(); db.deleteSessions(); db.deleteRecoveryAttempts(); db.assertEmptyContext();
 }
 @AfterAll static void cleanup() throws Exception { if(db!=null) db.close(); }

 private String attempt(UUID account,boolean mfa) {
  String hash=SessionSecrets.hash(SessionSecrets.newToken());
  Instant expires=recovery.create(UUID.randomUUID(),hash,account,
   cipher.seal("SYNTHETIC_RECOVERY_ACCESS",RecoveryStore.Attempt.contextFor(account,hash)),mfa?UUID.randomUUID():null,mfa,10);
  assertTrue(expires.isAfter(Instant.now().plusSeconds(500)) && expires.isBefore(Instant.now().plusSeconds(700)));
  db.assertEmptyContext();
  return hash;
 }
 private StoredWebSession session(UUID account) {
  String hash=SessionSecrets.hash(SessionSecrets.newToken());
  var session=new StoredWebSession(UUID.randomUUID(),hash,account,cipher.seal("SYNTHETIC_ACCESS",account+"/"+hash+"/access"),
   cipher.seal("SYNTHETIC_REFRESH",account+"/"+hash+"/refresh"),Instant.now().plusSeconds(300),Instant.now().plusSeconds(3600),0);
  sessions.create(session); db.assertEmptyContext(); return session;
 }

 @Test void attemptIsReadableOnlyThroughItsOwnHashAndNeverAsWebSession() throws Exception {
  String hash=attempt(db.accountA,false);
  assertEquals(1,db.recoveryCount(hash));
  // Mesma role runtime, outro hash: nenhuma linha visível.
  assertEquals(0,db.context(db.accountA,null,null,()->db.jdbc.queryForObject(
   "select count(*) from platform.recovery_attempts",Integer.class)));
  var stolen=assertThrows(BusinessProblem.class,()->sessions.load(hash));
  assertEquals("SESSION_EXPIRED",stolen.code(),"Hash de recuperação não abre sessão da API");
  db.assertEmptyContext();
 }
 @Test void inactiveAccountCannotStartRecoveryAndActorMustMatchAccount() throws Exception {
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountB);
  var inactive=assertThrows(BusinessProblem.class,()->recovery.create(UUID.randomUUID(),SessionSecrets.hash(SessionSecrets.newToken()),
   db.accountB,"v1.synthetic",null,false,10));
  assertEquals("ACCOUNT_INACTIVE",inactive.code()); db.assertEmptyContext();
  String forged=SessionSecrets.hash(SessionSecrets.newToken());
  assertThrows(DataAccessException.class,()->db.context(db.accountA,null,null,()->{
   db.jdbc.queryForObject("select set_config('app.recovery_hash',?,true)",String.class,forged);
   return db.jdbc.update("""
    insert into platform.recovery_attempts(id,token_hash,account_id,encrypted_access_token,mfa_required,expires_at)
    values(?,?,?,'v1.synthetic',false,clock_timestamp()+interval '10 minutes')
    """,UUID.randomUUID(),forged,db.accountC);
  }),"RLS exige que a tentativa pertença ao ator publicado");
  assertEquals(0,db.recoveryCount(forged)); db.assertEmptyContext();
 }
 @Test void leaseBlocksConcurrentCompletionAndFailuresExhaustTheAttempt() throws Exception {
  String hash=attempt(db.accountA,true);
  UUID lease=UUID.randomUUID();
  var claimed=recovery.claim(hash,lease);
  assertTrue(claimed.mfaRequired()); assertEquals(db.accountA,claimed.accountId()); assertNotNull(claimed.factorId());
  assertEquals("RECOVERY_IN_PROGRESS",assertThrows(BusinessProblem.class,()->recovery.claim(hash,UUID.randomUUID())).code());
  recovery.elevate(hash,lease,cipher.seal("SYNTHETIC_AAL2",RecoveryStore.Attempt.contextFor(db.accountA,hash)));
  assertEquals(4,recovery.fail(hash,lease)); db.assertEmptyContext();
  var again=recovery.claim(hash,UUID.randomUUID());
  assertFalse(again.mfaRequired(),"Segundo fator confirmado não é pedido de novo na mesma tentativa");
  assertEquals("SYNTHETIC_AAL2",cipher.open(again.encryptedAccessToken(),again.cipherContext()));
  db.adminUpdate("update platform.recovery_attempts set lease_id=null,lease_until=null where token_hash=?",hash);
  for(int remaining=3;remaining>=0;remaining--) {
   UUID next=UUID.randomUUID(); recovery.claim(hash,next);
   assertEquals(remaining,recovery.fail(hash,next));
  }
  assertEquals(0,db.recoveryCount(hash),"Tentativas esgotadas removem a linha");
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->recovery.claim(hash,UUID.randomUUID())).code());
  db.assertEmptyContext();
 }
 @Test void interruptedOrExpiredAttemptIsRemovedInsteadOfReused() throws Exception {
  String stale=attempt(db.accountA,false);
  db.adminUpdate("update platform.recovery_attempts set lease_id=?,lease_until=clock_timestamp()-interval '1 second' where token_hash=?",
   UUID.randomUUID(),stale);
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->recovery.claim(stale,UUID.randomUUID())).code());
  assertEquals(0,db.recoveryCount(stale),"Reserva vencida: resultado incerto não é repetido");
  String expired=attempt(db.accountA,false);
  db.adminUpdate("update platform.recovery_attempts set created_at=clock_timestamp()-interval '11 minutes',"
   +"expires_at=clock_timestamp()-interval '1 minute' where token_hash=?",expired);
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->recovery.claim(expired,UUID.randomUUID())).code());
  assertEquals(0,db.recoveryCount(expired)); db.assertEmptyContext();
 }
 @Test void suspendedAccountIsSeenBeforeCompletionAndExpiredLeaseCannotElevate() throws Exception {
  String hash=attempt(db.accountA,true);
  UUID lease=UUID.randomUUID();
  recovery.claim(hash,lease);
  assertTrue(recovery.accountActive(db.accountA)); db.assertEmptyContext();
  db.adminUpdate("update platform.accounts set status='SUSPENDED' where id=?",db.accountA);
  assertFalse(recovery.accountActive(db.accountA),"Suspensão depois do link é vista antes da troca"); db.assertEmptyContext();
  db.adminUpdate("update platform.recovery_attempts set lease_until=clock_timestamp()-interval '1 second' where token_hash=?",hash);
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->recovery.elevate(hash,lease,
   cipher.seal("SYNTHETIC_AAL2",RecoveryStore.Attempt.contextFor(db.accountA,hash)))).code(),"Reserva vencida não guarda acesso elevado");
  db.assertEmptyContext();
 }
 @Test void purgeRemovesEveryWebSessionOfTheAccountOnlyInsideRecovery() throws Exception {
  var first=session(db.accountA); var second=session(db.accountA); var other=session(db.accountB);
  // Fora da recuperação, nem o próprio ator lê ou remove sessões por conta.
  assertEquals(0,db.context(db.accountA,null,null,()->db.jdbc.update(
   "delete from platform.web_sessions where account_id=?",db.accountA)));
  assertEquals(1,db.sessionCount(first.tokenHash()));
  assertEquals(2,recovery.purgeSessions(db.accountA)); db.assertEmptyContext();
  assertEquals(0,db.sessionCount(first.tokenHash())); assertEquals(0,db.sessionCount(second.tokenHash()));
  assertEquals(1,db.sessionCount(other.tokenHash()),"Sessões de outra conta permanecem");
 }
}
