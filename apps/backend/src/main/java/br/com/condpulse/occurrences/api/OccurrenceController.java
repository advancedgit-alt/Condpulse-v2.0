package br.com.condpulse.occurrences.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.occurrences.application.OccurrenceModels;
import br.com.condpulse.occurrences.application.OccurrenceService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ocorrências da portaria (CP-027), na mesma flag das visitas e acessos (CP_VISITS_ENABLED). AAL2 e capacidade no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.visits.enabled",havingValue="true")
public class OccurrenceController {
 private final OccurrenceService service;
 public OccurrenceController(OccurrenceService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/concierge/occurrences")
 public ResponseEntity<OccurrenceModels.Page> page(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(name="resolved",defaultValue="false") boolean resolved) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(account(jwt),tenantId,assurance(jwt),resolved));
 }
 @PostMapping("/v1/tenants/{tenantId}/concierge/occurrences")
 public ResponseEntity<OccurrenceModels.Occurrence> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody OccurrenceModels.Create request) {
  var result=service.create(account(jwt),tenantId,assurance(jwt),request);
  return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.occurrence());
 }
 @PostMapping("/v1/tenants/{tenantId}/concierge/occurrences/{occurrenceId}/resolve")
 public ResponseEntity<OccurrenceModels.Occurrence> resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID occurrenceId,@RequestBody OccurrenceModels.Resolve request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolve(account(jwt),tenantId,assurance(jwt),occurrenceId,request));
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
