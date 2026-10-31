package br.com.condpulse.securitylog.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.securitylog.application.SecurityLogModels;
import br.com.condpulse.securitylog.application.SecurityLogService;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.tenancy.application.TenantModuleExempt;
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

/** Log de Segurança (CPX-009/CPX-010, V050), atrás de CP_SECURITY_LOG_ENABLED=false. Autorização no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.security-log.enabled",havingValue="true")
@TenantModuleExempt("Log de Segurança: trilha transversal de prova de todos os módulos de segurança.")
public class SecurityLogController {
 private final SecurityLogService service;
 public SecurityLogController(SecurityLogService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/security-log")
 public ResponseEntity<SecurityLogModels.Page> page(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String type,@RequestParam(required=false) String severity,@RequestParam(required=false) String actorId,
     @RequestParam(required=false) String from,@RequestParam(required=false) String until,@RequestParam(required=false) Long beforeSeq,
     @RequestParam(required=false) Integer limit) {
  var filter=SecurityLogModels.Filter.of(blank(type),blank(severity),uuid(actorId),instant(from),instant(until),beforeSeq,limit);
  return ok(service.page(account(jwt),tenantId,assurance(jwt),filter));
 }
 @GetMapping("/v1/tenants/{tenantId}/security-log/integrity")
 public ResponseEntity<SecurityLogModels.Integrity> integrity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(service.integrity(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/security-log/exports")
 public ResponseEntity<SecurityLogModels.Export> export(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody(required=false) SecurityLogModels.ExportRequest request) {
  return ok(service.export(account(jwt),tenantId,assurance(jwt),request));
 }

 private static String blank(String value) { return value==null || value.isBlank()?null:value; }
 private static UUID uuid(String value) {
  if(blank(value)==null) return null;
  try {
   var parsed=UUID.fromString(value);
   if(!parsed.toString().equals(value)) throw new IllegalArgumentException();
   return parsed;
  } catch(IllegalArgumentException invalid) {
   throw new BusinessProblem("SECURITY_LOG_ACTOR_INVALID",400,"Autor inválido.");
  }
 }
 private static Instant instant(String value) {
  if(blank(value)==null) return null;
  try { return Instant.parse(value); }
  catch(DateTimeParseException invalid) { throw new BusinessProblem("SECURITY_LOG_PERIOD_INVALID",400,"Use data e hora ISO-8601 em UTC."); }
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
