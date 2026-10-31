package br.com.condpulse.accesssubjects.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** AC-005: resolução determinística de contextos, sem união permissiva (HAC-030). */
class ContextPrecedenceTest {
 private static final Instant NOW=Instant.parse("2026-10-31T15:00:00Z");
 private static final UUID A=UUID.fromString("00000000-0000-4000-8000-0000000000a1"),B=UUID.fromString("00000000-0000-4000-8000-0000000000b2"),
  C=UUID.fromString("00000000-0000-4000-8000-0000000000c3"),D=UUID.fromString("00000000-0000-4000-8000-0000000000d4");
 private static ContextPrecedence.Candidate c(UUID id,String kind,String status,Instant until,boolean current) {
  return new ContextPrecedence.Candidate(id,kind,status,NOW.minus(Duration.ofDays(1)),until,current);
 }

 @Test void expiredInvitationDoesNotCancelResidenceAndViceVersa() {
  var residence=c(A,"RESIDENT","ACTIVE",null,true);
  var expiredInvite=c(B,"VISITOR","ACTIVE",NOW.minusSeconds(1),true);
  assertEquals(List.of(A),ContextPrecedence.eligible("ACTIVE",List.of(expiredInvite,residence),NOW),
   "Convite vencido não derruba a residência válida");
  var event=c(C,"VISITOR","ACTIVE",NOW.plus(Duration.ofHours(4)),true);
  assertEquals(List.of(A,C),ContextPrecedence.eligible("ACTIVE",List.of(event,residence),NOW),
   "Residência não absorve o convite: cada contexto segue separado, na ordem fixa");
 }
 @Test void subjectBlockIsTransversalWhileContextRevocationIsLocal() {
  var residence=c(A,"RESIDENT","ACTIVE",null,true);
  var functional=c(B,"SERVICE_PROVIDER","REVOKED",NOW.plus(Duration.ofDays(3)),false);
  assertEquals(List.of(A),ContextPrecedence.eligible("ACTIVE",List.of(residence,functional),NOW),"Revogar um contexto só afeta ele");
  assertTrue(ContextPrecedence.eligible("BLOCKED",List.of(residence),NOW).isEmpty(),"Bloqueio do sujeito vale para todos");
  assertTrue(ContextPrecedence.eligible("ERASURE_PENDING",List.of(residence),NOW).isEmpty());
  assertTrue(ContextPrecedence.eligible(null,List.of(residence),NOW).isEmpty());
 }
 @Test void staleSourceFutureStartAndUnknownKindNeverAuthorize() {
  var stale=c(A,"RESIDENT","ACTIVE",null,false);
  var future=new ContextPrecedence.Candidate(B,"VISITOR","ACTIVE",NOW.plusSeconds(60),NOW.plus(Duration.ofDays(1)),true);
  var unknown=c(C,"VIP","ACTIVE",null,true);
  assertTrue(ContextPrecedence.eligible("ACTIVE",List.of(stale,future,unknown),NOW).isEmpty());
 }
 @Test void orderIsDeterministicForAnyInputOrder() {
  var list=new ArrayList<>(List.of(c(D,"VISITOR","ACTIVE",NOW.plus(Duration.ofHours(1)),true),
   c(C,"EMPLOYEE","ACTIVE",NOW.plus(Duration.ofDays(2)),true),c(B,"EMPLOYEE","ACTIVE",null,true),c(A,"DEPENDENT","ACTIVE",null,true)));
  var expected=List.of(A,B,C,D);
  for(int i=0;i<8;i++) { Collections.shuffle(list,new java.util.Random(i)); assertEquals(expected,ContextPrecedence.eligible("ACTIVE",list,NOW)); }
 }
}
