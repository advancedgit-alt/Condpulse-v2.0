package br.com.condpulse.platform.api;

import java.util.UUID;
import br.com.condpulse.platform.application.ManagerInvitationService;
import br.com.condpulse.platform.application.PlatformModels;
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

/** Aceite autenticado do convite de primeiro gestor (painel master). O condomínio vem do convite gravado. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.platform.enabled",havingValue="true")
public class ManagerInvitationController {
 private final ManagerInvitationService invitations;
 public ManagerInvitationController(ManagerInvitationService invitations) { this.invitations=invitations; }
 @PostMapping("/v1/manager-invitations/accept")
 public ResponseEntity<MembershipModels.InvitationAccepted> accept(@AuthenticationPrincipal Jwt jwt,
     @RequestBody PlatformModels.AcceptManagerInvitation request) {
  Object email=jwt.getClaims().get("email");
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(invitations.accept(
   UUID.fromString(jwt.getSubject()),email instanceof String text?text:null,request));
 }
}
