package br.com.condpulse.partners.application;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.StaffCapability;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CPX-003: matriz papel × capacidade do PRD complementar §4.4, papel por papel (o JDBC confere a mesma matriz no banco). */
class PartnerCapabilityTest {
 static final Map<String,Set<String>> MATRIX=Map.of(
  "PARTNER_ADMIN",Set.of("partner.manage","partner.team.manage","security.log.read"),
  "SECURITY_LEAD",Set.of("security.command","security.log.read","security.log.export","security.remote_open.review",
   "security.policy.publish","security.blocklist.approve"),
  "SECURITY_SUPERVISOR",Set.of("security.log.read.shift","ops.shift.supervise","ops.remote.operate","ops.remote.open"),
  "REMOTE_OPERATOR",Set.of("ops.remote.operate","ops.remote.open"),
  "TECHNICIAN",Set.of("devices.commission"));

 private static Set<String> keys(Set<PartnerCapability> capabilities) {
  return capabilities.stream().map(PartnerCapability::key).collect(Collectors.toSet());
 }
 @Test void everyRoleHasExactlyTheCapabilitiesOfThePrd() {
  assertEquals(MATRIX.keySet(),Set.copyOf(PartnerCapability.ROLES));
  for(String role:PartnerCapability.ROLES) assertEquals(MATRIX.get(role),keys(PartnerCapability.forRole(role)),role);
  assertTrue(PartnerCapability.forRole("MANAGER").isEmpty());
  assertTrue(PartnerCapability.forRole("PLATFORM_ADMIN").isEmpty());
  assertTrue(PartnerCapability.forRole(null).isEmpty());
  // Toda capacidade nova pertence a pelo menos um papel.
  var covered=EnumSet.noneOf(PartnerCapability.class);
  for(String role:PartnerCapability.ROLES) covered.addAll(PartnerCapability.forRole(role));
  assertEquals(EnumSet.allOf(PartnerCapability.class),covered);
 }
 @Test void managerKeepsLocalManagementAndGainsOnlyTheTwoTenantSecurityCapabilities() {
  var manager=StaffCapability.forRole("MANAGER").stream().map(StaffCapability::key).collect(Collectors.toSet());
  assertTrue(manager.containsAll(Set.of("security.blocklist.approve","security.log.read.tenant")));
  for(String role:Set.of("CONCIERGE","MAINTENANCE","RESIDENT","RESPONSIBLE","DEPENDENT","OWNER","SUPPORT"))
   assertTrue(StaffCapability.forRole(role).stream().noneMatch(c->c.key().startsWith("security.")),role);
  // Gestão local não ganha operação remota, comando ou log da carteira.
  for(String key:Set.of("security.command","security.log.read","ops.remote.open","partner.manage")) assertFalse(manager.contains(key),key);
 }
 @Test void panelsFollowThePrdRoutes() {
  assertEquals(Map.of("PARTNER_ADMIN","gestao","SECURITY_LEAD","comando","SECURITY_SUPERVISOR","turno","REMOTE_OPERATOR","console",
   "TECHNICIAN","tecnico"),PartnerCapability.ROLES.stream().collect(Collectors.toMap(r->r,PartnerCapability::panel)));
  assertThrows(IllegalArgumentException.class,()->PartnerCapability.panel("MANAGER"));
 }
 @Test void partnerContextNeverPublishesTenantOperationsAndRequiresAal2() {
  var partner=UUID.randomUUID(); var account=UUID.randomUUID();
  var supervisor=new PartnerContext(account,partner,Set.of("SECURITY_SUPERVISOR"),AssuranceLevel.AAL2);
  assertEquals(Set.of("security.log.read.shift","ops.shift.supervise"),keys(supervisor.effectiveCapabilities()));
  var admin=new PartnerContext(account,partner,Set.of("PARTNER_ADMIN","TECHNICIAN"),AssuranceLevel.AAL1);
  assertTrue(admin.effectiveCapabilities().isEmpty());
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,()->admin.require(PartnerCapability.MANAGE_TEAM)).code());
  var operator=new PartnerContext(account,partner,Set.of("REMOTE_OPERATOR"),AssuranceLevel.AAL2);
  var denied=assertThrows(BusinessProblem.class,()->operator.require(PartnerCapability.MANAGE_TEAM));
  assertEquals("PARTNER_ACCESS_DENIED",denied.code()); assertEquals(403,denied.status());
  assertDoesNotThrow(()->new PartnerContext(account,partner,Set.of("PARTNER_ADMIN"),AssuranceLevel.AAL2).require(PartnerCapability.MANAGE_TEAM));
 }
}
