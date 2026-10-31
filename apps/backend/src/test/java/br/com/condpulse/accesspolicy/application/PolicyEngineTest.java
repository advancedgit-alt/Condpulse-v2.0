package br.com.condpulse.accesspolicy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** AC-008: motor determinístico. HAC-031 (17 anos em área 18+), HAC-033 (idade desconhecida), HAC-036 (visitante), HAC-040 (horário), HAC-043 (capacidade). */
class PolicyEngineTest {
 static final UUID AREA=UUID.randomUUID(),POINT=UUID.randomUUID();
 static final List<String> ALL_CAPS=PolicyEngine.CAPABILITIES;
 static PolicyModels.PolicyDocument doc(List<String> contexts,String visitor,Integer min,List<PolicyModels.WeeklyWindow> windows,String offline,String missing) {
  return new PolicyModels.PolicyDocument("1.0","Academia",AREA,"America/Sao_Paulo","ALL",contexts,visitor,min,true,windows,List.of(),
   "HOLIDAY_OVERRIDES_WEEKLY",new PolicyModels.ReservationRule("NOT_REQUIRED",0,0),new PolicyModels.CompanionRule("NONE",0),
   new PolicyModels.OfflineRule(offline,300,true),new PolicyModels.FailureRule(missing,"DENY"),"OFF",null,true,null,"DENY");
 }
 static PolicyModels.WeeklyWindow w(String start,String end,boolean crosses,String... days) { return new PolicyModels.WeeklyWindow(List.of(days),start,end,crosses); }
 static final List<PolicyModels.WeeklyWindow> DAY=List.of(w("06:00","22:00",false,"MON","TUE","WED","THU","FRI","SAT","SUN"));
 static final PolicyModels.AreaFacts ACTIVE=new PolicyModels.AreaFacts(AREA,"ACTIVE","COMMON_AREA",null,"America/Sao_Paulo",false);
 static final PolicyModels.Destination COMMISSIONED=new PolicyModels.Destination(POINT,"COMMISSIONED","FROM_TO",2,"APROVADO_PILOTO",null,ALL_CAPS);
 static PolicyModels.SyntheticSubject subject(String context,String age) { return new PolicyModels.SyntheticSubject(context,age,false,false,false); }
 // 31/10/2026 é sábado; 13:00Z = 10:00 em São Paulo.
 static final Instant SAT_10=Instant.parse("2026-10-31T13:00:00Z"),SAT_23=Instant.parse("2026-11-01T02:00:00Z");

