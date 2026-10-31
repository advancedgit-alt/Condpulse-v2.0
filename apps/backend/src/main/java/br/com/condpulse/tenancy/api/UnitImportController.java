package br.com.condpulse.tenancy.api;

import br.com.condpulse.tenancy.application.TenantModuleExempt;
import java.util.UUID;
import br.com.condpulse.tenancy.application.UnitImportModels;
import br.com.condpulse.tenancy.application.UnitImportService;
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

/** Importação de unidades em staging (CP-016), atrás de CP_MANAGEMENT_ENABLED; units.manage com AAL2 no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.management.enabled",havingValue="true")
@TenantModuleExempt("Importação de blocos e unidades: cadastro base do condomínio.")
public class UnitImportController {
 private final UnitImportService service;
 public UnitImportController(UnitImportService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/management/imports")
 public ResponseEntity<UnitImportModels.BatchPage> recent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.recent(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt)));
 }
 /** 201 com lote novo; 200 quando o mesmo arquivo já tem lote vivo (nenhuma linha nova é gravada). */
 @PostMapping("/v1/tenants/{tenantId}/management/imports")
 public ResponseEntity<UnitImportModels.Detail> open(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody UnitImportModels.Open request) {
  var opened=service.open(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),request);
  return ResponseEntity.status(opened.created()?201:200).cacheControl(CacheControl.noStore())
   .header("Import-Batch-Created",Boolean.toString(opened.created())).body(opened.detail());
 }
 @GetMapping("/v1/tenants/{tenantId}/management/imports/{batchId}")
 public ResponseEntity<UnitImportModels.Detail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID batchId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.detail(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),batchId));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/imports/{batchId}/approve")
 public ResponseEntity<UnitImportModels.Detail> approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID batchId,@RequestBody UnitImportModels.Approve request) {
  var decided=service.approve(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),batchId,request);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .header("Import-Decision-Applied",Boolean.toString(decided.applied())).body(decided.detail());
 }
 @PostMapping("/v1/tenants/{tenantId}/management/imports/{batchId}/discard")
 public ResponseEntity<UnitImportModels.Detail> discard(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID batchId,@RequestBody UnitImportModels.Discard request) {
  var decided=service.discard(UnitManagementController.account(jwt),tenantId,UnitManagementController.assurance(jwt),batchId,request);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .header("Import-Decision-Applied",Boolean.toString(decided.applied())).body(decided.detail());
 }
}
