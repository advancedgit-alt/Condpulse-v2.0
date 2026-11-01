package br.com.condpulse.platform.application;

import java.sql.SQLException;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.infrastructure.DemoTenantStore;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** CPX-046: validação antes da transação e tradução dos SQLSTATE próprios da V055, sem expor mensagem do banco. */
class DemoTenantServiceTest {
 private static final UUID ACCOUNT=UUID.randomUUID();

 @Test void invalidRequestsNeverOpenATransaction() {
  var transactions=mock(PlatformTransactionExecutor.class);
  var service=new DemoTenantService(transactions,mock(DemoTenantStore.class));
  record Case(String key,DemoTenantModels.ProvisionDemo request,String code) {}
  String key="demo-chave-unitaria-0001";
  for(var item:new Case[]{
    new Case(null,new DemoTenantModels.ProvisionDemo(null,"Motivo","DEMO"),"IDEMPOTENCY_KEY_INVALID"),
    new Case("curta",new DemoTenantModels.ProvisionDemo(null,"Motivo","DEMO"),"IDEMPOTENCY_KEY_INVALID"),
    new Case(key,null,"DEMO_CONFIRMATION_REQUIRED"),
    new Case(key,new DemoTenantModels.ProvisionDemo(null,"Motivo",null),"DEMO_CONFIRMATION_REQUIRED"),
    new Case(key,new DemoTenantModels.ProvisionDemo(null,"Motivo","demo"),"DEMO_CONFIRMATION_REQUIRED"),
    new Case(key,new DemoTenantModels.ProvisionDemo(null,null,"DEMO"),"AUDIT_REASON_REQUIRED"),
    new Case(key,new DemoTenantModels.ProvisionDemo(null,"ok","DEMO"),"AUDIT_REASON_REQUIRED"),
    new Case(key,new DemoTenantModels.ProvisionDemo(null,"x".repeat(501),"DEMO"),"AUDIT_REASON_REQUIRED")}) {
   var error=assertThrows(BusinessProblem.class,()->service.provision(ACCOUNT,AssuranceLevel.AAL2,item.key(),item.request()));
   assertEquals(item.code(),error.code()); assertEquals(400,error.status());
  }
  verifyNoInteractions(transactions);
 }

 @Test void databaseStatesBecomeStableCodes() {
  record Case(String state,String code,int status) {}
  for(var item:new Case[]{new Case("CPD01","DEMO_RESET_REAL_TENANT",422),new Case("CPD02","DEMO_CHANGED",409),
    new Case("CPD03","DEMO_KEY_REUSED",409),new Case("42501","PLATFORM_ACCESS_DENIED",403),
    new Case("22023","AUDIT_REASON_REQUIRED",400),new Case("XX000","PLATFORM_DEPENDENCY_UNAVAILABLE",503)}) {
   var problem=DemoTenantService.translate(new DataIntegrityViolationException("x",new SQLException("detalhe interno",item.state())));
   assertEquals(item.code(),problem.code()); assertEquals(item.status(),problem.status());
   assertFalse(problem.getMessage().contains("detalhe interno"));
  }
 }

 @Test void capabilityIsSeparatedAndGatedByItsOwnFlag() {
  assertEquals("platform.demo.manage",PlatformCapability.MANAGE_DEMO.key());
  assertTrue(PlatformCapability.MANAGE_DEMO.demo());
  assertFalse(PlatformCapability.MANAGE_PARTNERS.demo());
  assertTrue(PlatformCapability.forRole("PLATFORM_ADMIN").contains(PlatformCapability.MANAGE_DEMO));
  assertFalse(PlatformCapability.forRole("MANAGER").contains(PlatformCapability.MANAGE_DEMO));
 }
}
