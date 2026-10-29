package br.com.condpulse.visits.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.visits.application.VisitModels;
import br.com.condpulse.visits.application.VisitService;
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

/** Visitas e chegadas (CP-025/CP-026), atrás de CP_VISITS_ENABLED=false. Vínculo, capacidade e prazo no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.visits.enabled",havingValue="true")
public class VisitController {
 private final VisitService service;
 public VisitController(VisitService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/permits")
 public ResponseEntity<VisitModels.PermitPage> unitPermits(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(service.unitPermits(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/permits")
 public ResponseEntity<VisitModels.Permit> createPermit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody VisitModels.CreatePermit request) {
  var result=service.createPermit(account(jwt),tenantId,unitId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.permit());
 }
 @PostMapping("/v1/tenants/{tenantId}/permits/{permitId}/revoke")
 public ResponseEntity<VisitModels.Permit> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID permitId,
     @RequestBody VisitModels.RevokePermit request) {
  return ok(service.revokePermit(account(jwt),tenantId,permitId,request));
 }
 /** Link compartilhável (CP-037): gera um novo (revogando o anterior) e devolve o token uma única vez. */
 @PostMapping("/v1/tenants/{tenantId}/permits/{permitId}/link")
 public ResponseEntity<VisitModels.PermitLink> shareLink(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID permitId) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer")
   .body(service.shareLink(account(jwt),tenantId,permitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/permits/{permitId}/link/revoke")
 public ResponseEntity<VisitModels.Permit> revokeLink(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID permitId) {
  return ok(service.revokeLink(account(jwt),tenantId,permitId));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/arrivals")
 public ResponseEntity<VisitModels.ArrivalPage> unitArrivals(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(service.unitArrivals(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/arrivals/{arrivalId}/decision")
 public ResponseEntity<VisitModels.Arrival> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID arrivalId,
     @RequestBody VisitModels.Decision request) {
  return ok(service.decide(account(jwt),tenantId,arrivalId,request));
 }

 @GetMapping("/v1/tenants/{tenantId}/concierge/permits")
 public ResponseEntity<VisitModels.PermitPage> expected(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) UUID unitId,@RequestParam(required=false) String q) {
  return ok(service.expected(account(jwt),tenantId,assurance(jwt),unitId,q));
 }
 @GetMapping("/v1/tenants/{tenantId}/arrivals")
 public ResponseEntity<VisitModels.ArrivalPage> queue(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String status) {
  return ok(service.queue(account(jwt),tenantId,assurance(jwt),status));
 }
 @PostMapping("/v1/tenants/{tenantId}/arrivals")
 public ResponseEntity<VisitModels.Arrival> register(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody VisitModels.RegisterArrival request) {
  var result=service.register(account(jwt),tenantId,assurance(jwt),key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.arrival());
 }
 @PostMapping("/v1/tenants/{tenantId}/arrivals/{arrivalId}/assisted-decision")
 public ResponseEntity<VisitModels.Arrival> assisted(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID arrivalId,
     @RequestBody VisitModels.AssistedDecision request) {
  return ok(service.assistedDecision(account(jwt),tenantId,assurance(jwt),arrivalId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/arrivals/{arrivalId}/cancel")
 public ResponseEntity<VisitModels.Arrival> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID arrivalId,
     @RequestBody VisitModels.CancelArrival request) {
  return ok(service.cancel(account(jwt),tenantId,assurance(jwt),arrivalId,request));
 }

 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
