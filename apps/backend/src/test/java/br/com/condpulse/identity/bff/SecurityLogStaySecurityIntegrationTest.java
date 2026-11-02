package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.securitylog.api.SecurityLogController;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.securitylog.application.SecurityLogService;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.stays.api.StayController;
import br.com.condpulse.stays.api.VisitorIdentityController;
import br.com.condpulse.stays.application.VisitorIdentityService;
import br.com.condpulse.stays.application.StayModels;
import br.com.condpulse.stays.application.StayService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Regressão HTTP: os controllers existiam, mas a cadeia denyAll recusava log e permanências antes dos serviços.
 * Regras reais de capacidade, tenancy e auditoria continuam cobertas por SecurityLogJdbcTest e StayJdbcTest. */
class SecurityLogStaySecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID STAY=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final Instant NOW=Instant.parse("2026-11-01T12:00:00Z");
 private static final String BASE="/v1/tenants/"+TENANT;
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 private WebSessionService sessions;

 private void start(boolean enabled) {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-security-log-stays",Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.security-log.enabled",Boolean.toString(enabled),
   "condpulse.visits.enabled",Boolean.toString(enabled))));
  context.register(TestConfiguration.class); context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private SecurityLogService log() { return context.getBean(SecurityLogService.class); }
 private StayService stays() { return context.getBean(StayService.class); }
 private Jwt jwt(String aal) {
  return Jwt.withTokenValue("synthetic-access").header("alg","ES256").subject(ACCOUNT.toString()).claim("aal",aal)
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build();
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
 private MockHttpServletRequestBuilder read(String path,String raw) {
  return get(path).servletPath(path).secure(true).cookie(new Cookie(BffCookies.SESSION,raw));
 }
 private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,String path,String raw,String[] csrf) {
  return request.servletPath(path).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON);
 }
 private void ok(MockHttpServletRequestBuilder request) throws Exception {
  var response=mvc.perform(request).andReturn().getResponse();
  assertEquals(200,response.getStatus(),response.getContentAsString()); assertEquals("no-store",response.getHeader("Cache-Control"));
 }

 @Test void logAndIntegrityReachServicesWithVerifiedIdentityAssuranceAndFilters() throws Exception {
  start(true); String raw=session("aal2");
  var filter=new SecurityLogModels.Filter("ROLE_GRANTED","INFO",ACCOUNT,NOW,NOW.plusSeconds(60),10L,5);
  when(log().page(ACCOUNT,TENANT,AssuranceLevel.AAL2,filter)).thenReturn(new SecurityLogModels.Page(List.of(),null));
  ok(read(BASE+"/security-log",raw).param("type","ROLE_GRANTED").param("severity","INFO").param("actorId",ACCOUNT.toString())
   .param("from",NOW.toString()).param("until",NOW.plusSeconds(60).toString()).param("beforeSeq","10").param("limit","5"));
  when(log().integrity(ACCOUNT,TENANT,AssuranceLevel.AAL2)).thenReturn(
   new SecurityLogModels.Integrity(true,0,0,"0".repeat(64),null,null,0,NOW));
  ok(read(BASE+"/security-log/integrity",raw));
  verify(log()).page(ACCOUNT,TENANT,AssuranceLevel.AAL2,filter); verify(log()).integrity(ACCOUNT,TENANT,AssuranceLevel.AAL2);
 }
 @Test void stayReadsPassStaffAssuranceUnitAndCivilDate() throws Exception {
  start(true); String raw=session("aal2"); var empty=new StayModels.StayPage(List.of(),false,NOW);
  when(stays().open(ACCOUNT,TENANT,AssuranceLevel.AAL2,true)).thenReturn(empty);
  ok(read(BASE+"/stays",raw).param("view","overdue"));
  var date=LocalDate.of(2026,11,1);
  when(stays().dayClose(ACCOUNT,TENANT,AssuranceLevel.AAL2,date)).thenReturn(
   new StayModels.DayClose(date,"America/Sao_Paulo",NOW,NOW.plusSeconds(86400),NOW,List.of(),false,0,"0".repeat(64)));
  ok(read(BASE+"/stays/day-close",raw).param("date",date.toString()));
  when(stays().unitOpen(ACCOUNT,TENANT,UNIT)).thenReturn(empty);
  ok(read(BASE+"/units/"+UNIT+"/stays",raw));
  when(stays().rules(ACCOUNT,TENANT,AssuranceLevel.AAL2)).thenReturn(new StayModels.Rules(List.of()));
  ok(read(BASE+"/stay-rules",raw));
  verify(stays()).open(ACCOUNT,TENANT,AssuranceLevel.AAL2,true);
  verify(stays()).dayClose(ACCOUNT,TENANT,AssuranceLevel.AAL2,date);
  verify(stays()).unitOpen(ACCOUNT,TENANT,UNIT); verify(stays()).rules(ACCOUNT,TENANT,AssuranceLevel.AAL2);
 }
 @Test void cookieWritesRequireCsrfThenDispatchOnlyTheDocumentedCommands() throws Exception {
  start(true); String raw=session("aal2"); String[] csrf=csrf();
  String justify=BASE+"/stays/"+STAY+"/justification",exit=BASE+"/units/"+UNIT+"/stays/"+STAY+"/exit-confirmation";
  String rules=BASE+"/stay-rules/VISITOR",export=BASE+"/security-log/exports";
  for(String route:List.of(justify,exit,export)) assertEquals(403,mvc.perform(post(route).servletPath(route).secure(true)
   .header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(put(rules).servletPath(rules).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
  verifyNoInteractions(log(),stays());
  var reason=new StayModels.Justify("Conferência fictícia da portaria");
  when(stays().justify(ACCOUNT,TENANT,AssuranceLevel.AAL2,STAY,reason)).thenReturn(new StayModels.Mutation(null,true));
  var justified=mvc.perform(write(post(justify),justify,raw,csrf).content("{\"reason\":\"Conferência fictícia da portaria\"}"))
   .andReturn().getResponse();
  assertEquals(200,justified.getStatus()); assertEquals("true",justified.getHeader("Idempotency-Replayed"));
  when(stays().confirmExit(ACCOUNT,TENANT,UNIT,STAY)).thenReturn(new StayModels.Mutation(null,false));
  ok(write(post(exit),exit,raw,csrf));
  var change=new StayModels.RuleChange(30,0,5,2L);
  when(stays().changeRule(ACCOUNT,TENANT,AssuranceLevel.AAL2,"VISITOR",change)).thenReturn(new StayModels.Rules(List.of()));
  ok(write(put(rules),rules,raw,csrf).content("{\"maxMinutes\":30,\"l1AfterMinutes\":0,\"l2AfterMinutes\":5,\"version\":2}"));
  var request=new SecurityLogModels.ExportRequest("Teste fictício de exportação",1L,3L);
  when(log().export(ACCOUNT,TENANT,AssuranceLevel.AAL2,request)).thenReturn(new SecurityLogModels.Export(null,List.of()));
  ok(write(post(export),export,raw,csrf).content("{\"reason\":\"Teste fictício de exportação\",\"fromSeq\":1,\"toSeq\":3}"));
  verify(stays()).justify(ACCOUNT,TENANT,AssuranceLevel.AAL2,STAY,reason);
  verify(stays()).confirmExit(ACCOUNT,TENANT,UNIT,STAY);
  verify(stays()).changeRule(ACCOUNT,TENANT,AssuranceLevel.AAL2,"VISITOR",change);
  verify(log()).export(ACCOUNT,TENANT,AssuranceLevel.AAL2,request);
 }
 @Test void nativeBearerWritesPassAuthenticationWithoutCookieCsrf() throws Exception {
  start(true);
  when(context.getBean("testDecoder",JwtDecoder.class).decode("synthetic-access")).thenReturn(jwt("aal2"));
  String route=BASE+"/stays/"+STAY+"/justification";
  var request=new StayModels.Justify("Teste nativo fictício");
  when(stays().justify(ACCOUNT,TENANT,AssuranceLevel.AAL2,STAY,request)).thenReturn(new StayModels.Mutation(null,false));
  ok(post(route).servletPath(route).secure(true).header("Authorization","Bearer synthetic-access")
   .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Teste nativo fictício\"}"));
  verify(stays()).justify(ACCOUNT,TENANT,AssuranceLevel.AAL2,STAY,request);
 }
 @Test void anonymousReadsAndUnknownMethodsStayDeniedWithoutServiceCalls() throws Exception {
  start(true);
  for(String route:List.of(BASE+"/security-log",BASE+"/security-log/integrity",BASE+"/stays",BASE+"/stays/day-close",
    BASE+"/units/"+UNIT+"/stays",BASE+"/stay-rules"))
   assertEquals(401,mvc.perform(get(route).servletPath(route).secure(true)).andReturn().getResponse().getStatus(),route);
  String raw=session("aal2"); String[] csrf=csrf();
  String unknown=BASE+"/stays/"+STAY;
  assertEquals(403,mvc.perform(read(unknown,raw)).andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(write(delete(unknown),unknown,raw,csrf)).andReturn().getResponse().getStatus());
  String logRoot=BASE+"/security-log";
  assertEquals(403,mvc.perform(write(post(logRoot),logRoot,raw,csrf).content("{}")).andReturn().getResponse().getStatus());
  verifyNoInteractions(log(),stays());
 }
 @Test void serviceDenialsAndMfaRequirementsAreNotConvertedToSuccess() throws Exception {
  start(true); String aal1=session("aal1"),aal2=session("aal2");
  when(log().integrity(ACCOUNT,TENANT,AssuranceLevel.AAL1)).thenThrow(
   new BusinessProblem("MFA_REQUIRED",403,"Confirme o segundo fator."));
  var mfa=mvc.perform(read(BASE+"/security-log/integrity",aal1)).andReturn().getResponse();
  assertEquals(403,mfa.getStatus()); assertTrue(mfa.getContentAsString().contains("MFA_REQUIRED"));
  when(stays().open(ACCOUNT,TENANT,AssuranceLevel.AAL2,false)).thenThrow(
   new BusinessProblem("STAY_FORBIDDEN",403,"Sem capacidade de portaria."));
  var denied=mvc.perform(read(BASE+"/stays",aal2)).andReturn().getResponse();
  assertEquals(403,denied.getStatus()); assertTrue(denied.getContentAsString().contains("STAY_FORBIDDEN"));
  when(stays().unitOpen(ACCOUNT,TENANT,UNIT)).thenThrow(new BusinessProblem("UNIT_ACCESS_DENIED",404,"Unidade indisponível."));
  assertEquals(404,mvc.perform(read(BASE+"/units/"+UNIT+"/stays",aal2)).andReturn().getResponse().getStatus());
 }
 @Test void disabledModulesKeepControllersAbsentAndAuthenticatedRoutesReturn404() throws Exception {
  start(false); String raw=session("aal2");
  assertTrue(context.getBeansOfType(SecurityLogController.class).isEmpty()); assertTrue(context.getBeansOfType(StayController.class).isEmpty());
  for(String route:List.of(BASE+"/security-log",BASE+"/security-log/integrity",BASE+"/stays",BASE+"/stay-rules"))
   assertEquals(404,mvc.perform(read(route,raw)).andReturn().getResponse().getStatus(),route);
  verifyNoInteractions(log(),stays());
 }


 @Test void identityRoutesRequireAuthenticationAndCookieCsrfAndPassActorAndAssurance() throws Exception {
  start(true);
  var service=context.getBean(VisitorIdentityService.class);
  String route=BASE+"/stay-identities",source=route+"/source",raw=session("aal2");
  assertEquals(401,mvc.perform(get(source).servletPath(source).secure(true).param("permitId",STAY.toString())).andReturn().getResponse().getStatus());
  when(service.source(ACCOUNT,TENANT,AssuranceLevel.AAL2,null,STAY)).thenReturn(new VisitorIdentityService.Source(null));
  ok(read(source,raw).param("permitId",STAY.toString()));
  verify(service).source(ACCOUNT,TENANT,AssuranceLevel.AAL2,null,STAY);
  assertEquals(403,mvc.perform(post(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))
   .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
  var request=new VisitorIdentityService.Verify(UNIT,null,STAY,null,"Documento conferido");
  var checked=new VisitorIdentityService.Check(UNIT,STAY,STAY,"ACTIVE",0,NOW);
  when(service.verify(ACCOUNT,TENANT,AssuranceLevel.AAL2,request)).thenReturn(checked);
  ok(write(post(route),route,raw,csrf()).content("{\"requestId\":\""+UNIT+"\",\"permitId\":\""+STAY+"\",\"reason\":\"Documento conferido\"}"));
  verify(service).verify(ACCOUNT,TENANT,AssuranceLevel.AAL2,request);
  String voidRoute=route+"/"+UNIT+"/void";
  when(service.voidCheck(ACCOUNT,TENANT,AssuranceLevel.AAL2,UNIT,new VisitorIdentityService.VoidCheck(0L,"Associação incorreta"))).thenReturn(checked);
  ok(write(post(voidRoute),voidRoute,raw,csrf()).content("{\"version\":0,\"reason\":\"Associação incorreta\"}"));
 }
 @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,SecurityLogController.class,StayController.class,VisitorIdentityController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean SecurityLogService testLog() { return mock(SecurityLogService.class); }
  @Bean VisitorIdentityService testIdentity() { return mock(VisitorIdentityService.class); }
  @Bean StayService testStays() { return mock(StayService.class); }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   return new BffSecurityConfigurer(sessions,new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8),csrf);
  }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf) {
   var limits=mock(AuthRateLimiter.class);
   return new BffController(sessions,csrf,Clock.fixed(NOW,ZoneOffset.UTC),limits);
  }
 }
}

