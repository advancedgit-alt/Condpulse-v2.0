package br.com.condpulse.parcels.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.application.DelegationModels;
import br.com.condpulse.parcels.application.DelegationService;
import br.com.condpulse.parcels.application.ParcelModels;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Retirada delegada com código (CP-041), atrás de CP_PARCELS_ENABLED. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.parcels.enabled",havingValue="true")
public class DelegationController {
 private final DelegationService service;
 public DelegationController(DelegationService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/delegation")
 public ResponseEntity<DelegationModels.Current> current(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(account(jwt),tenantId,assurance(jwt),parcelId));
 }
 @PostMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/delegation")
 public ResponseEntity<DelegationModels.Created> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody DelegationModels.Create request) {
  var result=service.create(account(jwt),tenantId,parcelId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(new DelegationModels.Created(result.delegation(),result.code()));
 }
 @PostMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/delegation/revoke")
 public ResponseEntity<DelegationModels.Delegation> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId,
     @RequestBody DelegationModels.Revoke request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.revoke(account(jwt),tenantId,parcelId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/handoff-code")
 public ResponseEntity<ParcelModels.Parcel> handoffByCode(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody DelegationModels.CodeHandoff request) {
  var result=service.handoffByCode(account(jwt),tenantId,assurance(jwt),parcelId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.parcel());
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
