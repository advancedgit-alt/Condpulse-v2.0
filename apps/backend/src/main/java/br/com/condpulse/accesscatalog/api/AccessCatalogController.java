package br.com.condpulse.accesscatalog.api;

import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import java.util.UUID;
import br.com.condpulse.accesscatalog.application.CatalogModels;
import br.com.condpulse.accesscatalog.application.CatalogService;
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

/** Catálogo e homologação (AC-019, V040): plataforma cria e decide; gestão consulta o estado. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.FACIAL_ACCESS)
public class AccessCatalogController {
 private final CatalogService catalog;
 public AccessCatalogController(CatalogService catalog) { this.catalog=catalog; }

 @GetMapping("/v1/platform/access-models")
 public ResponseEntity<CatalogModels.ProfilePage> profiles(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String manufacturer) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.profiles(account(jwt),assurance(jwt),manufacturer));
 }
 @GetMapping("/v1/platform/access-models/{modelId}")
 public ResponseEntity<CatalogModels.Profile> profile(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID modelId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.profile(account(jwt),assurance(jwt),modelId));
 }
 @GetMapping("/v1/platform/access-homologations")
 public ResponseEntity<CatalogModels.HomologationPage> homologations(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID modelId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.homologations(account(jwt),assurance(jwt),modelId));
 }
 @PostMapping("/v1/platform/access-homologations")
 public ResponseEntity<CatalogModels.Created> create(@AuthenticationPrincipal Jwt jwt,@RequestBody CatalogModels.CreateHomologation request) {
  var created=catalog.create(account(jwt),assurance(jwt),request);
  return ResponseEntity.status(created.applied()?201:200).cacheControl(CacheControl.noStore()).body(created);
 }
 @GetMapping("/v1/platform/access-homologations/{homologationId}")
 public ResponseEntity<CatalogModels.HomologationDetail> homologation(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID homologationId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.homologation(account(jwt),assurance(jwt),homologationId));
 }
 @PostMapping("/v1/platform/access-homologations/{homologationId}/decisions")
 public ResponseEntity<CatalogModels.HomologationDetail> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID homologationId,
   @RequestBody CatalogModels.Decide request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.decide(account(jwt),assurance(jwt),homologationId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-homologations")
 public ResponseEntity<CatalogModels.HomologationPage> management(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.managementHomologations(account(jwt),tenantId,assurance(jwt)));
 }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
