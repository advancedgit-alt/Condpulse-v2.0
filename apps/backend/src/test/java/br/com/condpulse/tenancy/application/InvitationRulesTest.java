package br.com.condpulse.tenancy.application;

import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvitationRulesTest {
 @Test void emailIsNormalizedAndHashIsBoundToTheInvitation() {
  assertEquals("pessoa@example.invalid",InvitationService.normalizedEmail("  Pessoa@Example.INVALID "));
  UUID first=UUID.randomUUID(),second=UUID.randomUUID();
  String hash=InvitationService.emailHash(first,"pessoa@example.invalid");
  assertTrue(hash.matches("[0-9a-f]{64}"));
  assertNotEquals(hash,InvitationService.emailHash(second,"pessoa@example.invalid"),"Hash depende do convite");
  assertFalse(hash.contains("pessoa"));
 }
 @Test void malformedEmailsAreRejected() {
  for(String email:new String[]{null,"","sem-arroba","a@b","a b@example.invalid","@example.invalid","a@@example.invalid"})
   assertEquals("INVITATION_EMAIL_INVALID",assertThrows(BusinessProblem.class,()->InvitationService.normalizedEmail(email)).code(),String.valueOf(email));
 }
 @Test void tokensAreHighEntropyUrlSafeAndOnlyTheirHashIsStored() {
  String token=InvitationService.newToken();
  assertTrue(token.matches("[A-Za-z0-9_-]{43}")); assertNotEquals(token,InvitationService.newToken());
  assertTrue(InvitationService.sha256(token).matches("[0-9a-f]{64}"));
  assertFalse(new MembershipModels.InvitationCreated(UUID.randomUUID(),token,java.time.Instant.now()).toString().contains(token));
  assertFalse(new MembershipModels.AcceptInvitation(token).toString().contains(token));
 }
 @Test void managerRoleIsNeverOfferedByInvitation() {
  assertFalse(InvitationService.ROLES.contains("MANAGER")); assertFalse(InvitationService.ROLES.contains("SUPPORT"));
 }
}
