package br.com.condpulse.platform.api;

import java.util.UUID;
import br.com.condpulse.platform.application.TenantModuleModels;
import br.com.condpulse.platform.application.TenantModuleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Módulos por condomínio na central (V045), atrás de CP_PLATFORM_ENABLED. Papel de plataforma, AAL2 e trilha no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.platform.enabled",havingValue="true")
public class TenantModuleController {
 private final TenantModuleService service;
 public TenantModuleController(TenantModuleService service) { this.service=service; }

 @GetMapping("/v1/platform/tenants/{tenantId}/modules")
 public ResponseEntity<TenantModuleModels.TenantModulePage> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.list(PlatformController.account(jwt),PlatformController.assurance(jwt),tenantId));
 }
 @PutMapping("/v1/platform/tenants/{tenantId}/modules")
 public ResponseEntity<TenantModuleModels.TenantModuleChange> change(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody TenantModuleModels.ChangeTenantModule request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.change(PlatformController.account(jwt),PlatformController.assurance(jwt),tenantId,request));
 }
}
