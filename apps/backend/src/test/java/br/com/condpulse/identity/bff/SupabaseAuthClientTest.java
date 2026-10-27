package br.com.condpulse.identity.bff;

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
}
