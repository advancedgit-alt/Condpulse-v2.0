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
import br.com.condpulse.announcements.api.AnnouncementController;
import br.com.condpulse.announcements.application.AnnouncementModels;
import br.com.condpulse.announcements.application.AnnouncementService;
import br.com.condpulse.identity.application.AssuranceLevel;
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

/** Cadeia HTTP real dos avisos; regras e banco são cobertos por AnnouncementJdbcTest. */
class AnnouncementSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID NOTICE=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final String KEY="announce-http-key-0001";
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
  if(enabled) properties.put("condpulse.announcements.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-announcements",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private AnnouncementService service() { return context.getBean(AnnouncementService.class); }
 private String session(String aal) {
  var jwt=Jwt.withTokenValue("synthetic-announcements").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).claim("aal",aal).build();
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(jwt); return raw;
 }
 private String[] csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }
 private static AnnouncementModels.ManagedAnnouncement managed(String state) {
  return new AnnouncementModels.ManagedAnnouncement(NOTICE,"Assembleia fictícia","Texto fictício.","TENANT",List.of(),state,true,NOW,NOW,
   "DRAFT".equals(state)?null:NOW,null,0,0);
 }

 @Test void managementListAndDraftNeedSessionCsrfAndKey() throws Exception {
  start(true);
  String raw=session("aal2");
  when(service().managed(ACCOUNT,TENANT,AssuranceLevel.AAL2)).thenReturn(new AnnouncementModels.ManagedPage(List.of(managed("DRAFT")),false));
  String route="/v1/tenants/"+TENANT+"/management/announcements";
  var listed=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,listed.getStatus()); assertEquals("no-store",listed.getHeader("Cache-Control"));
  String body="{\"title\":\"Assembleia fictícia\",\"body\":\"Texto fictício.\",\"audience\":\"TENANT\",\"audienceIds\":[]}";
  assertEquals(403,mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
  var csrf=csrf();
  var noKey=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave de idempotência: "+noKey.getStatus());
  verify(service(),never()).createDraft(any(),any(),any(),any(),any());
  when(service().createDraft(ACCOUNT,TENANT,AssuranceLevel.AAL2,KEY,new AnnouncementModels.Draft("Assembleia fictícia","Texto fictício.","TENANT",List.of())))
   .thenReturn(new AnnouncementModels.DraftMutation(managed("DRAFT"),false,201));
  var created=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("false",created.getHeader("Idempotency-Replayed"));
  String publish=route+"/"+NOTICE+"/publish";
  when(service().publish(ACCOUNT,TENANT,AssuranceLevel.AAL2,NOTICE,new AnnouncementModels.Publish(0L)))
   .thenThrow(new BusinessProblem("ANNOUNCEMENT_CHANGED",409,"O aviso foi alterado. Atualize a lista antes de continuar."));
  var conflict=mvc.perform(post(publish).servletPath(publish).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andReturn().getResponse();
  assertEquals(409,conflict.getStatus()); assertTrue(conflict.getContentAsString().contains("ANNOUNCEMENT_CHANGED"));
 }
 @Test void unitListHasNoBodyAndOpeningRequiresCsrf() throws Exception {
  start(true);
  String raw=session("aal1");
  when(service().unitAnnouncements(ACCOUNT,TENANT,UNIT)).thenReturn(new AnnouncementModels.UnitPage(
   List.of(new AnnouncementModels.UnitAnnouncement(NOTICE,"Assembleia fictícia",NOW,null)),false));
  String list="/v1/tenants/"+TENANT+"/units/"+UNIT+"/announcements";
  var listed=mvc.perform(get(list).servletPath(list).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,listed.getStatus()); assertFalse(listed.getContentAsString().contains("\"body\""),"Lista sem o texto do aviso");
  String open="/v1/tenants/"+TENANT+"/announcements/"+NOTICE+"/open";
  assertEquals(403,mvc.perform(post(open).servletPath(open).secure(true).header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus());
  verify(service(),never()).open(any(),any(),any());
  var csrf=csrf();
  when(service().open(ACCOUNT,TENANT,NOTICE)).thenReturn(new AnnouncementModels.OpenedAnnouncement(NOTICE,"Assembleia fictícia","Texto fictício.",NOW,NOW));
  var opened=mvc.perform(post(open).servletPath(open).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])).andReturn().getResponse();
  assertEquals(200,opened.getStatus()); assertTrue(opened.getContentAsString().contains("\"openedAt\""));
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(AnnouncementController.class).isEmpty());
  String route="/v1/tenants/"+TENANT+"/units/"+UNIT+"/announcements";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,session("aal1")))).andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(service());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,AnnouncementController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean AnnouncementService testAnnouncements() { return mock(AnnouncementService.class); }
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
