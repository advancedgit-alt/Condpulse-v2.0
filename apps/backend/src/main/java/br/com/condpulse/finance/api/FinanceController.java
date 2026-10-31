package br.com.condpulse.finance.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.finance.application.FinanceModels;
import br.com.condpulse.finance.application.FinanceService;
import br.com.condpulse.finance.application.FinanceWebhookService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Financeiro condominial com Asaas (ADR-0008), atrás de CP_FINANCE_ENABLED. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.finance.enabled",havingValue="true")
public class FinanceController {
 private final FinanceService finance;
 private final FinanceWebhookService webhook;
 public FinanceController(FinanceService finance,FinanceWebhookService webhook) { this.finance=finance; this.webhook=webhook; }

 private static final String BASE="/v1/tenants/{tenantId}/management/finance";
 @GetMapping(BASE+"/units")
 public ResponseEntity<List<FinanceModels.UnitFinance>> units(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(finance.units(account(jwt),tenantId,assurance(jwt)));
 }
 @PutMapping(BASE+"/units/{unitId}/payer")
 public ResponseEntity<FinanceModels.Payer> payer(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @RequestBody FinanceModels.PayerChange request) {
  return ok(finance.setPayer(account(jwt),tenantId,assurance(jwt),unitId,request));
 }
 @GetMapping(BASE+"/charges")
 public ResponseEntity<FinanceModels.ChargePage> charges(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String state,@RequestParam(required=false) UUID unitId) {
  return ok(finance.charges(account(jwt),tenantId,assurance(jwt),state,unitId));
 }
 @PostMapping(BASE+"/charges")
 public ResponseEntity<FinanceModels.Charge> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody FinanceModels.ChargeCreate request) {
  var result=finance.create(account(jwt),tenantId,assurance(jwt),key,request);
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.charge());
 }
 @PostMapping(BASE+"/charges/{chargeId}/issue")
 public ResponseEntity<FinanceModels.Charge> issue(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID chargeId) {
  return ok(finance.issue(account(jwt),tenantId,assurance(jwt),chargeId));
 }
 @PostMapping(BASE+"/charges/issue-pending")
 public ResponseEntity<FinanceModels.IssueResult> issuePending(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(finance.issuePending(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping(BASE+"/charges/{chargeId}/cancel")
 public ResponseEntity<FinanceModels.Charge> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID chargeId,
     @RequestBody FinanceModels.Cancel request) {
  return ok(finance.cancel(account(jwt),tenantId,assurance(jwt),chargeId,request));
 }
 @PostMapping(BASE+"/charges/{chargeId}/sync")
 public ResponseEntity<FinanceModels.Charge> sync(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID chargeId) {
  return ok(finance.sync(account(jwt),tenantId,assurance(jwt),chargeId));
 }
 @GetMapping(BASE+"/recurring-fees")
 public ResponseEntity<List<FinanceModels.RecurringFee>> fees(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(finance.recurringFees(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping(BASE+"/recurring-fees")
 public ResponseEntity<FinanceModels.RecurringFee> createFee(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody FinanceModels.RecurringCreate request) {
  return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(finance.createRecurringFee(account(jwt),tenantId,assurance(jwt),request));
 }
 @PostMapping(BASE+"/recurring-fees/{feeId}/end")
 public ResponseEntity<FinanceModels.RecurringFee> endFee(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID feeId,
     @RequestBody FinanceModels.Reason request) {
  return ok(finance.endRecurringFee(account(jwt),tenantId,assurance(jwt),feeId,request));
 }
 @PostMapping(BASE+"/recurring-fees/generate")
 public ResponseEntity<FinanceModels.GenerateResult> generate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestBody FinanceModels.Generate request) {
  return ok(finance.generate(account(jwt),tenantId,assurance(jwt),request));
 }
 @GetMapping(BASE+"/delinquency")
 public ResponseEntity<FinanceModels.DelinquencyReport> delinquency(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(finance.delinquency(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping(BASE+"/report")
 public ResponseEntity<FinanceModels.Report> report(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam String from,@RequestParam String until) {
  return ok(finance.report(account(jwt),tenantId,assurance(jwt),from,until));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/finance/charges")
 public ResponseEntity<FinanceModels.ChargePage> unitCharges(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId) {
  return ok(finance.unitCharges(account(jwt),tenantId,unitId));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/finance/charges/{chargeId}/payment")
 public ResponseEntity<FinanceModels.PaymentInfo> payment(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @PathVariable UUID chargeId) {
  return ok(finance.paymentInfo(account(jwt),tenantId,unitId,chargeId));
 }
 /** Webhook do Asaas: sem sessão; autenticado pelo cabeçalho asaas-access-token no serviço, antes de qualquer leitura. */
 @PostMapping("/v1/finance/asaas/webhook")
 public ResponseEntity<Map<String,String>> webhook(@RequestHeader(name="asaas-access-token",required=false) String token,
     @RequestBody(required=false) String body) {
  return ok(Map.of("outcome",webhook.handle(token,body).name()));
 }

 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
