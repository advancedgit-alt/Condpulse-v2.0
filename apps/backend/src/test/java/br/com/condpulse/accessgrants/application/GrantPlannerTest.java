package br.com.condpulse.accessgrants.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesspolicy.application.PolicyModels;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** AC-009: derivação determinística; vigência nunca além das origens; reserva e convidado; idade só por evidência. */
class GrantPlannerTest {
 static final UUID AREA=UUID.randomUUID(),UNIT=UUID.randomUUID();
 static final Instant NOW=Instant.parse("2026-10-31T13:00:00Z");
 static PolicyModels.PolicyDocument doc(List<String> contexts,String visitor,Integer min,String reservation) {
  return new PolicyModels.PolicyDocument("1.0","Area",AREA,"America/Sao_Paulo","ALL",contexts,visitor,min,true,
   List.of(new PolicyModels.WeeklyWindow(List.of("MON","TUE","WED","THU","FRI","SAT","SUN"),"00:00","24:00",false)),List.of(),"HOLIDAY_OVERRIDES_WEEKLY",
   new PolicyModels.ReservationRule(reservation,600,900),new PolicyModels.CompanionRule("NONE",0),new PolicyModels.OfflineRule("DENY_ENTRY",0,false),
   new PolicyModels.FailureRule("DENY","DENY"),"OFF",null,true,null,"DENY");
 }
 static GrantPlanner.Candidate member(String kind,Instant until,boolean active,boolean current) {
  return new GrantPlanner.Candidate(UUID.randomUUID(),UUID.randomUUID(),kind,"MEMBERSHIP",UUID.randomUUID(),null,UNIT,3,until,2,active,current,null,null);
 }
 static GrantPlanner.Candidate visitor(Instant until,UUID reservation) {
  return new GrantPlanner.Candidate(UUID.randomUUID(),UUID.randomUUID(),"VISITOR","VISIT_PERMIT",null,UUID.randomUUID(),UNIT,1,until,0,true,true,until,reservation);
 }

 @Test void membershipGrantIsFiniteAndBoundedByOrigin() {
  var open=member("RESIDENT",null,true,true);
  var shortLived=member("RESIDENT",NOW.plus(Duration.ofDays(3)),true,true);
  var plan=GrantPlanner.plan(doc(List.of("RESIDENT"),"DENY",null,"NOT_REQUIRED"),List.of(open,shortLived),List.of(),Map.of(),NOW,null);
  assertEquals(2,plan.grants().size());
  assertEquals(NOW.plus(GrantPlanner.HORIZON),plan.grants().get(0).validUntil(),"Sem fim no vínculo: horizonte de 30 dias, nunca infinito");
  assertEquals(NOW.plus(Duration.ofDays(3)),plan.grants().get(1).validUntil(),"Nunca além do vínculo");
  assertEquals("MEMBERSHIP",plan.grants().get(0).originKind()); assertEquals(open.membershipId(),plan.grants().get(0).originId());
  var capped=GrantPlanner.plan(doc(List.of("RESIDENT"),"DENY",null,"NOT_REQUIRED"),List.of(open),List.of(),Map.of(),NOW,NOW.plus(Duration.ofDays(1)));
  assertEquals(NOW.plus(Duration.ofDays(1)),capped.grants().getFirst().validUntil(),"Nunca além da revisão");
 }

 @Test void blockedStaleOrIneligibleContextsAreSkippedWithReason() {
  var plan=GrantPlanner.plan(doc(List.of("RESIDENT"),"DENY",null,"NOT_REQUIRED"),
   List.of(member("RESIDENT",null,false,true),member("RESIDENT",null,true,false),member("EMPLOYEE",null,true,true)),List.of(),Map.of(),NOW,null);
  assertTrue(plan.grants().isEmpty());
  assertEquals(List.of("SUBJECT_BLOCKED","CONTEXT_NOT_CURRENT","CONTEXT_NOT_ELIGIBLE"),plan.skipped().stream().map(GrantModels.Skip::reason).toList());
 }

