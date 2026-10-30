package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.parcels.api.ConciergeUnitsController;
import br.com.condpulse.parcels.application.ParcelService;
import br.com.condpulse.visits.api.VisitController;
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

/** Cadeia HTTP real de visitas e chegadas; regras e banco são cobertos por VisitJdbcTest. */
class VisitSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID PERMIT=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final UUID ARRIVAL=UUID.fromString("66666666-6666-4666-8666-666666666666");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final String KEY="visit-http-key-00001";
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
  if(enabled) properties.put("condpulse.visits.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-visits",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private VisitService visits() { return context.getBean(VisitService.class); }
 private String session(String aal) {
  var builder=Jwt.withTokenValue("synthetic-access").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600));
  if(aal!=null) builder.claim("aal",aal);
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(builder.build()); return raw;
 }
 private String[] csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }
 private static VisitModels.Permit permit() {
  return new VisitModels.Permit(PERMIT,UNIT,"101",null,"Pessoa Fictícia",null,null,NOW,NOW.plusSeconds(7200),"ACTIVE",true,null,null,0,NOW,false,null,null);
 }
 private static VisitModels.Arrival arrival(String state) {
  return new VisitModels.Arrival(ARRIVAL,UNIT,"101",null,"Pessoa Fictícia",null,null,state,NOW.plusSeconds(300),null,null,null,false,0,NOW);
 }

 @Test void unitListsPassTheUnitWithoutCacheAndConciergeListsPassAssurance() throws Exception {
  start(true);
  String raw=session("aal2");
  when(visits().unitPermits(ACCOUNT,TENANT,UNIT)).thenReturn(new VisitModels.PermitPage(List.of(permit()),false));
  String permits="/v1/tenants/"+TENANT+"/units/"+UNIT+"/permits";
  var response=mvc.perform(get(permits).servletPath(permits).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains(PERMIT.toString()));
  when(visits().queue(ACCOUNT,TENANT,AssuranceLevel.AAL2,"WAITING")).thenReturn(new VisitModels.ArrivalPage(List.of(arrival("WAITING")),false));
  String arrivals="/v1/tenants/"+TENANT+"/arrivals";
  var queue=mvc.perform(get(arrivals).servletPath(arrivals).secure(true).cookie(new Cookie(BffCookies.SESSION,raw)).param("status","WAITING"))
   .andReturn().getResponse();
  assertEquals(200,queue.getStatus()); assertTrue(queue.getContentAsString().contains(ARRIVAL.toString()));
  when(visits().expected(ACCOUNT,TENANT,AssuranceLevel.AAL2,UNIT,"Pessoa")).thenReturn(new VisitModels.PermitPage(List.of(),false));
  String expected="/v1/tenants/"+TENANT+"/concierge/permits";
  assertEquals(200,mvc.perform(get(expected).servletPath(expected).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))
   .param("unitId",UNIT.toString()).param("q","Pessoa")).andReturn().getResponse().getStatus());
  verify(visits()).expected(ACCOUNT,TENANT,AssuranceLevel.AAL2,UNIT,"Pessoa");
 }
 @Test void mutationsRequireCsrfAndCreationsRequireKey() throws Exception {
  start(true);
  String raw=session("aal2");
  String permits="/v1/tenants/"+TENANT+"/units/"+UNIT+"/permits";
  String body="{\"visitorName\":\"Pessoa Fictícia\",\"validFrom\":\"2026-10-29T12:00:00Z\",\"validUntil\":\"2026-10-29T14:00:00Z\"}";
  assertEquals(403,mvc.perform(post(permits).servletPath(permits).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
  var csrf=csrf();
  var noKey=mvc.perform(post(permits).servletPath(permits).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave de idempotência: "+noKey.getStatus());
  verifyNoInteractions(visits());
  var request=new VisitModels.CreatePermit("Pessoa Fictícia",null,null,NOW,NOW.plusSeconds(7200));
  when(visits().createPermit(ACCOUNT,TENANT,UNIT,KEY,request)).thenReturn(new VisitModels.PermitMutation(permit(),true,201));
  var created=mvc.perform(post(permits).servletPath(permits).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("true",created.getHeader("Idempotency-Replayed"));
  String decision="/v1/tenants/"+TENANT+"/arrivals/"+ARRIVAL+"/decision";
  when(visits().decide(ACCOUNT,TENANT,ARRIVAL,new VisitModels.Decision("APPROVE")))
   .thenThrow(new BusinessProblem("ARRIVAL_EXPIRED",409,"O prazo de resposta desta chegada terminou."));
  var expired=mvc.perform(post(decision).servletPath(decision).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}")).andReturn().getResponse();
  assertEquals(409,expired.getStatus()); assertTrue(expired.getContentAsString().contains("ARRIVAL_EXPIRED"));
  String assisted="/v1/tenants/"+TENANT+"/arrivals/"+ARRIVAL+"/assisted-decision";
  assertEquals(403,mvc.perform(post(assisted).servletPath(assisted).secure(true).header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw))
   .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"DENY\",\"source\":\"INTERCOM\",\"note\":\"Interfone\"}"))
   .andReturn().getResponse().getStatus());
  verify(visits(),never()).assistedDecision(any(),any(),any(),any(),any());
 }
 @Test void reservationGuestsNeedSessionCsrfAndOperationKey() throws Exception {
  start(true);
  String raw=session("aal1");
  UUID reservation=UUID.fromString("77777777-7777-4777-8777-777777777777");
  String route="/v1/tenants/"+TENANT+"/reservations/"+reservation+"/guests";
  assertEquals(401,mvc.perform(get(route).servletPath(route).secure(true)).andReturn().getResponse().getStatus());
  when(visits().reservationGuests(ACCOUNT,TENANT,reservation)).thenReturn(new VisitModels.PermitPage(List.of(permit()),false));
  var listed=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,listed.getStatus()); assertEquals("no-store",listed.getHeader("Cache-Control"));
  String body="{\"visitorName\":\"Pessoa Fictícia\",\"documentLast4\":null}";
  assertEquals(403,mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(),"Sem CSRF");
  var csrf=csrf();
  var noKey=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave: "+noKey.getStatus());
  verify(visits(),never()).addGuest(any(),any(),any(),any(),any());
  when(visits().addGuest(ACCOUNT,TENANT,reservation,KEY,new VisitModels.AddGuest("Pessoa Fictícia",null)))
   .thenReturn(new VisitModels.PermitMutation(permit(),false,201));
  var created=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("false",created.getHeader("Idempotency-Replayed"));
 }
 @Test void conciergeUnitsExistWithVisitsOnly() throws Exception {
  start(true);
  String raw=session("aal2");
  when(context.getBean(ParcelService.class).units(ACCOUNT,TENANT,AssuranceLevel.AAL2,null)).thenReturn(List.of());
  String units="/v1/tenants/"+TENANT+"/concierge/units";
  assertEquals(200,mvc.perform(get(units).servletPath(units).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse().getStatus());
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(VisitController.class).isEmpty());
  assertTrue(context.getBeansOfType(ConciergeUnitsController.class).isEmpty(),"Unidades da portaria somem sem encomendas nem visitas");
  String route="/v1/tenants/"+TENANT+"/arrivals";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,session("aal2"))))
   .andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(visits());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,VisitController.class,ConciergeUnitsController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean VisitService testVisits() { return mock(VisitService.class); }
  @Bean ParcelService testParcels() { return mock(ParcelService.class); }
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
