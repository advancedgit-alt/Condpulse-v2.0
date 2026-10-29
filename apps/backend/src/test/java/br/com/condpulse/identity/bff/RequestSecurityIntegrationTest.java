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
import br.com.condpulse.requests.api.RequestController;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.requests.application.RequestService;
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

/** Cadeia HTTP real dos pedidos; regras e banco são cobertos por RequestJdbcTest. */
class RequestSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID REQUEST=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final UUID MESSAGE=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final String KEY="request-http-key-0001";
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
  if(enabled) properties.put("condpulse.requests.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-requests",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private RequestService service() { return context.getBean(RequestService.class); }
 private String session(String aal) {
  var jwt=Jwt.withTokenValue("synthetic-requests").header("alg","ES256").subject(ACCOUNT.toString())
   .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).claim("aal",aal).build();
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenReturn(jwt); return raw;
 }
 private String[] csrf() throws Exception {
  var response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }
 private static RequestModels.UnitRequest unitRequest() {
  return new RequestModels.UnitRequest(REQUEST,UNIT,"MAINTENANCE","Vazamento fictício","Água no teto.","OPEN",false,null,null,
   "PESSOA FICTÍCIA",true,NOW,NOW,null,null,List.of(new RequestModels.Message(MESSAGE,"STAFF",null,false,"RESIDENT","Equipe a caminho.",NOW)),false);
 }

 @Test void unitOpensAndConversesWithSessionCsrfAndKey() throws Exception {
  start(true);
  String raw=session("aal1");
  String route="/v1/tenants/"+TENANT+"/units/"+UNIT+"/requests";
  when(service().unitRequests(ACCOUNT,TENANT,UNIT)).thenReturn(new RequestModels.UnitPage(List.of(new RequestModels.UnitSummary(REQUEST,UNIT,
   "MAINTENANCE","Vazamento fictício","OPEN",false,null,NOW,NOW)),false));
  var listed=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,listed.getStatus()); assertEquals("no-store",listed.getHeader("Cache-Control"));
  assertFalse(listed.getContentAsString().contains("\"description\""),"Lista sem o texto do pedido");
  String body="{\"category\":\"MAINTENANCE\",\"title\":\"Vazamento fictício\",\"description\":\"Água no teto.\"}";
  assertEquals(403,mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(),
   "Sem CSRF");
  var csrf=csrf();
  var noKey=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave de idempotência: "+noKey.getStatus());
  verify(service(),never()).open(any(),any(),any(),any(),any());
  when(service().open(ACCOUNT,TENANT,UNIT,KEY,new RequestModels.Open("MAINTENANCE","Vazamento fictício","Água no teto.")))
   .thenReturn(new RequestModels.OpenResult(unitRequest(),false,201));
  var created=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus()); assertEquals("false",created.getHeader("Idempotency-Replayed"));
  assertTrue(created.getContentAsString().contains("\"authorName\":null"),"Equipe sem nome na visão da unidade");
  String detail="/v1/tenants/"+TENANT+"/requests/"+REQUEST;
  when(service().unitRequest(ACCOUNT,TENANT,REQUEST)).thenThrow(new BusinessProblem("SERVICE_REQUEST_NOT_FOUND",404,"Pedido não encontrado."));
  var missing=mvc.perform(get(detail).servletPath(detail).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(404,missing.getStatus()); assertTrue(missing.getContentAsString().contains("SERVICE_REQUEST_NOT_FOUND"));
  String messages=detail+"/messages";
  when(service().unitMessage(ACCOUNT,TENANT,REQUEST,KEY,new RequestModels.UnitMessage("Obrigado.")))
   .thenThrow(new BusinessProblem("SERVICE_REQUEST_CLOSED",409,"O pedido foi encerrado; abra um novo pedido se precisar."));
  var closed=mvc.perform(post(messages).servletPath(messages).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Obrigado.\"}")).andReturn().getResponse();
  assertEquals(409,closed.getStatus()); assertTrue(closed.getContentAsString().contains("SERVICE_REQUEST_CLOSED"));
 }
 @Test void managementReadsAndUpdatesWithAal2Csrf() throws Exception {
  start(true);
  String raw=session("aal2");
  String route="/v1/tenants/"+TENANT+"/management/requests";
  when(service().managed(ACCOUNT,TENANT,AssuranceLevel.AAL2,"OPEN")).thenReturn(new RequestModels.ManagedPage(List.of(),false));
  var listed=mvc.perform(get(route).servletPath(route).secure(true).param("state","OPEN").cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse();
  assertEquals(200,listed.getStatus()); assertEquals("no-store",listed.getHeader("Cache-Control"));
  String update=route+"/"+REQUEST+"/state";
  String body="{\"state\":\"TRIAGED\",\"assigneeMembershipId\":null,\"dueOn\":\"2026-11-02\",\"resolution\":null,\"expectedVersion\":0}";
  assertEquals(403,mvc.perform(post(update).servletPath(update).secure(true).header("Origin",ORIGIN).cookie(new Cookie(BffCookies.SESSION,raw))
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(),"Sem CSRF");
  verify(service(),never()).update(any(),any(),any(),any(),any());
  var csrf=csrf();
  when(service().update(ACCOUNT,TENANT,AssuranceLevel.AAL2,REQUEST,new RequestModels.Update("TRIAGED",null,java.time.LocalDate.parse("2026-11-02"),
   null,0L))).thenThrow(new BusinessProblem("SERVICE_REQUEST_CHANGED",409,"O pedido foi alterado. Atualize antes de continuar."));
  var conflict=mvc.perform(post(update).servletPath(update).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(409,conflict.getStatus()); assertTrue(conflict.getContentAsString().contains("SERVICE_REQUEST_CHANGED"));
  String messages=route+"/"+REQUEST+"/messages";
  when(service().staffMessage(ACCOUNT,TENANT,AssuranceLevel.AAL2,REQUEST,KEY,new RequestModels.StaffMessage("Comentário interno.","INTERNAL")))
   .thenReturn(new RequestModels.MessageResult(new RequestModels.Message(MESSAGE,"STAFF","GESTÃO FICTÍCIA",true,"INTERNAL","Comentário interno.",NOW),
    false,201));
  var internal=mvc.perform(post(messages).servletPath(messages).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Comentário interno.\",\"visibility\":\"INTERNAL\"}")).andReturn().getResponse();
  assertEquals(201,internal.getStatus()); assertTrue(internal.getContentAsString().contains("\"visibility\":\"INTERNAL\""));
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(RequestController.class).isEmpty());
  String route="/v1/tenants/"+TENANT+"/units/"+UNIT+"/requests";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,session("aal1")))).andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(service());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,RequestController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean RequestService testRequests() { return mock(RequestService.class); }
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
