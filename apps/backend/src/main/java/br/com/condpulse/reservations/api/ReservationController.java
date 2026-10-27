package br.com.condpulse.reservations.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.reservations.enabled",havingValue="true")
public class ReservationController {
 private final ReservationService service;
 public ReservationController(ReservationService service) { this.service=service; }
 @GetMapping("/v1/tenants/{tenantId}/amenities")
 public ResponseEntity<List<ReservationModels.Amenity>> amenities(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.amenities(account(jwt),tenantId));
 }
 @GetMapping("/v1/tenants/{tenantId}/reservations")
 public ResponseEntity<ReservationModels.Page> reservations(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam UUID unitId,@RequestParam Instant from,@RequestParam Instant until) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reservations(account(jwt),tenantId,unitId,from,until));
 }
 @PostMapping("/v1/tenants/{tenantId}/reservations")
 public ResponseEntity<ReservationModels.View> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody ReservationModels.Create request) {
  return mutation(service.create(account(jwt),tenantId,key,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/reservations/{reservationId}/cancel")
 public ResponseEntity<ReservationModels.View> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID reservationId,@RequestHeader("Idempotency-Key") String key,@RequestBody ReservationModels.Cancel request) {
  return mutation(service.cancel(account(jwt),tenantId,reservationId,key,request));
 }
 private ResponseEntity<ReservationModels.View> mutation(ReservationModels.Mutation result) {
  return ResponseEntity.status(result.responseStatus()).cacheControl(CacheControl.noStore())
    .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.reservation());
 }
 private UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
