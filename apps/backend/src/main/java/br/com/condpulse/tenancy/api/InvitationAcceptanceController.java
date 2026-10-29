package br.com.condpulse.tenancy.api;

import java.util.UUID;
import br.com.condpulse.tenancy.application.InvitationService;
import br.com.condpulse.tenancy.application.MembershipModels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Aceite autenticado do convite. O condomínio e a unidade vêm do convite gravado, não do cliente. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
public class InvitationAcceptanceController {
 private final InvitationService invitations;
 public InvitationAcceptanceController(InvitationService invitations) { this.invitations=invitations; }
 @PostMapping("/v1/invitations/accept")
 public ResponseEntity<MembershipModels.InvitationAccepted> accept(@AuthenticationPrincipal Jwt jwt,
     @RequestBody MembershipModels.AcceptInvitation request) {
  Object email=jwt.getClaims().get("email");
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(invitations.accept(
   UUID.fromString(jwt.getSubject()),email instanceof String text?text:null,request));
 }
}