 @Test void ageOnlyFromVerifiedEvidenceAndBoundedByIt() {
  var adult=member("RESIDENT",null,true,true);
  var minor=member("DEPENDENT",null,true,true);
  var unknown=member("DEPENDENT",null,true,true);
  UUID evidence=UUID.randomUUID();
  var ages=Map.of(adult.subjectId(),new GrantPlanner.AgeFact("MEETS",evidence,NOW.plus(Duration.ofDays(5))),
   minor.subjectId(),new GrantPlanner.AgeFact("DOES_NOT_MEET",UUID.randomUUID(),NOW.plus(Duration.ofDays(5))));
  var plan=GrantPlanner.plan(doc(List.of("RESIDENT","DEPENDENT"),"DENY",18,"NOT_REQUIRED"),List.of(adult,minor,unknown),List.of(),ages,NOW,null);
  assertEquals(1,plan.grants().size());
  assertEquals(evidence,plan.grants().getFirst().ageEvidenceId());
  assertEquals(NOW.plus(Duration.ofDays(5)),plan.grants().getFirst().validUntil(),"Nunca além da evidência");
  assertEquals(List.of("AGE_BELOW_MINIMUM","AGE_NOT_VERIFIED"),plan.skipped().stream().map(GrantModels.Skip::reason).toList());
 }

 @Test void reservationRuleGrantsOnlyHostsAndListedGuestsWithinTheBookingWindow() {
  var host=member("RESIDENT",null,true,true);
  var other=new GrantPlanner.Candidate(UUID.randomUUID(),UUID.randomUUID(),"RESIDENT","MEMBERSHIP",UUID.randomUUID(),null,UUID.randomUUID(),1,null,0,true,true,null,null);
  var booking=new GrantPlanner.Reservation(UUID.randomUUID(),UNIT,4,NOW.plus(Duration.ofHours(2)),NOW.plus(Duration.ofHours(5)));
  var guest=visitor(NOW.plus(Duration.ofDays(1)),booking.reservationId());
  var stranger=visitor(NOW.plus(Duration.ofDays(1)),null);
  var plan=GrantPlanner.plan(doc(List.of("RESIDENT","VISITOR"),"ALLOW_WITH_VALID_PERMIT",null,"HOST_OR_LISTED_GUEST"),List.of(host,other,guest,stranger),
   List.of(booking),Map.of(),NOW,null);
  assertEquals(2,plan.grants().size());
  var h=plan.grants().get(0); var g=plan.grants().get(1);
  assertEquals("RESERVATION",h.originKind()); assertEquals(booking.reservationId(),h.originId()); assertEquals(4,h.originVersion());
  assertEquals(booking.startsAt().minusSeconds(600),h.validFrom()); assertEquals(booking.endsAt().plusSeconds(900),h.validUntil());
  assertEquals("VISIT_PERMIT",g.originKind()); assertTrue(g.reasonCodes().contains("RESERVATION_GUEST"));
  assertEquals(List.of("RESERVATION_REQUIRED","RESERVATION_REQUIRED"),plan.skipped().stream().map(GrantModels.Skip::reason).toList(),
   "Unidade sem reserva e visitante sem reserva não recebem direito (sem área vizinha)");
  var strict=GrantPlanner.plan(doc(List.of("RESIDENT","VISITOR"),"ALLOW_WITH_VALID_PERMIT",null,"VALID_RESERVATION"),List.of(guest),List.of(booking),Map.of(),NOW,null);
  assertTrue(strict.grants().isEmpty(),"Reserva obrigatória: convidado só na regra HOST_OR_LISTED_GUEST");
 }

 @Test void expiredWindowIsSkipped() {
  var plan=GrantPlanner.plan(doc(List.of("RESIDENT"),"DENY",null,"NOT_REQUIRED"),List.of(member("RESIDENT",NOW.minusSeconds(1),true,true)),List.of(),Map.of(),NOW,null);
  assertEquals("WINDOW_EMPTY",plan.skipped().getFirst().reason());
 }
}
