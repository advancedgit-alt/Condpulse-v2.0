package br.com.condpulse.partners.api;

import java.util.UUID;
import br.com.condpulse.partners.application.SecurityCommandModels;
import br.com.condpulse.partners.application.SecurityCommandService;
import br.com.condpulse.shared.application.BusinessProblem;
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

/**
 * Comando de Segurança do gestor líder (CPX-012 a CPX-014, V053): exige CP_PARTNERS_ENABLED e CP_SECURITY_LOG_ENABLED;
 * security.command, alcance e AAL2 conferidos no serviço e no banco.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.partners.enabled","condpulse.security-log.enabled"},havingValue="true")
public class SecurityCommandController {
 private final SecurityCommandService service;
 public SecurityCommandController(SecurityCommandService service) { this.service=service; }

 @GetMapping("/v1/partners/{partnerId}/command/portfolio")
 public ResponseEntity<SecurityCommandModels.Portfolio> portfolio(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId) {
  return ok(service.portfolio(account(jwt),assurance(jwt),partnerId));
 }
 @GetMapping("/v1/partners/{partnerId}/command/team")
 public ResponseEntity<SecurityCommandModels.Team> team(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId) {
  return ok(service.team(account(jwt),assurance(jwt),partnerId));
 }
 @GetMapping("/v1/partners/{partnerId}/command/incidents")
 public ResponseEntity<SecurityCommandModels.IncidentPage> incidents(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestParam(required=false) String state) {
  return ok(service.incidents(account(jwt),assurance(jwt),partnerId,state));
 }
 @PostMapping("/v1/partners/{partnerId}/command/incidents")
 public ResponseEntity<SecurityCommandModels.IncidentDetail> open(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody(required=false) SecurityCommandModels.OpenIncident request) {
  return created(service.open(account(jwt),assurance(jwt),partnerId,request));
 }
 @GetMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/incidents/{incidentId}")
 public ResponseEntity<SecurityCommandModels.IncidentDetail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@PathVariable UUID incidentId) {
  return ok(service.detail(account(jwt),assurance(jwt),partnerId,tenantId,incidentId));
 }
 @PostMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/incidents/{incidentId}/assignment")
 public ResponseEntity<SecurityCommandModels.IncidentDetail> assign(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@PathVariable UUID incidentId,@RequestBody(required=false) SecurityCommandModels.AssignIncident request) {
  return ok(service.assign(account(jwt),assurance(jwt),partnerId,tenantId,incidentId,request));
 }
 @PostMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/incidents/{incidentId}/links")
 public ResponseEntity<SecurityCommandModels.IncidentDetail> link(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@PathVariable UUID incidentId,@RequestBody(required=false) SecurityCommandModels.LinkEvents request) {
  return ok(service.link(account(jwt),assurance(jwt),partnerId,tenantId,incidentId,request));
 }
 @PostMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/incidents/{incidentId}/close")
 public ResponseEntity<SecurityCommandModels.IncidentDetail> close(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@PathVariable UUID incidentId,@RequestBody(required=false) SecurityCommandModels.CloseIncident request) {
  return ok(service.close(account(jwt),assurance(jwt),partnerId,tenantId,incidentId,request));
 }
 @PostMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/incidents/{incidentId}/dossier")
 public ResponseEntity<SecurityCommandModels.Dossier> dossier(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@PathVariable UUID incidentId,@RequestBody(required=false) SecurityCommandModels.DossierRequest request) {
  return ok(service.dossier(account(jwt),assurance(jwt),partnerId,tenantId,incidentId,request));
 }
 @GetMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/remote-opens")
 public ResponseEntity<SecurityCommandModels.RemoteOpenPage> remoteOpens(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@RequestParam(required=false) String status,@RequestParam(required=false) String operatorId,
   @RequestParam(required=false) Long beforeSeq,@RequestParam(required=false) Integer limit) {
  return ok(service.remoteOpens(account(jwt),assurance(jwt),partnerId,tenantId,status,operator(operatorId),beforeSeq,limit));
 }
 @PostMapping("/v1/partners/{partnerId}/command/tenants/{tenantId}/remote-opens/{eventId}/reviews")
 public ResponseEntity<SecurityCommandModels.RemoteOpen> review(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @PathVariable UUID tenantId,@PathVariable UUID eventId,@RequestBody(required=false) SecurityCommandModels.ReviewRequest request) {
  return created(service.review(account(jwt),assurance(jwt),partnerId,tenantId,eventId,request));
 }

 private static UUID operator(String value) {
  if(value==null || value.isBlank()) return null;
  try {
   var parsed=UUID.fromString(value);
   if(!parsed.toString().equals(value)) throw new IllegalArgumentException();
   return parsed;
  } catch(IllegalArgumentException invalid) {
   throw new BusinessProblem("REMOTE_OPEN_OPERATOR_INVALID",400,"Operador inválido.");
  }
 }
}
