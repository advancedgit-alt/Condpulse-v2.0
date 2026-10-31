package br.com.condpulse.partners.api;

import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.partners.application.PartnerModels;
import br.com.condpulse.partners.application.PartnerService;
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

/** Parceiros na central da plataforma (V049): exige CP_PLATFORM_ENABLED e CP_PARTNERS_ENABLED; papel, AAL2 e trilha no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.platform.enabled","condpulse.partners.enabled"},havingValue="true")
public class PartnerPlatformController {
 private final PartnerService service;
 public PartnerPlatformController(PartnerService service) { this.service=service; }

 @GetMapping("/v1/platform/partners")
 public ResponseEntity<PartnerModels.PartnerPage> list(@AuthenticationPrincipal Jwt jwt) {
  return ok(service.list(account(jwt),assurance(jwt)));
 }
 @PostMapping("/v1/platform/partners")
 public ResponseEntity<PartnerModels.PartnerDetail> create(@AuthenticationPrincipal Jwt jwt,@RequestBody PartnerModels.CreatePartner request) {
  return created(service.create(account(jwt),assurance(jwt),request));
 }
 @GetMapping("/v1/platform/partners/{partnerId}")
 public ResponseEntity<PartnerModels.PartnerDetail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId) {
  return ok(service.detail(account(jwt),assurance(jwt),partnerId));
 }
 @PutMapping("/v1/platform/partners/{partnerId}/status")
 public ResponseEntity<PartnerModels.PartnerChange> status(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody PartnerModels.ChangePartnerStatus request) {
  return ok(service.changeStatus(account(jwt),assurance(jwt),partnerId,request));
 }
 @PostMapping("/v1/platform/partners/{partnerId}/contracts")
 public ResponseEntity<PartnerModels.Contract> startContract(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody PartnerModels.StartContract request) {
  return created(service.startContract(account(jwt),assurance(jwt),partnerId,request));
 }
 @PostMapping("/v1/platform/partners/{partnerId}/contracts/{contractId}/end")
 public ResponseEntity<PartnerModels.Contract> endContract(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID contractId,@RequestBody PartnerModels.Reason request) {
  return ok(service.endContract(account(jwt),assurance(jwt),partnerId,contractId,request));
 }
 @PostMapping("/v1/platform/partners/{partnerId}/members")
 public ResponseEntity<PartnerModels.Member> addMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody PartnerModels.AddMember request) {
  return created(service.addMember(account(jwt),assurance(jwt),partnerId,request));
 }
 @PostMapping("/v1/platform/partners/{partnerId}/members/{memberId}/revoke")
 public ResponseEntity<PartnerModels.Member> revokeMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID memberId,@RequestBody PartnerModels.Reason request) {
  return ok(service.revokeMember(account(jwt),assurance(jwt),partnerId,memberId,request));
 }
 @GetMapping("/v1/platform/partners/{partnerId}/schedules")
 public ResponseEntity<PartnerModels.SchedulePage> schedules(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestParam(required=false) UUID tenantId) {
  return ok(service.schedules(account(jwt),assurance(jwt),partnerId,tenantId));
 }
 @PostMapping("/v1/platform/partners/{partnerId}/schedules")
 public ResponseEntity<PartnerModels.Schedule> createSchedule(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody PartnerModels.CreateSchedule request) {
  return created(service.createSchedule(account(jwt),assurance(jwt),partnerId,request));
 }
 @PostMapping("/v1/platform/partners/{partnerId}/schedules/{scheduleId}/cancel")
 public ResponseEntity<PartnerModels.Schedule> cancelSchedule(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID scheduleId,@RequestBody PartnerModels.Reason request) {
  return ok(service.cancelSchedule(account(jwt),assurance(jwt),partnerId,scheduleId,request));
 }

 static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 static <T> ResponseEntity<T> created(T body) { return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
