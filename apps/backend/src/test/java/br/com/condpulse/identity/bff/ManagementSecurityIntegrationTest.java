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
import br.com.condpulse.audit.api.AuditController;
import br.com.condpulse.audit.application.AuditQuery;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.reservations.api.AmenityManagementController;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.reservations.application.AmenityManagementService;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.api.UnitManagementController;
import br.com.condpulse.tenancy.application.UnitManagementModels;
import br.com.condpulse.tenancy.application.UnitManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Cadeia HTTP real das rotas de gestão; regras e banco são cobertos por LocalManagementJdbcTest. */
class ManagementSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID AMENITY=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
 private static final String BASE="/v1/tenants/"+TENANT+"/management";
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;
 private JwtDecoder decoder;
 private UnitManagementService units;
 private AmenityManagementService amenities;
 private AuditQuery audit;

 @BeforeEach void setup() {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-management",Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.management.enabled","true")));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class); decoder=context.getBean("testDecoder",JwtDecoder.class);
  units=context.getBean(UnitManagementService.class); amenities=context.getBean(AmenityManagementService.class);
  audit=context.getBean(AuditQuery.class);
  mvc=MockMvcBuilders.webAppContextSetup(context)
   .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }

 private Jwt jwt(String aal) {
  var builder=Jwt.withTokenValue("synthetic-access").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600));
  if(aal!=null) builder.claim("aal",aal);
  return builder.build();
 }
 private String session(String aal) {
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(jwt(aal)); return raw;
 }
 private String[] csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }

 @Test void aal2CookieSessionCreatesUnitWithCsrfAndPassesAssurance() throws Exception {
  String raw=session("aal2"); var csrf=csrf();
  var request=new UnitManagementModels.CreateUnit("101",null);
  when(units.createUnit(ACCOUNT,TENANT,AssuranceLevel.AAL2,request))
   .thenReturn(new UnitManagementModels.Unit(UNIT,"101",null,null,"ACTIVE",0));
  String route=BASE+"/units";
  var response=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"101\",\"blockId\":null}")).andReturn().getResponse();
  assertEquals(201,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains(UNIT.toString()));
  verify(units).createUnit(ACCOUNT,TENANT,AssuranceLevel.AAL2,request); verifyNoMoreInteractions(units);
 }
 @Test void aal1SessionReachesServiceAsAal1AndMfaProblemIsRendered() throws Exception {
  String raw=session(null); var csrf=csrf();
  var request=new UnitManagementModels.CreateUnit("102",null);
  when(units.createUnit(ACCOUNT,TENANT,AssuranceLevel.AAL1,request))
   .thenThrow(new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator."));
  String route=BASE+"/units";
  var response=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"102\"}")).andReturn().getResponse();
  assertEquals(403,response.getStatus()); assertTrue(response.getContentAsString().contains("\"code\":\"MFA_REQUIRED\""));
 }
 @Test void managementMutationWithoutCsrfOrIdentityNeverReachesServices() throws Exception {
  String route=BASE+"/amenities";
  var noCsrf=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,session("aal2"))).contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andReturn().getResponse();
  assertEquals(403,noCsrf.getStatus());
  var anonymous=mvc.perform(get(BASE+"/units").servletPath(BASE+"/units").secure(true)).andReturn().getResponse();
  assertEquals(401,anonymous.getStatus());
  verifyNoInteractions(units,amenities,audit);
 }
 @Test void bearerAal2ReadsUnitsAndWeakOrForeignClaimsStayAal1() throws Exception {
  when(decoder.decode("synthetic-bearer-aal2")).thenReturn(jwt("aal2"));
  when(decoder.decode("synthetic-bearer-other")).thenReturn(jwt("AAL2"));
  when(units.units(eq(ACCOUNT),eq(TENANT),any())).thenReturn(new UnitManagementModels.UnitPage(List.of(),false));
  String route=BASE+"/units";
  assertEquals(200,mvc.perform(get(route).servletPath(route).secure(true).header("Authorization","Bearer synthetic-bearer-aal2"))
   .andReturn().getResponse().getStatus());
  assertEquals(200,mvc.perform(get(route).servletPath(route).secure(true).header("Authorization","Bearer synthetic-bearer-other"))
   .andReturn().getResponse().getStatus());
  verify(units).units(ACCOUNT,TENANT,AssuranceLevel.AAL2); verify(units).units(ACCOUNT,TENANT,AssuranceLevel.AAL1);
 }
 @Test void amenityUpdateAndAuditPassPathAndQueryParameters() throws Exception {
  String raw=session("aal2"); var csrf=csrf();
  var policy=new AmenityManagementModels.PolicyInput(1,List.of(1,2),"08:00","22:00",60,30,240,1,120);
  var input=new AmenityManagementModels.AmenityInput("Salao",50,true,15000L,30,true,policy,7L,"Regra de 2027");
  when(amenities.update(ACCOUNT,TENANT,AssuranceLevel.AAL2,AMENITY,input)).thenReturn(
   new AmenityManagementModels.ManagedAmenity(AMENITY,"Salao",50,true,15000,30,true,2,8,"CONFIGURED",null));
  String route=BASE+"/amenities/"+AMENITY;
  var response=mvc.perform(put(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("""
    {"name":"Salao","capacity":50,"approvalRequired":true,"feeCents":15000,"bufferMinutes":30,"active":true,
     "policy":{"schemaVersion":1,"weekDays":[1,2],"opensAt":"08:00","closesAt":"22:00","minimumLeadMinutes":60,
      "maximumAdvanceDays":30,"maximumDurationMinutes":240,"maximumActivePerUnit":1,"cancellationLeadMinutes":120},
     "expectedVersion":7,"reason":"Regra de 2027"}
    """)).andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertTrue(response.getContentAsString().contains("\"policyVersion\":2"));
  when(audit.page(ACCOUNT,TENANT,AssuranceLevel.AAL2,"abc",5)).thenReturn(new AuditQuery.Page(List.of(),null));
  String auditRoute=BASE+"/audit-events";
  var events=mvc.perform(get(auditRoute).servletPath(auditRoute).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))
   .param("cursor","abc").param("limit","5")).andReturn().getResponse();
  assertEquals(200,events.getStatus()); assertEquals("no-store",events.getHeader("Cache-Control"));
  verify(audit).page(ACCOUNT,TENANT,AssuranceLevel.AAL2,"abc",5);
 }
 @Test void unlistedManagementMethodsAndNeighborsStayDenied() throws Exception {
  String raw=session("aal2"); var csrf=csrf();
  String unit=BASE+"/units/"+UNIT;
  assertEquals(403,mvc.perform(get(unit).servletPath(unit).secure(true).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(delete(unit).servletPath(unit).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]))
   .andReturn().getResponse().getStatus());
  String other=BASE+"/memberships";
  assertEquals(403,mvc.perform(get(other).servletPath(other).secure(true).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus());
  verifyNoInteractions(units,amenities,audit);
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,UnitManagementController.class,AmenityManagementController.class,
   AuditController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean UnitManagementService testUnits() { return mock(UnitManagementService.class); }
  @Bean AmenityManagementService testAmenities() { return mock(AmenityManagementService.class); }
  @Bean AuditQuery testAudit() { return mock(AuditQuery.class); }
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
   when(limits.mfa(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   return new BffController(sessions,csrf,Clock.fixed(NOW,ZoneOffset.UTC),limits);
  }
 }
}
