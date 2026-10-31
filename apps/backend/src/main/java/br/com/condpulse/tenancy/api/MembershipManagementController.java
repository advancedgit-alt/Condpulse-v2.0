package br.com.condpulse.tenancy.api;

import br.com.condpulse.tenancy.application.TenantModuleExempt;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.InvitationService;
import br.com.condpulse.tenancy.application.MembershipManagementService;
import br.com.condpulse.tenancy.application.MembershipModels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
@TenantModuleExempt("Vínculos e convites: cadastro base do condomínio.")
public class MembershipManagementController {
 private final MembershipManagementService memberships;
 private final InvitationService invitations;
 public MembershipManagementController(MembershipManagementService memberships,InvitationService invitations) {
  this.memberships=memberships; this.invitations=invitations;
 }
 @GetMapping("/v1/tenants/{tenantId}/management/memberships")
 public ResponseEntity<MembershipModels.MembershipPage> memberships(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String status) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(memberships.memberships(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),status));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/memberships/{membershipId}/decision")
 public ResponseEntity<MembershipModels.Membership> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID membershipId,@RequestBody MembershipModels.Decision request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memberships.decide(UnitManagementController.account(jwt),
   tenantId,UnitManagementController.assurance(jwt),membershipId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/memberships/{membershipId}/revoke")
 public ResponseEntity<MembershipModels.Membership> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID membershipId,@RequestBody MembershipModels.Revocation request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memberships.revoke(UnitManagementController.account(jwt),
   tenantId,UnitManagementController.assurance(jwt),membershipId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/invitations")
 public ResponseEntity<List<MembershipModels.Invitation>> invitations(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(invitations.invitations(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/invitations")
 public ResponseEntity<MembershipModels.InvitationCreated> invite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody MembershipModels.CreateInvitation request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(invitations.create(
   UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/invitations/{invitationId}/revoke")
 public ResponseEntity<Void> revokeInvitation(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID invitationId) {
  invitations.revoke(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),invitationId);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
}
