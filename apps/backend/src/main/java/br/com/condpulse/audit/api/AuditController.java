package br.com.condpulse.audit.api;

import br.com.condpulse.tenancy.application.TenantModuleExempt;
import java.util.UUID;
import br.com.condpulse.audit.application.AuditQuery;
import br.com.condpulse.identity.application.AssuranceLevel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
@TenantModuleExempt("Auditoria do condomínio: transversal a todos os módulos.")
public class AuditController {
 private final AuditQuery query;
 public AuditController(AuditQuery query) { this.query=query; }
 @GetMapping("/v1/tenants/{tenantId}/management/audit-events")
 public ResponseEntity<AuditQuery.Page> events(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.page(UUID.fromString(jwt.getSubject()),
   tenantId,AssuranceLevel.fromClaim(jwt.getClaims().get("aal")),cursor,limit));
 }
}
