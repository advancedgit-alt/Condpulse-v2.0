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
import br.com.condpulse.reservations.api.ReservationController;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.api.TenantUnitsController;
import br.com.condpulse.tenancy.application.TenantUnitsQuery;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Roteamento HTTP, identidade e CSRF reais; regras de negocio e JDBC possuem testes separados. */
class ReservationSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID AMENITY=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final UUID RESERVATION=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
 private static final Instant START=Instant.parse("2026-11-01T15:00:00Z");
 private static final Instant END=Instant.parse("2026-11-01T17:00:00Z");
 private static final String KEY="synthetic-create-request-01";
 private static final String ROUTE="/v1/tenants/"+TENANT+"/reservations";
 private static final ReservationModels.Create CREATE=new ReservationModels.Create(UNIT,AMENITY,START,END,4);
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;
 private ReservationService reservations;
 private TenantUnitsQuery units;

 @BeforeEach void setup() {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-reservations",Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.reservations.enabled","true")));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  reservations=context.getBean(ReservationService.class);
  units=context.getBean(TenantUnitsQuery.class);
  mvc=MockMvcBuilders.webAppContextSetup(context)
   .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() {
  SecurityContextHolder.clearContext();
  if(context!=null) context.close();
 }

 @Test void authenticatedCreatePassesActorTenantBodyAndIdempotencyKeyToService() throws Exception {
  var csrf=csrf(); String raw=authenticatedSession();
  when(reservations.create(ACCOUNT,TENANT,KEY,CREATE))
   .thenReturn(new ReservationModels.Mutation(view(),false,201));
  var response=mvc.perform(create(csrf).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse();
  assertEquals(201,response.getStatus());
  assertEquals("no-store",response.getHeader("Cache-Control"));
  assertEquals("false",response.getHeader("Idempotency-Replayed"));
  assertTrue(response.getContentAsString().contains("\"reservationId\":\""+RESERVATION+"\""));
  assertTrue(response.getContentAsString().contains("\"status\":\"CONFIRMED\""));
  assertFalse(response.getContentAsString().contains(raw));
  verify(reservations).create(ACCOUNT,TENANT,KEY,CREATE);
  verifyNoMoreInteractions(reservations); verifyNoInteractions(units);
 }

 @Test void createWithoutCsrfIsRejectedBeforeAuthenticationOrBusinessService() throws Exception {
  var response=mvc.perform(post(ROUTE).servletPath(ROUTE).secure(true)
   .cookie(new Cookie(BffCookies.SESSION,SessionSecrets.newToken())).header("Origin",ORIGIN)
   .header("Idempotency-Key",KEY).contentType(MediaType.APPLICATION_JSON).content(createBody()))
   .andReturn().getResponse();
  assertEquals(403,response.getStatus());
  verifyNoInteractions(reservations,units,sessions);
 }

 @Test void validCsrfWithoutIdentityCannotCreateReservation() throws Exception {
  var response=mvc.perform(create(csrf())).andReturn().getResponse();
  assertEquals(401,response.getStatus());
  assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
  verifyNoInteractions(reservations,units,sessions);
 }

 @Test void unitsRouteUsesAuthenticatedAccountAndRequestedTenant() throws Exception {
  String raw=authenticatedSession();
  when(units.execute(ACCOUNT,TENANT)).thenReturn(List.of(new TenantUnitsQuery.Unit(UNIT,"101",null,null)));
  String route="/v1/tenants/"+TENANT+"/units";
  var response=mvc.perform(get(route).servletPath(route).secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,response.getStatus());
  assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains("\"id\":\""+UNIT+"\""));
  verify(units).execute(ACCOUNT,TENANT); verifyNoMoreInteractions(units);
  verifyNoInteractions(reservations);
 }

 @Test void businessConflictIsRenderedByRealProblemHandler() throws Exception {
  var csrf=csrf(); String raw=authenticatedSession();
  when(reservations.create(ACCOUNT,TENANT,KEY,CREATE))
   .thenThrow(new BusinessProblem("RESERVATION_CONFLICT",409,"O periodo ja esta ocupado."));
  var response=mvc.perform(create(csrf).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse();
  assertEquals(409,response.getStatus());
  assertNotNull(response.getContentType());
  assertTrue(response.getContentType().startsWith("application/problem+json"));
  assertTrue(response.getContentAsString().contains("\"code\":\"RESERVATION_CONFLICT\""));
  assertTrue(response.getContentAsString().contains("\"traceId\":\""));
  verify(reservations).create(ACCOUNT,TENANT,KEY,CREATE);
  verifyNoMoreInteractions(reservations); verifyNoInteractions(units);
 }

 private Csrf csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true))
   .andReturn().getResponse();
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
 private MockHttpServletRequestBuilder create(Csrf csrf) {
  return post(ROUTE).servletPath(ROUTE).secure(true).header("Origin",ORIGIN)
   .cookie(csrf.cookie()).header("X-CSRF-Token",csrf.token()).header("Idempotency-Key",KEY)
   .contentType(MediaType.APPLICATION_JSON).content(createBody());
 }
 private String createBody() {
  return """
   {"unitId":"%s","amenityId":"%s","startsAt":"%s","endsAt":"%s","acceptedPolicyVersion":4}
   """.formatted(UNIT,AMENITY,START,END);
 }
 private ReservationModels.View view() {
  return new ReservationModels.View(RESERVATION,UNIT,AMENITY,"CONFIRMED",START,END,START,END,4,2500,1);
 }
 private record Csrf(Cookie cookie,String token) {}

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,ReservationController.class,TenantUnitsController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean ReservationService testReservations() { return mock(ReservationService.class); }
  @Bean TenantUnitsQuery testUnits() { return mock(TenantUnitsQuery.class); }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   var settings=new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
   return new BffSecurityConfigurer(sessions,settings,csrf);
  }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf) {
   return new BffController(sessions,csrf,Clock.fixed(NOW,ZoneOffset.UTC));
  }
 }
}
