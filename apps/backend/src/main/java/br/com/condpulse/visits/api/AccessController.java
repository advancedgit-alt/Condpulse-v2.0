package br.com.condpulse.visits.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.visits.application.AccessModels;
import br.com.condpulse.visits.application.AccessService;
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

/** Registro manual de entrada e saída (CP-028), atrás de CP_VISITS_ENABLED=false. Autorização e regras no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.visits.enabled",havingValue="true")
public class AccessController {
 private final AccessService service;
 public AccessController(AccessService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/access-events")
 public ResponseEntity<AccessModels.RecordPage> records(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(service.records(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/access-events")
 public ResponseEntity<AccessModels.RecordPage> unitRecords(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(service.unitRecords(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/access-events/entries")
 public ResponseEntity<AccessModels.AccessRecord> entry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody AccessModels.RecordEntry request) {
  return created(service.entry(account(jwt),tenantId,assurance(jwt),key,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/access-events/{eventId}/exit")
 public ResponseEntity<AccessModels.AccessRecord> exit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID eventId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) AccessModels.RecordExit request) {
  return created(service.exit(account(jwt),tenantId,assurance(jwt),eventId,key,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/access-events/{eventId}/correction")
 public ResponseEntity<AccessModels.AccessRecord> correct(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID eventId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody AccessModels.Correct request) {
  return created(service.correct(account(jwt),tenantId,assurance(jwt),eventId,key,request));
 }

 private static ResponseEntity<AccessModels.AccessRecord> created(AccessModels.RecordMutation result) {
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.record());
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
