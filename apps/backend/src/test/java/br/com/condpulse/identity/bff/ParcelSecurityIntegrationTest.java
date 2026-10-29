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
import br.com.condpulse.parcels.api.ParcelController;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
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

/** Cadeia HTTP real das encomendas; regras e banco são cobertos por ParcelJdbcTest. */
class ParcelSecurityIntegrationTest {
 private static final String ORIGIN="https://condpulse.example.invalid";
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 private static final UUID UNIT=UUID.fromString("33333333-3333-4333-8333-333333333333");
 private static final UUID PARCEL=UUID.fromString("44444444-4444-4444-8444-444444444444");
 private static final UUID MEMBER=UUID.fromString("55555555-5555-4555-8555-555555555555");
 private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 private static final String KEY="parcel-http-key-0001";
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
  if(enabled) properties.put("condpulse.parcels.enabled","true");
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-parcels",properties));
  context.register(TestConfiguration.class);
  context.refresh();
  sessions=context.getBean(WebSessionService.class);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); if(context!=null) context.close(); }
 private ParcelService parcels() { return context.getBean(ParcelService.class); }
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
 private static ParcelModels.Parcel parcel(String status,long version) {
  return new ParcelModels.Parcel(PARCEL,UNIT,"101",null,1,"Caixa","Armário 1",status,NOW,version,
   "DELIVERED".equals(status)?new ParcelModels.Handoff("Pessoa Fictícia",NOW,"ASSISTED"):null);
 }

 @Test void conciergeListAndUnitListPassFiltersAndAssuranceWithoutCache() throws Exception {
  start(true);
  String raw=session("aal2");
  when(parcels().list(ACCOUNT,TENANT,AssuranceLevel.AAL2,"RECEIVED",UNIT,"Armário"))
   .thenReturn(new ParcelModels.Page(List.of(parcel("RECEIVED",0)),false));
  String route="/v1/tenants/"+TENANT+"/parcels";
  var list=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))
   .param("status","RECEIVED").param("unitId",UNIT.toString()).param("location","Armário")).andReturn().getResponse();
  assertEquals(200,list.getStatus()); assertEquals("no-store",list.getHeader("Cache-Control"));
  assertTrue(list.getContentAsString().contains(PARCEL.toString()));
  when(parcels().unitParcels(ACCOUNT,TENANT,UNIT,null)).thenReturn(new ParcelModels.Page(List.of(),false));
  String unitRoute="/v1/tenants/"+TENANT+"/units/"+UNIT+"/parcels";
  assertEquals(200,mvc.perform(get(unitRoute).servletPath(unitRoute).secure(true).cookie(new Cookie(BffCookies.SESSION,raw)))
   .andReturn().getResponse().getStatus());
  verify(parcels()).unitParcels(ACCOUNT,TENANT,UNIT,null);
  String units="/v1/tenants/"+TENANT+"/concierge/units";
  when(parcels().units(ACCOUNT,TENANT,AssuranceLevel.AAL2,"10")).thenReturn(List.of(new ParcelModels.UnitOption(UNIT,"101",null)));
  assertTrue(mvc.perform(get(units).servletPath(units).secure(true).cookie(new Cookie(BffCookies.SESSION,raw)).param("q","10"))
   .andReturn().getResponse().getContentAsString().contains("\"label\":\"101\""));
 }
 @Test void mutationsRequireCsrfAndKeyAndReportReplay() throws Exception {
  start(true);
  String raw=session("aal2");
  String route="/v1/tenants/"+TENANT+"/parcels";
  String body="{\"unitId\":\""+UNIT+"\",\"quantity\":1,\"description\":\"Caixa\",\"storageLocation\":\"Armário 1\"}";
  var noCsrf=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(403,noCsrf.getStatus()); verifyNoInteractions(parcels());
  var csrf=csrf();
  var noKey=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertTrue(noKey.getStatus()>=400 && noKey.getStatus()<500,"Sem chave de idempotência: "+noKey.getStatus());
  verifyNoInteractions(parcels());
  var request=new ParcelModels.Receive(UNIT,1,"Caixa","Armário 1",null);
  when(parcels().receive(ACCOUNT,TENANT,AssuranceLevel.AAL2,KEY,request))
   .thenReturn(new ParcelModels.Mutation(parcel("RECEIVED",0),true,201));
  var replay=mvc.perform(post(route).servletPath(route).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  assertEquals(201,replay.getStatus()); assertEquals("true",replay.getHeader("Idempotency-Replayed"));
  assertEquals("no-store",replay.getHeader("Cache-Control"));
  String handoff=route+"/"+PARCEL+"/handoff";
  var deliver=new ParcelModels.Deliver(MEMBER,true,0L);
  when(parcels().deliver(ACCOUNT,TENANT,AssuranceLevel.AAL2,PARCEL,KEY,deliver))
   .thenThrow(new BusinessProblem("PARCEL_ALREADY_DELIVERED",409,"Esta encomenda já foi retirada."));
  var conflict=mvc.perform(post(handoff).servletPath(handoff).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{\"recipientMembershipId\":\""+MEMBER+"\",\"identityChecked\":true,\"expectedVersion\":0}"))
   .andReturn().getResponse();
  assertEquals(409,conflict.getStatus()); assertTrue(conflict.getContentAsString().contains("PARCEL_ALREADY_DELIVERED"));
  // Anulação: CSRF exigido; versão lida e motivo chegam ao serviço; resposta sem cache.
  String voidRoute=route+"/"+PARCEL+"/void";
  String voidBody="{\"reason\":\"Unidade errada\",\"expectedVersion\":0}";
  assertEquals(403,mvc.perform(post(voidRoute).servletPath(voidRoute).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw)).contentType(MediaType.APPLICATION_JSON).content(voidBody)).andReturn().getResponse().getStatus());
  verify(parcels(),never()).voidParcel(any(),any(),any(),any(),any(),any());
  var voided=new ParcelModels.Parcel(PARCEL,UNIT,"101",null,1,"Caixa","Armário 1","VOIDED",NOW,1,null,"Unidade errada",NOW);
  when(parcels().voidParcel(ACCOUNT,TENANT,AssuranceLevel.AAL2,PARCEL,KEY,new ParcelModels.VoidRequest("Unidade errada",0L)))
   .thenReturn(new ParcelModels.Mutation(voided,false,200));
  var annulled=mvc.perform(post(voidRoute).servletPath(voidRoute).secure(true).header("Origin",ORIGIN).header("Idempotency-Key",KEY)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content(voidBody)).andReturn().getResponse();
  assertEquals(200,annulled.getStatus()); assertEquals("no-store",annulled.getHeader("Cache-Control"));
  assertEquals("false",annulled.getHeader("Idempotency-Replayed"));
  assertTrue(annulled.getContentAsString().contains("\"voidReason\":\"Unidade errada\""));
 }
 @Test void recipientsListPassesTheParcelAndAssuranceWithoutCache() throws Exception {
  start(true);
  String raw=session("aal2");
  when(parcels().recipients(ACCOUNT,TENANT,AssuranceLevel.AAL2,PARCEL))
   .thenReturn(List.of(new ParcelModels.Recipient(MEMBER,"Pessoa Fictícia","RESIDENT")));
  String route="/v1/tenants/"+TENANT+"/parcels/"+PARCEL+"/recipients";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,raw))).andReturn().getResponse();
  assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeader("Cache-Control"));
  assertTrue(response.getContentAsString().contains("\"membershipId\":\""+MEMBER+"\""));
  verify(parcels()).recipients(ACCOUNT,TENANT,AssuranceLevel.AAL2,PARCEL);
 }
 @Test void anonymousAndUnlistedRoutesNeverReachTheService() throws Exception {
  start(true);
  String route="/v1/tenants/"+TENANT+"/parcels";
  assertEquals(401,mvc.perform(get(route).servletPath(route).secure(true)).andReturn().getResponse().getStatus());
  String raw=session("aal2"); var csrf=csrf();
  String item=route+"/"+PARCEL;
  assertEquals(403,mvc.perform(delete(item).servletPath(item).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1]))
   .andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(put(item).servletPath(item).secure(true).header("Origin",ORIGIN)
   .cookie(new Cookie(BffCookies.SESSION,raw),new Cookie("__Host-cp_csrf",csrf[0])).header("X-CSRF-Token",csrf[1])
   .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
  verifyNoInteractions(parcels());
 }
 @Test void routesStayAbsentWhileTheFlagIsOff() throws Exception {
  start(false);
  assertTrue(context.getBeansOfType(ParcelController.class).isEmpty());
  String route="/v1/tenants/"+TENANT+"/parcels";
  var response=mvc.perform(get(route).servletPath(route).secure(true).cookie(new Cookie(BffCookies.SESSION,session("aal2"))))
   .andReturn().getResponse();
  assertEquals(404,response.getStatus()); verifyNoInteractions(parcels());
 }

 @Configuration(proxyBeanMethods=false)
 @EnableWebMvc @EnableWebSecurity
 @Import({SecurityConfiguration.class,ParcelController.class,ProblemHandler.class})
 static class TestConfiguration {
  @Bean WebSessionService testSessions() { return mock(WebSessionService.class); }
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
