package br.com.condpulse.accesscatalog.application;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accesscatalog.infrastructure.CatalogStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** AC-019: validação do dossiê antes de abrir transação; aprovação nunca vem do cliente. */
class CatalogRulesTest {
 private final CatalogService service=new CatalogService(mock(PlatformTransactionExecutor.class),mock(TenantTransactionExecutor.class),
  mock(CatalogStore.class),mock(PlatformStore.class));
 private static CatalogModels.CreateHomologation request(java.util.Map<String,CatalogModels.CapabilityObservation> caps,java.util.Map<String,Long> limits) {
  return new CatalogModels.CreateHomologation(UUID.randomUUID(),UUID.randomUUID(),"1.0.0","control-id-rest","0.1.0","v1","EDGE_ONLINE","LOCAL_GATEWAY",
   caps,limits,List.of(),List.of(),"Dossie ficticio");
 }
 private static java.util.Map<String,CatalogModels.CapabilityObservation> caps() {
  var map=new HashMap<String,CatalogModels.CapabilityObservation>();
  for(String name:CatalogModels.CAPABILITIES) map.put(name,new CatalogModels.CapabilityObservation(null,null));
  return map;
 }
 private static java.util.Map<String,Long> limits() {
  var map=new HashMap<String,Long>(); for(String name:CatalogModels.LIMITS) map.put(name,null); return map;
 }

 @Test void dossierStoresApprovedAsUnknownAndRequiresTheWholeContract() {
  var dossier=service.validate(request(caps(),limits()));
  assertTrue(dossier.capabilities().contains("\"approved\":null"));
  assertFalse(dossier.capabilities().contains("\"approved\":true"));
  var missing=caps(); missing.remove("safeRecovery");
  assertEquals("HOMOLOGATION_CAPABILITIES_INVALID",assertThrows(BusinessProblem.class,()->service.validate(request(missing,limits()))).code());
  var negative=limits(); negative.put("faces",-1L);
  assertEquals("HOMOLOGATION_LIMITS_INVALID",assertThrows(BusinessProblem.class,()->service.validate(request(caps(),negative))).code());
  assertEquals("HOMOLOGATION_ID_REQUIRED",assertThrows(BusinessProblem.class,()->service.validate(null)).code());
 }
}
