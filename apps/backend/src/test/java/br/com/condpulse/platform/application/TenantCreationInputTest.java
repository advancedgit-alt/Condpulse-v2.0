package br.com.condpulse.platform.application;

import java.util.ArrayList;
import java.util.List;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Validação da criação pelo painel master: mesmas regras do manifesto do onboarding, sem banco. */
class TenantCreationInputTest {
 private static final String KEY="chave-operacao-0001";
 private static PlatformModels.ManagerInput manager() {
  return new PlatformModels.ManagerInput(" Gestora  Fictícia ","  Gestora@Example.INVALID ",null);
 }
 private static PlatformModels.CreateTenant request(String name,String zone,List<String> units,PlatformModels.ManagerInput manager,
   AmenityManagementModels.AmenityInput amenity,String reason) {
  return new PlatformModels.CreateTenant(name,zone,units,manager,amenity,reason);
 }
 private static PlatformModels.CreateTenant valid() {
  return request("  Residencial   Sol ","America/Sao_Paulo",List.of("101"," 102 "),manager(),null,"Contrato assinado");
 }
 private static String code(Runnable action) { return assertThrows(BusinessProblem.class,action::run).code(); }

 @Test void normalizesTextAndDefaultsTheInvitationValidity() {
  var input=TenantCreationInput.validate(KEY,valid());
  assertEquals("Residencial Sol",input.name()); assertEquals(List.of("101","102"),input.units());
  assertEquals("Gestora Fictícia",input.managerName()); assertEquals("gestora@example.invalid",input.managerEmail());
  assertEquals(7,input.validDays()); assertNull(input.amenity()); assertTrue(input.requestHash().matches("[0-9a-f]{64}"));
 }
 @Test void sameRequestHasTheSameHashAndAnyChangeAltersIt() {
  String hash=TenantCreationInput.validate(KEY,valid()).requestHash();
  assertEquals(hash,TenantCreationInput.validate("outra-chave-00000001",valid()).requestHash(),"A chave não entra no hash");
  assertNotEquals(hash,TenantCreationInput.validate(KEY,request("Residencial Sol","America/Sao_Paulo",List.of("101","103"),manager(),null,
   "Contrato assinado")).requestHash());
  assertNotEquals(hash,TenantCreationInput.validate(KEY,request("Residencial Sol","America/Sao_Paulo",List.of("101","102"),
   new PlatformModels.ManagerInput("Gestora Fictícia","outra@example.invalid",7),null,"Contrato assinado")).requestHash());
 }
 @Test void refusesMissingOrMalformedIdempotencyKey() {
  assertEquals("IDEMPOTENCY_KEY_INVALID",code(()->TenantCreationInput.validate(null,valid())));
  assertEquals("IDEMPOTENCY_KEY_INVALID",code(()->TenantCreationInput.validate("curta",valid())));
  assertEquals("IDEMPOTENCY_KEY_INVALID",code(()->TenantCreationInput.validate("chave com espaco 0001",valid())));
 }
 @Test void refusesInvalidZoneNameUnitsAndReason() {
  assertEquals("TENANT_TIME_ZONE_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","Brasil/Inexistente",List.of("1"),manager(),null,"Motivo"))));
  assertEquals("TENANT_TIME_ZONE_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol",null,List.of("1"),manager(),null,"Motivo"))));
  assertEquals("TENANT_NAME_INVALID",code(()->TenantCreationInput.validate(KEY,request("​","UTC",List.of("1"),manager(),null,"Motivo"))));
  assertEquals("TENANT_UNITS_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of(),manager(),null,"Motivo"))));
  var many=new ArrayList<String>();
  for(int i=0;i<=TenantCreationInput.MAX_UNITS;i++) many.add("U"+i);
  assertEquals("TENANT_UNITS_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",many,manager(),null,"Motivo"))));
  assertEquals("TENANT_UNITS_DUPLICATED",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("A1","a1"),manager(),null,"Motivo"))));
  assertEquals("UNIT_LABEL_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("x".repeat(41)),manager(),null,"Motivo"))));
  assertEquals("AUDIT_REASON_REQUIRED",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),manager(),null,"  "))));
 }
 @Test void refusesInvalidManager() {
  assertEquals("MANAGER_REQUIRED",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),null,null,"Motivo"))));
  assertEquals("MANAGER_EMAIL_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),
   new PlatformModels.ManagerInput("Gestor","sem-arroba",7),null,"Motivo"))));
  assertEquals("MANAGER_NAME_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),
   new PlatformModels.ManagerInput(" ","g@example.invalid",7),null,"Motivo"))));
  assertEquals("MANAGER_INVITATION_VALIDITY_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),
   new PlatformModels.ManagerInput("Gestor","g@example.invalid",15),null,"Motivo"))));
 }
 @Test void amenityUsesTheLocalManagementRules() {
  var policy=new AmenityManagementModels.PolicyInput(1,List.of(1,2,3,4,5,6,7),"08:00","22:00",60,30,240,2,120);
  var amenity=new AmenityManagementModels.AmenityInput("Salão de festas",50,false,15000L,30,null,policy,null,null);
  var input=TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),manager(),amenity,"Motivo"));
  assertNotNull(input.amenity()); assertTrue(input.amenity().active()); assertEquals("Salão de festas",input.amenity().name());
  assertEquals("AMENITY_POLICY_REQUIRED",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),manager(),
   new AmenityManagementModels.AmenityInput("Salão",50,false,0L,0,null,null,null,null),"Motivo"))));
  assertEquals("AMENITY_CAPACITY_INVALID",code(()->TenantCreationInput.validate(KEY,request("Sol","UTC",List.of("1"),manager(),
   new AmenityManagementModels.AmenityInput("Salão",0,false,0L,0,null,policy,null,null),"Motivo"))));
 }
 @Test void tokensAndEmailHashesHaveTheExpectedShape() {
  String token=ManagerInvitationTokens.newToken();
  assertTrue(token.matches(ManagerInvitationTokens.TOKEN_PATTERN));
  var id=java.util.UUID.randomUUID();
  assertEquals(ManagerInvitationTokens.emailHash(id,ManagerInvitationTokens.normalizedEmail(" A@B.CO ")),
   ManagerInvitationTokens.emailHash(id,"a@b.co"));
 }
}
