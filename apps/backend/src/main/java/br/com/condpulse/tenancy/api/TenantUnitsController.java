package br.com.condpulse.tenancy.api;

import br.com.condpulse.tenancy.application.TenantModuleExempt;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.tenancy.application.TenantUnitsQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
@TenantModuleExempt("Unidades do próprio vínculo: cadastro base do condomínio.")
public class TenantUnitsController {
 private final TenantUnitsQuery query;
 public TenantUnitsController(TenantUnitsQuery query) { this.query=query; }
 @GetMapping("/v1/tenants/{tenantId}/units")
 public ResponseEntity<List<TenantUnitsQuery.Unit>> units(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(query.execute(UUID.fromString(jwt.getSubject()),tenantId));
 }
}
