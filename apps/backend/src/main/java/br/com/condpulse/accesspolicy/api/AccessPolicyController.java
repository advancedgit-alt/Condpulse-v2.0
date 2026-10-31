package br.com.condpulse.accesspolicy.api;

import java.util.UUID;
import br.com.condpulse.accesspolicy.application.PolicyModels;
import br.com.condpulse.accesspolicy.application.PolicyService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Políticas de ingresso da gestão (policy.edit/policy.publish, AAL2) — AC-007/AC-008, V043. Validar e simular nunca acionam porta. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessPolicyController {
 private final PolicyService policies;
 public AccessPolicyController(PolicyService policies) { this.policies=policies; }

 @GetMapping("/v1/tenants/{tenantId}/management/access-policies")
 public ResponseEntity<PolicyModels.PolicyPage> policies(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(policies.policies(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-policies")
 public ResponseEntity<PolicyModels.Policy> createPolicy(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) PolicyModels.CreatePolicy request) {
  return ok(policies.createPolicy(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}")
 public ResponseEntity<PolicyModels.Policy> updatePolicy(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,
   @RequestBody(required=false) PolicyModels.UpdatePolicy request) {
  return ok(policies.updatePolicy(account(jwt),tenantId,assurance(jwt),policyId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions")
 public ResponseEntity<PolicyModels.PolicyVersionPage> versions(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId) {
  return ok(policies.versions(account(jwt),tenantId,assurance(jwt),policyId));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions")
 public ResponseEntity<PolicyModels.PolicyVersion> createVersion(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,
   @RequestBody(required=false) PolicyModels.CreateVersion request) {
  return ok(policies.createVersion(account(jwt),tenantId,assurance(jwt),policyId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/validate")
 public ResponseEntity<PolicyModels.ValidationResult> validate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,
   @RequestBody(required=false) PolicyModels.ValidateRequest request) {
  return ok(policies.validate(account(jwt),tenantId,assurance(jwt),policyId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/simulate")
 public ResponseEntity<PolicyModels.SimulationResult> simulate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,
   @RequestBody(required=false) PolicyModels.SimulateRequest request) {
  return ok(policies.simulate(account(jwt),tenantId,assurance(jwt),policyId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions/{versionId}/approve")
 public ResponseEntity<PolicyModels.PolicyVersion> approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,@PathVariable UUID versionId,
   @RequestBody(required=false) PolicyModels.VersionAction request) {
  return ok(policies.approve(account(jwt),tenantId,assurance(jwt),policyId,versionId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions/{versionId}/reject")
 public ResponseEntity<PolicyModels.PolicyVersion> reject(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID policyId,@PathVariable UUID versionId,
   @RequestBody(required=false) PolicyModels.RejectVersion request) {
  return ok(policies.reject(account(jwt),tenantId,assurance(jwt),policyId,versionId,request));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
