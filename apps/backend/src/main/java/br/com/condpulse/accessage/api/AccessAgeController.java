package br.com.condpulse.accessage.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.util.List;
import java.util.UUID;
import br.com.condpulse.accessage.application.AgeModels;
import br.com.condpulse.accessage.application.AgeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Parâmetros do acesso facial e evidência de idade (AC-006, V039), só pela gestão com AAL2. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.FACIAL_ACCESS)
public class AccessAgeController {
 private final AgeService ages;
 public AccessAgeController(AgeService ages) { this.ages=ages; }

 @GetMapping("/v1/tenants/{tenantId}/management/facial-settings")
 public ResponseEntity<AgeModels.Settings> settings(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ages.settings(account(jwt),tenantId,assurance(jwt)));
 }
 @PutMapping("/v1/tenants/{tenantId}/management/facial-settings")
 public ResponseEntity<AgeModels.Settings> updateSettings(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody AgeModels.UpdateSettings request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ages.updateSettings(account(jwt),tenantId,assurance(jwt),request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/age-evidence")
 public ResponseEntity<AgeModels.Evidence> record(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId,
   @RequestBody AgeModels.RecordEvidence request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ages.record(account(jwt),tenantId,assurance(jwt),subjectId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/age-evidence")
 public ResponseEntity<List<AgeModels.Evidence>> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ages.history(account(jwt),tenantId,assurance(jwt),subjectId));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/age-check")
 public ResponseEntity<AgeModels.AgeCheck> check(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId,
   @RequestParam(required=false) Integer minimumAge) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ages.check(account(jwt),tenantId,assurance(jwt),subjectId,minimumAge));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/age-evidence/{evidenceId}/revoke")
 public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID evidenceId,
   @RequestBody AgeModels.RevokeEvidence request) {
  ages.revoke(account(jwt),tenantId,assurance(jwt),evidenceId,request);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
