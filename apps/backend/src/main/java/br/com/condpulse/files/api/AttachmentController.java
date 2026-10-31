package br.com.condpulse.files.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.util.UUID;
import br.com.condpulse.files.application.AttachmentModels;
import br.com.condpulse.files.application.AttachmentService;
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

/** Anexos privados de pedido (CP-017), atrás de CP_FILES_ENABLED=false. Visibilidade decidida no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.files.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.REQUESTS)
public class AttachmentController {
 private final AttachmentService service;
 public AttachmentController(AttachmentService service) { this.service=service; }

 @PostMapping("/v1/tenants/{tenantId}/uploads/intents")
 public ResponseEntity<AttachmentModels.Upload> intent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody AttachmentModels.Intent request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.intent(account(jwt),tenantId,assurance(jwt),request));
 }
 @PostMapping("/v1/tenants/{tenantId}/attachments/{attachmentId}/complete")
 public ResponseEntity<AttachmentModels.Attachment> complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID attachmentId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.complete(account(jwt),tenantId,assurance(jwt),attachmentId));
 }
 @PostMapping("/v1/tenants/{tenantId}/attachments/{attachmentId}/download")
 public ResponseEntity<AttachmentModels.Download> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID attachmentId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.download(account(jwt),tenantId,assurance(jwt),attachmentId));
 }
 @GetMapping("/v1/tenants/{tenantId}/requests/{requestId}/attachments")
 public ResponseEntity<AttachmentModels.Page> forRequest(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID requestId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.forRequest(account(jwt),tenantId,assurance(jwt),requestId));
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
