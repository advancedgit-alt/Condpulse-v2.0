package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebSessionServiceTest {
 private final WebSessionStore store=mock(WebSessionStore.class);
 private final SupabaseAuthClient auth=mock(SupabaseAuthClient.class);
 private final JwtDecoder decoder=mock(JwtDecoder.class);
 private final TokenCipher cipher=new TokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
 private final Instant now=Instant.parse("2030-01-01T00:00:00Z");
 private final BffSettings settings=new BffSettings("https://app.example.invalid","https://auth.example.invalid/auth/v1","synthetic-publishable",8);
 private WebSessionService service() { return new WebSessionService(store,auth,cipher,decoder,settings,Clock.fixed(now,ZoneOffset.UTC)); }
 private StoredWebSession session(String raw) {
  UUID account=UUID.fromString("11111111-1111-4111-8111-111111111111");
  String hash=SessionSecrets.hash(raw);
  String context=account+"/"+hash;
  return new StoredWebSession(UUID.randomUUID(),hash,account,
   cipher.seal("synthetic-access",context+"/access"),cipher.seal("synthetic-refresh",context+"/refresh"),
   now.plusSeconds(60),now.plusSeconds(3600),0);
 }
 private Jwt jwt(UUID account) {
  return Jwt.withTokenValue("synthetic-new-access").header("alg","ES256").subject(account.toString())
   .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
 }
 @Test void concurrentRefreshLoserDoesNotCallProvider() {
  String raw=SessionSecrets.newToken();
  when(store.claimRefresh(eq(SessionSecrets.hash(raw)),any())).thenThrow(
   new BusinessProblem("SESSION_REFRESH_IN_PROGRESS",409,"Aguarde."));
  var ex=assertThrows(BusinessProblem.class,()->service().refresh(raw));
  assertEquals(409,ex.status()); verifyNoInteractions(auth);
 }
 @Test void failedRefreshInvalidatesLocalSessionWithoutRetry() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.claimRefresh(eq(row.tokenHash()),any())).thenReturn(row);
  when(auth.refresh("synthetic-refresh")).thenThrow(new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Indisponível."));
  assertThrows(BusinessProblem.class,()->service().refresh(raw));
  verify(auth,times(1)).refresh("synthetic-refresh"); verify(store).remove(row.tokenHash());
 }
 @Test void expiredUncertainLeaseNeverReusesProviderRefreshToken() {
  String raw=SessionSecrets.newToken();
  when(store.claimRefresh(eq(SessionSecrets.hash(raw)),any())).thenThrow(
   new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente."));
  var ex=assertThrows(BusinessProblem.class,()->service().refresh(raw));
  assertEquals("SESSION_EXPIRED",ex.code()); verifyNoInteractions(auth);
 }
 @Test void logoutDeletesDurableSessionBeforeProviderCall() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.load(row.tokenHash())).thenReturn(row);
  service().logout(raw);
  var order=inOrder(store,auth); order.verify(store).load(row.tokenHash()); order.verify(store).remove(row.tokenHash());
  order.verify(auth).signOutBestEffort("synthetic-access");
 }
 @Test void refreshCannotResurrectSessionRemovedByLogout() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.claimRefresh(eq(row.tokenHash()),any())).thenReturn(row);
  when(auth.refresh("synthetic-refresh")).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-new-access","synthetic-new-refresh",600));
  when(decoder.decode("synthetic-new-access")).thenReturn(jwt(row.accountId()));
  when(store.completeRefresh(eq(row.tokenHash()),any(),eq(0L),anyString(),anyString(),any())).thenReturn(false);
  assertThrows(BusinessProblem.class,()->service().refresh(raw));
  verify(store).remove(row.tokenHash()); verify(auth).signOutBestEffort("synthetic-new-access");
 }
 @Test void providerCannotChangeAccountDuringRefresh() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.claimRefresh(eq(row.tokenHash()),any())).thenReturn(row);
  when(auth.refresh("synthetic-refresh")).thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-new-access","synthetic-new-refresh",600));
  when(decoder.decode("synthetic-new-access")).thenReturn(jwt(UUID.fromString("22222222-2222-4222-8222-222222222222")));
  assertThrows(BusinessProblem.class,()->service().refresh(raw));
  verify(store,never()).completeRefresh(anyString(),any(),anyLong(),anyString(),anyString(),any());
  verify(store).remove(row.tokenHash());
 }
 private static final String FACTOR="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
 private Jwt jwt(UUID account,String token,String aal) {
  return Jwt.withTokenValue(token).header("alg","ES256").subject(account.toString()).claim("aal",aal)
   .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
 }
 private StoredWebSession claimed(String raw) {
  var row=session(raw);
  when(store.claimRefresh(eq(row.tokenHash()),any())).thenReturn(row);
  when(decoder.decode("synthetic-access")).thenReturn(jwt(row.accountId(),"synthetic-access","aal1"));
  return row;
 }
 @Test void totpVerificationAtomicallyReplacesWithAal2SessionKeepingAbsoluteExpiry() {
  String raw=SessionSecrets.newToken(); var row=claimed(raw);
  when(auth.verifyTotp("synthetic-access",UUID.fromString(FACTOR),"123456"))
   .thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-aal2","synthetic-refresh-2",600));
  when(decoder.decode("synthetic-aal2")).thenReturn(jwt(row.accountId(),"synthetic-aal2","aal2"));
  var ticket=service().verifyTotp(raw,FACTOR,"123456");
  assertNotEquals(raw,ticket.opaqueToken()); assertEquals(row.expiresAt(),ticket.expiresAt());
  var lease=org.mockito.ArgumentCaptor.forClass(UUID.class);
  var created=org.mockito.ArgumentCaptor.forClass(StoredWebSession.class);
  var order=inOrder(store,auth);
  order.verify(store).claimRefresh(eq(row.tokenHash()),lease.capture());
  order.verify(auth).verifyTotp("synthetic-access",UUID.fromString(FACTOR),"123456");
  order.verify(store).replace(eq(row.tokenHash()),eq(lease.getValue()),created.capture());
  var stored=created.getValue();
  assertEquals(SessionSecrets.hash(ticket.opaqueToken()),stored.tokenHash());
  assertEquals(row.accountId(),stored.accountId()); assertEquals(row.expiresAt(),stored.expiresAt());
  assertEquals("synthetic-aal2",cipher.open(stored.encryptedAccessToken(),stored.cipherContext("access")));
  assertEquals("synthetic-refresh-2",cipher.open(stored.encryptedRefreshToken(),stored.cipherContext("refresh")));
  verify(store,never()).remove(anyString()); verify(store,never()).releaseRefresh(anyString(),any());
  verify(auth,never()).signOutBestEffort(anyString());
 }
 @Test void malformedCodeOrFactorNeverCallsProviderOrTouchesSession() {
  String raw=SessionSecrets.newToken();
  for(String code:new String[]{null,"","12345","1234567","12a456"}) {
   var error=assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,code));
   assertEquals("MFA_CODE_REJECTED",error.code());
  }
  var factor=assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,"not-a-factor","123456"));
  assertEquals("MFA_FACTOR_NOT_FOUND",factor.code());
  verifyNoInteractions(auth,store);
 }
 @Test void definitiveRejectionReleasesTheLeaseAndPreservesTheSession() {
  for(String code:new String[]{"MFA_CODE_REJECTED","MFA_FACTOR_NOT_FOUND","MFA_OPERATION_REJECTED","AUTH_RATE_LIMITED"}) {
   reset(store,auth,decoder);
   String raw=SessionSecrets.newToken(); var row=claimed(raw);
   when(auth.verifyTotp(anyString(),any(),anyString())).thenThrow(new BusinessProblem(code,422,"Recusado."));
   assertEquals(code,assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"000000")).code());
   verify(store).releaseRefresh(eq(row.tokenHash()),any());
   verify(store,never()).remove(anyString()); verify(store,never()).replace(anyString(),any(),any());
   verify(auth,never()).signOutBestEffort(anyString());
  }
 }
 @Test void uncertainProviderResultClosesLocalAndProviderSessionWithoutRetry() {
  String raw=SessionSecrets.newToken(); var row=claimed(raw);
  when(auth.verifyTotp(anyString(),any(),anyString()))
   .thenThrow(new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Indisponível."));
  assertEquals(503,assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"123456")).status());
  verify(auth,times(1)).verifyTotp(anyString(),any(),anyString());
  verify(store).remove(row.tokenHash()); verify(auth).signOutBestEffort("synthetic-access");
  verify(store,never()).releaseRefresh(anyString(),any()); verify(store,never()).replace(anyString(),any(),any());
 }
 @Test void providerTokenWithoutAal2OrForAnotherAccountIsDiscardedEverywhere() {
  String raw=SessionSecrets.newToken(); var row=claimed(raw);
  when(auth.verifyTotp(anyString(),any(),anyString()))
   .thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-weak","synthetic-refresh-2",600));
  when(decoder.decode("synthetic-weak")).thenReturn(jwt(row.accountId(),"synthetic-weak","aal1"));
  assertEquals(503,assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"123456")).status());
  when(decoder.decode("synthetic-weak")).thenReturn(jwt(UUID.fromString("22222222-2222-4222-8222-222222222222"),"synthetic-weak","aal2"));
  assertEquals(503,assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"123456")).status());
  verify(store,never()).replace(anyString(),any(),any());
  verify(store,times(2)).remove(row.tokenHash()); verify(auth,times(2)).signOutBestEffort("synthetic-weak");
 }
 @Test void lostLeaseDuringReplaceDiscardsOldAndNewSessions() {
  String raw=SessionSecrets.newToken(); var row=claimed(raw);
  when(auth.verifyTotp(anyString(),any(),anyString()))
   .thenReturn(new SupabaseAuthClient.TokenResponse("synthetic-aal2","synthetic-refresh-2",600));
  when(decoder.decode("synthetic-aal2")).thenReturn(jwt(row.accountId(),"synthetic-aal2","aal2"));
  doThrow(new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente.")).when(store).replace(anyString(),any(),any());
  assertEquals("SESSION_EXPIRED",assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"123456")).code());
  verify(store).remove(row.tokenHash()); verify(auth).signOutBestEffort("synthetic-aal2");
 }
 @Test void concurrentRefreshBlocksVerificationBeforeProviderCall() {
  String raw=SessionSecrets.newToken();
  when(store.claimRefresh(eq(SessionSecrets.hash(raw)),any()))
   .thenThrow(new BusinessProblem("SESSION_REFRESH_IN_PROGRESS",409,"Aguarde."));
  assertEquals(409,assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"123456")).status());
  verifyNoInteractions(auth);
 }
 @Test void expiredAccessTokenReleasesLeaseAndAsksForRefreshBeforeVerification() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.claimRefresh(eq(row.tokenHash()),any())).thenReturn(row);
  when(decoder.decode("synthetic-access")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("expired"));
  assertEquals("SESSION_REFRESH_REQUIRED",assertThrows(BusinessProblem.class,()->service().verifyTotp(raw,FACTOR,"123456")).code());
  verify(store).releaseRefresh(eq(row.tokenHash()),any()); verifyNoInteractions(auth);
 }
 @Test void expiredAccessTokenAsksForRefreshBeforeMfa() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.load(row.tokenHash())).thenReturn(row);
  when(decoder.decode("synthetic-access")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("expired"));
  var error=assertThrows(BusinessProblem.class,()->service().mfaStatus(raw));
  assertEquals("SESSION_REFRESH_REQUIRED",error.code()); assertEquals(401,error.status());
  verifyNoInteractions(auth); verify(store,never()).remove(anyString());
 }
 @Test void mfaStatusReportsAssuranceFromTheValidatedSessionToken() {
  String raw=SessionSecrets.newToken(); var row=session(raw);
  when(store.load(row.tokenHash())).thenReturn(row);
  when(decoder.decode("synthetic-access")).thenReturn(jwt(row.accountId(),"synthetic-access","aal2"));
  var factor=new SupabaseAuthClient.Factor(UUID.fromString(FACTOR),"VERIFIED","CondPulse 1");
  when(auth.totpFactors("synthetic-access")).thenReturn(List.of(factor));
  var status=service().mfaStatus(raw);
  assertEquals(AssuranceLevel.AAL2,status.assuranceLevel()); assertEquals(List.of(factor),status.factors());
 }
}
