package br.com.condpulse.stays.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.shared.application.BusinessProblem;
import br.com.condpulse.stays.application.StayModels;
import br.com.condpulse.stays.application.StayService;
import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
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

/**
 * Permanências (CPX-015 a CPX-019, V055), atrás de CP_VISITS_ENABLED (mesma flag e módulo do registro de entrada e saída,
 * que as abre). Autorização no serviço e no banco.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.visits.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.VISITS)
public class StayController {
 private final StayService service;
 public StayController(StayService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/stays")
 public ResponseEntity<StayModels.StayPage> open(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String view) {
  boolean overdue;
  if(view==null || view.isBlank() || "open".equals(view)) overdue=false;
  else if("overdue".equals(view)) overdue=true;
  else throw new BusinessProblem("STAY_VIEW_INVALID",400,"Use view=open ou view=overdue.");
  return ok(service.open(account(jwt),tenantId,assurance(jwt),overdue));
 }
 @GetMapping("/v1/tenants/{tenantId}/stays/day-close")
 public ResponseEntity<StayModels.DayClose> dayClose(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String date) {
  LocalDate day=null;
  if(date!=null && !date.isBlank()) {
   try { day=LocalDate.parse(date); }
   catch(DateTimeParseException error) { throw new BusinessProblem("STAY_DAY_CLOSE_DATE_INVALID",400,"Informe a data no formato AAAA-MM-DD."); }
  }
  return ok(service.dayClose(account(jwt),tenantId,assurance(jwt),day));
 }
 @PostMapping("/v1/tenants/{tenantId}/stays/{stayId}/justification")
 public ResponseEntity<StayModels.Stay> justify(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID stayId,
     @RequestBody(required=false) StayModels.Justify request) {
  return mutation(service.justify(account(jwt),tenantId,assurance(jwt),stayId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/stays")
 public ResponseEntity<StayModels.StayPage> unitOpen(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(service.unitOpen(account(jwt),tenantId,unitId));
 }
 @PostMapping("/v1/tenants/{tenantId}/units/{unitId}/stays/{stayId}/exit-confirmation")
 public ResponseEntity<StayModels.Stay> confirmExit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @PathVariable UUID stayId) {
  return mutation(service.confirmExit(account(jwt),tenantId,unitId,stayId));
 }
 @GetMapping("/v1/tenants/{tenantId}/stay-rules")
 public ResponseEntity<StayModels.Rules> rules(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(service.rules(account(jwt),tenantId,assurance(jwt)));
 }
 @PutMapping("/v1/tenants/{tenantId}/stay-rules/{kind}")
 public ResponseEntity<StayModels.Rules> changeRule(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable String kind,
     @RequestBody(required=false) StayModels.RuleChange change) {
  return ok(service.changeRule(account(jwt),tenantId,assurance(jwt),kind,change));
 }

 private static ResponseEntity<StayModels.Stay> mutation(StayModels.Mutation result) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.stay());
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
