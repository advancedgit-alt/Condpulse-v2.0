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
 static PolicyModels.PolicyDocument variant(Integer maxAge,String antiPassback,Integer occupancy,String companion,List<UUID> holidays) {
  var b=doc(List.of("RESIDENT"),"DENY",null,DAY,"SAFE_LOCAL_SUBSET","DENY");
  return new PolicyModels.PolicyDocument(b.schemaVersion(),b.name(),b.areaId(),b.timezone(),b.ruleCombination(),b.eligibleContexts(),b.visitorPolicy(),
   null,true,b.weeklyWindows(),holidays,b.calendarPrecedence(),b.reservation(),new PolicyModels.CompanionRule(companion,companion.equals("NONE")?0:30),
   b.offline(),b.failure(),antiPassback,occupancy,true,maxAge,"DENY");
 }
 @Test void everyUnsupportedRestrictionBlocksValidationAndDeniesEvaluation() {
  var supported=variant(null,"OFF",null,"NONE",List.of());
  assertTrue(PolicyEngine.unsupported(supported).isEmpty());
  assertEquals("ALLOW",PolicyEngine.evaluate(supported,ACTIVE,COMMISSIONED,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10).result());
  var cases=java.util.Map.of(
   "AGE_MAXIMUM_UNSUPPORTED",variant(12,"OFF",null,"NONE",List.of()),
   "ANTIPASSBACK_UNSUPPORTED",variant(null,"GLOBAL",null,"NONE",List.of()),
   "OCCUPANCY_UNSUPPORTED",variant(null,"OFF",30,"NONE",List.of()),
   "COMPANION_TOPOLOGY_UNAVAILABLE",variant(null,"OFF",null,"VERIFIED_PHYSICAL_COMPANION",List.of()),
   "HOLIDAY_CALENDAR_UNKNOWN",variant(null,"OFF",null,"NONE",List.of(UUID.randomUUID())));
  var local=variant(null,"LOCAL",null,"NONE",List.of());
  assertEquals(List.of("ANTIPASSBACK_UNSUPPORTED"),PolicyEngine.unsupported(local).stream().map(PolicyEngine.Unsupported::code).toList());
  for(var entry:cases.entrySet()) {
   var d=entry.getValue();
   assertNull(PolicyEngine.structure(d,AREA),entry.getKey());
   assertTrue(PolicyEngine.validate(d,ACTIVE,List.of(COMMISSIONED),SAT_10).stream()
    .anyMatch(i->entry.getKey().equals(i.code()) && "BLOCKER".equals(i.severity())),entry.getKey());
   // Cenário que satisfaz tudo o que o motor avalia, inclusive acompanhante verificado: ainda assim nunca ALLOW.
   var outcome=PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,new PolicyModels.SyntheticSubject("RESIDENT","VERIFIED_MEETS_MINIMUM",true,true,true),SAT_10);
   assertEquals("DENY",outcome.result(),entry.getKey());
   assertTrue(outcome.reasonCodes().contains("UNSUPPORTED_RESTRICTION"),entry.getKey());
  }
 }
 @Test void largeInstallationsAggregateIssuesWithinTheStoredLimit() {
  var d=doc(List.of("RESIDENT"),"DENY",18,DAY,"SAFE_LOCAL_SUBSET","DENY");
  var destinations=new java.util.ArrayList<PolicyModels.Destination>();
  for(int i=0;i<400;i++) destinations.add(new PolicyModels.Destination(UUID.randomUUID(),"MAPPED","FROM_TO",null,null,null,List.of()));
  for(int i=0;i<600;i++) destinations.add(new PolicyModels.Destination(UUID.randomUUID(),"COMMISSIONED","FROM_TO",1,"APROVADO_PILOTO",null,
   List.of("faceEnrollment","perDoorRights")));
  var issues=PolicyEngine.validate(d,ACTIVE,destinations,SAT_10);
  assertTrue(issues.size()<=300,"Cabe no limite do banco: "+issues.size());
  assertTrue(PolicyEngine.blocked(issues));
  var notCommissioned=issues.stream().filter(i->"NOT_COMMISSIONED".equals(i.code())).toList();
  assertEquals(1,notCommissioned.size()); assertEquals(400,notCommissioned.getFirst().occurrences());
  assertEquals(PolicyEngine.POINT_SAMPLE,notCommissioned.getFirst().pointIds().size());
  var missing=issues.stream().filter(i->"CAPABILITY_MISSING".equals(i.code())).toList();
  assertEquals(List.of("faceDeletion","localValidity","offlineRestriction","secureTransport"),missing.stream().map(PolicyModels.ValidationIssue::capability).toList(),
   "Todas as capacidades ausentes preservadas");
  assertTrue(missing.stream().allMatch(i->i.occurrences()==600 && "BLOCKER".equals(i.severity())));
  assertEquals(issues,PolicyEngine.validate(d,ACTIVE,destinations,SAT_10),"Agregação determinística");
 }
 /** Propriedade: em qualquer cenário em que a validação tem impeditivo (na regra, na área ou na passagem X), a avaliação em X nunca é ALLOW. */
 @Test void anyValidationBlockerOnATargetNeverEvaluatesToAllow() {
  var random=new java.util.Random(20261031L);
  var allContexts=List.of("RESIDENT","DEPENDENT","OWNER_NONRESIDENT","VISITOR","SERVICE_PROVIDER","EMPLOYEE","EMERGENCY_RESPONDER");
  int blockedCases=0,allowedCases=0;
  for(int n=0;n<5000;n++) {
   String context=allContexts.get(random.nextInt(allContexts.size()));
   var contexts=random.nextBoolean()?List.of(context):List.of(context,"RESIDENT".equals(context)?"DEPENDENT":"RESIDENT");
   String visitor=contexts.contains("VISITOR")?(contexts.size()==1 && random.nextBoolean()?"ONLY_VISITORS":"ALLOW_WITH_VALID_PERMIT"):"DENY";
   var windows=random.nextBoolean()?List.of(w("00:00","24:00",false,"MON","TUE","WED","THU","FRI","SAT","SUN")):DAY;
   String reservation=random.nextInt(4)==0?"VALID_RESERVATION":"NOT_REQUIRED";
   String companion=List.of("NONE","NONE","ASSISTED_BY_STAFF","VERIFIED_PHYSICAL_COMPANION").get(random.nextInt(4));
   var d=new PolicyModels.PolicyDocument("1.0","Regra",AREA,"America/Sao_Paulo","ALL",contexts,visitor,random.nextInt(3)==0?18:null,true,windows,
    random.nextInt(8)==0?List.of(UUID.randomUUID()):List.of(),"HOLIDAY_OVERRIDES_WEEKLY",new PolicyModels.ReservationRule(reservation,0,0),
    new PolicyModels.CompanionRule(companion,"NONE".equals(companion)?0:30),
    new PolicyModels.OfflineRule(random.nextBoolean()?"SAFE_LOCAL_SUBSET":"DENY_ENTRY",300,true),new PolicyModels.FailureRule("DENY","DENY"),
    random.nextInt(8)==0?"GLOBAL":"OFF",random.nextInt(8)==0?50:null,true,random.nextInt(8)==0?60:null,"DENY");
   assertNull(PolicyEngine.structure(d,AREA),"Cenário gerado precisa ser estruturalmente válido");
   var area=new PolicyModels.AreaFacts(AREA,List.of("ACTIVE","ACTIVE","ACTIVE","INTERDICTED","RETIRED").get(random.nextInt(5)),"COMMON_AREA",
    random.nextBoolean()?UUID.randomUUID():null,random.nextInt(6)==0?"America/Manaus":"America/Sao_Paulo",random.nextInt(6)==0);
   var caps=ALL_CAPS.stream().filter(c->random.nextInt(10)!=0).toList();
   var point=new PolicyModels.Destination(POINT,random.nextInt(6)==0?"MAPPED":"COMMISSIONED",random.nextInt(6)==0?"UNKNOWN":"FROM_TO",
    random.nextInt(8)==0?null:1,List.of("APROVADO_PILOTO","HOMOLOGADO","APROVADO_BANCADA","SUSPENSO").get(random.nextInt(4)),
    random.nextInt(8)==0?SAT_10.minusSeconds(60):null,caps);
   // Sujeito que satisfaz tudo o que depende dele: só os impeditivos podem negar.
   var subject=new PolicyModels.SyntheticSubject(context,"VERIFIED_MEETS_MINIMUM",true,true,true);
   var issues=PolicyEngine.validate(d,area,List.of(point),SAT_10);
   var outcome=PolicyEngine.evaluate(d,area,point,subject,SAT_10);
   if(PolicyEngine.blocked(issues)) {
    blockedCases++;
    assertNotEquals("ALLOW",outcome.result(),"Impeditivo "+issues.stream().filter(i->"BLOCKER".equals(i.severity())).map(PolicyModels.ValidationIssue::code).toList()
     +" virou ALLOW");
   } else if("ALLOW".equals(outcome.result())) allowedCases++;
  }
  assertTrue(blockedCases>1000 && allowedCases>50,"Gerador cobre os dois lados: "+blockedCases+"/"+allowedCases);
  // Caso do achado: sentido desconhecido bloqueia a validação e nega a avaliação.
  var d=doc(List.of("RESIDENT"),"DENY",null,DAY,"SAFE_LOCAL_SUBSET","DENY");
  var unknown=new PolicyModels.Destination(POINT,"COMMISSIONED","UNKNOWN",1,"APROVADO_PILOTO",null,ALL_CAPS);
  assertTrue(PolicyEngine.blocked(PolicyEngine.validate(d,ACTIVE,List.of(unknown),SAT_10)));
  var outcome=PolicyEngine.evaluate(d,ACTIVE,unknown,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10);
  assertEquals("DENY",outcome.result()); assertTrue(outcome.reasonCodes().contains("POLICY_BLOCKED"));
  // Mais passagens do que o limite: bloqueio explícito, nunca lista truncada.
  assertTrue(PolicyEngine.validate(d,ACTIVE,List.of(COMMISSIONED),PolicyEngine.MAX_TARGETS+1,SAT_10).stream().anyMatch(i->"TOO_MANY_TARGETS".equals(i.code())));
  assertEquals("DENY",PolicyEngine.evaluate(d,ACTIVE,COMMISSIONED,PolicyEngine.MAX_TARGETS+1,subject("RESIDENT","VERIFIED_MEETS_MINIMUM"),SAT_10).result());
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

 static PolicyModels.SubjectFacts real(String context,String age,boolean active,boolean current,boolean grant,boolean revoked) {
  return new PolicyModels.SubjectFacts(true,active,current,context,"MEMBERSHIP",age,false,false,grant,revoked);
 }
 @Test void realSubjectSharesTheRuleAndNeedsAnUnrevokedGrant() {
  var d=doc(List.of("RESIDENT","DEPENDENT"),"DENY",18,DAY,"SAFE_LOCAL_SUBSET","DENY");
  var ok=PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,real("RESIDENT","VERIFIED_MEETS_MINIMUM",true,true,true,false),SAT_10);
  assertEquals("ALLOW",ok.result(),ok.reasonCodes().toString());
  var noGrant=PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,real("RESIDENT","VERIFIED_MEETS_MINIMUM",true,true,false,false),SAT_10);
  assertEquals("DENY",noGrant.result()); assertEquals(List.of("NO_ACTIVE_GRANT"),noGrant.reasonCodes());
  var revoked=PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,real("RESIDENT","VERIFIED_MEETS_MINIMUM",false,true,false,true),SAT_10);
  assertEquals("DENY",revoked.result());
  assertTrue(revoked.reasonCodes().containsAll(List.of("SUBJECT_BLOCKED","REVOKED","NO_ACTIVE_GRANT")),"Revogação vence direito antigo");
  var minor=PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,real("DEPENDENT","VERIFIED_BELOW_MINIMUM",true,true,true,false),SAT_10);
  assertEquals("DENY",minor.result()); assertTrue(minor.reasonCodes().contains("AGE_BELOW_MINIMUM"),"HAC-031 com sujeito real");
  var night=PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,real("RESIDENT","VERIFIED_MEETS_MINIMUM",true,true,true,false),SAT_23);
  assertEquals("DENY",night.result()); assertTrue(night.reasonCodes().contains("OUTSIDE_SCHEDULE"));
  var unmapped=PolicyEngine.evaluateSubject(d,ACTIVE,new PolicyModels.Destination(POINT,"MAPPED","FROM_TO",null,null,null,List.of()),1,
   real("RESIDENT","VERIFIED_MEETS_MINIMUM",true,true,false,false),SAT_10);
  assertEquals("UNAVAILABLE",unmapped.result(),"Mesmos impeditivos da validação");
  assertEquals("DENY",PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,new PolicyModels.SubjectFacts(false,false,false,null,null,null,false,false,false,false),
   SAT_10).result());
  assertEquals("DENY",PolicyEngine.evaluateSubject(d,ACTIVE,COMMISSIONED,1,real("MANAGER","VERIFIED_MEETS_MINIMUM",true,true,true,false),SAT_10).result(),
   "Tipo de contexto desconhecido nunca libera");
 }
}
