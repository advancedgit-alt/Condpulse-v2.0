package br.com.condpulse.announcements.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.util.UUID;
import br.com.condpulse.announcements.application.AnnouncementModels;
import br.com.condpulse.announcements.application.AnnouncementService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Avisos (CP-032) atrás de CP_ANNOUNCEMENTS_ENABLED=false. Capacidade, público e vínculo conferidos no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.announcements.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.ANNOUNCEMENTS)
public class AnnouncementController {
 private final AnnouncementService service;
 public AnnouncementController(AnnouncementService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/management/announcements")
 public ResponseEntity<AnnouncementModels.ManagedPage> managed(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.managed(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/announcements")
 public ResponseEntity<AnnouncementModels.ManagedAnnouncement> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody AnnouncementModels.Draft request) {
  var result=service.createDraft(account(jwt),tenantId,assurance(jwt),key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.announcement());
 }
 @PutMapping("/v1/tenants/{tenantId}/management/announcements/{announcementId}")
 public ResponseEntity<AnnouncementModels.ManagedAnnouncement> edit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID announcementId,@RequestBody AnnouncementModels.EditDraft request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.editDraft(account(jwt),tenantId,assurance(jwt),announcementId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/announcements/{announcementId}/publish")
 public ResponseEntity<AnnouncementModels.ManagedAnnouncement> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID announcementId,@RequestBody AnnouncementModels.Publish request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publish(account(jwt),tenantId,assurance(jwt),announcementId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/announcements/{announcementId}/archive")
 public ResponseEntity<AnnouncementModels.ManagedAnnouncement> archive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID announcementId,@RequestBody AnnouncementModels.Archive request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.archive(account(jwt),tenantId,assurance(jwt),announcementId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/announcements")
 public ResponseEntity<AnnouncementModels.UnitPage> unit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unitAnnouncements(account(jwt),tenantId,unitId));
 }
 /** Abertura autenticada: devolve o texto e registra a primeira abertura. Não é ciência jurídica. */
 @PostMapping("/v1/tenants/{tenantId}/announcements/{announcementId}/open")
 public ResponseEntity<AnnouncementModels.OpenedAnnouncement> open(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID announcementId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.open(account(jwt),tenantId,announcementId));
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
