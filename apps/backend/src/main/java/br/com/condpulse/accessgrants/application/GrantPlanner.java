package br.com.condpulse.accessgrants.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.accesspolicy.application.PolicyModels;

/**
 * Planejamento determinístico da derivação (AC-009): mesma entrada, mesmo plano, sem banco nem relógio. Cada direito
 * tem uma origem identificável e vigência que nunca passa do contexto, do convite, da reserva (com tolerâncias da
 * regra), da evidência de idade, da revisão e do horizonte. O banco confere tudo de novo ao gravar.
 */
public final class GrantPlanner {
 private GrantPlanner() {}
 /** Horizonte de um direito derivado (decisão provisória P16): renovado por nova derivação. */
 public static final Duration HORIZON=Duration.ofDays(30);
 /** Máximo de contextos avaliados numa derivação; acima disso a derivação é recusada (nunca truncada). */
 public static final int MAX_CANDIDATES=5000;

 public record Candidate(UUID contextId,UUID subjectId,String contextKind,String sourceKind,UUID membershipId,UUID permitId,UUID unitId,
   long sourceVersion,Instant contextUntil,long restrictionEpoch,boolean subjectActive,boolean current,Instant permitUntil,UUID permitReservationId,
   boolean permitReservationConfirmed) {}
 public record Reservation(UUID reservationId,UUID unitId,long version,Instant startsAt,Instant endsAt) {}
 /** Resultado da conferência de idade (MEETS/DOES_NOT_MEET/UNKNOWN) e a evidência usada. */
 public record AgeFact(String outcome,UUID evidenceId,Instant validUntil) {}
 public record Planned(Candidate candidate,String originKind,UUID originId,long originVersion,Instant validFrom,Instant validUntil,UUID ageEvidenceId,
   List<String> reasonCodes) {}
 public record Plan(List<Planned> grants,List<GrantModels.Skip> skipped) {}

