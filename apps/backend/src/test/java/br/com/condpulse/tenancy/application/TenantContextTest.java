package br.com.condpulse.tenancy.application;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {
 private final UUID account=UUID.randomUUID(),tenant=UUID.randomUUID(),unit=UUID.randomUUID();
 private TenantContext context(String role,UUID linkedUnit,boolean create,boolean cancel) {
  return new TenantContext(account,tenant,ZoneId.of("America/Sao_Paulo"),
   List.of(new TenantContext.Membership(UUID.randomUUID(),linkedUnit,role,create,cancel)));
 }
 @Test void residentsAndResponsibleCanBookOnlyTheirLinkedUnit() {
  for(String role:List.of("RESIDENT","RESPONSIBLE")) {
   var context=context(role,unit,false,false);
   assertDoesNotThrow(()->context.requireBookableUnit(unit));
   assertDoesNotThrow(()->context.requireCancellableUnit(unit));
   var denied=assertThrows(BusinessProblem.class,()->context.requireBookableUnit(UUID.randomUUID()));
   assertEquals(404,denied.status()); assertEquals("UNIT_ACCESS_DENIED",denied.code());
  }
 }
 @Test void dependentNeedsExplicitCapabilityForEachOperation() {
  var denied=context("DEPENDENT",unit,false,false);
  assertThrows(BusinessProblem.class,()->denied.requireBookableUnit(unit));
  var createOnly=context("DEPENDENT",unit,true,false);
  assertDoesNotThrow(()->createOnly.requireBookableUnit(unit));
  assertThrows(BusinessProblem.class,()->createOnly.requireCancellableUnit(unit));
  var cancelOnly=context("DEPENDENT",unit,false,true);
  assertThrows(BusinessProblem.class,()->cancelOnly.requireBookableUnit(unit));
  assertDoesNotThrow(()->cancelOnly.requireCancellableUnit(unit));
 }
 @Test void staffAndOwnerDoNotInheritResidentAuthorityFromUnitOrFlags() {
  for(String role:List.of("OWNER","CONCIERGE","MANAGER","SUPPORT","UNKNOWN")) {
   var context=context(role,unit,true,true);
   assertThrows(BusinessProblem.class,()->context.requireBookableUnit(unit));
   assertThrows(BusinessProblem.class,()->context.requireCancellableUnit(unit));
  }
 }
 @Test void missingUnitNeverConfersReservationAccess() {
  var context=context("RESIDENT",null,true,true);
  assertThrows(BusinessProblem.class,()->context.requireBookableUnit(null));
  assertThrows(BusinessProblem.class,()->context.requireCancellableUnit(unit));
 }
 private TenantContext staff(String role,AssuranceLevel assurance) {
  return new TenantContext(account,tenant,ZoneId.of("America/Sao_Paulo"),
   List.of(new TenantContext.Membership(UUID.randomUUID(),null,role,false,false)),assurance);
 }
 @Test void managerWithAal1IsAskedForMfaAndHasNoEffectiveCapability() {
  var context=staff("MANAGER",AssuranceLevel.AAL1);
  var error=assertThrows(BusinessProblem.class,()->context.requireCapability(StaffCapability.MANAGE_UNITS));
  assertEquals("MFA_REQUIRED",error.code()); assertEquals(403,error.status());
  assertEquals(Set.of(),context.effectiveCapabilities());
  assertTrue(context.grantedCapabilities().contains(StaffCapability.MANAGE_UNITS));
 }
 @Test void managerWithAal2ManagesButDoesNotOperateConcierge() {
  var context=staff("MANAGER",AssuranceLevel.AAL2);
  for(var capability:List.of(StaffCapability.MANAGE_UNITS,StaffCapability.MANAGE_MEMBERSHIPS,
    StaffCapability.MANAGE_AMENITIES,StaffCapability.DECIDE_RESERVATIONS,StaffCapability.READ_AUDIT,StaffCapability.PUBLISH_ANNOUNCEMENTS))
   assertDoesNotThrow(()->context.requireCapability(capability));
  var error=assertThrows(BusinessProblem.class,()->context.requireCapability(StaffCapability.OPERATE_CONCIERGE));
  assertEquals("STAFF_ACCESS_DENIED",error.code());
  assertThrows(BusinessProblem.class,()->context.requireBookableUnit(unit));
 }
 @Test void conciergeOnlyOperatesTheGateAndNeedsAal2() {
  assertEquals("MFA_REQUIRED",assertThrows(BusinessProblem.class,
   ()->staff("CONCIERGE",AssuranceLevel.AAL1).requireCapability(StaffCapability.OPERATE_CONCIERGE)).code());
  var context=staff("CONCIERGE",AssuranceLevel.AAL2);
  assertDoesNotThrow(()->context.requireCapability(StaffCapability.OPERATE_CONCIERGE));
  assertEquals(Set.of(StaffCapability.OPERATE_CONCIERGE),context.effectiveCapabilities());
  assertEquals("STAFF_ACCESS_DENIED",assertThrows(BusinessProblem.class,
   ()->context.requireCapability(StaffCapability.MANAGE_MEMBERSHIPS)).code());
 }
 @Test void residentialSupportOwnerAndUnknownRolesNeverGainStaffCapabilitiesEvenWithAal2() {
  for(String role:List.of("RESIDENT","RESPONSIBLE","DEPENDENT","OWNER","SUPPORT","UNKNOWN")) {
   var context=new TenantContext(account,tenant,ZoneId.of("America/Sao_Paulo"),
    List.of(new TenantContext.Membership(UUID.randomUUID(),unit,role,true,true)),AssuranceLevel.AAL2);
   assertEquals(Set.of(),context.effectiveCapabilities(),role);
   for(var capability:StaffCapability.values()) {
    var error=assertThrows(BusinessProblem.class,()->context.requireCapability(capability));
    assertEquals("STAFF_ACCESS_DENIED",error.code(),role);
   }
  }
 }
 @Test void legacyConstructorDefaultsToAal1() {
  assertEquals(AssuranceLevel.AAL1,context("RESIDENT",unit,false,false).assurance());
  assertThrows(NullPointerException.class,()->new TenantContext(account,tenant,ZoneId.of("UTC"),List.of(),null));
 }
 @Test void membershipSnapshotIsImmutableAndDefensive() {
  var source=new ArrayList<TenantContext.Membership>();
  source.add(new TenantContext.Membership(UUID.randomUUID(),unit,"RESIDENT",false,false));
  var context=new TenantContext(account,tenant,ZoneId.of("America/Sao_Paulo"),source);
  source.clear();
  assertEquals(1,context.memberships().size());
  assertThrows(UnsupportedOperationException.class,()->context.memberships().clear());
 }
}
