package br.com.condpulse.accessgovernance.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.util.UUID;
import br.com.condpulse.accessgovernance.application.TechnicalGrantModels;
import br.com.condpulse.accessgovernance.application.TechnicalGrantService;
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
import org.springframework.web.bind.annotation.RestController;

/** Concessões técnicas (AC-003, V037): plataforma emite/revoga, beneficiado lê a própria sessão, gestor só consulta. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.FACIAL_ACCESS)
public class TechnicalGrantController {
 private final TechnicalGrantService grants;
 public TechnicalGrantController(TechnicalGrantService grants) { this.grants=grants; }

 @GetMapping("/v1/platform/tenants/{tenantId}/technical-grants")
 public ResponseEntity<TechnicalGrantModels.TechnicalGrantPage> platformList(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grants.platformList(account(jwt),assurance(jwt),tenantId));
 }
 @PostMapping("/v1/platform/tenants/{tenantId}/technical-grants")
 public ResponseEntity<TechnicalGrantModels.TechnicalGrantIssued> issue(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody TechnicalGrantModels.IssueTechnicalGrant request) {
  var issued=grants.issue(account(jwt),assurance(jwt),tenantId,request);
  return ResponseEntity.status(issued.applied()?201:200).cacheControl(CacheControl.noStore()).body(issued);
 }
 @PostMapping("/v1/platform/tenants/{tenantId}/technical-grants/{grantId}/revoke")
 public ResponseEntity<TechnicalGrantModels.TechnicalGrant> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @PathVariable UUID grantId,@RequestBody TechnicalGrantModels.RevokeTechnicalGrant request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grants.revoke(account(jwt),assurance(jwt),tenantId,grantId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/technical/session")
 public ResponseEntity<TechnicalGrantModels.TechnicalSession> session(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grants.session(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/technical-grants")
 public ResponseEntity<TechnicalGrantModels.TechnicalGrantPage> managerList(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grants.managerList(account(jwt),tenantId,assurance(jwt)));
 }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
