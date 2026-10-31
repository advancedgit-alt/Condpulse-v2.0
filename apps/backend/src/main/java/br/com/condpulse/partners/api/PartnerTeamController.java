package br.com.condpulse.partners.api;

import java.util.UUID;
import br.com.condpulse.partners.application.PartnerModels;
import br.com.condpulse.partners.application.PartnerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static br.com.condpulse.partners.api.PartnerPlatformController.*;

/** Painel do parceiro (gestor, partner.team.manage/partner.manage, AAL2): própria equipe, escala e carteira (V049). */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.partners.enabled",havingValue="true")
public class PartnerTeamController {
 private final PartnerService service;
 public PartnerTeamController(PartnerService service) { this.service=service; }

 @GetMapping("/v1/partners/{partnerId}/team")
 public ResponseEntity<PartnerModels.MemberPage> team(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId) {
  return ok(service.team(account(jwt),assurance(jwt),partnerId));
 }
 @PostMapping("/v1/partners/{partnerId}/team")
 public ResponseEntity<PartnerModels.Member> add(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody PartnerModels.AddMember request) {
  return created(service.teamAdd(account(jwt),assurance(jwt),partnerId,request));
 }
 @PostMapping("/v1/partners/{partnerId}/team/{memberId}/revoke")
 public ResponseEntity<PartnerModels.Member> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID memberId,@RequestBody PartnerModels.Reason request) {
  return ok(service.teamRevoke(account(jwt),assurance(jwt),partnerId,memberId,request));
 }
 @GetMapping("/v1/partners/{partnerId}/schedules")
 public ResponseEntity<PartnerModels.SchedulePage> schedules(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestParam(required=false) UUID tenantId) {
  return ok(service.teamSchedules(account(jwt),assurance(jwt),partnerId,tenantId));
 }
 @PostMapping("/v1/partners/{partnerId}/schedules")
 public ResponseEntity<PartnerModels.Schedule> createSchedule(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody PartnerModels.CreateSchedule request) {
  return created(service.teamCreateSchedule(account(jwt),assurance(jwt),partnerId,request));
 }
 @PostMapping("/v1/partners/{partnerId}/schedules/{scheduleId}/cancel")
 public ResponseEntity<PartnerModels.Schedule> cancelSchedule(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID scheduleId,@RequestBody PartnerModels.Reason request) {
  return ok(service.teamCancelSchedule(account(jwt),assurance(jwt),partnerId,scheduleId,request));
 }
 @GetMapping("/v1/partners/{partnerId}/portfolio")
 public ResponseEntity<PartnerModels.ContractPage> portfolio(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId) {
  return ok(service.portfolio(account(jwt),assurance(jwt),partnerId));
 }
}
