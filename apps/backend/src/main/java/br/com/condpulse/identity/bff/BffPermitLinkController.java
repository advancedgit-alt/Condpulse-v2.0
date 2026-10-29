package br.com.condpulse.identity.bff;

import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.visits.application.PermitLinkPublicService;
import br.com.condpulse.visits.application.PermitLinkTokens;
import br.com.condpulse.visits.application.VisitModels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Abertura do link de convite sem conta (CP-037), atrás de CP_BFF_ENABLED e CP_VISITS_ENABLED. O token vai no corpo de
 * um POST com Origin e CSRF do BFF (nunca na URL do servidor); limite de tentativas global e por hash, como na
 * recuperação de acesso. Resposta sem unidade, sem identificadores e sem cache.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.bff.enabled","condpulse.visits.enabled"},havingValue="true")
public class BffPermitLinkController {
 private final PermitLinkPublicService service;
 private final AuthRateLimiter limits;
 public BffPermitLinkController(PermitLinkPublicService service,AuthRateLimiter limits) { this.service=service; this.limits=limits; }

 @PostMapping("/auth/permit-links/view")
 public ResponseEntity<VisitModels.PublicPermit> view(@RequestBody(required=false) VisitModels.PermitLinkView body,HttpServletResponse response) {
  String token=body==null?null:body.token();
  // O limite por link usa o hash (nunca o token); token malformado conta no mesmo limite que um hash inválido.
  BffController.requireAdmission(limits.permitLinkView(PermitLinkTokens.wellFormed(token)?PermitLinkTokens.hash(token):null),response);
  var permit=service.view(token);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(permit);
 }
}
