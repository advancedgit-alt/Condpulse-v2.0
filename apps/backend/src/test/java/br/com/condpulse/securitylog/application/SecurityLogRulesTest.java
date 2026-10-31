package br.com.condpulse.securitylog.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.shared.infrastructure.SensitiveViewRecorder;
import br.com.condpulse.tenancy.application.StaffCapability;
import br.com.condpulse.tenancy.application.TenantContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regras do Log de Segurança sem banco: filtros, capacidades, máscara da equipe do parceiro e encadeamento. */
class SecurityLogRulesTest {
 private static TenantContext context(String role,AssuranceLevel assurance) {
  return new TenantContext(UUID.randomUUID(),UUID.randomUUID(),ZoneId.of("America/Sao_Paulo"),
   List.of(new TenantContext.Membership(UUID.randomUUID(),null,role,false,false)),assurance);
 }
 private static SecurityLogModels.Event event(long seq,String prev,String hash,String role,UUID actor) {
  var details=new HashMap<String,Object>(); details.put("view","SECURITY_LOG"); details.put("nullable",null);
  return new SecurityLogModels.Event(UUID.randomUUID(),seq,Instant.EPOCH,Instant.EPOCH,null,"SENSITIVE_VIEWED","INFO","MANAGEMENT",null,actor,role,
   "SECURITY_LOG",null,null,null,null,null,null,null,null,null,details,prev,hash);
 }

 @Test void filtersAreValidatedBeforeAnyQuery() {
  var filter=SecurityLogModels.Filter.of(null,null,null,null,null,null,null);
  assertEquals(SecurityLogModels.DEFAULT_LIMIT,filter.limit());
  for(Integer limit:new Integer[]{0,201})
   assertEquals("SECURITY_LOG_LIMIT_INVALID",assertThrows(BusinessProblem.class,()->SecurityLogModels.Filter.of(null,null,null,null,null,null,limit)).code());
  assertEquals("SECURITY_LOG_TYPE_INVALID",assertThrows(BusinessProblem.class,()->SecurityLogModels.Filter.of("x';drop",null,null,null,null,null,10)).code());
  assertEquals("SECURITY_LOG_SEVERITY_INVALID",assertThrows(BusinessProblem.class,()->SecurityLogModels.Filter.of(null,"HIGH",null,null,null,null,10)).code());
  var start=Instant.parse("2026-10-01T00:00:00Z");
  assertEquals("SECURITY_LOG_PERIOD_INVALID",assertThrows(BusinessProblem.class,()->SecurityLogModels.Filter.of(null,null,null,start,start,null,10)).code());
  assertEquals("SECURITY_LOG_PERIOD_INVALID",assertThrows(BusinessProblem.class,
   ()->SecurityLogModels.Filter.of(null,null,null,start,start.plusSeconds(367L*86400),null,10)).code());
  assertEquals("SECURITY_LOG_CURSOR_INVALID",assertThrows(BusinessProblem.class,()->SecurityLogModels.Filter.of(null,null,null,null,null,1L,10)).code());
  var described=SecurityLogModels.Filter.of("ENTRY_RECORDED","ATTENTION",null,start,start.plusSeconds(3600),10L,5).describe();
  assertEquals("ENTRY_RECORDED",described.get("eventType")); assertEquals(10L,described.get("beforeSeq")); assertEquals(5,described.get("limit"));
 }

 @Test void managerReadsOwnLogWithSecondFactorAndNobodyExportsYet() {
  assertTrue(StaffCapability.forRole("MANAGER").contains(StaffCapability.READ_SECURITY_LOG_TENANT));
  assertEquals("security.log.read.tenant",StaffCapability.READ_SECURITY_LOG_TENANT.key());
  for(String role:List.of("MANAGER","CONCIERGE","MAINTENANCE","SUPPORT","RESIDENT","RESPONSIBLE","DEPENDENT","OWNER")) {
   assertFalse(StaffCapability.forRole(role).contains(StaffCapability.EXPORT_SECURITY_LOG),role);
   assertFalse(StaffCapability.forRole(role).contains(StaffCapability.READ_SECURITY_LOG),role);
  }
  assertFalse(SecurityLogService.requireReader(context("MANAGER",AssuranceLevel.AAL2)),"Gestão local lê com máscara do parceiro");
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->SecurityLogService.requireReader(context("MANAGER",AssuranceLevel.AAL1))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->SecurityLogService.requireReader(context("CONCIERGE",AssuranceLevel.AAL2))).code());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,()->SecurityLogService.requireReader(context("RESIDENT",AssuranceLevel.AAL2))).code());
 }

 @Test void tenantReadersSeePartnerStaffOnlyByRole() {
  UUID actor=UUID.randomUUID();
  var partner=event(1,"0".repeat(64),"a".repeat(64),"SECURITY_LEAD",actor).maskedForTenant();
  assertNull(partner.actorAccountId()); assertEquals("SECURITY_LEAD",partner.actorRole());
  var local=event(1,"0".repeat(64),"a".repeat(64),"MANAGER",actor).maskedForTenant();
  assertEquals(actor,local.actorAccountId());
  assertTrue(partner.details().containsKey("nullable"),"details aceita valor nulo do JSON");
 }

 @Test void exportRangeMustBeContiguousAndLinked() {
  var events=new ArrayList<SecurityLogModels.Event>();
  events.add(event(3,"1".repeat(64),"2".repeat(64),null,null));
  events.add(event(4,"2".repeat(64),"3".repeat(64),null,null));
  events.add(event(5,"3".repeat(64),"4".repeat(64),null,null));
  assertTrue(SecurityLogModels.linked(events,3,5));
  assertFalse(SecurityLogModels.linked(events,3,6),"Faltou evento");
  assertFalse(SecurityLogModels.linked(events.subList(0,2),3,5));
  var gap=new ArrayList<>(events); gap.set(1,event(7,"2".repeat(64),"3".repeat(64),null,null));
  assertFalse(SecurityLogModels.linked(gap,3,5),"Buraco na sequência");
  var broken=new ArrayList<>(events); broken.set(2,event(5,"f".repeat(64),"4".repeat(64),null,null));
  assertFalse(SecurityLogModels.linked(broken,3,5),"Elo quebrado");
 }

 @Test void disabledRecorderNeverTouchesTheDatabaseAndEnabledOneValidatesInput() {
  var disabled=SensitiveViewRecorder.disabled();
  assertFalse(disabled.enabled());
  assertDoesNotThrow(()->disabled.record(UUID.randomUUID(),"MANAGEMENT","SECURITY_LOG",null,null,null,Map.of()));
  var enabled=new SensitiveViewRecorder(new org.springframework.jdbc.core.JdbcTemplate(),true);
  assertThrows(IllegalArgumentException.class,()->enabled.record(UUID.randomUUID(),"RESIDENT_APP","SECURITY_LOG",null,null,null,Map.of()));
  assertThrows(IllegalArgumentException.class,()->enabled.record(UUID.randomUUID(),"MANAGEMENT","view; drop",null,null,null,Map.of()));
  assertThrows(NullPointerException.class,()->enabled.record(null,"MANAGEMENT","SECURITY_LOG",null,null,null,Map.of()));
 }
}
