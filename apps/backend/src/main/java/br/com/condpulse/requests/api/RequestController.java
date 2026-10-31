package br.com.condpulse.requests.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.requests.application.RequestModels;
import br.com.condpulse.requests.application.RequestService;
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

/** Pedidos da unidade (CP-033) atrás de CP_REQUESTS_ENABLED=false. Vínculo e capacidade conferidos no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.requests.enabled",havingValue="true")
public class RequestController {
 private final RequestService service;
 public RequestController(RequestService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/requests")
 public ResponseEntity<RequestModels.UnitPage> unit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unitRequests(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/requests")
 public ResponseEntity<RequestModels.UnitRequest> open(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody RequestModels.Open request) {
  var result=service.open(account(jwt),tenantId,unitId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.request());
 }
 @GetMapping("/v1/tenants/{tenantId}/requests/{requestId}")
 public ResponseEntity<RequestModels.UnitRequest> request(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unitRequest(account(jwt),tenantId,requestId));
 }
 @GetMapping("/v1/tenants/{tenantId}/requests/{requestId}/messages")
 public ResponseEntity<RequestModels.MessagePage> olderMessages(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId,@RequestParam(name="before") UUID before) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unitOlderMessages(account(jwt),tenantId,requestId,before));
 }
 @PostMapping("/v1/tenants/{tenantId}/requests/{requestId}/messages")
 public ResponseEntity<RequestModels.Message> message(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID requestId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody RequestModels.UnitMessage request) {
  var result=service.unitMessage(account(jwt),tenantId,requestId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.message());
 }
 @GetMapping("/v1/tenants/{tenantId}/management/requests")
 public ResponseEntity<RequestModels.ManagedPage> managed(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(name="state",required=false) String state) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.managed(account(jwt),tenantId,assurance(jwt),state));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/requests/{requestId}")
 public ResponseEntity<RequestModels.ManagedRequest> managedRequest(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.managedRequest(account(jwt),tenantId,assurance(jwt),requestId));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/requests/{requestId}/messages")
 public ResponseEntity<RequestModels.MessagePage> staffOlderMessages(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId,@RequestParam(name="before") UUID before) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.staffOlderMessages(account(jwt),tenantId,assurance(jwt),requestId,before));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/requests/{requestId}/messages")
 public ResponseEntity<RequestModels.Message> staffMessage(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId,@RequestHeader("Idempotency-Key") String key,@RequestBody RequestModels.StaffMessage request) {
  var result=service.staffMessage(account(jwt),tenantId,assurance(jwt),requestId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.message());
 }
 @PostMapping("/v1/tenants/{tenantId}/management/requests/{requestId}/state")
 public ResponseEntity<RequestModels.ManagedRequest> update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId,@RequestBody RequestModels.Update request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(account(jwt),tenantId,assurance(jwt),requestId,request));
 }
 // Zeladoria (V045): pedidos atribuídos ao próprio vínculo MAINTENANCE.
 @GetMapping("/v1/tenants/{tenantId}/maintenance/requests")
 public ResponseEntity<RequestModels.MaintenancePage> maintenance(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.maintenance(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping("/v1/tenants/{tenantId}/maintenance/requests/{requestId}")
 public ResponseEntity<RequestModels.MaintenanceRequest> maintenanceRequest(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.maintenanceRequest(account(jwt),tenantId,assurance(jwt),requestId));
 }
 @PostMapping("/v1/tenants/{tenantId}/maintenance/requests/{requestId}/state")
 public ResponseEntity<RequestModels.MaintenanceRequest> maintenanceUpdate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId,@RequestBody RequestModels.MaintenanceUpdate request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.maintenanceUpdate(account(jwt),tenantId,assurance(jwt),requestId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/maintenance/requests/{requestId}/messages")
 public ResponseEntity<RequestModels.Message> maintenanceMessage(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId,@RequestHeader("Idempotency-Key") String key,@RequestBody RequestModels.StaffMessage request) {
  var result=service.maintenanceMessage(account(jwt),tenantId,assurance(jwt),requestId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.message());
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
