package br.com.condpulse.accessinstall.api;

import java.util.UUID;
import br.com.condpulse.accessinstall.application.InstallModels;
import br.com.condpulse.accessinstall.application.InstallService;
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
import org.springframework.web.bind.annotation.RestController;

/** Equipamentos, canais, vínculos e aceite (MASTER de implantação) e assinatura das regras (gestão) — AC-004 parte 2, V042. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessInstallController {
 private final InstallService install;
 public AccessInstallController(InstallService install) { this.install=install; }

 @GetMapping("/v1/tenants/{tenantId}/technical/access-devices")
 public ResponseEntity<InstallModels.DevicePage> devices(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(install.devices(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/access-devices")
 public ResponseEntity<InstallModels.Device> createDevice(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) InstallModels.CreateDevice request) {
  return ok(install.createDevice(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/technical/access-devices/{deviceId}")
 public ResponseEntity<InstallModels.Device> updateDevice(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID deviceId,
   @RequestBody(required=false) InstallModels.UpdateDevice request) {
  return ok(install.updateDevice(account(jwt),tenantId,assurance(jwt),deviceId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/technical/device-channels")
 public ResponseEntity<InstallModels.ChannelPage> channels(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(install.channels(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/device-channels")
 public ResponseEntity<InstallModels.Channel> createChannel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) InstallModels.CreateChannel request) {
  return ok(install.createChannel(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/technical/device-channels/{channelId}")
 public ResponseEntity<InstallModels.Channel> updateChannel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID channelId,
   @RequestBody(required=false) InstallModels.UpdateChannel request) {
  return ok(install.updateChannel(account(jwt),tenantId,assurance(jwt),channelId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/technical/point-bindings")
 public ResponseEntity<InstallModels.BindingPage> bindings(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(install.bindings(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/point-bindings")
 public ResponseEntity<InstallModels.Binding> createBinding(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) InstallModels.CreateBinding request) {
  return ok(install.createBinding(account(jwt),tenantId,assurance(jwt),request));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/point-bindings/{bindingId}/submit")
 public ResponseEntity<InstallModels.Binding> submitBinding(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID bindingId,
   @RequestBody(required=false) InstallModels.SubmitBinding request) {
  return ok(install.submitBinding(account(jwt),tenantId,assurance(jwt),bindingId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/point-bindings/{bindingId}/discard")
 public ResponseEntity<InstallModels.Binding> discardBinding(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID bindingId,
   @RequestBody(required=false) InstallModels.VersionOnly request) {
  return ok(install.discardBinding(account(jwt),tenantId,assurance(jwt),bindingId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/technical/installation-acceptances")
 public ResponseEntity<InstallModels.AcceptancePage> acceptances(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(install.acceptances(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/installation-acceptances")
 public ResponseEntity<InstallModels.Acceptance> createAcceptance(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) InstallModels.CreateAcceptance request) {
  return ok(install.createAcceptance(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/technical/installation-acceptances/{acceptanceId}/results")
 public ResponseEntity<InstallModels.Acceptance> recordResults(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID acceptanceId,
   @RequestBody(required=false) InstallModels.AcceptanceResults request) {
  return ok(install.recordResults(account(jwt),tenantId,assurance(jwt),acceptanceId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/installation-acceptances/{acceptanceId}/technical-signature")
 public ResponseEntity<InstallModels.Acceptance> signTechnical(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID acceptanceId,
   @RequestBody(required=false) InstallModels.VersionOnly request) {
  return ok(install.signTechnical(account(jwt),tenantId,assurance(jwt),acceptanceId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/installation-acceptances/{acceptanceId}/status")
 public ResponseEntity<InstallModels.Acceptance> transition(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID acceptanceId,
   @RequestBody(required=false) InstallModels.AcceptanceTransition request) {
  return ok(install.transition(account(jwt),tenantId,assurance(jwt),acceptanceId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/installation-acceptances")
 public ResponseEntity<InstallModels.AcceptanceSummaryPage> managementAcceptances(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(install.managementAcceptances(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/installation-acceptances/{acceptanceId}/rules-signature")
 public ResponseEntity<InstallModels.AcceptanceSummary> signRules(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID acceptanceId,
   @RequestBody(required=false) InstallModels.VersionOnly request) {
  return ok(install.signRules(account(jwt),tenantId,assurance(jwt),acceptanceId,request));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
