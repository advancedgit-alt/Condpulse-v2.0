package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.identity.infrastructure.SecurityConfiguration;
import br.com.condpulse.shared.api.ProblemHandler;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.InvitationService;
import br.com.condpulse.tenancy.application.MembershipModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
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

/** Cadeia real de segurança do primeiro acesso por convite, sem rede, banco ou provedor. */
class BffInvitationSecurityTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final Instant NOW=Instant.parse("2030-01-01T12:00:00Z");
 private static final UUID ACCOUNT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID TENANT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID MEMBERSHIP=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final String TOKEN="T".repeat(43);
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;

 private void start(boolean management) {
  context=new AnnotationConfigWebApplicationContext();
  context.setServletContext(new MockServletContext());
  context.getEnvironment().setActiveProfiles("api");
  Map<String,Object> properties=new HashMap<>(Map.of(
   "condpulse.auth.issuer","https://auth.example.invalid/auth/v1",
   "condpulse.auth.jwk-set-uri","https://auth.example.invalid/auth/v1/.well-known/jwks.json",
   "condpulse.bff.enabled","true"));
  if(management) properties.put("condpulse.management.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-invitation",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private WebSessionService sessions() { return context.getBean(WebSessionService.class); }
 private InvitationService invitations() { return context.getBean(InvitationService.class); }
 private AuthRateLimiter limits() { return context.getBean(AuthRateLimiter.class); }
 private String[] csrf() throws Exception {
  MockHttpServletResponse response=mvc.perform(get("/auth/csrf").servletPath("/auth/csrf").secure(true)).andReturn().getResponse();
  var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());
  assertTrue(matcher.find());
  return new String[]{response.getCookie("__Host-cp_csrf").getValue(),matcher.group(1)};
 }
 private MockHttpServletRequestBuilder mutation(String[] csrf,String token) {
  return post("/auth/invitation/accept").servletPath("/auth/invitation/accept").secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]).contentType(MediaType.APPLICATION_JSON)
   .content("{\"email\":\"convidada@example.invalid\",\"password\":\"Senha-sintetica-1\",\"token\":\""+token+"\"}");
 }
 @SuppressWarnings("unchecked")
 private void acceptance(WebSessionService.SessionTicket ticket) {
  when(sessions().loginAcceptingInvitation(eq("convidada@example.invalid"),eq("Senha-sintetica-1"),any())).thenAnswer(invocation->{
   BiFunction<UUID,String,MembershipModels.InvitationAccepted> accept=invocation.getArgument(2);
   return new WebSessionService.FirstAccess<>(accept.apply(ACCOUNT,"convidada@example.invalid"),ticket);
  });
  when(invitations().accept(eq(ACCOUNT),eq("convidada@example.invalid"),any()))
   .thenReturn(new MembershipModels.InvitationAccepted(TENANT,MEMBERSHIP,"PENDING"));
 }

 @Test void acceptanceStartsTheSessionRotatesCsrfAndNeverEchoesCredentials() throws Exception {
  start(true);
  String raw=SessionSecrets.newToken();
  acceptance(new WebSessionService.SessionTicket(raw,ACCOUNT,NOW.plusSeconds(28_800)));
  var response=mvc.perform(mutation(csrf(),TOKEN)).andReturn().getResponse();
  assertEquals(201,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  String content=response.getContentAsString();
  assertTrue(content.contains("\"sessionStarted\":true")); assertTrue(content.contains("\"status\":\"PENDING\""));
  assertFalse(content.contains("Senha-sintetica-1")); assertFalse(content.contains(TOKEN)); assertFalse(content.contains(raw));
  var cookies=response.getHeaders("Set-Cookie");
  assertTrue(cookies.stream().anyMatch(h->h.startsWith("__Host-cp_session="+raw+";") && h.contains("HttpOnly") && h.contains("Secure")));
  assertTrue(cookies.stream().anyMatch(h->h.startsWith("__Host-cp_csrf=") && !h.contains("Max-Age=0")),"CSRF renovado com a sessão nova");
  var captured=org.mockito.ArgumentCaptor.forClass(MembershipModels.AcceptInvitation.class);
  verify(invitations()).accept(eq(ACCOUNT),eq("convidada@example.invalid"),captured.capture());
  assertEquals(TOKEN,captured.getValue().token());
  verify(limits()).login("convidada@example.invalid");
 }
 @Test void acceptanceWithoutSessionAnswersCreatedWithoutCookie() throws Exception {
  start(true);
  acceptance(null);
  var response=mvc.perform(mutation(csrf(),TOKEN)).andReturn().getResponse();
  assertEquals(201,response.getStatus()); assertTrue(response.getContentAsString().contains("\"sessionStarted\":false"));
  assertFalse(response.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_session=")));
 }
 @Test void malformedTokenAndRateLimitNeverReachTheProvider() throws Exception {
  start(true);
  var malformed=mvc.perform(mutation(csrf(),"curto")).andReturn().getResponse();
  assertEquals(404,malformed.getStatus()); assertTrue(malformed.getContentAsString().contains("INVITATION_NOT_AVAILABLE"));
  when(limits().login("convidada@example.invalid")).thenReturn(new AuthRateLimiter.Admission(false,600));
  var limited=mvc.perform(mutation(csrf(),TOKEN)).andReturn().getResponse();
  assertEquals(429,limited.getStatus()); assertEquals("600",limited.getHeader("Retry-After"));
  verify(sessions(),never()).loginAcceptingInvitation(any(),any(),any());
  verifyNoInteractions(invitations());
 }
 @Test void routeRequiresCsrfAndOriginAndRefusalPropagatesWithoutSession() throws Exception {
  start(true);
  var withoutCsrf=mvc.perform(post("/auth/invitation/accept").servletPath("/auth/invitation/accept").secure(true).header("Origin",ORIGIN)
   .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"convidada@example.invalid\",\"password\":\"x\",\"token\":\""+TOKEN+"\"}"))
   .andReturn().getResponse();
  assertEquals(403,withoutCsrf.getStatus());
  var csrf=csrf();
  var foreign=mvc.perform(post("/auth/invitation/accept").servletPath("/auth/invitation/accept").secure(true)
   .header("Origin","https://attacker.example.invalid").cookie(new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"convidada@example.invalid\",\"password\":\"x\",\"token\":\""+TOKEN+"\"}"))
   .andReturn().getResponse();
  assertEquals(403,foreign.getStatus());
  verify(sessions(),never()).loginAcceptingInvitation(any(),any(),any());
  when(sessions().loginAcceptingInvitation(any(),any(),any()))
   .thenThrow(new BusinessProblem("INVITATION_NOT_AVAILABLE",404,"Indisponível."));
  var refused=mvc.perform(mutation(csrf(),TOKEN)).andReturn().getResponse();
  assertEquals(404,refused.getStatus());
  assertFalse(refused.getHeaders("Set-Cookie").stream().anyMatch(h->h.startsWith("__Host-cp_session=")));
 }
 @Test void routeIsAbsentWhileManagementFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(BffInvitationController.class).isEmpty());
  assertEquals(404,mvc.perform(mutation(csrf(),TOKEN)).andReturn().getResponse().getStatus());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,ProblemHandler.class,BffInvitationController.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
  @Bean InvitationService testInvitations() { return mock(InvitationService.class); }
  @Bean @Primary JwtDecoder testDecoder() { return mock(JwtDecoder.class); }
  @Bean CsrfTokenRepository testCsrfRepository() { return new BffConfiguration().bffCsrfRepository(); }
  @Bean BffSecurityConfigurer testBffConfigurer(WebSessionService sessions,CsrfTokenRepository csrf) {
   var settings=new BffSettings(ORIGIN,"https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
   return new BffSecurityConfigurer(sessions,settings,csrf);
  }
  @Bean AuthRateLimiter testRateLimiter() {
   var limits=mock(AuthRateLimiter.class);
   when(limits.login(any())).thenReturn(AuthRateLimiter.Admission.permitted());
   return limits;
  }
  @Bean Clock testClock() { return Clock.fixed(NOW,ZoneOffset.UTC); }
  @Bean BffController testBffController(WebSessionService sessions,CsrfTokenRepository csrf,Clock clock,AuthRateLimiter limits) {
   return new BffController(sessions,csrf,clock,limits);
  }
 }
}
