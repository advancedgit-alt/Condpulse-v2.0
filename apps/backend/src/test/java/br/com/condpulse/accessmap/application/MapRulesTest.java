package br.com.condpulse.accessmap.application;

import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalTransactionExecutor;
import br.com.condpulse.accessmap.infrastructure.MapStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.AuditRecorder;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** AC-004: validação antes da transação e capacidade de área só do gestor. */
class MapRulesTest {
 private final MapService service=new MapService(mock(TenantTransactionExecutor.class),mock(TechnicalTransactionExecutor.class),mock(MapStore.class),
  mock(AuditRecorder.class));
 private static String code(org.junit.jupiter.api.function.Executable action) { return assertThrows(BusinessProblem.class,action).code(); }

 @Test void areaCapabilityBelongsOnlyToTheManager() {
  assertTrue(StaffCapability.forRole("MANAGER").contains(StaffCapability.MANAGE_AREAS));
  assertFalse(StaffCapability.forRole("CONCIERGE").contains(StaffCapability.MANAGE_AREAS));
  assertEquals("area.manage",StaffCapability.MANAGE_AREAS.key());
 }
 @Test void invalidInputIsRejectedBeforeAnyTransaction() {
  UUID id=UUID.randomUUID(),other=UUID.randomUUID();
  assertEquals("ACCESS_AREA_CODE_INVALID",code(()->service.validate(new MapModels.CreateArea(id,"academia","Academia","COMMON_AREA",null,null,null,null,null,null))));
  assertEquals("ACCESS_AREA_AMENITY_INVALID",code(()->service.validate(new MapModels.CreateArea(id,"A","Academia","AMENITY",null,null,null,null,null,null))));
  assertEquals("ACCESS_AREA_TIME_ZONE_INVALID",code(()->service.validate(new MapModels.CreateArea(id,"A","Academia","COMMON_AREA",null,null,null,"Lua/Base",null,null))));
  assertEquals("ACCESS_AREA_STATUS_INVALID",code(()->service.validate(new MapModels.CreateArea(id,"A","Academia","COMMON_AREA",null,null,null,null,null,"RETIRED"))));
  assertEquals("ACCESS_AREA_PARENT_INVALID",code(()->service.validate(id,new MapModels.UpdateArea("Academia","COMMON_AREA",null,id,null,null,null,"ACTIVE",0L))));
  assertEquals("ACCESS_POINT_AREAS_INVALID",code(()->service.validate(new MapModels.CreatePoint(id,"P","Porta",other,other,"DOOR",null,null,null,null,null))));
  assertEquals("ACCESS_POINT_STATUS_INVALID",code(()->service.validate(new MapModels.CreatePoint(id,"P","Porta",id,other,"DOOR",null,null,null,null,"COMMISSIONED"))));
  var normalized=service.validate(new MapModels.CreatePoint(id,"P","  Porta   principal ",id,other,"DOOR",null,null,null,null,null));
  assertEquals("Porta principal",normalized.name()); assertEquals("UNKNOWN",normalized.flowDirection()); assertEquals("UNVERIFIED",normalized.egressClass());
  assertEquals("DRAFT",normalized.status());
 }
}
