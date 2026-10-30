package br.com.condpulse.platform.api;

import java.util.UUID;
import br.com.condpulse.platform.application.OutboxOperationsModels;
import br.com.condpulse.platform.application.OutboxOperationsService;
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

/** Fila de tarefas no painel master (CP-034), atrás de CP_PLATFORM_ENABLED. outbox.operate, AAL2 e auditoria no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.platform.enabled",havingValue="true")
public class OutboxOperationsController {
 private final OutboxOperationsService service;
 public OutboxOperationsController(OutboxOperationsService service) { this.service=service; }

 @GetMapping("/v1/platform/outbox")
 public ResponseEntity<OutboxOperationsModels.Summary> summary(@AuthenticationPrincipal Jwt jwt) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(PlatformController.account(jwt),PlatformController.assurance(jwt)));
 }
 @GetMapping("/v1/platform/outbox/tasks")
 public ResponseEntity<OutboxOperationsModels.TaskPage> tasks(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID tenantId,
     @RequestParam String state) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.tasks(PlatformController.account(jwt),PlatformController.assurance(jwt),tenantId,state));
 }
 @PostMapping("/v1/platform/outbox/tasks/{taskId}/replay")
 public ResponseEntity<OutboxOperationsModels.Task> replay(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID taskId,
     @RequestBody OutboxOperationsModels.Replay request) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.replay(PlatformController.account(jwt),PlatformController.assurance(jwt),taskId,request));
 }
}
