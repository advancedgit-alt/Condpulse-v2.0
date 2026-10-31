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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
 @GetMapping("/v1/platform/tenants/{tenantId}")
 public ResponseEntity<PlatformModels.TenantDetail> tenant(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.tenant(account(jwt),assurance(jwt),tenantId));
 }
 /** Criação com chave de idempotência: 201 na criação; 200 na repetição da mesma chave e do mesmo pedido. */
 @PostMapping("/v1/platform/tenants")
 public ResponseEntity<PlatformModels.TenantCreated> createTenant(@AuthenticationPrincipal Jwt jwt,
     @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody PlatformModels.CreateTenant request) {
  var created=service.createTenant(account(jwt),assurance(jwt),key,request);
  return ResponseEntity.status(created.applied()?201:200).cacheControl(CacheControl.noStore()).body(created);
 }
 @PostMapping("/v1/platform/tenants/{tenantId}/manager-invitations")
 public ResponseEntity<PlatformModels.ManagerInvitationIssued> inviteManager(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@RequestBody PlatformModels.IssueManagerInvitation request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
   .body(service.issueManagerInvitation(account(jwt),assurance(jwt),tenantId,request));
 }
 @PostMapping("/v1/platform/tenants/{tenantId}/manager-invitations/{invitationId}/revoke")
 public ResponseEntity<PlatformModels.TenantDetail> revokeManagerInvitation(@AuthenticationPrincipal Jwt jwt,
     @PathVariable UUID tenantId,@PathVariable UUID invitationId,@RequestBody PlatformModels.RevokeManagerInvitation request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.revokeManagerInvitation(account(jwt),assurance(jwt),tenantId,invitationId,request));
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
