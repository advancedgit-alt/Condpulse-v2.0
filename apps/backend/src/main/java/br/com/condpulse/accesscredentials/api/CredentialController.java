package br.com.condpulse.accesscredentials.api;

import java.util.UUID;
import br.com.condpulse.accesscredentials.application.CredentialModels;
import br.com.condpulse.accesscredentials.application.CredentialService;
import br.com.condpulse.identity.application.AssuranceLevel;
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

/** Credenciais de acesso com revogação prioritária (CP-046), atrás de CP_ACCESS_CREDENTIALS_ENABLED. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.access-credentials.enabled",havingValue="true")
public class CredentialController {
 private final CredentialService service;
 public CredentialController(CredentialService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/access-connectors")
 public ResponseEntity<CredentialModels.Connectors> connectors(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(service.connectors(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-credentials")
 public ResponseEntity<CredentialModels.Page> managed(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) UUID unitId,@RequestParam(required=false,defaultValue="false") boolean pending) {
  return ok(service.managed(account(jwt),tenantId,assurance(jwt),unitId,pending));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-credentials")
 public ResponseEntity<CredentialModels.Credential> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody CredentialModels.Create request) {
  var result=service.create(account(jwt),tenantId,assurance(jwt),key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.credential());
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-credentials/{credentialId}/revoke")
 public ResponseEntity<CredentialModels.Credential> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID credentialId,
     @RequestBody CredentialModels.Revoke request) {
  return ok(service.revoke(account(jwt),tenantId,assurance(jwt),credentialId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/concierge/pending-revocations")
 public ResponseEntity<CredentialModels.Page> pendingRevocations(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(service.pendingRevocations(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/access-credentials")
 public ResponseEntity<CredentialModels.Page> unit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(service.unit(account(jwt),tenantId,unitId));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
