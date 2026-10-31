package br.com.condpulse.reservations.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.application.AmenityManagementModels;
import br.com.condpulse.reservations.application.AmenityManagementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.RESERVATIONS)
public class AmenityManagementController {
 private final AmenityManagementService service;
 public AmenityManagementController(AmenityManagementService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/management/amenities")
 public ResponseEntity<List<AmenityManagementModels.ManagedAmenity>> amenities(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.amenities(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/amenities")
 public ResponseEntity<AmenityManagementModels.ManagedAmenity> create(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@RequestBody AmenityManagementModels.AmenityInput input) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
   .body(service.create(account(jwt),tenantId,assurance(jwt),input));
 }
 @PutMapping("/v1/tenants/{tenantId}/management/amenities/{amenityId}")
 public ResponseEntity<AmenityManagementModels.ManagedAmenity> update(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@PathVariable UUID amenityId,@RequestBody AmenityManagementModels.AmenityInput input) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.update(account(jwt),tenantId,assurance(jwt),amenityId,input));
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
