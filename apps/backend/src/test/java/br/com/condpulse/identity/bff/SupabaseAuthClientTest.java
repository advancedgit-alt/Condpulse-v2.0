package br.com.condpulse.identity.bff;

import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SupabaseAuthClientTest {
 private BffSettings settings() {
  return new BffSettings("https://app.example.invalid","https://auth.example.invalid/auth/v1","synthetic-publishable",8);
 }
 @Test void passwordRequestUsesProviderAndNeverReturnsTokensInToString() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/token?grant_type=password"))
   .andExpect(method(HttpMethod.POST)).andExpect(header("apikey","synthetic-publishable"))
   .andRespond(withSuccess("{\"access_token\":\"synthetic-access\",\"refresh_token\":\"synthetic-refresh\",\"expires_in\":3600}",MediaType.APPLICATION_JSON));
  var result=new SupabaseAuthClient(builder.build(),settings()).password("synthetic@example.invalid","synthetic-password");
  assertEquals("synthetic-access",result.access_token());
  assertFalse(result.toString().contains("synthetic-access"));
  server.verify();
 }
 @Test void providerRejectionDoesNotExposeProviderBody() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/token?grant_type=password"))
   .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("sensitive-provider-detail"));
  var client=new SupabaseAuthClient(builder.build(),settings());
  var ex=assertThrows(BusinessProblem.class,()->client.password("synthetic@example.invalid","synthetic-password"));
  assertEquals(401,ex.status()); assertFalse(ex.getMessage().contains("sensitive-provider-detail"));
 }
 @Test void refreshUsesRefreshGrantAndRateLimitRemainsExplicit() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/token?grant_type=refresh_token"))
   .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
  var client=new SupabaseAuthClient(builder.build(),settings());
  var ex=assertThrows(BusinessProblem.class,()->client.refresh("synthetic-refresh"));
  assertEquals(429,ex.status());
 }
 private static final String FACTOR="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
 private static final String CHALLENGE="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
 @Test void realisticProviderTokenPayloadWithExtraFieldsIsAccepted() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/token?grant_type=password"))
   .andRespond(withSuccess("""
    {"access_token":"synthetic-access","token_type":"bearer","expires_in":3600,"expires_at":1893456000,
     "refresh_token":"synthetic-refresh","user":{"id":"11111111-1111-4111-8111-111111111111","aud":"authenticated",
     "factors":[]},"weak_password":null}
    """,MediaType.APPLICATION_JSON));
  var result=new SupabaseAuthClient(builder.build(),settings()).password("synthetic@example.invalid","synthetic-password");
  assertEquals("synthetic-refresh",result.refresh_token()); assertEquals(3600,result.expires_in());
  server.verify();
 }
 @Test void factorsAreReadWithUserTokenAndOnlyTotpIsExposed() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/user")).andExpect(method(HttpMethod.GET))
   .andExpect(header("apikey","synthetic-publishable")).andExpect(header("Authorization","Bearer synthetic-access"))
   .andRespond(withSuccess("""
    {"id":"11111111-1111-4111-8111-111111111111","email":"synthetic@example.invalid","factors":[
     {"id":"%s","factor_type":"totp","status":"verified","friendly_name":"CondPulse 1","created_at":"2026-10-27T12:00:00Z"},
     {"id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","factor_type":"phone","status":"verified"}]}
    """.formatted(FACTOR),MediaType.APPLICATION_JSON));
  var factors=new SupabaseAuthClient(builder.build(),settings()).totpFactors("synthetic-access");
  assertEquals(1,factors.size()); assertEquals(UUID.fromString(FACTOR),factors.getFirst().factorId());
  assertEquals("VERIFIED",factors.getFirst().status()); assertFalse(factors.toString().contains("synthetic@example"));
  server.verify();
 }
 @Test void userWithoutFactorsHasEmptyList() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/user"))
   .andRespond(withSuccess("{\"id\":\"11111111-1111-4111-8111-111111111111\"}",MediaType.APPLICATION_JSON));
  assertTrue(new SupabaseAuthClient(builder.build(),settings()).totpFactors("synthetic-access").isEmpty());
 }
 @Test void totpEnrollmentValidatesProviderMaterialAndRedactsSecret() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors")).andExpect(method(HttpMethod.POST))
   .andExpect(header("Authorization","Bearer synthetic-access"))
   .andExpect(jsonPath("$.factor_type").value("totp"))
   .andRespond(withSuccess("""
    {"id":"%s","type":"totp","friendly_name":"CondPulse 1","totp":{"qr_code":"data:image/svg+xml;utf-8,<svg></svg>",
     "secret":"JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP","uri":"otpauth://totp/CondPulse:synthetic?secret=JBSWY3DPEHPK3PXP"}}
    """.formatted(FACTOR),MediaType.APPLICATION_JSON));
  var enrollment=new SupabaseAuthClient(builder.build(),settings()).enrollTotp("synthetic-access");
  assertEquals(UUID.fromString(FACTOR),enrollment.factorId());
  assertTrue(enrollment.otpauthUri().startsWith("otpauth://totp/"));
  assertTrue(enrollment.qrCodeDataUri().startsWith("data:image/svg+xml"));
  assertFalse(enrollment.toString().contains("JBSWY3DP"));
  server.verify();
 }
 @Test void enrollmentWithUnexpectedUriFailsClosed() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors"))
   .andRespond(withSuccess("""
    {"id":"%s","type":"totp","totp":{"qr_code":"data:image/svg+xml;utf-8,<svg></svg>","secret":"JBSWY3DPEHPK3PXP","uri":"https://phishing.example.invalid"}}
    """.formatted(FACTOR),MediaType.APPLICATION_JSON));
  var error=assertThrows(BusinessProblem.class,()->new SupabaseAuthClient(builder.build(),settings()).enrollTotp("synthetic-access"));
  assertEquals(503,error.status());
 }
 @Test void unexpectedQrIsDroppedSoOnlyTheValidatedUriAndSecretAreShown() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors"))
   .andRespond(withSuccess("""
    {"id":"%s","type":"totp","totp":{"qr_code":"javascript:alert(1)","secret":"JBSWY3DPEHPK3PXP","uri":"otpauth://totp/CondPulse:synthetic?secret=JBSWY3DPEHPK3PXP"}}
    """.formatted(FACTOR),MediaType.APPLICATION_JSON));
  var enrollment=new SupabaseAuthClient(builder.build(),settings()).enrollTotp("synthetic-access");
  assertNull(enrollment.qrCodeDataUri()); assertTrue(enrollment.otpauthUri().startsWith("otpauth://totp/"));
 }
 @Test void enrollmentWithMalformedSecretFailsClosed() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors"))
   .andRespond(withSuccess("""
    {"id":"%s","type":"totp","totp":{"secret":"not base32!","uri":"otpauth://totp/CondPulse:synthetic"}}
    """.formatted(FACTOR),MediaType.APPLICATION_JSON));
  assertEquals(503,assertThrows(BusinessProblem.class,
   ()->new SupabaseAuthClient(builder.build(),settings()).enrollTotp("synthetic-access")).status());
 }
 @Test void verifyChallengesThenVerifiesWithTheChallengeId() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors/"+FACTOR+"/challenge")).andExpect(method(HttpMethod.POST))
   .andRespond(withSuccess("{\"id\":\""+CHALLENGE+"\",\"type\":\"totp\",\"expires_at\":1893456000}",MediaType.APPLICATION_JSON));
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors/"+FACTOR+"/verify")).andExpect(method(HttpMethod.POST))
   .andExpect(jsonPath("$.challenge_id").value(CHALLENGE)).andExpect(jsonPath("$.code").value("123456"))
   .andRespond(withSuccess("""
    {"access_token":"synthetic-aal2","token_type":"bearer","expires_in":3600,"refresh_token":"synthetic-refresh-2","user":{}}
    """,MediaType.APPLICATION_JSON));
  var tokens=new SupabaseAuthClient(builder.build(),settings()).verifyTotp("synthetic-access",UUID.fromString(FACTOR),"123456");
  assertEquals("synthetic-aal2",tokens.access_token());
  server.verify();
 }
 @Test void recoveryRequestNeverThrowsForProviderOutcomes() {
  var cases=new Object[][]{{HttpStatus.OK,SupabaseAuthClient.RecoveryDispatch.ACCEPTED},
   {HttpStatus.BAD_REQUEST,SupabaseAuthClient.RecoveryDispatch.REFUSED},
   {HttpStatus.TOO_MANY_REQUESTS,SupabaseAuthClient.RecoveryDispatch.RATE_LIMITED},
   {HttpStatus.INTERNAL_SERVER_ERROR,SupabaseAuthClient.RecoveryDispatch.UNAVAILABLE}};
  for(var item:cases) {
   var builder=RestClient.builder();
   var server=MockRestServiceServer.bindTo(builder).build();
   server.expect(requestTo("https://auth.example.invalid/auth/v1/recover")).andExpect(method(HttpMethod.POST))
    .andExpect(header("apikey","synthetic-publishable")).andExpect(jsonPath("$.email").value("pessoa@example.invalid"))
    .andRespond(withStatus((HttpStatus)item[0]).body("{\"msg\":\"sensitive-provider-detail\"}"));
   assertEquals(item[1],new SupabaseAuthClient(builder.build(),settings()).recover("pessoa@example.invalid"));
   server.verify();
  }
 }
 @Test void recoveryLinkIsExchangedByHashAndRejectionHidesProviderDetail() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/verify")).andExpect(method(HttpMethod.POST))
   .andExpect(jsonPath("$.type").value("recovery")).andExpect(jsonPath("$.token_hash").value("synthetic-hash-0123456789"))
   .andRespond(withSuccess("""
    {"access_token":"synthetic-recovery","token_type":"bearer","expires_in":3600,"refresh_token":"synthetic-refresh","user":{}}
    """,MediaType.APPLICATION_JSON));
  assertEquals("synthetic-recovery",new SupabaseAuthClient(builder.build(),settings()).verifyRecovery("synthetic-hash-0123456789").access_token());
  server.verify();
  var rejected=RestClient.builder();
  MockRestServiceServer.bindTo(rejected).build().expect(requestTo("https://auth.example.invalid/auth/v1/verify"))
   .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"msg\":\"sensitive-provider-detail\"}"));
  var error=assertThrows(BusinessProblem.class,()->new SupabaseAuthClient(rejected.build(),settings()).verifyRecovery("synthetic-hash-0123456789"));
  assertEquals("RECOVERY_LINK_INVALID",error.code()); assertEquals(400,error.status());
  assertFalse(error.getMessage().contains("sensitive-provider-detail"));
 }
 @Test void passwordUpdateUsesUserTokenAndSeparatesPolicyRejectionFromUncertainty() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/user")).andExpect(method(HttpMethod.PUT))
   .andExpect(header("Authorization","Bearer synthetic-recovery")).andExpect(jsonPath("$.password").value("Senha-sintetica-longa"))
   .andRespond(withSuccess("{\"id\":\"11111111-1111-4111-8111-111111111111\"}",MediaType.APPLICATION_JSON));
  new SupabaseAuthClient(builder.build(),settings()).updatePassword("synthetic-recovery","Senha-sintetica-longa");
  server.verify();
  var statuses=new Object[][]{{422,"PASSWORD_REJECTED"},{401,"RECOVERY_SESSION_EXPIRED"},{403,"RECOVERY_NOT_ALLOWED"},
   {429,"AUTH_RATE_LIMITED"},{502,"AUTH_PROVIDER_UNAVAILABLE"}};
  for(var item:statuses) {
   var failing=RestClient.builder();
   MockRestServiceServer.bindTo(failing).build().expect(requestTo("https://auth.example.invalid/auth/v1/user"))
    .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf((Integer)item[0])).body("{\"msg\":\"sensitive-provider-detail\"}"));
   var error=assertThrows(BusinessProblem.class,()->new SupabaseAuthClient(failing.build(),settings())
    .updatePassword("synthetic-recovery","Senha-sintetica-longa"));
   assertEquals(item[1],error.code()); assertFalse(error.getMessage().contains("sensitive-provider-detail"));
  }
 }
 @Test void globalSignOutReportsWhetherProviderConfirmed() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/logout?scope=global")).andExpect(method(HttpMethod.POST))
   .andExpect(header("Authorization","Bearer synthetic-recovery")).andRespond(withStatus(HttpStatus.NO_CONTENT));
  assertTrue(new SupabaseAuthClient(builder.build(),settings()).signOutGlobal("synthetic-recovery"));
  server.verify();
  var failing=RestClient.builder();
  MockRestServiceServer.bindTo(failing).build().expect(requestTo("https://auth.example.invalid/auth/v1/logout?scope=global"))
   .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
  assertFalse(new SupabaseAuthClient(failing.build(),settings()).signOutGlobal("synthetic-recovery"));
 }
 @Test void rejectedCodeIsStableAndHidesProviderDetail() {
  var builder=RestClient.builder();
  var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors/"+FACTOR+"/challenge"))
   .andRespond(withSuccess("{\"id\":\""+CHALLENGE+"\"}",MediaType.APPLICATION_JSON));
  server.expect(requestTo("https://auth.example.invalid/auth/v1/factors/"+FACTOR+"/verify"))
   .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(422)).body("{\"msg\":\"sensitive-provider-detail\"}"));
  var error=assertThrows(BusinessProblem.class,()->new SupabaseAuthClient(builder.build(),settings())
   .verifyTotp("synthetic-access",UUID.fromString(FACTOR),"000000"));
  assertEquals("MFA_CODE_REJECTED",error.code()); assertEquals(422,error.status());
  assertFalse(error.getMessage().contains("sensitive-provider-detail"));
 }
}
