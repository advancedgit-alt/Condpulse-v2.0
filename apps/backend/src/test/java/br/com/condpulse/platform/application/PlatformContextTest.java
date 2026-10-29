package br.com.condpulse.platform.application;

import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformContextTest {
 private static final UUID ACCOUNT=UUID.fromString("11111111-1111-4111-8111-111111111111");

 @Test void capabilitiesOnlyExistForThePlatformRoleWithAal2() {
  var aal2=new PlatformContext(ACCOUNT,"PLATFORM_ADMIN",AssuranceLevel.AAL2);
  assertEquals(Set.of(PlatformCapability.READ_TENANTS,PlatformCapability.MANAGE_TENANTS,PlatformCapability.READ_AUDIT,
   PlatformCapability.READ_BILLING,PlatformCapability.MANAGE_BILLING),aal2.effectiveCapabilities());
  assertTrue(new PlatformContext(ACCOUNT,"PLATFORM_ADMIN",AssuranceLevel.AAL1).effectiveCapabilities().isEmpty());
  assertTrue(new PlatformContext(ACCOUNT,"MANAGER",AssuranceLevel.AAL2).effectiveCapabilities().isEmpty());
  assertEquals("tenants.read",PlatformCapability.READ_TENANTS.key());
  assertEquals("platform.audit.read",PlatformCapability.READ_AUDIT.key());
 }
 @Test void requireSeparatesMissingRoleFromMissingSecondFactor() {
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,
   ()->new PlatformContext(ACCOUNT,"PLATFORM_ADMIN",AssuranceLevel.AAL1).require(PlatformCapability.READ_TENANTS)).code());
  var denied=assertThrows(BusinessProblem.class,
   ()->new PlatformContext(ACCOUNT,"MANAGER",AssuranceLevel.AAL2).require(PlatformCapability.MANAGE_TENANTS));
  assertEquals("PLATFORM_ACCESS_DENIED",denied.code()); assertEquals(403,denied.status());
  assertDoesNotThrow(()->new PlatformContext(ACCOUNT,"PLATFORM_ADMIN",AssuranceLevel.AAL2).require(PlatformCapability.MANAGE_TENANTS));
 }
 @Test void invalidTenantChangeIsRefusedBeforeOpeningATransaction() {
  var transactions=mock(PlatformTransactionExecutor.class);
  var service=new PlatformService(transactions,mock(br.com.condpulse.platform.infrastructure.PlatformStore.class));
  UUID tenant=UUID.randomUUID();
  record Case(PlatformModels.UpdateTenant request,String code) {}
  for(var item:new Case[]{
    new Case(null,"TENANT_VERSION_REQUIRED"),
    new Case(new PlatformModels.UpdateTenant("Nome",true,-1L,"Motivo"),"TENANT_VERSION_REQUIRED"),
    new Case(new PlatformModels.UpdateTenant(" ",true,0L,"Motivo"),"TENANT_NAME_INVALID"),
    new Case(new PlatformModels.UpdateTenant("Nome\u0007",true,0L,"Motivo"),"TENANT_NAME_INVALID"),
    new Case(new PlatformModels.UpdateTenant("Nome",null,0L,"Motivo"),"TENANT_STATE_INVALID"),
    new Case(new PlatformModels.UpdateTenant("Nome",true,0L,null),"AUDIT_REASON_REQUIRED"),
    new Case(new PlatformModels.UpdateTenant("Nome",true,0L,"x".repeat(501)),"AUDIT_REASON_REQUIRED")}) {
   var error=assertThrows(BusinessProblem.class,()->service.updateTenant(ACCOUNT,AssuranceLevel.AAL2,tenant,item.request()));
   assertEquals(item.code(),error.code()); assertEquals(400,error.status());
  }
  assertEquals("TENANT_NOT_FOUND",assertThrows(BusinessProblem.class,()->service.updateTenant(ACCOUNT,AssuranceLevel.AAL2,null,
   new PlatformModels.UpdateTenant("Nome",true,0L,"Motivo"))).code());
  assertEquals("AUDIT_LIMIT_INVALID",assertThrows(BusinessProblem.class,()->service.audit(ACCOUNT,AssuranceLevel.AAL2,null,0)).code());
  assertEquals("AUDIT_CURSOR_INVALID",assertThrows(BusinessProblem.class,()->service.audit(ACCOUNT,AssuranceLevel.AAL2,"%%%",10)).code());
  verifyNoInteractions(transactions);
 }
}
