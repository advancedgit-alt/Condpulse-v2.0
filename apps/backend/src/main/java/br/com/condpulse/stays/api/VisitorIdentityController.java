package br.com.condpulse.stays.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.stays.application.VisitorIdentityService;
import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.visits.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.VISITS)
public class VisitorIdentityController {
 private final VisitorIdentityService service;
 public VisitorIdentityController(VisitorIdentityService service) { this.service=service; }
 @GetMapping("/v1/tenants/{tenantId}/stay-identities/source")
 public ResponseEntity<VisitorIdentityService.Source> source(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
  @RequestParam(required=false) UUID arrivalId,@RequestParam(required=false) UUID permitId) {
  return ok(service.source(actor(jwt),tenantId,aal(jwt),arrivalId,permitId));
 }
 @GetMapping("/v1/tenants/{tenantId}/stay-identities")
 public ResponseEntity<VisitorIdentityService.Candidates> search(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@RequestParam String prefix) {
  return ok(service.search(actor(jwt),tenantId,aal(jwt),prefix));
 }
 @PostMapping("/v1/tenants/{tenantId}/stay-identities")
 public ResponseEntity<VisitorIdentityService.Check> verify(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
  @RequestBody(required=false) VisitorIdentityService.Verify request) { return ok(service.verify(actor(jwt),tenantId,aal(jwt),request)); }
 @PostMapping("/v1/tenants/{tenantId}/stay-identities/{checkId}/void")
 public ResponseEntity<VisitorIdentityService.Check> voidCheck(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID checkId,
  @RequestBody(required=false) VisitorIdentityService.VoidCheck request) { return ok(service.voidCheck(actor(jwt),tenantId,aal(jwt),checkId,request)); }
 private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel aal(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
 private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
