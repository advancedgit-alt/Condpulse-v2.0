package br.com.condpulse.parcels.api;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unidades ativas para a portaria (recebimento de encomenda e registro de chegada): existe com CP_PARCELS_ENABLED ou
 * CP_VISITS_ENABLED. Capacidade da portaria com AAL2 conferida no serviço.
 */
@RestController @Profile("api")
@ConditionalOnExpression("${condpulse.parcels.enabled:false} or ${condpulse.visits.enabled:false}")
public class ConciergeUnitsController {
 private final ParcelService service;
 public ConciergeUnitsController(ParcelService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/concierge/units")
 public ResponseEntity<List<ParcelModels.UnitOption>> units(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String q) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.units(UUID.fromString(jwt.getSubject()),tenantId,AssuranceLevel.fromClaim(jwt.getClaims().get("aal")),q));
 }
}
