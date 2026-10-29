package br.com.condpulse.platform.api;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.platform.application.BillingModels;
import br.com.condpulse.platform.application.BillingService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financeiro da plataforma (fase 5), atrás de CP_PLATFORM_ENABLED e CP_PLATFORM_BILLING_ENABLED (ambos false por
 * padrão). Papel de plataforma, AAL2, capacidades billing.* e auditoria no serviço e no banco.
 */
@RestController @Profile("api")
@ConditionalOnProperty(name={"condpulse.platform.enabled","condpulse.platform.billing-enabled"},havingValue="true")
public class BillingController {
 private static final String BASE="/v1/platform/billing";
 private final BillingService billing;
 public BillingController(BillingService billing) { this.billing=billing; }

 @GetMapping(BASE+"/plans")
 public ResponseEntity<List<BillingModels.Plan>> plans(@AuthenticationPrincipal Jwt jwt) {
  return ok(billing.plans(account(jwt),assurance(jwt)));
 }
 @PostMapping(BASE+"/plans")
 public ResponseEntity<BillingModels.Plan> createPlan(@AuthenticationPrincipal Jwt jwt,@RequestBody BillingModels.CreatePlan request) {
  return created(billing.createPlan(account(jwt),assurance(jwt),request));
 }
 @PutMapping(BASE+"/plans/{planId}")
 public ResponseEntity<BillingModels.PlanChange> updatePlan(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID planId,
     @RequestBody BillingModels.UpdatePlan request) {
  return ok(billing.updatePlan(account(jwt),assurance(jwt),planId,request));
 }
 @GetMapping(BASE+"/subscriptions")
 public ResponseEntity<List<BillingModels.Subscription>> subscriptions(@AuthenticationPrincipal Jwt jwt,
     @RequestParam(required=false) UUID tenantId) {
  return ok(billing.subscriptions(account(jwt),assurance(jwt),tenantId));
 }
 @PostMapping(BASE+"/subscriptions")
 public ResponseEntity<BillingModels.Subscription> createSubscription(@AuthenticationPrincipal Jwt jwt,
     @RequestBody BillingModels.CreateSubscription request) {
  return created(billing.createSubscription(account(jwt),assurance(jwt),request));
 }
 @PostMapping(BASE+"/subscriptions/{subscriptionId}/end")
 public ResponseEntity<BillingModels.Subscription> endSubscription(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID subscriptionId,
     @RequestBody BillingModels.EndSubscription request) {
  return ok(billing.endSubscription(account(jwt),assurance(jwt),subscriptionId,request));
 }
 @PostMapping(BASE+"/subscriptions/{subscriptionId}/charges")
 public ResponseEntity<BillingModels.Charge> createCharge(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID subscriptionId,
     @RequestBody BillingModels.CreateCharge request) {
  return created(billing.createCharge(account(jwt),assurance(jwt),subscriptionId,request));
 }
 @GetMapping(BASE+"/charges")
 public ResponseEntity<BillingModels.ChargePage> charges(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String status,
     @RequestParam(required=false) UUID tenantId) {
  return ok(billing.charges(account(jwt),assurance(jwt),status,tenantId));
 }
 @PostMapping(BASE+"/charges/{chargeId}/cancel")
 public ResponseEntity<BillingModels.Charge> cancelCharge(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID chargeId,
     @RequestBody BillingModels.CancelCharge request) {
  return ok(billing.cancelCharge(account(jwt),assurance(jwt),chargeId,request));
 }
 @PostMapping(BASE+"/charges/{chargeId}/payments")
 public ResponseEntity<BillingModels.Charge> recordPayment(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID chargeId,
     @RequestBody BillingModels.RecordPayment request) {
  return ok(billing.recordPayment(account(jwt),assurance(jwt),chargeId,request));
 }
 @PostMapping(BASE+"/payments/{paymentId}/void")
 public ResponseEntity<BillingModels.Charge> voidPayment(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID paymentId,
     @RequestBody BillingModels.VoidPayment request) {
  return ok(billing.voidPayment(account(jwt),assurance(jwt),paymentId,request));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static <T> ResponseEntity<T> created(T body) { return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
