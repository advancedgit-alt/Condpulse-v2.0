package br.com.condpulse.tenancy.application;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 @Test void membershipSnapshotIsImmutableAndDefensive() {
  var source=new ArrayList<TenantContext.Membership>();
  source.add(new TenantContext.Membership(UUID.randomUUID(),unit,"RESIDENT",false,false));
  var context=new TenantContext(account,tenant,ZoneId.of("America/Sao_Paulo"),source);
  source.clear();
  assertEquals(1,context.memberships().size());
  assertThrows(UnsupportedOperationException.class,()->context.memberships().clear());
 }
}
