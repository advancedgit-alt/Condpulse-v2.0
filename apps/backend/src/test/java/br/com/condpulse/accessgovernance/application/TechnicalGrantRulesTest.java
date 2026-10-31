package br.com.condpulse.accessgovernance.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.accessgovernance.infrastructure.TechnicalGrantStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** AC-003: matriz de escopos e validação antes de abrir transação, sem banco. */
class TechnicalGrantRulesTest {
 private static final UUID ACTOR=UUID.fromString("11111111-1111-4111-8111-111111111111");
 private static final UUID OTHER=UUID.fromString("22222222-2222-4222-8222-222222222222");

 private static TechnicalGrantService service(int maxDays) {
  return new TechnicalGrantService(mock(PlatformTransactionExecutor.class),mock(TenantTransactionExecutor.class),
   mock(TechnicalTransactionExecutor.class),mock(TechnicalGrantStore.class),mock(PlatformStore.class),maxDays);
 }
 private static String code(TechnicalGrantService service,TechnicalGrantModels.IssueTechnicalGrant request) {
  return assertThrows(BusinessProblem.class,()->service.validate(ACTOR,request)).code();
 }

 @Test void scopeKeysMatchTheClosedDatabaseList() {
  assertEquals(List.of("installation.read","installation.manage","installation.bind_channel","installation.commission",
   "connector.rotate_secret","gateway.manage","homologation.request","person.review","access.request_open","audit.export"),
   Arrays.stream(TechnicalScope.values()).map(TechnicalScope::key).toList(),"Mesma lista de cp.technical_scopes_valid (V037)");
  assertEquals(EnumSet.allOf(TechnicalScope.class),TechnicalScope.allowedFor("MASTER_IMPLANTACAO"));
  assertEquals(EnumSet.of(TechnicalScope.INSTALLATION_READ,TechnicalScope.HOMOLOGATION_REQUEST),TechnicalScope.allowedFor("TECHNICAL_SUPPORT"));
  assertTrue(TechnicalScope.allowedFor("MANAGER").isEmpty(),"Gestor não recebe escopo técnico por herança");
  assertTrue(TechnicalScope.allowedFor(null).isEmpty());
  assertTrue(TechnicalScope.fromKey("*").isEmpty());
  var defaults=TechnicalScope.implantationDefaults();
  assertFalse(defaults.contains(TechnicalScope.PERSON_REVIEW) || defaults.contains(TechnicalScope.ACCESS_REQUEST_OPEN)
   || defaults.contains(TechnicalScope.AUDIT_EXPORT),"Pessoas, abertura e exportação só por pedido específico");
 }
 @Test void managerCapabilitiesNeverIncludeTechnicalScopes() {
  var technicalKeys=Arrays.stream(TechnicalScope.values()).map(TechnicalScope::key).toList();
  for(var capability:StaffCapability.forRole("MANAGER")) assertFalse(technicalKeys.contains(capability.key()),capability.key());
 }
 @Test void contextRequiresAGrantedScope() {
  var context=new TechnicalContext(ACTOR,OTHER,ZoneId.of("America/Sao_Paulo"),Set.of(TechnicalScope.INSTALLATION_READ),Instant.now());
  assertDoesNotThrow(()->context.require(TechnicalScope.INSTALLATION_READ));
  var denied=assertThrows(BusinessProblem.class,()->context.require(TechnicalScope.INSTALLATION_BIND_CHANNEL));
  assertEquals("TECHNICAL_SCOPE_REQUIRED",denied.code()); assertEquals(403,denied.status());
 }
 @Test void issueIsValidatedBeforeAnyTransaction() {
  var service=service(30);
  UUID id=UUID.randomUUID();
  String reason="Implantacao piloto ficticia";
  assertEquals("TECHNICAL_GRANT_ID_REQUIRED",code(service,null));
  assertEquals("TECHNICAL_GRANT_ID_REQUIRED",code(service,new TechnicalGrantModels.IssueTechnicalGrant(null,OTHER,"MASTER_IMPLANTACAO",null,7,reason)));
  assertEquals("TECHNICAL_GRANT_ACCOUNT_REQUIRED",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,null,"MASTER_IMPLANTACAO",null,7,reason)));
  assertEquals("TECHNICAL_GRANT_SELF",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,ACTOR,"MASTER_IMPLANTACAO",null,7,reason)));
  assertEquals("TECHNICAL_GRANT_ROLE_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MANAGER",null,7,reason)));
  assertEquals("TECHNICAL_GRANT_SCOPES_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"TECHNICAL_SUPPORT",null,7,reason)));
  assertEquals("TECHNICAL_GRANT_SCOPES_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"TECHNICAL_SUPPORT",
   List.of("connector.rotate_secret"),7,reason)));
  assertEquals("TECHNICAL_GRANT_SCOPES_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",
   List.of("installation.read","installation.read"),7,reason)));
  assertEquals("TECHNICAL_GRANT_SCOPES_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",
   Arrays.asList("installation.read",null),7,reason)));
  assertEquals("TECHNICAL_GRANT_VALIDITY_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",null,31,reason)));
  assertEquals("TECHNICAL_GRANT_VALIDITY_INVALID",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",null,0,reason)));
  assertEquals("AUDIT_REASON_REQUIRED",code(service,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",null,7,"curto")));
  var valid=service.validate(ACTOR,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",
   List.of("installation.manage","installation.bind_channel"),7,"  Implantacao   piloto ficticia "));
  assertEquals(List.of("installation.bind_channel","installation.manage"),valid.scopes());
  assertEquals("Implantacao piloto ficticia",valid.reason());
  assertEquals(90,service(90).validate(ACTOR,new TechnicalGrantModels.IssueTechnicalGrant(id,OTHER,"MASTER_IMPLANTACAO",null,90,reason)).validDays());
  assertThrows(IllegalArgumentException.class,()->service(91),"Teto rígido de 90 dias, igual ao banco");
 }
}
