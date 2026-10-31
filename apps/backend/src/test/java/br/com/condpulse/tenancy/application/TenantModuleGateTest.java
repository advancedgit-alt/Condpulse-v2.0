package br.com.condpulse.tenancy.application;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantModuleGateTest {
 private static final UUID TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222");
 @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

 private static void route(Object required) {
  var request=new MockHttpServletRequest();
  if(required!=null) request.setAttribute(TenantModuleGate.REQUIRED_ATTRIBUTE,required);
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
 }

 @Test void keysAreStableUniqueAndMatchTheDatabaseList() {
  Set<String> keys=new HashSet<>();
  for(TenantModule module:TenantModule.values()) {
   assertTrue(keys.add(module.key()),module.name());
   assertEquals(module,TenantModule.fromKey(module.key()).orElseThrow());
   assertTrue(module.flagProperty().startsWith("condpulse.") && module.flagProperty().endsWith(".enabled"));
  }
  assertEquals(Set.of("reservations","parcels","visits","announcements","requests","finance","access_credentials","facial_access"),keys);
  assertTrue(TenantModule.fromKey("RESERVATIONS").isEmpty(),"Chave é exata");
  assertTrue(TenantModule.fromKey(null).isEmpty());
 }

 @Test void outsideARequestOrUnmarkedRouteNothingIsRequired() {
  var jdbc=mock(JdbcTemplate.class);
  TenantModuleGate.check(jdbc,TENANT);
  route(null);
  TenantModuleGate.check(jdbc,TENANT);
  verifyNoInteractions(jdbc);
 }

 @Test void emptyOrMalformedRequirementFailsClosed() {
  var jdbc=mock(JdbcTemplate.class);
  route(EnumSet.noneOf(TenantModule.class));
  assertEquals("MODULE_DISABLED",assertThrows(BusinessProblem.class,()->TenantModuleGate.check(jdbc,TENANT)).code());
  route("reservations");
  assertEquals("MODULE_DISABLED",assertThrows(BusinessProblem.class,()->TenantModuleGate.check(jdbc,TENANT)).code());
  route(Set.of("reservations"));
  assertEquals("MODULE_DISABLED",assertThrows(BusinessProblem.class,()->TenantModuleGate.check(jdbc,TENANT)).code());
  verifyNoInteractions(jdbc);
  assertEquals(403,TenantModuleGate.disabled().status());
 }

 @Test void anyEnabledModuleOfTheRouteIsEnough() {
  var jdbc=mock(JdbcTemplate.class);
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(TENANT),eq("parcels"))).thenReturn(false);
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(TENANT),eq("visits"))).thenReturn(true);
  route(EnumSet.of(TenantModule.PARCELS,TenantModule.VISITS));
  TenantModuleGate.check(jdbc,TENANT);
  route(EnumSet.of(TenantModule.PARCELS));
  assertEquals("MODULE_DISABLED",assertThrows(BusinessProblem.class,()->TenantModuleGate.check(jdbc,TENANT)).code());
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(TENANT),eq("finance"))).thenReturn(null);
  route(EnumSet.of(TenantModule.FINANCE));
  assertEquals("MODULE_DISABLED",assertThrows(BusinessProblem.class,()->TenantModuleGate.check(jdbc,TENANT)).code(),"Resposta nula é desligado");
 }
}