 @Test void structureRejectsOpenOrInconsistentDocuments() {
  var good=doc(List.of("RESIDENT"),"DENY",18,DAY,"SAFE_LOCAL_SUBSET","DENY");
  assertNull(PolicyEngine.structure(good,AREA));
  assertEquals("POLICY_AREA_MISMATCH",PolicyEngine.structure(good,UUID.randomUUID()).code());
  assertEquals("POLICY_VISITOR_INCONSISTENT",PolicyEngine.structure(doc(List.of("RESIDENT","VISITOR"),"DENY",null,DAY,"DENY_ENTRY","DENY"),AREA).code());
  assertEquals("POLICY_WINDOW_INVALID",PolicyEngine.structure(doc(List.of("RESIDENT"),"DENY",null,List.of(w("22:00","06:00",false,"MON")),"DENY_ENTRY","DENY"),AREA).code(),
   "Janela sem virada exige fim depois do início");
  assertEquals("POLICY_WINDOW_INVALID",PolicyEngine.structure(doc(List.of("RESIDENT"),"DENY",null,List.of(w("22:00","24:00",true,"MON")),"DENY_ENTRY","DENY"),AREA).code());
  assertNull(PolicyEngine.structure(doc(List.of("RESIDENT"),"DENY",null,List.of(w("22:00","06:00",true,"FRI")),"DENY_ENTRY","DENY"),AREA));
  var noEvidence=new PolicyModels.PolicyDocument("1.0","A",AREA,"America/Sao_Paulo","ALL",List.of("RESIDENT"),"DENY",18,false,DAY,List.of(),
   "HOLIDAY_OVERRIDES_WEEKLY",new PolicyModels.ReservationRule("NOT_REQUIRED",0,0),new PolicyModels.CompanionRule("NONE",0),
   new PolicyModels.OfflineRule("DENY_ENTRY",0,false),new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY");
  assertEquals("POLICY_AGE_EVIDENCE_REQUIRED",PolicyEngine.structure(noEvidence,AREA).code(),"Idade nunca inferida pela face");
  assertEquals("POLICY_TIMEZONE_INVALID",PolicyEngine.structure(new PolicyModels.PolicyDocument("1.0","A",AREA,"UTC","ALL",List.of("RESIDENT"),"DENY",null,
   true,DAY,List.of(),"HOLIDAY_OVERRIDES_WEEKLY",new PolicyModels.ReservationRule("NOT_REQUIRED",0,0),new PolicyModels.CompanionRule("NONE",0),
   new PolicyModels.OfflineRule("DENY_ENTRY",0,false),new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY"),AREA).code());
 }
 @Test void validationBlocksMissingCapabilitiesAndRestrictedEssentialRoutes() {
  var d=doc(List.of("RESIDENT"),"DENY",18,DAY,"SAFE_LOCAL_SUBSET","DENY");
  assertEquals(List.of("faceEnrollment","faceDeletion","perDoorRights","localValidity","offlineRestriction","secureTransport"),PolicyEngine.requiredCapabilities(d));
  var partial=new PolicyModels.Destination(POINT,"COMMISSIONED","FROM_TO",2,"APROVADO_PILOTO",null,List.of("faceEnrollment","faceDeletion","perDoorRights","secureTransport"));
  var issues=PolicyEngine.validate(d,ACTIVE,List.of(partial),SAT_10);
  assertTrue(PolicyEngine.blocked(issues));
  assertEquals(List.of("localValidity","offlineRestriction"),issues.stream().filter(i->"CAPABILITY_MISSING".equals(i.code())).map(PolicyModels.ValidationIssue::capability).toList(),
   "HAC-043: incompatibilidade com capacidade aprovada bloqueia");
  assertFalse(PolicyEngine.blocked(PolicyEngine.validate(d,ACTIVE,List.of(COMMISSIONED),SAT_10)));
  var essential=new PolicyModels.AreaFacts(AREA,"ACTIVE","RESIDENTIAL_ROUTE",null,"America/Sao_Paulo",true);
  assertTrue(PolicyEngine.validate(d,essential,List.of(COMMISSIONED),SAT_10).stream().anyMatch(i->"ESSENTIAL_ROUTE_RESTRICTED".equals(i.code())));
  assertTrue(PolicyEngine.validate(d,ACTIVE,List.of(),SAT_10).stream().anyMatch(i->"NO_DESTINATION".equals(i.code())));
  var uncommissioned=new PolicyModels.Destination(POINT,"MAPPED","FROM_TO",null,null,null,List.of());
  assertTrue(PolicyEngine.validate(d,ACTIVE,List.of(uncommissioned),SAT_10).stream().anyMatch(i->"NOT_COMMISSIONED".equals(i.code())));
  var expired=new PolicyModels.Destination(POINT,"COMMISSIONED","FROM_TO",2,"APROVADO_PILOTO",SAT_10.minusSeconds(1),ALL_CAPS);
  assertTrue(PolicyEngine.validate(d,ACTIVE,List.of(expired),SAT_10).stream().anyMatch(i->"HOMOLOGATION_NOT_APPROVED".equals(i.code())));
  assertEquals(PolicyEngine.validate(d,ACTIVE,List.of(partial),SAT_10),PolicyEngine.validate(d,ACTIVE,List.of(partial),SAT_10),"Determinístico");
 }
 @Test void evaluationIsDeterministicAndDeniesByDefault() {
  var d=doc(List.of("RESIDENT","DEPENDENT"),"DENY",18,DAY,"SAFE_LOCAL_SUBSET","DENY");
  assertEquals("ALLOW",PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10).result());
  var minor=PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("DEPENDENT","VERIFIED_BELOW_MINIMUM"),SAT_10);
  assertEquals("DENY",minor.result()); assertEquals(List.of("AGE_BELOW_MINIMUM"),minor.reasonCodes(),"HAC-031");
  assertEquals(List.of("AGE_NOT_VERIFIED"),PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("RESIDENT","UNKNOWN"),SAT_10).reasonCodes(),"HAC-033");
  var assisted=doc(List.of("RESIDENT"),"DENY",18,DAY,"SAFE_LOCAL_SUBSET","ASSISTED_VERIFICATION");
  assertEquals("REQUIRE_ASSISTANCE",PolicyEngine.evaluate(assisted,ACTIVE,COMMISSIONED,subject("RESIDENT","UNKNOWN"),SAT_10).result(),
   "Atendimento nunca vira ALLOW silencioso");
  assertEquals(List.of("VISITOR_NOT_ALLOWED"),PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("VISITOR","VERIFIED_MEETS_MINIMUM"),SAT_10).reasonCodes(),"HAC-036");
  assertEquals(List.of("OUTSIDE_SCHEDULE"),PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_23).reasonCodes(),"HAC-040");
  var night=doc(List.of("RESIDENT"),"DENY",null,List.of(w("22:00","06:00",true,"SAT")),"SAFE_LOCAL_SUBSET","DENY");
  assertEquals("ALLOW",PolicyEngine.evaluate(night,ACTIVE,COMMISSIONED,subject("RESIDENT","UNKNOWN"),SAT_23).result(),"Janela que vira a noite");
  assertEquals("DENY",PolicyEngine.evaluate(night,ACTIVE,COMMISSIONED,subject("RESIDENT","UNKNOWN"),SAT_10).result());
  var uncommissioned=new PolicyModels.Destination(POINT,"MAPPED","FROM_TO",null,null,null,List.of());
  assertEquals("UNAVAILABLE",PolicyEngine.evaluate(d,ACTIVE,uncommissioned,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10).result());
  var interdicted=new PolicyModels.AreaFacts(AREA,"INTERDICTED","COMMON_AREA",null,"America/Sao_Paulo",false);
  assertEquals(List.of("AREA_INTERDICTED"),PolicyEngine.evaluate(d,interdicted,COMMISSIONED,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10).reasonCodes());
  var partial=new PolicyModels.Destination(POINT,"COMMISSIONED","FROM_TO",2,"APROVADO_PILOTO",null,List.of("faceEnrollment"));
  var missing=PolicyEngine.evaluate(d,ACTIVE,partial,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10);
  assertEquals("DENY",missing.result()); assertTrue(missing.missingCapabilities().contains("localValidity"));
  assertEquals(PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("DEPENDENT","UNKNOWN"),SAT_23),
   PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,subject("DEPENDENT","UNKNOWN"),SAT_23),"Mesma entrada, mesma saída");
 }
}
