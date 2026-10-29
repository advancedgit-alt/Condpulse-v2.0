package br.com.condpulse.reservations.application;

import java.util.Arrays;
import java.util.List;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmenityManagementModelsTest {
 private static AmenityManagementModels.PolicyInput policy(List<Integer> days,String opens,String closes) {
  return new AmenityManagementModels.PolicyInput(1,days,opens,closes,30,60,240,2,120);
 }
 private static AmenityManagementModels.AmenityInput input(AmenityManagementModels.PolicyInput policy) {
  return new AmenityManagementModels.AmenityInput("  Churrasqueira   Norte ",20,false,5000L,30,null,policy,null,null);
 }
 private static String code(Runnable action) {
  return assertThrows(BusinessProblem.class,action::run).code();
 }
 @Test void validInputIsNormalizedAndSerializedInTheStoredRuleShape() {
  var value=AmenityManagementModels.validate(input(policy(List.of(7,1,3),"08:00","22:30")),true);
  assertEquals("Churrasqueira Norte",value.name()); assertTrue(value.active());
  assertEquals("{\"schemaVersion\":1,\"weekDays\":[1,3,7],\"opensAt\":\"08:00\",\"closesAt\":\"22:30\","
   +"\"minimumLeadMinutes\":30,\"maximumAdvanceDays\":60,\"maximumDurationMinutes\":240,"
   +"\"maximumActivePerUnit\":2,\"cancellationLeadMinutes\":120}",value.policyJson());
 }
 @Test void missingOrPermissiveValuesAreNeverDefaulted() {
  assertEquals("AMENITY_POLICY_REQUIRED",code(()->AmenityManagementModels.validate(input(null),true)));
  assertEquals("AMENITY_INPUT_INVALID",code(()->AmenityManagementModels.validate(null,true)));
  var noApproval=new AmenityManagementModels.AmenityInput("Quadra",10,null,0L,0,true,policy(List.of(1),"08:00","09:00"),null,null);
  assertEquals("AMENITY_APPROVAL_REQUIRED",code(()->AmenityManagementModels.validate(noApproval,true)));
  var noActive=new AmenityManagementModels.AmenityInput("Quadra",10,false,0L,0,null,policy(List.of(1),"08:00","09:00"),3L,null);
  assertEquals("AMENITY_ACTIVE_REQUIRED",code(()->AmenityManagementModels.validate(noActive,false)));
  var incomplete=new AmenityManagementModels.PolicyInput(1,List.of(1),"08:00","09:00",null,60,240,2,120);
  assertEquals("AMENITY_POLICY_INVALID",code(()->AmenityManagementModels.validate(input(incomplete),true)));
 }
 @Test void invalidDaysTimesAndLimitsAreRejected() {
  for(var days:List.of(List.<Integer>of(),List.of(0),List.of(8),List.of(1,1),List.of(1,2,3,4,5,6,7,1)))
   assertEquals("AMENITY_POLICY_INVALID",code(()->AmenityManagementModels.validate(input(policy(days,"08:00","22:00")),true)),days.toString());
  assertEquals("AMENITY_POLICY_INVALID",code(()->AmenityManagementModels.validate(input(policy(Arrays.asList(1,null),"08:00","22:00")),true)));
  for(String time:List.of("8:00","24:00","08:60","08:00:00"," 08:00"))
   assertEquals("AMENITY_POLICY_INVALID",code(()->AmenityManagementModels.validate(input(policy(List.of(1),time,"22:00")),true)),time);
  assertEquals("AMENITY_POLICY_INVALID",code(()->AmenityManagementModels.validate(input(policy(List.of(1),"22:00","08:00")),true)));
  var excessive=new AmenityManagementModels.PolicyInput(1,List.of(1),"08:00","22:00",0,400,240,2,120);
  assertEquals("AMENITY_POLICY_INVALID",code(()->AmenityManagementModels.validate(input(excessive),true)));
  var fee=new AmenityManagementModels.AmenityInput("Quadra",10,false,-1L,0,true,policy(List.of(1),"08:00","09:00"),null,null);
  assertEquals("AMENITY_FEE_INVALID",code(()->AmenityManagementModels.validate(fee,true)));
  var capacity=new AmenityManagementModels.AmenityInput("Quadra",0,false,0L,0,true,policy(List.of(1),"08:00","09:00"),null,null);
  assertEquals("AMENITY_CAPACITY_INVALID",code(()->AmenityManagementModels.validate(capacity,true)));
  var name=new AmenityManagementModels.AmenityInput("Qua\u0000dra",10,false,0L,0,true,policy(List.of(1),"08:00","09:00"),null,null);
  assertEquals("AMENITY_NAME_INVALID",code(()->AmenityManagementModels.validate(name,true)));
 }
}
