package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Exercita a cadeia real da API e os endpoints BFF sem rede/JDBC.
 * O endpoint /v1/me aqui e apenas uma sonda de autenticacao; o dominio tem testes proprios.
 * Nao substitui ensaio HTTPS, Supabase Auth real ou pool PostgreSQL.
 */
class BffSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;
 private JwtDecoder decoder;

 @BeforeEach void setup() {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-auth",Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json")));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  decoder=context.getBean("testDecoder",JwtDecoder.class);
  mvc=MockMvcBuilders.webAppContextSetup(context)
   .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() {
  SecurityContextHolder.clearContext();
  if(context!=null) context.close();
 }

 @Test void csrfEndpointIssuesReadableBodyAndProtectedHostCookie() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  assertEquals(200,response.getStatus());
  assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains("\"headerName\":\"X-CSRF-Token\""));
  String header=response.getHeader("Set-Cookie");
  assertNotNull(header); assertTrue(header.startsWith("__Host-cp_csrf="));
  assertTrue(header.contains("Path=/")); assertTrue(header.contains("Secure"));
  assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Lax"));
  assertFalse(header.contains("Domain="));
  verifyNoInteractions(sessions,decoder);
 }

 @Test void loginReachesControllerOnlyWithMatchingCsrfAndOrigin() throws Exception {
  var csrf=csrf();
  String raw=SessionSecrets.newToken();
  when(sessions.login("resident@example.invalid","synthetic-password"))
   .thenReturn(new WebSessionService.SessionTicket(raw,ACCOUNT,NOW.plusSeconds(3600)));
  var response=mvc.perform(mutation("/auth/login",csrf)
   .contentType(MediaType.APPLICATION_JSON)
   .content("{\"email\":\"resident@example.invalid\",\"password\":\"synthetic-password\"}"))
   .andReturn().getResponse();
  assertEquals(200,response.getStatus());
  assertTrue(response.getContentAsString().contains(ACCOUNT.toString()));
  assertFalse(response.getContentAsString().contains(raw));
  assertFalse(response.getContentAsString().contains("synthetic-password"));
  assertFalse(response.getContentAsString().contains("access_token"));
  assertFalse(response.getContentAsString().contains("refresh_token"));
  assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(h->
   h.startsWith("__Host-cp_session="+raw+";") && h.contains("HttpOnly") && h.contains("Secure")));
  assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_csrf=")));
  verify(sessions).login("resident@example.invalid","synthetic-password");
  verifyNoMoreInteractions(sessions); verifyNoInteractions(decoder);
 }

 @Test void matchingOriginWithoutCsrfCannotMutate() throws Exception {
  var response=mvc.perform(post("/auth/login").servletPath("/auth/login").secure(true)
   .header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andReturn().getResponse();
  assertEquals(403,response.getStatus());
  verifyNoInteractions(sessions,decoder);
 }

 @Test void matchingCsrfWithoutAllowedOriginCannotMutate() throws Exception {
  var csrf=csrf();
  var response=mvc.perform(post("/auth/logout").servletPath("/auth/logout").secure(true)
   .cookie(csrf.cookie()).header("X-CSRF-Token",csrf.token())
   .header("Origin","https://attacker.example.invalid"))
   .andReturn().getResponse();
  assertEquals(403,response.getStatus());
  assertTrue(response.getContentAsString().contains("CSRF_ORIGIN_REJECTED"));
  verifyNoInteractions(sessions,decoder);
 }

 @Test void refreshIsPostWithCsrfAndUsesOpaqueSessionCookie() throws Exception {
  var csrf=csrf(); String raw=SessionSecrets.newToken();
  when(sessions.refresh(raw)).thenReturn(new WebSessionService.SessionTicket(raw,ACCOUNT,NOW.plusSeconds(3600)));
  var response=mvc.perform(mutation("/auth/refresh",csrf).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse();
  assertEquals(200,response.getStatus());
  assertFalse(response.getContentAsString().contains(raw));
  assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_csrf=")));
  verify(sessions).refresh(raw); verifyNoMoreInteractions(sessions); verifyNoInteractions(decoder);
 }

 @Test void logoutClearsSessionAndCsrfCookiesThroughRealChain() throws Exception {
  var csrf=csrf(); String raw=SessionSecrets.newToken();
  var response=mvc.perform(mutation("/auth/logout",csrf).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse();
  assertEquals(204,response.getStatus());
  assertTrue(response.getHeaders("Set-Cookie").stream()
   .anyMatch(h->h.startsWith("__Host-cp_session=") && h.contains("Max-Age=0")));
  assertTrue(response.getHeaders("Set-Cookie").stream()
   .anyMatch(h->h.startsWith("__Host-cp_csrf=") && h.contains("Max-Age=0")));
  verify(sessions).logout(raw); verifyNoMoreInteractions(sessions); verifyNoInteractions(decoder);
 }

 @Test void validCookieAuthenticatesApiWithinRealSecurityChain() throws Exception {
  String raw=SessionSecrets.newToken();
  when(sessions.authenticate(raw)).thenReturn(jwt());
  var response=mvc.perform(get("/v1/me").servletPath("/v1/me").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,response.getStatus());
  assertEquals(ACCOUNT.toString(),response.getContentAsString());
  verify(sessions).authenticate(raw); verifyNoMoreInteractions(sessions); verifyNoInteractions(decoder);
 }

 @Test void invalidBearerNeverFallsBackToValidCookieWithinRealChain() throws Exception {
  String raw=SessionSecrets.newToken();
  when(decoder.decode("synthetic-invalid")).thenThrow(new BadJwtException("synthetic-invalid"));
  var response=mvc.perform(get("/v1/me").servletPath("/v1/me").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw)).header("Authorization","Bearer synthetic-invalid"))
   .andReturn().getResponse();
  assertEquals(401,response.getStatus());
  assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
  verify(decoder).decode("synthetic-invalid"); verifyNoMoreInteractions(decoder); verifyNoInteractions(sessions);
 }

 private Csrf csrf() throws Exception {
  MockHttpServletResponse response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true))
   .andReturn().getResponse();
  assertEquals(200,response.getStatus());
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find(),"Endpoint CSRF deve expor token somente no corpo.");
  Cookie cookie=response.getCookie("__Host-cp_csrf");
  assertNotNull(cookie);
  return new Csrf(cookie,matcher.group(1));
 }
 private MockHttpServletRequestBuilder mutation(String path,Csrf csrf) {
  return post(path).servletPath(path).secure(true).header("Origin",ORIGIN)
   .cookie(csrf.cookie()).header("X-CSRF-Token",csrf.token());
 }
 private static Jwt jwt() {
  return Jwt.withTokenValue("synthetic-access").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build();
 }
 private record Csrf(Cookie cookie,String token) {}

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   var settings=new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
   return new BffSecurityConfigurer(sessions,settings,csrf);
  }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf) {
   return new BffController(sessions,csrf,Clock.fixed(NOW,ZoneOffset.UTC));
  }
  @Bean IdentityProbe identityProbe() { return new IdentityProbe(); }
 }
 @RestController
 static class IdentityProbe {
  @GetMapping("/v1/me") public String me(Authentication authentication) { return authentication.getName(); }
 }
}
