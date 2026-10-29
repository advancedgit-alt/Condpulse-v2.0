package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Cadeia real de segurança para as rotas de recuperação, sem rede, banco ou provedor. */
class BffRecoverySecurityTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final Instant NOW=Instant.parse("2030-01-01T12:00:00Z");
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;

 private void start(boolean recoveryEnabled) {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  Map<String,Object> properties=new HashMap<>(Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.bff.enabled","true"));
  if(recoveryEnabled) properties.put("condpulse.bff.recovery-enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-recovery",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private RecoveryService recovery() { return context.getBean(RecoveryService.class); }
 private AuthRateLimiter limits() { return context.getBean(AuthRateLimiter.class); }
 private String[] csrf() throws Exception {
  MockHttpServletResponse response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }
 private MockHttpServletRequestBuilder mutation(String path,String[] csrf) {
  return post(path).servletPath(path).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON);
 }

 @Test void requestNeedsCsrfAndOriginAndAnswersNeutrally() throws Exception {
  start(true);
  var withoutCsrf=mvc.perform(post("/auth/recovery").servletPath("/auth/recovery").secure(true).header("Origin",ORIGIN)
   .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"pessoa@example.invalid\"}")).andReturn().getResponse();
  assertEquals(403,withoutCsrf.getStatus());
  var csrf=csrf();
  var foreign=mvc.perform(post("/auth/recovery").servletPath("/auth/recovery").secure(true).header("Origin","https://attacker.example.invalid")
   .cookie(new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON)
   .content("{\"email\":\"pessoa@example.invalid\"}")).andReturn().getResponse();
  assertEquals(403,foreign.getStatus());
  verifyNoInteractions(recovery());
  var response=mvc.perform(mutation("/auth/recovery",csrf).content("{\"email\":\"pessoa@example.invalid\"}")).andReturn().getResponse();
  assertEquals(202,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  assertEquals("{\"status\":\"RECOVERY_REQUESTED\"}",response.getContentAsString());
  verify(limits()).recoveryRequest("pessoa@example.invalid"); verify(recovery()).request("pessoa@example.invalid");
 }
 @Test void requestLimitIsAnsweredBeforeProvider() throws Exception {
  start(true);
  when(limits().recoveryRequest("pessoa@example.invalid")).thenReturn(new AuthRateLimiter.Admission(false,900));
  var response=mvc.perform(mutation("/auth/recovery",csrf()).content("{\"email\":\"pessoa@example.invalid\"}")).andReturn().getResponse();
  assertEquals(429,response.getStatus()); assertEquals("900",response.getHeader("Retry-After"));
  assertFalse(response.getContentAsString().contains("pessoa@example.invalid")); verifyNoInteractions(recovery());
 }
 @Test void verifySetsStrictHostOnlyRecoveryCookieAndNeverReturnsTokens() throws Exception {
  start(true);
  String raw=SessionSecrets.newToken();
  when(recovery().verify("synthetic-link-hash-0123")).thenReturn(new RecoveryService.Ticket(raw,true,NOW.plusSeconds(600)));
  var response=mvc.perform(mutation("/auth/recovery/verify",csrf()).content("{\"tokenHash\":\"synthetic-link-hash-0123\"}"))
   .andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains("\"mfaRequired\":true"));
  assertFalse(response.getContentAsString().contains(raw)); assertFalse(response.getContentAsString().contains("synthetic-link-hash"));
  String cookie=response.getHeaders("Set-Cookie").stream().filter(h->h.startsWith("__Host-cp_recovery=")).findFirst().orElseThrow();
  assertTrue(cookie.startsWith("__Host-cp_recovery="+raw+";")); assertTrue(cookie.contains("Path=/"));
  assertTrue(cookie.contains("Max-Age=600")); assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("HttpOnly"));
  assertTrue(cookie.contains("SameSite=Strict")); assertFalse(cookie.contains("Domain="));
  assertFalse(response.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_session=")));
 }
 @Test void completeClearsRecoverySessionAndCsrfCookiesAfterConfirmedChange() throws Exception {
  start(true);
  String raw=SessionSecrets.newToken();
  when(recovery().complete(raw,"Senha-sintetica-longa","123456")).thenReturn(new RecoveryService.Completion(true));
  var response=mvc.perform(mutation("/auth/recovery/complete",csrf()).cookie(new Cookie(BffCookies.RECOVERY,raw))
   .content("{\"password\":\"Senha-sintetica-longa\",\"code\":\"123456\"}")).andReturn().getResponse();
  assertEquals(200,response.getStatus());
  assertEquals("{\"status\":\"PASSWORD_CHANGED\",\"otherSessionsEnded\":true}",response.getContentAsString());
  var cookies=response.getHeaders("Set-Cookie");
  assertTrue(cookies.stream().anyMatch(h->h.startsWith("__Host-cp_recovery=;") && h.contains("Max-Age=0")));
  assertTrue(cookies.stream().anyMatch(h->h.startsWith("__Host-cp_session=;") && h.contains("Max-Age=0")));
  assertTrue(cookies.stream().anyMatch(h->h.startsWith("__Host-cp_csrf=") && h.contains("Max-Age=0")));
  assertFalse(response.getContentAsString().contains("Senha-sintetica-longa"));
 }
 @Test void completeWithoutRecoveryCookieNeverReachesService() throws Exception {
  start(true);
  var response=mvc.perform(mutation("/auth/recovery/complete",csrf()).content("{\"password\":\"Senha-sintetica-longa\"}"))
   .andReturn().getResponse();
  assertEquals(401,response.getStatus()); assertTrue(response.getContentAsString().contains("RECOVERY_SESSION_EXPIRED"));
  verifyNoInteractions(recovery());
 }
 @Test void retryableRefusalKeepsRecoveryCookieAndTerminalOutcomeClearsIt() throws Exception {
  start(true);
  String raw=SessionSecrets.newToken();
  when(recovery().complete(eq(raw),anyString(),any()))
   .thenThrow(new BusinessProblem("PASSWORD_REJECTED",422,"Recusada."))
   .thenThrow(new BusinessProblem("RECOVERY_OUTCOME_UNKNOWN",503,"Incerto."));
  var rejected=mvc.perform(mutation("/auth/recovery/complete",csrf()).cookie(new Cookie(BffCookies.RECOVERY,raw))
   .content("{\"password\":\"Senha-sintetica-longa\"}")).andReturn().getResponse();
  assertEquals(422,rejected.getStatus());
  assertFalse(rejected.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_recovery=")));
  var uncertain=mvc.perform(mutation("/auth/recovery/complete",csrf()).cookie(new Cookie(BffCookies.RECOVERY,raw))
   .content("{\"password\":\"Senha-sintetica-longa\"}")).andReturn().getResponse();
  assertEquals(503,uncertain.getStatus()); assertTrue(uncertain.getContentAsString().contains("RECOVERY_OUTCOME_UNKNOWN"));
  assertTrue(uncertain.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_recovery=;") && h.contains("Max-Age=0")));
 }
 @Test void recoveryCookieNeverAuthenticatesTheApi() throws Exception {
  start(true);
  var response=mvc.perform(get("/v1/me").servletPath("/v1/me").secure(true)
   .cookie(new Cookie(BffCookies.RECOVERY,SessionSecrets.newToken()))).andReturn().getResponse();
  assertEquals(401,response.getStatus()); verifyNoInteractions(context.getBean(WebSessionService.class));
 }
 @Test void routesStayAbsentWhileRecoveryFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(RecoveryService.class).isEmpty());
  var response=mvc.perform(mutation("/auth/recovery",csrf()).content("{\"email\":\"pessoa@example.invalid\"}")).andReturn().getResponse();
  assertEquals(404,response.getStatus());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,ProblemHandler.class,BffRecoveryController.class,RecoveryServiceHolder.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   var settings=new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
   return new BffSecurityConfigurer(sessions,settings,csrf);
  }
  @Bean AuthRateLimiter testRateLimiter() {
   var limits=mock(AuthRateLimiter.class);
   when(limits.recoveryRequest(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   when(limits.recoveryVerify(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   when(limits.recoveryComplete(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   return limits;
  }
  @Bean Clock testClock() { return Clock.fixed(NOW,ZoneOffset.UTC); }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf,Clock clock,AuthRateLimiter limits) {
   return new BffController(sessions,csrf,clock,limits);
  }
  @Bean IdentityProbe identityProbe() { return new IdentityProbe(); }
 }
 /** Serviço simulado registrado somente com a flag ligada, como a configuração real. */
 @Configuration(proxyBeanMethods=false)
 @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
  name={"condpulse.bff.enabled","condpulse.bff.recovery-enabled"},havingValue="true")
 static class RecoveryServiceHolder {
  @Bean RecoveryService testRecovery() { return mock(RecoveryService.class); }
 }
 @RestController
 static class IdentityProbe {
  @GetMapping("/v1/me") public String me(Authentication authentication) { return authentication.getName(); }
 }
}
