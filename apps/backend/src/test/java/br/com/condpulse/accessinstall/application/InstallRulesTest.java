package br.com.condpulse.accessinstall.application;

import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessinstall.infrastructure.InstallStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** AC-004 parte 2 (V042): validação antes da transação; nenhuma chamada chega ao banco com pedido inválido. */
class InstallRulesTest {
 private final TenantTransactionExecutor tenants=mock(TenantTransactionExecutor.class);
 private final TechnicalTransactionExecutor technical=mock(TechnicalTransactionExecutor.class);
 private final InstallService service=new InstallService(tenants,technical,mock(InstallStore.class),mock(AuditRecorder.class));
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }
 private static final UUID T=UUID.randomUUID(),A=UUID.randomUUID();

 @Test void deviceAndChannelInputIsCheckedFirst() {
  UUID id=UUID.randomUUID(),k=UUID.randomUUID();
  assertEquals("INSTALLATION_DEVICE_EXTERNAL_INVALID",code(()->InstallService.validate(new InstallModels.CreateDevice(id,k,"com espaço","X",null,null,null,null,null))));
  assertEquals("INSTALLATION_DEVICE_MODE_INVALID",code(()->InstallService.validate(new InstallModels.CreateDevice(id,k,"c1","X",null,null,null,null,"VPN"))));
  assertEquals("INSTALLATION_DEVICE_ROLE_INVALID",code(()->InstallService.validate(new InstallModels.CreateDevice(id,k,"c1","X",null,null,null,"RELAY",null))));
  var normalized=InstallService.validate(new InstallModels.CreateDevice(id,k,"c1","  Controlador  ",null," r2 ",null,null,null));
  assertEquals("UNKNOWN",normalized.direction()); assertEquals("r2",normalized.hardwareRevision());
  assertEquals("INSTALLATION_CHANNEL_STATUS_INVALID",code(()->InstallService.validate(new InstallModels.CreateChannel(id,k,"relay","1","ACCESS_ACTUATOR","DISABLED"))));
  assertEquals("INSTALLATION_CHANNEL_NATIVE_INVALID",code(()->InstallService.validate(new InstallModels.CreateChannel(id,k,"Relay","1","ACCESS_ACTUATOR",null))));
  assertEquals("DISCOVERED",InstallService.validate(new InstallModels.CreateChannel(id,k,"relay","1","ACCESS_ACTUATOR",null)).status());
 }
 @Test void bindingNeedsReaderSenseAndEvidence() {
  UUID id=UUID.randomUUID(),p=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID(),r=UUID.randomUUID(),h=UUID.randomUUID();
  assertEquals("INSTALLATION_BINDING_DIRECTION_INVALID",code(()->InstallService.validate(new InstallModels.CreateBinding(id,p,c,d,r,null,null,null,null,h,
   new InstallModels.Directions(null,"FROM_TO"),"ligacao/1"))));
  assertEquals("INSTALLATION_BINDING_DIRECTION_INVALID",code(()->InstallService.validate(new InstallModels.CreateBinding(id,p,c,d,r,null,null,null,null,h,
   new InstallModels.Directions("ENTRY",null),"ligacao/1"))));
  assertEquals("INSTALLATION_BINDING_REQUIRED",code(()->InstallService.validate(new InstallModels.CreateBinding(id,p,c,d,r,null,null,null,null,null,
   new InstallModels.Directions("FROM_TO",null),"ligacao/1"))),"Combinação homologada obrigatória");
  assertEquals("INSTALLATION_BINDING_EVIDENCE_INVALID",code(()->InstallService.validate(new InstallModels.CreateBinding(id,p,c,d,null,null,null,null,null,h,null,"x"))));
  var ok=InstallService.validate(new InstallModels.CreateBinding(id,p,c,d,null,null,null,null,null,h,null,"ligacao/1"));
  assertEquals(new InstallModels.Directions(null,null),ok.directions());
 }
 @Test void acceptanceRequiresEvidenceHashesAndCounts() {
  UUID id=UUID.randomUUID(); String h="a".repeat(64);
  assertEquals("INSTALLATION_ACCEPTANCE_HASH_INVALID",code(()->InstallService.validate(new InstallModels.CreateAcceptance(id,"planta/1","A".repeat(64),"top/1",h,h,
   "s1","rel/1",0,0,"emg/1","rb/1",null,null))));
  assertEquals("INSTALLATION_ACCEPTANCE_REF_INVALID",code(()->InstallService.validate(new InstallModels.CreateAcceptance(id,"planta/1",h,"top/1",h,h,
   "s1","rel/1",0,0,null,"rb/1",null,null))),"Teste de emergência obrigatório");
  assertEquals("INSTALLATION_RESULTS_INVALID",code(()->InstallService.validate(new InstallModels.CreateAcceptance(id,"planta/1",h,"top/1",h,h,
   "s1","rel/1",-1,0,"emg/1","rb/1",null,null))));
  var ok=InstallService.validate(new InstallModels.CreateAcceptance(id,"planta/1",h,"top/1",h,h,"s1","rel/1",0,0,"emg/1","rb/1",null,
   Instant.parse("2027-01-01T00:00:00.123456789Z")));
  assertEquals(Instant.parse("2027-01-01T00:00:00.123456Z"),ok.reviewDueAt(),"Precisão do banco para a repetição");
 }
 @Test void transitionsAndVersionsAreValidatedBeforeTheDatabase() {
  UUID id=UUID.randomUUID();
  assertEquals("INSTALLATION_TRANSITION_INVALID",code(()->service.transition(A,T,null,id,new InstallModels.AcceptanceTransition("DRAFT",null,0L))));
  assertEquals("INSTALLATION_REASON_REQUIRED",code(()->service.transition(A,T,null,id,new InstallModels.AcceptanceTransition("SUSPENDED"," ",0L))));
  assertEquals("INSTALLATION_VERSION_REQUIRED",code(()->service.signTechnical(A,T,null,id,new InstallModels.VersionOnly(null))));
  assertEquals("INSTALLATION_VERSION_REQUIRED",code(()->service.signRules(A,T,null,id,null)));
  assertEquals("INSTALLATION_ACCEPTANCE_REQUIRED",code(()->service.submitBinding(A,T,null,id,new InstallModels.SubmitBinding(null,0L))));
  verifyNoInteractions(tenants,technical);
 }
}
