package br.com.condpulse.partners.api;

import java.util.UUID;
import br.com.condpulse.partners.application.ContextSwitchService;
import br.com.condpulse.partners.application.PartnerModels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static br.com.condpulse.partners.api.PartnerPlatformController.*;

/**
 * Troca de contexto (R4.4, V052): a web chama ao entrar no painel de um papel de parceiro. Grava CONTEXT_SWITCHED no log
 * dos condomínios afetados para papéis de segurança; com CP_SECURITY_LOG_ENABLED=false confere e não grava.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.partners.enabled",havingValue="true")
public class PartnerContextController {
 private final ContextSwitchService service;
 public PartnerContextController(ContextSwitchService service) { this.service=service; }

 @PostMapping("/v1/partners/{partnerId}/context-switches")
 public ResponseEntity<PartnerModels.ContextSwitchResult> contextSwitch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody(required=false) PartnerModels.ContextSwitchRequest request) {
  Object session=jwt.getClaims().get("session_id");
  return ok(service.record(account(jwt),assurance(jwt),session instanceof String value?value:null,partnerId,request));
 }
 /** Saída do painel para um condomínio por vínculo ou para a central: grava o lado da origem (R4.4). */
 @PostMapping("/v1/partners/{partnerId}/context-exits")
 public ResponseEntity<PartnerModels.ContextSwitchResult> contextExit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID partnerId,
   @RequestBody(required=false) PartnerModels.ContextExitRequest request) {
  Object session=jwt.getClaims().get("session_id");
  return ok(service.recordExit(account(jwt),assurance(jwt),session instanceof String value?value:null,partnerId,request));
 }
}
