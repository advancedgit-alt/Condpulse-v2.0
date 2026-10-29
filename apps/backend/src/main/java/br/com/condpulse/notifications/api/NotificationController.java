package br.com.condpulse.notifications.api;

import java.util.UUID;
import br.com.condpulse.notifications.application.NotificationModels;
import br.com.condpulse.notifications.application.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Caixa interna da unidade, atrás de CP_NOTIFICATIONS_ENABLED=false. Destinatário e vínculo conferidos no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.notifications.enabled",havingValue="true")
public class NotificationController {
 private final NotificationService service;
 public NotificationController(NotificationService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/notifications")
 public ResponseEntity<NotificationModels.Page> unit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unit(account(jwt),tenantId,unitId));
 }
 /** Abertura autenticada: única forma de marcar leitura. Repetir devolve a leitura já gravada. */
 @PostMapping("/v1/tenants/{tenantId}/notifications/{notificationId}/open")
 public ResponseEntity<NotificationModels.Notification> open(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID notificationId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.open(account(jwt),tenantId,notificationId));
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
