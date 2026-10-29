package br.com.condpulse.tenancy.api;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.tenancy.application.UnitManagementModels;
import br.com.condpulse.tenancy.application.UnitManagementService;
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

@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
public class UnitManagementController {
 private final UnitManagementService service;
 public UnitManagementController(UnitManagementService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/management/blocks")
 public ResponseEntity<List<UnitManagementModels.Block>> blocks(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.blocks(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/blocks")
 public ResponseEntity<UnitManagementModels.Block> createBlock(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody UnitManagementModels.CreateBlock request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
   .body(service.createBlock(account(jwt),tenantId,assurance(jwt),request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/units")
 public ResponseEntity<UnitManagementModels.UnitPage> units(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.units(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/units")
 public ResponseEntity<UnitManagementModels.Unit> createUnit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody UnitManagementModels.CreateUnit request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
   .body(service.createUnit(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/management/units/{unitId}")
 public ResponseEntity<UnitManagementModels.Unit> updateUnit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID unitId,@RequestBody UnitManagementModels.UpdateUnit request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.updateUnit(account(jwt),tenantId,assurance(jwt),unitId,request));
 }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
