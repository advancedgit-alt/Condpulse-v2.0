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
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.notifications.api.NotificationController;
import br.com.condpulse.notifications.application.NotificationModels;
import br.com.condpulse.notifications.application.NotificationService;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
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

/** Cadeia HTTP real da caixa interna; regras e banco são cobertos por OutboxWorkerJdbcTest. */
class NotificationSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID NOTICE=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final UUID PARCEL=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
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
  if(enabled) properties.put("condpulse.notifications.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-notifications",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private NotificationService inbox() { return context.getBean(NotificationService.class); }
 private String session() {
  var jwt=Jwt.withTokenValue("synthetic-notifications").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).claim("aal","aal1").build();
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(jwt); return raw;
 }
 private String[] csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }
 private static NotificationModels.Notification notice(String state,Instant readAt) {
  return new NotificationModels.Notification(NOTICE,UNIT,"PARCEL_RECEIVED",PARCEL,"RECEIVED",state,NOW,readAt);
 }

 @Test void inboxIsReadWithoutCacheAndCarriesNoPersonalData() throws Exception {
  start(true);
  String raw=session();
  when(inbox().unit(ACCOUNT,TENANT,UNIT)).thenReturn(new NotificationModels.Page(List.of(notice("AVAILABLE",null)),1));
  String route="/v1/tenants/"+TENANT+"/units/"+UNIT+"/notifications";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  String body=response.getContentAsString();
  assertTrue(body.contains("\"state\":\"AVAILABLE\"") && body.contains("\"unread\":1"),body);
  assertFalse(body.contains("visitorName") || body.contains("description"),"Aviso sem dado do registro");
  assertEquals(401,mvc.perform(get(route).servletPath(route).secure(true)).andReturn().getResponse().getStatus());
 }
 @Test void openingRequiresCsrfAndIsTheOnlyWayToMarkRead() throws Exception {
  start(true);
  String raw=session();
  String open="/v1/tenants/"+TENANT+"/notifications/"+NOTICE+"/open";
  assertEquals(403,mvc.perform(post(open).servletPath(open).secure(true).header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus());
  verify(inbox(),never()).open(any(),any(),any());
  var csrf=csrf();
  when(inbox().open(ACCOUNT,TENANT,NOTICE)).thenReturn(notice("READ_IN_APP",NOW));
  var opened=mvc.perform(post(open).servletPath(open).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])).andReturn().getResponse();
  assertEquals(200,opened.getStatus()); assertEquals("no-store",opened.getHeader("Cache-Control"));
  assertTrue(opened.getContentAsString().contains("\"state\":\"READ_IN_APP\""));
  when(inbox().open(ACCOUNT,TENANT,PARCEL)).thenThrow(new BusinessProblem("NOTIFICATION_NOT_FOUND",404,"Aviso não encontrado."));
  String other="/v1/tenants/"+TENANT+"/notifications/"+PARCEL+"/open";
  var missing=mvc.perform(post(other).servletPath(other).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])).andReturn().getResponse();
  assertEquals(404,missing.getStatus()); assertTrue(missing.getContentAsString().contains("NOTIFICATION_NOT_FOUND"));
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(NotificationController.class).isEmpty());
  String route="/v1/tenants/"+TENANT+"/units/"+UNIT+"/notifications";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,session()))).andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(inbox());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,NotificationController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean NotificationService testInbox() { return mock(NotificationService.class); }
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
