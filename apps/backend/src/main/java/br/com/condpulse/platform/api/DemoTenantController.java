package br.com.condpulse.platform.api;

import br.com.condpulse.platform.application.DemoTenantModels;
import br.com.condpulse.platform.application.DemoTenantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Condomínio demo (CPX-046), atrás de CP_PLATFORM_ENABLED e CP_DEMO_ENABLED (ambas false por padrão). */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.platform.enabled","condpulse.demo.enabled"},havingValue="true")
public class DemoTenantController {
 private final DemoTenantService service;
 public DemoTenantController(DemoTenantService service) { this.service=service; }

 @GetMapping("/v1/platform/demo-tenant")
 public ResponseEntity<DemoTenantModels.DemoState> state(@AuthenticationPrincipal Jwt jwt) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.state(PlatformController.account(jwt),PlatformController.assurance(jwt)));
 }
 /** 201 quando cria (ou reseta); 200 na repetição da mesma chave e do mesmo pedido. */
 @PostMapping("/v1/platform/demo-tenant")
 public ResponseEntity<DemoTenantModels.DemoProvisioned> provision(@AuthenticationPrincipal Jwt jwt,
   @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody DemoTenantModels.ProvisionDemo request) {
  var result=service.provision(PlatformController.account(jwt),PlatformController.assurance(jwt),key,request);
  return ResponseEntity.status(result.applied()?201:200).cacheControl(CacheControl.noStore()).body(result);
 }
}
