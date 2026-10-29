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
import br.com.condpulse.visits.api.AccessController;
import br.com.condpulse.visits.application.AccessModels;
import br.com.condpulse.visits.application.AccessService;
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

/** Cadeia HTTP real do registro manual de entrada e saída; regras e banco são cobertos por AccessJdbcTest. */
class AccessSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID ARRIVAL=UUID.fromString("66666666-6666-4666-8666-666666666666");
 private static final UUID ENTRY=UUID.fromString("77777777-7777-4777-8777-777777777777");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final String KEY="access-http-key-00001";
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
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-access",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private AccessService access() { return context.getBean(AccessService.class); }
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
 private static AccessModels.AccessRecord record() {
  return new AccessModels.AccessRecord(ENTRY,UNIT,"101",null,"Pessoa Fictícia",null,ARRIVAL,null,null,"MANUAL_IN","CONCIERGE","DOCUMENT_CHECKED",
   null,NOW,true,null,null);
 }

 @Test void listsPassAssuranceAndTheUnitWithoutCache() throws Exception {
  start(true);
  String raw=session("aal2");
  when(access().records(ACCOUNT,TENANT,AssuranceLevel.AAL2)).thenReturn(new AccessModels.RecordPage(List.of(record()),false));
  String route="/v1/tenants/"+TENANT+"/access-events";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains("\"source\":\"CONCIERGE\""));
  when(access().unitRecords(ACCOUNT,TENANT,UNIT)).thenReturn(new AccessModels.RecordPage(List.of(),false));
  String unit="/v1/tenants/"+TENANT+"/units/"+UNIT+"/access-events";
  assertEquals(200,mvc.perform(get(unit).servletPath(unit).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse().getStatus());
  verify(access()).unitRecords(ACCOUNT,TENANT,UNIT);
 }
 @Test void registrationsRequireCsrfAndKey() throws Exception {
  start(true);
  String raw=session("aal2");
  String entries="/v1/tenants/"+TENANT+"/access-events/entries";
  String body="{\"arrivalId\":\""+ARRIVAL+"\",\"evidence\":\"DOCUMENT_CHECKED\"}";
  assertEquals(403,mvc.perform(post(entries).servletPath(entries).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
  var csrf=csrf();
  var noKey=mvc.perform(post(entries).servletPath(entries).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave de idempotência: "+noKey.getStatus());
  verifyNoInteractions(access());
  when(access().entry(ACCOUNT,TENANT,AssuranceLevel.AAL2,KEY,new AccessModels.RecordEntry(ARRIVAL,null,"DOCUMENT_CHECKED",null)))
   .thenReturn(new AccessModels.RecordMutation(record(),false,201));
  var created=mvc.perform(post(entries).servletPath(entries).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("false",created.getHeader("Idempotency-Replayed"));
  // Saída sem corpo (observação opcional) e recusa de estado com o código do serviço.
  String exit="/v1/tenants/"+TENANT+"/access-events/"+ENTRY+"/exit";
  when(access().exit(ACCOUNT,TENANT,AssuranceLevel.AAL2,ENTRY,KEY,null))
   .thenThrow(new BusinessProblem("ACCESS_EXIT_ALREADY_RECORDED",409,"A saída desta entrada já foi registrada."));
  var repeated=mvc.perform(post(exit).servletPath(exit).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])).andReturn().getResponse();
  assertEquals(409,repeated.getStatus()); assertTrue(repeated.getContentAsString().contains("ACCESS_EXIT_ALREADY_RECORDED"));
  String correction="/v1/tenants/"+TENANT+"/access-events/"+ENTRY+"/correction";
  assertEquals(403,mvc.perform(post(correction).servletPath(correction).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Pessoa não entrou\"}"))
   .andReturn().getResponse().getStatus());
  verify(access(),never()).correct(any(),any(),any(),any(),any(),any());
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(AccessController.class).isEmpty());
  String route="/v1/tenants/"+TENANT+"/access-events";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,session("aal2"))))
   .andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(access());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,AccessController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean AccessService testAccess() { return mock(AccessService.class); }
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
