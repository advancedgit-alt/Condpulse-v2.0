package br.com.condpulse.moves.api;

import java.time.Instant;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.moves.application.MoveModels;
import br.com.condpulse.moves.application.MoveService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mudança e entrega de móveis (CP-042), atrás de CP_REQUESTS_ENABLED. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.requests.enabled",havingValue="true")
public class MoveController {
 private final MoveService service;
 public MoveController(MoveService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/move-rules")
 public ResponseEntity<MoveModels.CurrentRules> rules(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(service.rules(account(jwt),tenantId,assurance(jwt)));
 }
 @PutMapping("/v1/tenants/{tenantId}/management/move-rules")
 public ResponseEntity<MoveModels.Rules> updateRules(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@RequestBody MoveModels.RulesUpdate request) {
  return ok(service.updateRules(account(jwt),tenantId,assurance(jwt),request));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/moves")
 public ResponseEntity<MoveModels.Page> unitMoves(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(service.unitMoves(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/moves")
 public ResponseEntity<MoveModels.Move> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody MoveModels.Create request) {
  var result=service.create(account(jwt),tenantId,unitId,key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.move());
 }
 @PostMapping("/v1/tenants/{tenantId}/moves/{moveId}/cancel")
 public ResponseEntity<MoveModels.Move> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID moveId,
     @RequestBody MoveModels.Cancel request) {
  return ok(service.cancel(account(jwt),tenantId,assurance(jwt),moveId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/moves")
 public ResponseEntity<MoveModels.Page> managed(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@RequestParam(required=false) String state) {
  return ok(service.managed(account(jwt),tenantId,assurance(jwt),state));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/moves/{moveId}/decision")
 public ResponseEntity<MoveModels.Move> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID moveId,
     @RequestBody MoveModels.Decision request) {
  return ok(service.decide(account(jwt),tenantId,assurance(jwt),moveId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/concierge/moves")
 public ResponseEntity<MoveModels.Page> concierge(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant until) {
  return ok(service.concierge(account(jwt),tenantId,assurance(jwt),from,until));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