 public static Plan plan(PolicyModels.PolicyDocument d,List<Candidate> candidates,List<Reservation> reservations,Map<UUID,AgeFact> ages,
   Instant now,Instant versionUntil) {
  var grants=new ArrayList<Planned>();
  var skipped=new ArrayList<GrantModels.Skip>();
  String mode=d.reservation().mode();
  long before=d.reservation().entryGraceBeforeSeconds(),after=d.reservation().entryGraceAfterSeconds();
  boolean ageRule=d.minimumAgeYears()!=null;
  for(var c:candidates) {
   if(!d.eligibleContexts().contains(c.contextKind())) { skipped.add(new GrantModels.Skip(c.contextId(),"CONTEXT_NOT_ELIGIBLE")); continue; }
   if(!c.subjectActive()) { skipped.add(new GrantModels.Skip(c.contextId(),"SUBJECT_BLOCKED")); continue; }
   if(!c.current()) { skipped.add(new GrantModels.Skip(c.contextId(),"CONTEXT_NOT_CURRENT")); continue; }
   if(d.maximumAgeYears()!=null) { skipped.add(new GrantModels.Skip(c.contextId(),"AGE_MAXIMUM_UNSUPPORTED")); continue; }
   Instant limit=min(now.plus(HORIZON),c.contextUntil(),versionUntil);
   UUID evidence=null;
   var reasons=new ArrayList<String>(List.of("ELIGIBLE_CONTEXT"));
   if(ageRule) {
    var age=ages.get(c.subjectId());
    if(age==null || !"MEETS".equals(age.outcome()) || age.evidenceId()==null) {
     skipped.add(new GrantModels.Skip(c.contextId(),age!=null && "DOES_NOT_MEET".equals(age.outcome())?"AGE_BELOW_MINIMUM":"AGE_NOT_VERIFIED")); continue;
    }
    evidence=age.evidenceId(); limit=min(limit,age.validUntil()); reasons.add("AGE_VERIFIED");
   }
   switch(c.sourceKind()) {
    case "MEMBERSHIP" -> {
     if("NOT_REQUIRED".equals(mode)) add(grants,skipped,c,"MEMBERSHIP",c.membershipId(),c.sourceVersion(),now,limit,evidence,reasons);
     else {
      if(!"RESIDENT".equals(c.contextKind()) && !"DEPENDENT".equals(c.contextKind()) || c.unitId()==null) {
       skipped.add(new GrantModels.Skip(c.contextId(),"RESERVATION_REQUIRED")); continue;
      }
      var own=reservations.stream().filter(r->c.unitId().equals(r.unitId())).toList();
      if(own.isEmpty()) { skipped.add(new GrantModels.Skip(c.contextId(),"RESERVATION_REQUIRED")); continue; }
      for(var r:own) {
       var rr=new ArrayList<>(reasons); rr.add("RESERVATION_HOST");
       Instant from=max(now,r.startsAt().minusSeconds(before));
       add(grants,skipped,c,"RESERVATION",r.reservationId(),r.version(),from,min(limit,r.endsAt().plusSeconds(after)),evidence,rr);
      }
     }
    }
    case "VISIT_PERMIT" -> {
     // Convite de convidado vale só com a reserva do anfitrião confirmada (o banco confere o mesmo).
     if(c.permitReservationId()!=null && !c.permitReservationConfirmed()) { skipped.add(new GrantModels.Skip(c.contextId(),"RESERVATION_REQUIRED")); continue; }
     Instant until=min(limit,c.permitUntil());
     if("NOT_REQUIRED".equals(mode)) {
      var rr=new ArrayList<>(reasons); rr.add("VISIT_PERMIT");
      add(grants,skipped,c,"VISIT_PERMIT",c.permitId(),c.sourceVersion(),now,until,evidence,rr);
     } else if("HOST_OR_LISTED_GUEST".equals(mode) && c.permitReservationId()!=null) {
      var r=reservations.stream().filter(x->x.reservationId().equals(c.permitReservationId())).findFirst().orElse(null);
      if(r==null) { skipped.add(new GrantModels.Skip(c.contextId(),"RESERVATION_REQUIRED")); continue; }
      var rr=new ArrayList<>(reasons); rr.add("RESERVATION_GUEST");
      add(grants,skipped,c,"VISIT_PERMIT",c.permitId(),c.sourceVersion(),max(now,r.startsAt().minusSeconds(before)),min(until,r.endsAt().plusSeconds(after)),
       evidence,rr);
     } else skipped.add(new GrantModels.Skip(c.contextId(),"RESERVATION_REQUIRED"));
    }
    case "FUNCTIONAL_AUTHORIZATION" -> {
     if(!"NOT_REQUIRED".equals(mode)) { skipped.add(new GrantModels.Skip(c.contextId(),"RESERVATION_REQUIRED")); continue; }
     var rr=new ArrayList<>(reasons); rr.add("FUNCTIONAL_AUTHORIZATION");
     add(grants,skipped,c,"FUNCTIONAL_AUTHORIZATION",c.contextId(),0,now,limit,evidence,rr);
    }
    default -> skipped.add(new GrantModels.Skip(c.contextId(),"ORIGIN_UNSUPPORTED"));
   }
  }
  return new Plan(List.copyOf(grants),List.copyOf(skipped));
 }
 private static void add(List<Planned> grants,List<GrantModels.Skip> skipped,Candidate c,String kind,UUID origin,long version,Instant from,Instant until,
   UUID evidence,List<String> reasons) {
  if(until!=null) until=until.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  from=from.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  if(until==null || !until.isAfter(from)) { skipped.add(new GrantModels.Skip(c.contextId(),"WINDOW_EMPTY")); return; }
  grants.add(new Planned(c,kind,origin,version,from,until,evidence,List.copyOf(reasons)));
 }
 static Instant min(Instant... values) {
  Instant out=null;
  for(var v:values) if(v!=null && (out==null || v.isBefore(out))) out=v;
  return out;
 }
 static Instant max(Instant a,Instant b) { return a.isAfter(b)?a:b; }
}
