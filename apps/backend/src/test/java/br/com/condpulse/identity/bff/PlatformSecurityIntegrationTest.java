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
import br.com.condpulse.platform.api.PlatformController;
import br.com.condpulse.platform.application.PlatformModels;
import br.com.condpulse.platform.application.PlatformService;
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

/** Cadeia HTTP real do painel master; regras e banco são cobertos por PlatformAdministrationJdbcTest. */
class PlatformSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
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
  if(enabled) properties.put("condpulse.platform.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-platform",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private PlatformService platform() { return context.getBean(PlatformService.class); }
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
 private PlatformModels.Tenant tenant(boolean active,long version) {
  return new PlatformModels.Tenant(TENANT,"Condomínio Fictício","America/Sao_Paulo",active,version,NOW,NOW,null,"PLATFORM",
   new PlatformModels.TenantUsage(2,1,1,NOW),null);
 }

 @Test void sessionAndCatalogPassAccountAndAssuranceWithoutCache() throws Exception {
  start(true);
  String raw=session("aal2");
  when(platform().session(ACCOUNT,AssuranceLevel.AAL2)).thenReturn(new PlatformModels.PlatformSession(ACCOUNT,"PLATFORM_ADMIN",
   List.of("platform.audit.read","tenants.manage","tenants.read"),false));
  when(platform().tenants(ACCOUNT,AssuranceLevel.AAL2,null)).thenReturn(new PlatformModels.TenantPage(List.of(tenant(true,0)),false));
  when(platform().tenants(ACCOUNT,AssuranceLevel.AAL2,"Sol 100%")).thenReturn(new PlatformModels.TenantPage(List.of(),false));
  var session=mvc.perform(get("/v1/platform/session").servletPath("/v1/platform/session").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,session.getStatus()); assertEquals("no-store",session.getHeader("Cache-Control"));
  assertTrue(session.getContentAsString().contains("\"mfaRequired\":false"));
  var catalog=mvc.perform(get("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,catalog.getStatus()); assertTrue(catalog.getContentAsString().contains(TENANT.toString()));
  verify(platform()).session(ACCOUNT,AssuranceLevel.AAL2); verify(platform()).tenants(ACCOUNT,AssuranceLevel.AAL2,null);
  var search=mvc.perform(get("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true).param("q","Sol 100%")
   .cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,search.getStatus()); assertTrue(search.getContentAsString().contains("\"tenants\":[]"));
  verify(platform()).tenants(ACCOUNT,AssuranceLevel.AAL2,"Sol 100%");
 }
 @Test void bearerTokenReachesTheServiceWithItsAssurance() throws Exception {
  start(true);
  var jwt=Jwt.withTokenValue("synthetic-bearer").header("alg","ES256").subject(ACCOUNT.toString()).claim("aal","aal2")
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build();
  when(context.getBean(JwtDecoder.class).decode("synthetic-bearer")).thenReturn(jwt);
  when(platform().session(ACCOUNT,AssuranceLevel.AAL2)).thenReturn(new PlatformModels.PlatformSession(ACCOUNT,"PLATFORM_ADMIN",List.of(),false));
  var response=mvc.perform(get("/v1/platform/session").servletPath("/v1/platform/session").secure(true)
   .header("Authorization","Bearer synthetic-bearer")).andReturn().getResponse();
  assertEquals(200,response.getStatus()); verify(platform()).session(ACCOUNT,AssuranceLevel.AAL2);
 }
 @Test void aal1SessionReachesServiceAsAal1AndProblemIsRendered() throws Exception {
  start(true);
  String raw=session(null);
  when(platform().tenants(ACCOUNT,AssuranceLevel.AAL1,null)).thenThrow(new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator."));
  var response=mvc.perform(get("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(403,response.getStatus()); assertTrue(response.getContentAsString().contains("\"code\":\"MFA_REQUIRED\""));
 }
 @Test void tenantUpdateRequiresCsrfAndPassesTheReadVersionAndReason() throws Exception {
  start(true);
  String raw=session("aal2");
  String route="/v1/platform/tenants/"+TENANT;
  var noCsrf=mvc.perform(put(route).servletPath(route).secure(true).header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw))
   .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Novo\",\"active\":false,\"expectedVersion\":3,\"reason\":\"Contrato\"}"))
   .andReturn().getResponse();
  assertEquals(403,noCsrf.getStatus()); verifyNoInteractions(platform());
  var request=new PlatformModels.UpdateTenant("Novo",false,3L,"Contrato");
  when(platform().updateTenant(ACCOUNT,AssuranceLevel.AAL2,TENANT,request)).thenReturn(new PlatformModels.TenantChange(tenant(false,4),true));
  var csrf=csrf();
  var ok=mvc.perform(put(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Novo\",\"active\":false,\"expectedVersion\":3,\"reason\":\"Contrato\"}"))
   .andReturn().getResponse();
  assertEquals(200,ok.getStatus()); assertEquals("no-store",ok.getHeader("Cache-Control"));
  assertTrue(ok.getContentAsString().contains("\"applied\":true"));
  verify(platform()).updateTenant(ACCOUNT,AssuranceLevel.AAL2,TENANT,request);
 }
 @Test void auditPassesCursorAndLimit() throws Exception {
  start(true);
  String raw=session("aal2");
  when(platform().audit(ACCOUNT,AssuranceLevel.AAL2,"abc",5)).thenReturn(new PlatformModels.AuditPage(List.of(),null));
  var response=mvc.perform(get("/v1/platform/audit-events").servletPath("/v1/platform/audit-events").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,raw)).param("cursor","abc").param("limit","5")).andReturn().getResponse();
  assertEquals(200,response.getStatus()); verify(platform()).audit(ACCOUNT,AssuranceLevel.AAL2,"abc",5);
 }
 @Test void anonymousAndUnlistedRoutesNeverReachTheService() throws Exception {
  start(true);
  assertEquals(401,mvc.perform(get("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true))
   .andReturn().getResponse().getStatus());
  int anonymousPut=mvc.perform(put("/v1/platform/tenants/"+TENANT).servletPath("/v1/platform/tenants/"+TENANT).secure(true)
   .header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
   .content("{\"name\":null,\"active\":false,\"expectedVersion\":0,\"reason\":\"Anônimo\"}")).andReturn().getResponse().getStatus();
  assertTrue(anonymousPut==401 || anonymousPut==403,"PUT anônimo é recusado antes do serviço: "+anonymousPut);
  String raw=session("aal2"); var csrf=csrf();
  // Criação sem sessão não chega ao serviço.
  int anonymousPost=mvc.perform(post("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]).header("Idempotency-Key","k".repeat(20))
   .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
  assertTrue(anonymousPost==401 || anonymousPost==403,"POST anônimo é recusado antes do serviço: "+anonymousPost);
  String route="/v1/platform/tenants/"+TENANT;
  assertEquals(403,mvc.perform(delete(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]))
   .andReturn().getResponse().getStatus());
  String nested=route+"/memberships";
  assertEquals(403,mvc.perform(get(nested).servletPath(nested).secure(true).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus());
  verifyNoInteractions(platform());
 }
 @Test void creationRequiresCsrfAndPassesTheIdempotencyKeyAndAssurance() throws Exception {
  start(true);
  String raw=session("aal2");
  String body="{\"name\":\"Residencial Sol\",\"timeZone\":\"America/Sao_Paulo\",\"units\":[\"101\",\"102\"],"
   +"\"manager\":{\"displayName\":\"Gestora Ficticia\",\"email\":\"gestora@example.invalid\",\"validDays\":7},"
   +"\"amenity\":null,\"reason\":\"Contrato assinado\"}";
  var noCsrf=mvc.perform(post("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw)).header("Idempotency-Key","k".repeat(20)).contentType(MediaType.APPLICATION_JSON).content(body))
   .andReturn().getResponse();
  assertEquals(403,noCsrf.getStatus()); verifyNoInteractions(platform());
  var request=new PlatformModels.CreateTenant("Residencial Sol","America/Sao_Paulo",List.of("101","102"),
   new PlatformModels.ManagerInput("Gestora Ficticia","gestora@example.invalid",7),null,"Contrato assinado");
  UUID invitation=UUID.fromString("33333333-3333-4333-8333-333333333333");
  when(platform().createTenant(ACCOUNT,AssuranceLevel.AAL2,"k".repeat(20),request)).thenReturn(
   new PlatformModels.TenantCreated(tenant(true,0),true,invitation,"T".repeat(43),NOW.plusSeconds(604800)));
  when(platform().createTenant(ACCOUNT,AssuranceLevel.AAL2,"r".repeat(20),request)).thenReturn(
   new PlatformModels.TenantCreated(tenant(true,0),false,null,null,null));
  var csrf=csrf();
  var created=mvc.perform(post("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .header("Idempotency-Key","k".repeat(20)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("no-store",created.getHeader("Cache-Control"));
  assertTrue(created.getContentAsString().contains("\"invitationToken\":\""+"T".repeat(43)));
  var replay=mvc.perform(post("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .header("Idempotency-Key","r".repeat(20)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(200,replay.getStatus()); assertTrue(replay.getContentAsString().contains("\"applied\":false"));
  verify(platform()).createTenant(ACCOUNT,AssuranceLevel.AAL2,"k".repeat(20),request);
 }
 @Test void detailAndManagerInvitationRoutesReachTheService() throws Exception {
  start(true);
  String raw=session("aal2");
  String route="/v1/platform/tenants/"+TENANT;
  when(platform().tenant(ACCOUNT,AssuranceLevel.AAL2,TENANT)).thenReturn(new PlatformModels.TenantDetail(tenant(true,0),List.of(),List.of(),List.of()));
  var detail=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,detail.getStatus()); assertTrue(detail.getContentAsString().contains("\"activeUnits\":2"));
  var request=new PlatformModels.IssueManagerInvitation("Gestor","gestor@example.invalid",3,"Troca de síndico");
  when(platform().issueManagerInvitation(ACCOUNT,AssuranceLevel.AAL2,TENANT,request)).thenReturn(
   new PlatformModels.ManagerInvitationIssued(TENANT,"T".repeat(43),NOW));
  var csrf=csrf();
  String invite=route+"/manager-invitations";
  var issued=mvc.perform(post(invite).servletPath(invite).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON)
   .content("{\"displayName\":\"Gestor\",\"email\":\"gestor@example.invalid\",\"validDays\":3,\"reason\":\"Troca de síndico\"}"))
   .andReturn().getResponse();
  assertEquals(201,issued.getStatus()); verify(platform()).issueManagerInvitation(ACCOUNT,AssuranceLevel.AAL2,TENANT,request);
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(PlatformController.class).isEmpty());
  var response=mvc.perform(get("/v1/platform/tenants").servletPath("/v1/platform/tenants").secure(true)
   .cookie(new Cookie(BffCookies.SESSION,session("aal2")))).andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(platform());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,PlatformController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean PlatformService testPlatform() { return mock(PlatformService.class); }
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
