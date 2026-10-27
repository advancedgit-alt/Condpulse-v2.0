package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
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
}
