package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecoveryServiceTest {
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID FACTOR=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
 private static final String LINK="pkce_synthetic0123456789abcdef";
 private static final String PASSWORD="Senha-sintética-longa-1";
 private final RecoveryStore store=mock(RecoveryStore.class);
 private final SupabaseAuthClient auth=mock(SupabaseAuthClient.class);
 private final JwtDecoder decoder=mock(JwtDecoder.class);
 private final TokenCipher cipher=new TokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
 private final Instant now=Instant.parse("2030-01-01T12:00:00Z");
 private final List<Duration> sleeps=new ArrayList<>();
 private static final RecoveryService.SecondFactorGate OPEN=account->{};
 private RecoveryService service() {
  return service(Duration.ofMillis(1200),task->{ task.run(); return CompletableFuture.completedFuture(null); });
 }
 private RecoveryService service(Duration minimum,RecoveryService.Dispatcher dispatcher) {
  return new RecoveryService(store,auth,cipher,decoder,Clock.fixed(now,ZoneOffset.UTC),minimum,()->Duration.ZERO,dispatcher,sleeps::add);
 }
 private Jwt jwt(String token,UUID account,String aal) {
  var builder=Jwt.withTokenValue(token).header("alg","ES256").subject(account.toString()).issuedAt(now).expiresAt(now.plusSeconds(600));
  if(aal!=null) builder.claim("aal",aal);
  return builder.build();
 }
 private RecoveryStore.Attempt attempt(String raw,boolean mfa,String access) {
  String hash=SessionSecrets.hash(raw);
  return new RecoveryStore.Attempt(UUID.randomUUID(),hash,ACCOUNT,cipher.seal(access,RecoveryStore.Attempt.contextFor(ACCOUNT,hash)),
   mfa?FACTOR:null,mfa,0,now.plusSeconds(600),0);
 }
 private String claimed(boolean mfa) {
  String raw=SessionSecrets.newToken();
  when(store.claim(eq(SessionSecrets.hash(raw)),any())).thenReturn(attempt(raw,mfa,"synthetic-recovery-access"));
  when(decoder.decode("synthetic-recovery-access")).thenReturn(jwt("synthetic-recovery-access",ACCOUNT,"aal1"));
  when(store.accountActive(ACCOUNT)).thenReturn(true);
  return raw;
 }

 @Test void requestIsNeutralForEveryProviderOutcomeAndKeepsMinimumDuration() {
  for(var outcome:SupabaseAuthClient.RecoveryDispatch.values()) {
   when(auth.recover("pessoa@example.invalid")).thenReturn(outcome);
   service().request("  Pessoa@Example.invalid ");
  }
  verify(auth,times(SupabaseAuthClient.RecoveryDispatch.values().length)).recover("pessoa@example.invalid");
  assertEquals(SupabaseAuthClient.RecoveryDispatch.values().length,sleeps.size());
  assertTrue(sleeps.stream().allMatch(Duration.ofMillis(1200)::equals));
  verifyNoInteractions(store);
 }
 @Test void malformedEmailIsRejectedBeforeProvider() {
  for(String email:new String[]{null,"","sem-arroba","a@b@c","x".repeat(250)+"@example.invalid"}) {
   assertEquals("RECOVERY_REQUEST_INVALID",assertThrows(BusinessProblem.class,()->service().request(email)).code());
  }
  verifyNoInteractions(auth,store); assertTrue(sleeps.isEmpty());
 }
 @Test void verifyStoresSealedAccessWithoutMfaWhenNoFactorIsVerified() {
  when(auth.verifyRecovery(LINK)).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-recovery-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-recovery-access")).thenReturn(jwt("synthetic-recovery-access",ACCOUNT,"aal1"));
  when(auth.recoveryFactors("synthetic-recovery-access")).thenReturn(new SupabaseAuthClient.RecoveryFactors(ACCOUNT,null,false));
  when(store.create(any(),anyString(),eq(ACCOUNT),anyString(),isNull(),eq(false),eq(10))).thenReturn(now.plusSeconds(600));
  var ticket=service().verify(LINK);
  assertFalse(ticket.mfaRequired()); assertEquals(now.plusSeconds(600),ticket.expiresAt());
  assertTrue(ticket.opaqueToken().matches("[A-Za-z0-9_-]{43}"));
  assertFalse(ticket.toString().contains(ticket.opaqueToken()));
  var sealed=org.mockito.ArgumentCaptor.forClass(String.class); var hash=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(store).create(any(),hash.capture(),eq(ACCOUNT),sealed.capture(),isNull(),eq(false),eq(10));
  assertEquals(SessionSecrets.hash(ticket.opaqueToken()),hash.getValue());
  assertFalse(sealed.getValue().contains("synthetic-recovery-access"));
  assertEquals("synthetic-recovery-access",cipher.open(sealed.getValue(),RecoveryStore.Attempt.contextFor(ACCOUNT,hash.getValue())));
  verify(auth,never()).signOutBestEffort(any());
 }
 @Test void verifiedTotpFactorMakesSecondFactorMandatory() {
  when(auth.verifyRecovery(LINK)).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-recovery-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-recovery-access")).thenReturn(jwt("synthetic-recovery-access",ACCOUNT,"aal1"));
  when(auth.recoveryFactors("synthetic-recovery-access")).thenReturn(new SupabaseAuthClient.RecoveryFactors(ACCOUNT,FACTOR,false));
  when(store.create(any(),anyString(),eq(ACCOUNT),anyString(),eq(FACTOR),eq(true),eq(10))).thenReturn(now.plusSeconds(600));
  assertTrue(service().verify(LINK).mfaRequired());
 }
 @Test void malformedLinkNeverReachesProviderAndInactiveAccountEndsProviderSession() {
  for(String link:new String[]{null,"curto","com espaço 0123456789","x".repeat(513)})
   assertEquals("RECOVERY_LINK_INVALID",assertThrows(BusinessProblem.class,()->service().verify(link)).code());
  verifyNoInteractions(auth);
  when(auth.verifyRecovery(LINK)).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-recovery-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-recovery-access")).thenReturn(jwt("synthetic-recovery-access",ACCOUNT,"aal1"));
  when(auth.recoveryFactors("synthetic-recovery-access")).thenReturn(new SupabaseAuthClient.RecoveryFactors(ACCOUNT,null,false));
  when(store.create(any(),anyString(),any(),anyString(),any(),anyBoolean(),anyInt()))
   .thenThrow(new BusinessProblem("ACCOUNT_INACTIVE",403,"Seu acesso precisa ser validado pela gestão."));
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,()->service().verify(LINK)).code());
  verify(auth).signOutBestEffort("synthetic-recovery-access");
 }
 @Test void invalidJwtFromProviderFailsClosedAndSignsOut() {
  when(auth.verifyRecovery(LINK)).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-recovery-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-recovery-access")).thenThrow(new BadJwtException("synthetic"));
  assertEquals("AUTH_PROVIDER_UNAVAILABLE",assertThrows(BusinessProblem.class,()->service().verify(LINK)).code());
  verify(auth).signOutBestEffort("synthetic-recovery-access"); verifyNoInteractions(store);
 }
 @Test void weakPasswordIsRejectedBeforeTouchingTheAttempt() {
  for(String password:new String[]{null,"curta-11","aaaaaaaaaaaaaaaa","abababababababab","ç".repeat(40),"x".repeat(73)}) {
   assertEquals("PASSWORD_INVALID",assertThrows(BusinessProblem.class,
    ()->service().complete(SessionSecrets.newToken(),password,null,OPEN)).code(),String.valueOf(password));
  }
  verifyNoInteractions(store,auth);
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete("forjado",PASSWORD,null,OPEN)).code());
 }
 @Test void successChangesPasswordEndsProviderAndWebSessionsAndRemovesAttempt() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  when(auth.signOutGlobal("synthetic-recovery-access")).thenReturn(true);
  assertTrue(service().complete(raw,PASSWORD,null,OPEN).otherSessionsEnded());
  var order=inOrder(store,auth);
  order.verify(store).claim(eq(hash),any());
  order.verify(auth).updatePassword("synthetic-recovery-access",PASSWORD,ACCOUNT);
  order.verify(store).remove(hash);
  order.verify(auth).signOutGlobal("synthetic-recovery-access");
  order.verify(store).purgeSessions(ACCOUNT);
  verify(auth,never()).verifyTotp(any(),any(),any());
 }
 @Test void providerSignOutFailureStillPurgesWebSessionsAndIsReported() {
  String raw=claimed(false);
  when(auth.signOutGlobal("synthetic-recovery-access")).thenReturn(false);
  assertFalse(service().complete(raw,PASSWORD,null,OPEN).otherSessionsEnded());
  verify(store).purgeSessions(ACCOUNT);
 }
 @Test void rejectedPasswordCountsFailureAndKeepsAttemptForAnotherTry() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  doThrow(new BusinessProblem("PASSWORD_REJECTED",422,"Recusada.")).when(auth).updatePassword("synthetic-recovery-access",PASSWORD,ACCOUNT);
  when(store.fail(eq(hash),any())).thenReturn(4);
  assertEquals("PASSWORD_REJECTED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).fail(eq(hash),any()); verify(store,never()).remove(any()); verify(store,never()).purgeSessions(any());
  verify(auth,never()).signOutGlobal(any()); verify(auth,never()).signOutBestEffort(any());
 }
 @Test void lastRejectedTryEndsTheLinkSession() {
  String raw=claimed(false);
  doThrow(new BusinessProblem("PASSWORD_REJECTED",422,"Recusada.")).when(auth).updatePassword(any(),any(),any());
  when(store.fail(anyString(),any())).thenReturn(0);
  assertEquals("RECOVERY_ATTEMPTS_EXHAUSTED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(auth).signOutBestEffort("synthetic-recovery-access");
 }
 @Test void uncertainProviderOutcomeEndsAttemptAndSessionsWithoutClaimingSuccess() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  doThrow(new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Indisponível.")).when(auth).updatePassword(any(),any(),any());
  var error=assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN));
  assertEquals("RECOVERY_OUTCOME_UNKNOWN",error.code()); assertEquals(503,error.status());
  verify(store).remove(hash); verify(auth).signOutGlobal("synthetic-recovery-access"); verify(store).purgeSessions(ACCOUNT);
  verify(store,never()).fail(any(),any());
 }
 @Test void secondFactorIsRequiredBeforePasswordAndCodeRejectionCountsFailure() {
  String raw=claimed(true); String hash=SessionSecrets.hash(raw);
  var missing=assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN));
  assertEquals("MFA_CODE_REQUIRED",missing.code()); verify(store).release(eq(hash),any());
  when(auth.verifyTotp("synthetic-recovery-access",FACTOR,"000000"))
   .thenThrow(new BusinessProblem("MFA_CODE_REJECTED",422,"Código inválido."));
  when(store.fail(eq(hash),any())).thenReturn(4);
  assertEquals("MFA_CODE_REJECTED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,"000000",OPEN)).code());
  verify(store).fail(eq(hash),any());
  verify(auth,never()).updatePassword(any(),any(),any());
 }
 @Test void verifiedSecondFactorElevatesAttemptAndPasswordUsesAal2Access() {
  String raw=claimed(true); String hash=SessionSecrets.hash(raw);
  when(auth.verifyTotp("synthetic-recovery-access",FACTOR,"123456"))
   .thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-aal2-access","synthetic-aal2-refresh",3600));
  when(decoder.decode("synthetic-aal2-access")).thenReturn(jwt("synthetic-aal2-access",ACCOUNT,"aal2"));
  when(auth.signOutGlobal("synthetic-aal2-access")).thenReturn(true);
  assertTrue(service().complete(raw,PASSWORD,"123456",OPEN).otherSessionsEnded());
  var sealed=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(store).elevate(eq(hash),any(),sealed.capture());
  assertEquals("synthetic-aal2-access",cipher.open(sealed.getValue(),RecoveryStore.Attempt.contextFor(ACCOUNT,hash)));
  verify(auth).updatePassword("synthetic-aal2-access",PASSWORD,ACCOUNT);
 }
 @Test void secondFactorFromAnotherAccountOrWithoutAal2FailsClosed() {
  String raw=claimed(true);
  when(auth.verifyTotp(any(),any(),any())).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-aal1-again","synthetic-refresh",3600));
  when(decoder.decode("synthetic-aal1-again")).thenReturn(jwt("synthetic-aal1-again",ACCOUNT,"aal1"));
  assertEquals("AUTH_PROVIDER_UNAVAILABLE",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,"123456",OPEN)).code());
  verify(auth).signOutBestEffort("synthetic-aal1-again"); verify(store).remove(SessionSecrets.hash(raw));
  verify(auth,never()).updatePassword(any(),any(),any());
 }
 @Test void expiredLinkSessionIsDiscardedWithoutPasswordChange() {
  String raw=SessionSecrets.newToken();
  when(store.claim(eq(SessionSecrets.hash(raw)),any())).thenReturn(attempt(raw,false,"synthetic-expired-access"));
  when(decoder.decode("synthetic-expired-access")).thenThrow(new BadJwtException("expired"));
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).remove(SessionSecrets.hash(raw)); verify(auth,never()).updatePassword(any(),any(),any());
 }
 @Test void attemptSealedForAnotherAccountCannotBeOpened() {
  String raw=SessionSecrets.newToken(); String hash=SessionSecrets.hash(raw);
  UUID other=UUID.fromString("22222222-2222-4222-8222-222222222222");
  var foreign=new RecoveryStore.Attempt(UUID.randomUUID(),hash,ACCOUNT,
   cipher.seal("synthetic-recovery-access",RecoveryStore.Attempt.contextFor(other,hash)),null,false,0,now.plusSeconds(600),0);
  when(store.claim(eq(hash),any())).thenReturn(foreign);
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).remove(hash); verifyNoInteractions(auth);
 }

 @Test void requestAnswersAtTheFixedDeadlineEvenWithSlowOrSaturatedProvider() {
  long started=System.nanoTime();
  service(Duration.ofMillis(50),task->new CompletableFuture<>()).request("pessoa@example.invalid");
  assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(2))<0,"Provedor lento não segura a resposta");
  service(Duration.ofMillis(50),task->{ throw new RejectedExecutionException("cheia"); }).request("pessoa@example.invalid");
  assertEquals(List.of(Duration.ofMillis(50),Duration.ofMillis(50)),sleeps,"Fila cheia ou provedor lento: mesmo prazo");
  verifyNoInteractions(auth,store);
 }
 @Test void otherVerifiedFactorTypeBlocksRecoveryAndEndsLinkSession() {
  when(auth.verifyRecovery(LINK)).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-recovery-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-recovery-access")).thenReturn(jwt("synthetic-recovery-access",ACCOUNT,"aal1"));
  when(auth.recoveryFactors("synthetic-recovery-access")).thenReturn(new SupabaseAuthClient.RecoveryFactors(ACCOUNT,FACTOR,true));
  assertEquals("RECOVERY_FACTOR_UNSUPPORTED",assertThrows(BusinessProblem.class,()->service().verify(LINK)).code());
  verify(auth).signOutBestEffort("synthetic-recovery-access"); verifyNoInteractions(store);
 }
 @Test void providerUserFromAnotherAccountFailsClosed() {
  when(auth.verifyRecovery(LINK)).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-recovery-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-recovery-access")).thenReturn(jwt("synthetic-recovery-access",ACCOUNT,"aal1"));
  when(auth.recoveryFactors("synthetic-recovery-access"))
   .thenReturn(new SupabaseAuthClient.RecoveryFactors(UUID.fromString("22222222-2222-4222-8222-222222222222"),null,false));
  assertEquals("AUTH_PROVIDER_UNAVAILABLE",assertThrows(BusinessProblem.class,()->service().verify(LINK)).code());
  verify(auth).signOutBestEffort("synthetic-recovery-access"); verifyNoInteractions(store);
 }
 @Test void accountSuspendedAfterTheLinkEndsAttemptWithoutPasswordChange() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  when(store.accountActive(ACCOUNT)).thenReturn(false);
  assertEquals("ACCOUNT_INACTIVE",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).remove(hash); verify(auth).signOutBestEffort("synthetic-recovery-access");
  verify(auth,never()).updatePassword(any(),any(),any()); verify(store,never()).purgeSessions(any());
 }
 @Test void accountCheckFailureReleasesTheLeaseWithoutEndingTheAttempt() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  when(store.accountActive(ACCOUNT)).thenThrow(new org.springframework.dao.QueryTimeoutException("sintético"));
  assertEquals("RECOVERY_DEPENDENCY_UNAVAILABLE",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).release(eq(hash),any()); verify(store,never()).remove(any()); verifyNoInteractions(auth);
 }
 @Test void secondFactorUsesThePerAccountLimitBeforeTheProvider() {
  String raw=claimed(true); String hash=SessionSecrets.hash(raw);
  var gated=new AtomicInteger();
  RecoveryService.SecondFactorGate limited=account->{
   assertEquals(ACCOUNT,account); gated.incrementAndGet();
   throw new BusinessProblem("AUTH_RATE_LIMITED",429,"Aguarde antes de tentar novamente.");
  };
  assertEquals("AUTH_RATE_LIMITED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,"123456",limited)).code());
  assertEquals(1,gated.get()); verify(store).release(eq(hash),any());
  verify(auth,never()).verifyTotp(any(),any(),any()); verify(store,never()).fail(any(),any()); verify(store,never()).remove(any());
 }
 @Test void failureThatCannotBeCountedEndsTheAttempt() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  doThrow(new BusinessProblem("PASSWORD_REJECTED",422,"Recusada.")).when(auth).updatePassword(any(),any(),any());
  when(store.fail(eq(hash),any())).thenThrow(new org.springframework.dao.QueryTimeoutException("sintético"));
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).remove(hash); verify(auth).signOutBestEffort("synthetic-recovery-access");
 }
 @Test void elevatedAccessForAnotherAccountFailsClosed() {
  String raw=claimed(true);
  when(auth.verifyTotp(any(),any(),any())).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-foreign-aal2","synthetic-refresh",3600));
  when(decoder.decode("synthetic-foreign-aal2"))
   .thenReturn(jwt("synthetic-foreign-aal2",UUID.fromString("22222222-2222-4222-8222-222222222222"),"aal2"));
  assertEquals("AUTH_PROVIDER_UNAVAILABLE",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,"123456",OPEN)).code());
  verify(auth).signOutBestEffort("synthetic-foreign-aal2"); verify(store,never()).elevate(any(),any(),any());
  verify(auth,never()).updatePassword(any(),any(),any());
 }
 @Test void storedAccessForAnotherAccountIsDiscarded() {
  String raw=claimed(false);
  when(decoder.decode("synthetic-recovery-access"))
   .thenReturn(jwt("synthetic-recovery-access",UUID.fromString("22222222-2222-4222-8222-222222222222"),"aal1"));
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).remove(SessionSecrets.hash(raw)); verify(auth,never()).updatePassword(any(),any(),any());
 }
 @Test void lostLeaseWhileElevatingDiscardsTheElevatedSession() {
  String raw=claimed(true); String hash=SessionSecrets.hash(raw);
  when(auth.verifyTotp(any(),any(),any())).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-aal2-access","synthetic-refresh",3600));
  when(decoder.decode("synthetic-aal2-access")).thenReturn(jwt("synthetic-aal2-access",ACCOUNT,"aal2"));
  doThrow(new BusinessProblem("RECOVERY_SESSION_EXPIRED",401,"Expirada.")).when(store).elevate(eq(hash),any(),anyString());
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,"123456",OPEN)).code());
  verify(auth).signOutBestEffort("synthetic-aal2-access"); verify(auth,never()).updatePassword(any(),any(),any());
 }
 @Test void expiredProviderSessionDuringChangeEndsWithoutPurgingOrClaimingSuccess() {
  String raw=claimed(false); String hash=SessionSecrets.hash(raw);
  doThrow(new BusinessProblem("RECOVERY_SESSION_EXPIRED",401,"Expirada.")).when(auth).updatePassword(any(),any(),any());
  assertEquals("RECOVERY_SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().complete(raw,PASSWORD,null,OPEN)).code());
  verify(store).remove(hash); verify(auth).signOutBestEffort("synthetic-recovery-access");
  verify(store,never()).purgeSessions(any()); verify(auth,never()).signOutGlobal(any());
 }
}
