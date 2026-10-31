package br.com.condpulse.accessedge.api;

import java.util.UUID;
import br.com.condpulse.accessedge.application.EdgeAgentService;
import br.com.condpulse.accessedge.application.EdgeModels;
import br.com.condpulse.identity.application.AssuranceLevel;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Identidade do gateway pelo MASTER de implantação (V048): só o hash da credencial entra; nada de segredo sai. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.FACIAL_ACCESS)
public class EdgeAgentController {
 private final EdgeAgentService agents;
 public EdgeAgentController(EdgeAgentService agents) { this.agents=agents; }

 @GetMapping("/v1/tenants/{tenantId}/technical/edge-agents")
 public ResponseEntity<EdgeModels.AgentPage> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(agents.agents(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/edge-agents")
 public ResponseEntity<EdgeModels.Agent> register(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
   @RequestBody(required=false) EdgeModels.RegisterAgent request) {
  return ok(agents.register(account(jwt),tenantId,assurance(jwt),request));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/edge-agents/{agentId}/rotate")
 public ResponseEntity<EdgeModels.Agent> rotate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID agentId,
   @RequestBody(required=false) EdgeModels.RotateAgent request) {
  return ok(agents.rotate(account(jwt),tenantId,assurance(jwt),agentId,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/edge-agents/{agentId}/revoke")
 public ResponseEntity<EdgeModels.Agent> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID agentId,
   @RequestBody(required=false) EdgeModels.RevokeAgent request) {
  return ok(agents.revoke(account(jwt),tenantId,assurance(jwt),agentId,request));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
