package br.com.condpulse.tenancy.api;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.MembershipModels;
import br.com.condpulse.tenancy.application.RentalModels;
import br.com.condpulse.tenancy.application.RentalService;
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
import org.springframework.web.bind.annotation.RestController;

/** Proprietário × inquilino (V034), atrás da mesma flag da gestão de vínculos (CP_MANAGEMENT_ENABLED). */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
public class RentalController {
 private final RentalService rentals;
 public RentalController(RentalService rentals) { this.rentals=rentals; }

 @GetMapping("/v1/tenants/{tenantId}/management/rentals")
 public ResponseEntity<List<RentalModels.ManagedRental>> active(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(rentals.active(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/units/{unitId}/rental")
 public ResponseEntity<RentalModels.ManagedRental> start(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID unitId,@RequestBody RentalModels.RentalChange request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(rentals.start(UnitManagementController.account(jwt),
   tenantId,UnitManagementController.assurance(jwt),unitId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/units/{unitId}/rental/end")
 public ResponseEntity<Void> end(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @RequestBody RentalModels.RentalChange request) {
  rentals.end(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),unitId,request);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }

 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/rental")
 public ResponseEntity<RentalModels.OwnerView> view(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rentals.view(UnitManagementController.account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/rental/invitations")
 public ResponseEntity<MembershipModels.InvitationCreated> invite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID unitId,@RequestBody RentalModels.TenantInvitation request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
   .body(rentals.invite(UnitManagementController.account(jwt),tenantId,unitId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/rental/invitations/{invitationId}/revoke")
 public ResponseEntity<Void> revokeInvitation(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @PathVariable UUID invitationId) {
  rentals.revokeInvitation(UnitManagementController.account(jwt),tenantId,unitId,invitationId);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/rental/occupants/{membershipId}/remove")
 public ResponseEntity<RentalModels.Occupant> remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @PathVariable UUID membershipId,@RequestBody RentalModels.OccupantRemoval request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(rentals.remove(UnitManagementController.account(jwt),tenantId,unitId,membershipId,request));
 }
}
