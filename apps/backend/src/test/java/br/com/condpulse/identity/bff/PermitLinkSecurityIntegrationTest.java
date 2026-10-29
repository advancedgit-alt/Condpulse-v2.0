package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.visits.api.VisitController;
import br.com.condpulse.visits.application.PermitLinkPublicService;
import br.com.condpulse.visits.application.PermitLinkTokens;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Cadeia HTTP real do link de convite (CP-037); banco e regras em PermitLinkJdbcTest e tests/database/run.py. */
class PermitLinkSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID PERMIT=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final String VIEW="/auth/permit-links/view";
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;
 private AuthRateLimiter limits;

 private void start(boolean visits) {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  Map<String,Object> properties=new HashMap<>(Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.bff.enabled","true"));
  if(visits) properties.put("condpulse.visits.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-permit-links",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  limits=context.getBean(AuthRateLimiter.class);
  when(limits.permitLinkView(any())).thenReturn(AuthRateLimiter.Admission.permitted());
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private VisitService visits() { return context.getBean(VisitService.class); }
 private PermitLinkPublicService publicView() { return context.getBean(PermitLinkPublicService.class); }
 private String session() {
  var jwt=Jwt.withTokenValue("synthetic-access").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).claim("aal","aal1").build();
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(jwt); return raw;
 }
 private String[] csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }

 @Test void residentCreatesALinkWithCsrfAndTheTokenIsNeverCached() throws Exception {
  start(true);
  String raw=session();
  String route="/v1/tenants/"+TENANT+"/permits/"+PERMIT+"/link";
  assertEquals(403,mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus(),"Sem CSRF");
  when(context.getBean(JwtDecoder.class).decode("x.y.z")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("bad"));
  assertEquals(401,mvc.perform(post(route).servletPath(route).secure(true).header("Authorization","Bearer x.y.z")).andReturn().getResponse().getStatus());
  verifyNoInteractions(visits());
  var csrf=csrf();
  String token=PermitLinkTokens.newToken();
  when(visits().shareLink(ACCOUNT,TENANT,PERMIT)).thenReturn(new VisitModels.PermitLink(token,NOW.plusSeconds(7200)));
  var created=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("no-store",created.getHeader("Cache-Control"));
  assertEquals("no-referrer",created.getHeader("Referrer-Policy"));
  assertTrue(created.getContentAsString().contains(token));
 }

 @Test void anonymousViewNeedsOriginAndCsrfIsRateLimitedAndNeverSeesTheUnit() throws Exception {
  start(true);
  String token=PermitLinkTokens.newToken();
  String body="{\"token\":\""+token+"\"}";
  assertEquals(403,mvc.perform(post(VIEW).servletPath(VIEW).secure(true).contentType(MediaType.APPLICATION_JSON).content(body))
   .andReturn().getResponse().getStatus(),"Sem Origin");
  assertEquals(403,mvc.perform(post(VIEW).servletPath(VIEW).secure(true).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
   .andReturn().getResponse().getStatus(),"Sem CSRF");
  verifyNoInteractions(publicView());
  var csrf=csrf();
  when(publicView().view(token)).thenReturn(new VisitModels.PublicPermit("CONDOMÍNIO FICTÍCIO","Pessoa","Aniversário",NOW,NOW.plusSeconds(7200),
   "America/Sao_Paulo","ACTIVE"));
  var seen=mvc.perform(post(VIEW).servletPath(VIEW).secure(true).header("Origin",ORIGIN).cookie(new Cookie("__Host-cp_csrf",csrf[0]))
   .header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(200,seen.getStatus()); assertEquals("no-store",seen.getHeader("Cache-Control"));
  String json=seen.getContentAsString();
  assertTrue(json.contains("\"visitorFirstName\":\"Pessoa\""),json);
  assertFalse(json.contains("unit") || json.contains(PERMIT.toString()) || json.contains(token),"Sem unidade, identificador ou token: "+json);
  verify(limits).permitLinkView(PermitLinkTokens.hash(token));
  when(limits.permitLinkView(any())).thenReturn(new AuthRateLimiter.Admission(false,60));
  var limited=mvc.perform(post(VIEW).servletPath(VIEW).secure(true).header("Origin",ORIGIN).cookie(new Cookie("__Host-cp_csrf",csrf[0]))
   .header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(429,limited.getStatus()); assertEquals("60",limited.getHeader("Retry-After"));
  verify(publicView(),times(1)).view(any());
 }

 @Test void malformedTokenCountsAsInvalidAndReachesTheSameNotFound() throws Exception {
  start(true);
  var csrf=csrf();
  when(publicView().view("curto")).thenThrow(PermitLinkPublicService.unavailable());
  var missing=mvc.perform(post(VIEW).servletPath(VIEW).secure(true).header("Origin",ORIGIN).cookie(new Cookie("__Host-cp_csrf",csrf[0]))
   .header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"curto\"}")).andReturn().getResponse();
  assertEquals(404,missing.getStatus()); assertTrue(missing.getContentAsString().contains("PERMIT_LINK_UNAVAILABLE"));
  verify(limits).permitLinkView(null);
 }

 @Test void viewIsAbsentWithoutVisits() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(BffPermitLinkController.class).isEmpty());
  var csrf=csrf();
  assertEquals(404,mvc.perform(post(VIEW).servletPath(VIEW).secure(true).header("Origin",ORIGIN).cookie(new Cookie("__Host-cp_csrf",csrf[0]))
   .header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}")).andReturn().getResponse().getStatus());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,VisitController.class,BffPermitLinkController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean VisitService testVisits() { return mock(VisitService.class); }
  @Bean PermitLinkPublicService testPublicView() { return mock(PermitLinkPublicService.class); }
  @Bean AuthRateLimiter testLimits() { return mock(AuthRateLimiter.class); }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   var settings=new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
   return new BffSecurityConfigurer(sessions,settings,csrf);
  }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf,AuthRateLimiter limits) {
   when(limits.login(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   when(limits.refresh(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   return new BffController(sessions,csrf,Clock.fixed(NOW,ZoneOffset.UTC),limits);
  }
 }
}
