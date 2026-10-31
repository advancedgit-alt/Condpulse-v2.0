package br.com.condpulse.accessedge.api;

import java.util.UUID;
import br.com.condpulse.accessedge.application.EdgeCredentials;
import br.com.condpulse.accessedge.application.EdgeGatewayService;
import br.com.condpulse.accessedge.application.EdgeModels;
import br.com.condpulse.tenancy.application.RequiresTenantModule;
import br.com.condpulse.tenancy.application.TenantModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas do gateway (V048), sem sessão de pessoa: autenticação pelo cabeçalho X-CondPulse-Edge-Credential (só o hash vai
 * ao banco; o valor nunca é registrado). O condomínio vem da identidade, nunca do corpo. Módulo FACIAL_ACCESS conferido
 * dentro da transação, depois da identidade.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
@RequiresTenantModule(TenantModule.FACIAL_ACCESS)
public class EdgeGatewayController {
 private final EdgeGatewayService gateway;
 public EdgeGatewayController(EdgeGatewayService gateway) { this.gateway=gateway; }

 @GetMapping("/v1/edge/identity")
 public ResponseEntity<EdgeModels.Identity> identity(@RequestHeader(name=EdgeCredentials.HEADER,required=false) String credential) {
  return ok(gateway.identity(credential));
 }
 @PostMapping("/v1/edge/sync-commands/lease")
 public ResponseEntity<EdgeModels.LeasePage> lease(@RequestHeader(name=EdgeCredentials.HEADER,required=false) String credential,
   @RequestBody(required=false) EdgeModels.LeaseRequest request) {
  return ok(gateway.lease(credential,request));
 }
 @PostMapping("/v1/edge/sync-commands/{commandId}/result")
 public ResponseEntity<EdgeModels.ReportResult> report(@RequestHeader(name=EdgeCredentials.HEADER,required=false) String credential,
   @PathVariable UUID commandId,@RequestHeader(name="Idempotency-Key",required=false) String key,
   @RequestBody(required=false) EdgeModels.ReportRequest request) {
  return ok(gateway.report(credential,commandId,key,request));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
