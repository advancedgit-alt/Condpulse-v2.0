package br.com.condpulse.reservations.api;

import java.util.UUID;
import br.com.condpulse.reservations.application.WaitlistModels;
import br.com.condpulse.reservations.application.WaitlistService;
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

/** Fila de espera de reservas (CP-040), atrás da mesma flag das reservas. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.reservations.enabled",havingValue="true")
public class WaitlistController {
 private final WaitlistService service;
 public WaitlistController(WaitlistService service) { this.service=service; }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/waitlist")
 public ResponseEntity<WaitlistModels.Page> entries(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unitEntries(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/waitlist")
 public ResponseEntity<WaitlistModels.Entry> join(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody WaitlistModels.Join request) {
  var result=service.join(account(jwt),tenantId,key,request);
  return ResponseEntity.status(result.responseStatus()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.entry());
 }
 @PostMapping("/v1/tenants/{tenantId}/waitlist/{waitlistId}/leave")
 public ResponseEntity<WaitlistModels.Entry> leave(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID waitlistId,@RequestBody WaitlistModels.Change request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.leave(account(jwt),tenantId,waitlistId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/waitlist/{waitlistId}/accept")
 public ResponseEntity<WaitlistModels.Acceptance> accept(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID waitlistId,@RequestHeader("Idempotency-Key") String key,@RequestBody WaitlistModels.Change request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.accept(account(jwt),tenantId,waitlistId,key,request));
 }
 private UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
