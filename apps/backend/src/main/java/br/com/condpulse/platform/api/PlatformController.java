package br.com.condpulse.platform.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.PlatformModels;
import br.com.condpulse.platform.application.PlatformService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Painel master (OPS-01), atrás de CP_PLATFORM_ENABLED=false. Papel de plataforma, AAL2 e auditoria no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.platform.enabled",havingValue="true")
public class PlatformController {
 private final PlatformService service;
 public PlatformController(PlatformService service) { this.service=service; }

 @GetMapping("/v1/platform/session")
 public ResponseEntity<PlatformModels.PlatformSession> session(@AuthenticationPrincipal Jwt jwt) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.session(account(jwt),assurance(jwt)));
 }
 @GetMapping("/v1/platform/tenants")
 public ResponseEntity<PlatformModels.TenantPage> tenants(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.tenants(account(jwt),assurance(jwt),q));
 }
 @PutMapping("/v1/platform/tenants/{tenantId}")
 public ResponseEntity<PlatformModels.TenantChange> updateTenant(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody PlatformModels.UpdateTenant request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.updateTenant(account(jwt),assurance(jwt),tenantId,request));
 }
 @GetMapping("/v1/platform/audit-events")
 public ResponseEntity<PlatformModels.AuditPage> audit(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.audit(account(jwt),assurance(jwt),cursor,limit));
 }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
