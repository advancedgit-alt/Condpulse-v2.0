package br.com.condpulse.accessgrants.api;

import java.util.UUID;
import br.com.condpulse.accessgrants.application.GrantModels;
import br.com.condpulse.accessgrants.application.GrantService;
import br.com.condpulse.accessgrants.application.PublicationModels;
import br.com.condpulse.accessgrants.application.PublicationService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Direitos derivados, revogações e publicação por destino da gestão (memberships.manage + policy.publish, AAL2) — AC-009/AC-012/AC-013/AC-015, V044/V045. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessGrantController {
 private final GrantService grants;
 private final PublicationService publications;
 public AccessGrantController(GrantService grants,PublicationService publications) { this.grants=grants; this.publications=publications; }

 @PostMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/grants/derive")
 public ResponseEntity<GrantModels.Derivation> derive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,
   @RequestBody(required=false) GrantModels.DeriveRequest request) {
  return ok(grants.derive(account(jwt),tenantId,assurance(jwt),policyId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-grants")
 public ResponseEntity<GrantModels.GrantPage> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestParam(required=false) UUID subjectId,@RequestParam(required=false) UUID areaId) {
  return ok(grants.grants(account(jwt),tenantId,assurance(jwt),subjectId,areaId));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-grants/{grantId}/revoke")
 public ResponseEntity<GrantModels.Grant> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID grantId,
   @RequestBody(required=false) GrantModels.RevokeGrant request) {
  return ok(grants.revoke(account(jwt),tenantId,assurance(jwt),grantId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/revocation-markers")
 public ResponseEntity<GrantModels.MarkerPage> markers(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId) {
  return ok(grants.markers(account(jwt),tenantId,assurance(jwt),subjectId));
 }
 /** Publicação por destino (V045): compila e grava trabalhos; nunca confirma porta. */
 @PostMapping("/v1/tenants/{tenantId}/management/access-publications/plan")
 public ResponseEntity<PublicationModels.Plan> plan(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) PublicationModels.PlanRequest request) {
  return ok(publications.plan(account(jwt),tenantId,assurance(jwt),request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-publications")
 public ResponseEntity<PublicationModels.PublicationPage> publications(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestParam(required=false) UUID operationId,@RequestParam(required=false) UUID subjectId,@RequestParam(required=false) UUID deviceId) {
  return ok(publications.publications(account(jwt),tenantId,assurance(jwt),operationId,subjectId,deviceId));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
