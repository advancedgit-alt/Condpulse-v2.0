package br.com.condpulse.accesssubjects.api;

import java.util.UUID;
import br.com.condpulse.accesssubjects.application.SubjectModels;
import br.com.condpulse.accesssubjects.application.SubjectService;
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

/** Sujeitos e contextos (AC-005, V038): gestão materializa, bloqueia e encerra; portaria materializa visitantes. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessSubjectController {
 private final SubjectService subjects;
 public AccessSubjectController(SubjectService subjects) { this.subjects=subjects; }

 @GetMapping("/v1/tenants/{tenantId}/management/access-subjects")
 public ResponseEntity<SubjectModels.SubjectPage> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subjects.list(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-subjects/materialize")
 public ResponseEntity<SubjectModels.Materialized> materialize(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subjects.materializeMemberships(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/concierge/access-subjects/materialize")
 public ResponseEntity<SubjectModels.Materialized> materializePermits(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subjects.materializePermits(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/block")
 public ResponseEntity<Void> block(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId,
   @RequestBody SubjectModels.Block request) {
  subjects.block(account(jwt),tenantId,assurance(jwt),subjectId,request);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/unblock")
 public ResponseEntity<Void> unblock(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId,
   @RequestBody SubjectModels.Unblock request) {
  subjects.unblock(account(jwt),tenantId,assurance(jwt),subjectId,request);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/functional-authorizations")
 public ResponseEntity<Void> functional(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID subjectId,
   @RequestBody SubjectModels.FunctionalAuthorization request) {
  subjects.addFunctional(account(jwt),tenantId,assurance(jwt),subjectId,request);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 @PostMapping("/v1/tenants/{tenantId}/management/subject-contexts/{contextId}/revoke")
 public ResponseEntity<Void> revokeContext(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID contextId,
   @RequestBody SubjectModels.CloseContext request) {
  subjects.closeContext(account(jwt),tenantId,assurance(jwt),contextId,request);
  return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
