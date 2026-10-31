package br.com.condpulse.reservations.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.reservations.application.ReservationManagementModels;
import br.com.condpulse.reservations.application.ReservationManagementService;
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

/** Pedidos, decisões e bloqueios de manutenção pela gestão local (CP-023), atrás de CP_MANAGEMENT_ENABLED. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.RESERVATIONS)
public class ReservationManagementController {
 private final ReservationManagementService service;
 public ReservationManagementController(ReservationManagementService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/management/reservations")
 public ResponseEntity<ReservationManagementModels.ManagedReservationPage> reservations(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@RequestParam(required=false) String status,
     @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant until) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.reservations(account(jwt),tenantId,assurance(jwt),status,from,until));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/waitlist")
 public ResponseEntity<ReservationManagementModels.ManagedWaitlistPage> waitlist(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(defaultValue="true") boolean open) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.waitlist(account(jwt),tenantId,assurance(jwt),open));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/reservations/{reservationId}/decision")
 public ResponseEntity<ReservationManagementModels.ManagedReservation> decide(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@PathVariable UUID reservationId,@RequestBody ReservationManagementModels.Decision request) {
  var outcome=service.decideOutcome(account(jwt),tenantId,assurance(jwt),reservationId,request);
  // Aplicada por esta chamada ou estado já existente (decisão anterior): a tela não infere autoria pela versão.
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .header("Reservation-Decision-Applied",Boolean.toString(outcome.applied())).body(outcome.reservation());
 }
 @PostMapping("/v1/tenants/{tenantId}/management/reservations/{reservationId}/cancel")
 public ResponseEntity<ReservationManagementModels.ManagedReservation> cancel(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@PathVariable UUID reservationId,@RequestBody ReservationManagementModels.Cancellation request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.cancel(account(jwt),tenantId,assurance(jwt),reservationId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/amenities/{amenityId}/maintenance-blocks")
 public ResponseEntity<ReservationManagementModels.ManagedReservation> block(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@PathVariable UUID amenityId,@RequestHeader("Idempotency-Key") String key,
     @RequestBody ReservationManagementModels.CreateBlock request) {
  var result=service.createBlock(account(jwt),tenantId,assurance(jwt),amenityId,key,request);
  return ResponseEntity.status(result.responseStatus()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.reservation());
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
