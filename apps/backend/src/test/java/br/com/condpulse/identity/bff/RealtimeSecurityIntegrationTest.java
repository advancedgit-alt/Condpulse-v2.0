package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.realtime.api.RealtimeController;
import br.com.condpulse.realtime.application.RealtimeHub;
import br.com.condpulse.realtime.application.RealtimeService;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletResponse;
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

/** Cadeia HTTP real do canal em tempo real (CP-036); regras de estado em RealtimeHubTest e banco em RealtimeJdbcTest. */
class RealtimeSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final Instant NOW=Instant.now();
 private static final String ROUTE="/v1/tenants/"+TENANT+"/units/"+UNIT+"/events";
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;

 private void start(boolean enabled) {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  Map<String,Object> properties=new HashMap<>(Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json"));
  if(enabled) properties.put("condpulse.realtime.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-realtime",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private RealtimeService service() { return context.getBean(RealtimeService.class); }
 private RealtimeHub hub() { return context.getBean(RealtimeHub.class); }
 private static Jwt jwt() {
  return Jwt.withTokenValue("synthetic-realtime").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).claim("aal","aal1").build();
 }
 private String session() { String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(jwt()); return raw; }
 private MockHttpServletResponse open(Cookie... cookies) throws Exception {
  var request=get(ROUTE).servletPath(ROUTE).secure(true).header("Accept","text/event-stream");
  if(cookies.length>0) request.cookie(cookies);
  return mvc.perform(request).andReturn().getResponse();
 }

 @Test void sessionOpensAStreamThatAnnouncesOnlyTopicNamesAndIsNeverCached() throws Exception {
  start(true);
  when(service().snapshot(ACCOUNT,TENANT,UNIT)).thenReturn(Map.of("parcels","0:0:"));
  var response=open(new Cookie(BffCookies.SESSION,session()));
  assertEquals(200,response.getStatus());
  assertTrue(response.getContentType().startsWith("text/event-stream"),response.getContentType());
  assertEquals("no-store",response.getHeader("Cache-Control")); assertEquals("no",response.getHeader("X-Accel-Buffering"));
  String body=response.getContentAsString();
  assertTrue(body.contains("event:ready") && body.contains("\"topics\":[\"reservations\""),body);
  assertTrue(body.contains("retry:5000"),body);
  assertEquals(1,hub().open());
 }

 @Test void loggingOutClosesTheOpenStreamOnTheNextPass() throws Exception {
  start(true);
  when(service().snapshot(ACCOUNT,TENANT,UNIT)).thenReturn(Map.of("parcels","0:0:"));
  String raw=session();
  assertEquals(200,open(new Cookie(BffCookies.SESSION,raw)).getStatus());
  when(sessions.authenticate(raw)).thenThrow(new BusinessProblem("SESSION_EXPIRED",401,"Entre novamente para continuar."));
  hub().tick();
  assertEquals(0,hub().open(),"Sessão removida encerra o canal");
  verify(service(),times(1)).snapshot(any(),any(),any());
 }

 @Test void withoutSessionDeniedUnitOrTooManyStreamsNothingIsOpened() throws Exception {
  start(true);
  assertEquals(401,open().getStatus());
  verifyNoInteractions(service());
  when(service().snapshot(ACCOUNT,TENANT,UNIT)).thenThrow(new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade não disponível para esta operação."));
  var denied=open(new Cookie(BffCookies.SESSION,session()));
  assertEquals(404,denied.getStatus());
  assertTrue(denied.getContentType().startsWith("application/problem+json"),denied.getContentType());
  assertTrue(denied.getContentAsString().contains("UNIT_ACCESS_DENIED"));
  assertEquals(0,hub().open());
  reset(service());
  when(service().snapshot(ACCOUNT,TENANT,UNIT)).thenReturn(Map.of());
  String raw=session();
  assertEquals(200,open(new Cookie(BffCookies.SESSION,raw)).getStatus());
  assertEquals(200,open(new Cookie(BffCookies.SESSION,raw)).getStatus());
  var limited=open(new Cookie(BffCookies.SESSION,raw));
  assertEquals(429,limited.getStatus()); assertTrue(limited.getContentAsString().contains("REALTIME_LIMIT_REACHED"));
  assertEquals(2,hub().open());
 }

 @Test void bearerTokenOpensTheStreamAndInvalidTokenIsRefused() throws Exception {
  start(true);
  when(service().snapshot(ACCOUNT,TENANT,UNIT)).thenReturn(Map.of());
  when(context.getBean(JwtDecoder.class).decode("synthetic-mobile")).thenReturn(jwt());
  var response=mvc.perform(get(ROUTE).servletPath(ROUTE).secure(true).header("Accept","text/event-stream")
   .header("Authorization","Bearer synthetic-mobile")).andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertTrue(response.getContentAsString().contains("event:ready"));
  verifyNoInteractions(sessions);
  when(context.getBean(JwtDecoder.class).decode("synthetic-bad")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("bad"));
  assertEquals(401,mvc.perform(get(ROUTE).servletPath(ROUTE).secure(true).header("Authorization","Bearer synthetic-bad"))
   .andReturn().getResponse().getStatus());
 }

 @Test void routeStaysAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(RealtimeController.class).isEmpty());
  assertEquals(404,open(new Cookie(BffCookies.SESSION,session())).getStatus());
 }
 /**
  * No servidor real, a rota de um módulo desligado vira 404 e o contêiner faz o despacho ERROR para /error, sem o
  * cookie autenticado: esse despacho não pode virar 401 (o navegador encerraria a sessão e voltaria ao login).
  */
 @Test void errorDispatchOfADisabledModuleIsNotTurnedIntoA401() throws Exception {
  start(false);
  var response=mvc.perform(get("/error").servletPath("/error").secure(true).with(request->{
   request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
   request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE,404);
   request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI,ROUTE);
   return request;
  })).andReturn().getResponse();
  assertNotEquals(401,response.getStatus());
  assertNotEquals(403,response.getStatus());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,RealtimeController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean RealtimeService testRealtime() { return mock(RealtimeService.class); }
  @Bean RealtimeHub testHub() {
   return new RealtimeHub(new RealtimeHub.Settings(Duration.ofSeconds(5),Duration.ofSeconds(300),40,2),Clock.systemUTC());
  }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   var settings=new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
   return new BffSecurityConfigurer(sessions,settings,csrf);
  }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf) {
   var limits=mock(AuthRateLimiter.class);
   when(limits.login(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   when(limits.refresh(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   return new BffController(sessions,csrf,Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"),ZoneOffset.UTC),limits);
  }
 }
}
