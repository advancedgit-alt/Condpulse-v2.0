package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.reservations.api.WaitlistController;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.WaitlistModels;
import br.com.condpulse.reservations.application.WaitlistService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Rotas da fila de espera (CP-040): identidade, CSRF, chave de operação e flag reais; regras em WaitlistJdbcTest. */
class WaitlistSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID AMENITY=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final UUID ENTRY=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private static final UUID HELD=UUID.fromString("66666666-6666-4666-8666-666666666666");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final Instant START=Instant.parse("2026-11-01T15:00:00Z");
 private static final Instant END=Instant.parse("2026-11-01T17:00:00Z");
 private static final String KEY="synthetic-waitlist-request-01";
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;
 private WaitlistService waitlist;

 private void start(boolean enabled) {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-waitlist",Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.reservations.enabled",Boolean.toString(enabled))));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  waitlist=context.getBean(WaitlistService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context)
   .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() {
  SecurityContextHolder.clearContext();
  if(context!=null) context.close();
 }

 @Test void listJoinLeaveAndAcceptPassActorTenantAndKeyToTheService() throws Exception {
  start(true);
  String raw=authenticatedSession();
  String list="/v1/tenants/"+TENANT+"/units/"+UNIT+"/waitlist";
  assertEquals(401,mvc.perform(get(list).servletPath(list).secure(true)).andReturn().getResponse().getStatus());
  when(waitlist.unitEntries(ACCOUNT,TENANT,UNIT)).thenReturn(new WaitlistModels.Page(List.of(entry("WAITING")),false));
  var listed=mvc.perform(get(list).servletPath(list).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,listed.getStatus()); assertEquals("no-store",listed.getHeader("Cache-Control"));
  assertTrue(listed.getContentAsString().contains("\"position\":1"));

  String join="/v1/tenants/"+TENANT+"/waitlist";
  String body="""
   {"unitId":"%s","amenityId":"%s","startsAt":"%s","endsAt":"%s","acceptedPolicyVersion":1}
   """.formatted(UNIT,AMENITY,START,END);
  assertEquals(403,mvc.perform(post(join).servletPath(join).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(),"Sem CSRF");
  var csrf=csrf();
  var joinRequest=new WaitlistModels.Join(UNIT,AMENITY,START,END,1);
  when(waitlist.join(ACCOUNT,TENANT,KEY,joinRequest)).thenReturn(new WaitlistModels.Mutation(entry("WAITING"),false,201));
  var joined=mvc.perform(post(join).servletPath(join).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),csrf.cookie()).header("X-CSRF-Token",csrf.token())
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,joined.getStatus()); assertEquals("false",joined.getHeader("Idempotency-Replayed"));
  verify(waitlist).join(ACCOUNT,TENANT,KEY,joinRequest);

  String leave="/v1/tenants/"+TENANT+"/waitlist/"+ENTRY+"/leave";
  var change=new WaitlistModels.Change(UNIT,0L);
  when(waitlist.leave(ACCOUNT,TENANT,ENTRY,change)).thenReturn(entry("LEFT"));
  var left=mvc.perform(post(leave).servletPath(leave).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),csrf.cookie()).header("X-CSRF-Token",csrf.token())
   .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\""+UNIT+"\",\"expectedVersion\":0}")).andReturn().getResponse();
  assertEquals(200,left.getStatus()); assertTrue(left.getContentAsString().contains("\"state\":\"LEFT\""));

  String accept="/v1/tenants/"+TENANT+"/waitlist/"+ENTRY+"/accept";
  var noKey=mvc.perform(post(accept).servletPath(accept).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),csrf.cookie()).header("X-CSRF-Token",csrf.token())
   .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\""+UNIT+"\",\"expectedVersion\":1}")).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave: "+noKey.getStatus());
  var acceptChange=new WaitlistModels.Change(UNIT,1L);
  when(waitlist.accept(ACCOUNT,TENANT,ENTRY,KEY,acceptChange)).thenReturn(new WaitlistModels.Acceptance(entry("ACCEPTED"),
   new ReservationModels.View(HELD,UNIT,AMENITY,"CONFIRMED",START,END,START,END,1,0,2,null)));
  var accepted=mvc.perform(post(accept).servletPath(accept).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),csrf.cookie()).header("X-CSRF-Token",csrf.token())
   .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\""+UNIT+"\",\"expectedVersion\":1}")).andReturn().getResponse();
  assertEquals(200,accepted.getStatus()); assertTrue(accepted.getContentAsString().contains("\"status\":\"CONFIRMED\""));
  verify(waitlist).accept(ACCOUNT,TENANT,ENTRY,KEY,acceptChange);
  assertFalse(accepted.getContentAsString().contains(raw));
 }

 @Test void businessRefusalUsesTheProblemHandlerAndDisabledFlagHidesTheRoutes() throws Exception {
  start(true);
  String raw=authenticatedSession(); var csrf=csrf();
  String accept="/v1/tenants/"+TENANT+"/waitlist/"+ENTRY+"/accept";
  when(waitlist.accept(any(),any(),any(),any(),any())).thenThrow(new BusinessProblem("WAITLIST_OFFER_EXPIRED",409,"Oferta vencida."));
  var expired=mvc.perform(post(accept).servletPath(accept).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),csrf.cookie()).header("X-CSRF-Token",csrf.token())
   .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\""+UNIT+"\",\"expectedVersion\":1}")).andReturn().getResponse();
  assertEquals(409,expired.getStatus()); assertTrue(expired.getContentAsString().contains("\"code\":\"WAITLIST_OFFER_EXPIRED\""));
  cleanup();
  start(false);
  raw=authenticatedSession();
  String list="/v1/tenants/"+TENANT+"/units/"+UNIT+"/waitlist";
  var hidden=mvc.perform(get(list).servletPath(list).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(404,hidden.getStatus());
  assertTrue(context.getBeansOfType(WaitlistController.class).isEmpty());
 }

 private WaitlistModels.Entry entry(String state) {
  boolean offered="OFFERED".equals(state) || "ACCEPTED".equals(state);
  return new WaitlistModels.Entry(ENTRY,UNIT,AMENITY,state,START,END,START,END,1,false,0,offered?HELD:null,offered?NOW:null,
   offered?NOW.plusSeconds(900):null,"WAITING".equals(state)?null:NOW,true,"WAITING".equals(state)?1:null,0,NOW);
 }
 private Csrf csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  assertEquals(200,response.getStatus());
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  Cookie cookie=response.getCookie("__Host-cp_csrf"); assertNotNull(cookie);
  return new Csrf(cookie,matcher.group(1));
 }
 private String authenticatedSession() {
  String raw=SessionSecrets.newToken();
  var jwt=Jwt.withTokenValue("synthetic-access").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build();
  when(sessions.authenticate(raw)).thenReturn(jwt);
  return raw;
 }
 private record Csrf(Cookie cookie,String token) {}

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,WaitlistController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean WaitlistService testWaitlist() { return mock(WaitlistService.class); }
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
   return new BffController(sessions,csrf,Clock.fixed(NOW,ZoneOffset.UTC),limits);
  }
 }
}
