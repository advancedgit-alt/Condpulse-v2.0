package br.com.condpulse.parcels.api;

import java.util.List;
import java.util.UUID;
import br.com.condpulse.identity.application.AssuranceLevel;
import br.com.condpulse.parcels.application.ParcelModels;
import br.com.condpulse.parcels.application.ParcelService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Encomendas (CP-029/CP-030/CP-031), atrás de CP_PARCELS_ENABLED=false. Capacidade e vínculo conferidos no serviço e no banco. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.parcels.enabled",havingValue="true")
public class ParcelController {
 private final ParcelService service;
 public ParcelController(ParcelService service) { this.service=service; }

 @GetMapping("/v1/tenants/{tenantId}/parcels")
 public ResponseEntity<ParcelModels.Page> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestParam(required=false) String status,@RequestParam(required=false) UUID unitId,@RequestParam(required=false) String location) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore())
   .body(service.list(account(jwt),tenantId,assurance(jwt),status,unitId,location));
 }
 @GetMapping("/v1/tenants/{tenantId}/parcels/{parcelId}")
 public ResponseEntity<ParcelModels.Parcel> parcel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.parcel(account(jwt),tenantId,assurance(jwt),parcelId));
 }
 @GetMapping("/v1/tenants/{tenantId}/units/{unitId}/parcels")
 public ResponseEntity<ParcelModels.Page> unitParcels(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID unitId,
     @RequestParam(required=false) String status) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unitParcels(account(jwt),tenantId,unitId,status));
 }
 /** Pessoas com vínculo que podem retirar a encomenda (portaria). */
 @GetMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/recipients")
 public ResponseEntity<List<ParcelModels.Recipient>> recipients(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @PathVariable UUID parcelId) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recipients(account(jwt),tenantId,assurance(jwt),parcelId));
 }
 @PostMapping("/v1/tenants/{tenantId}/parcels")
 public ResponseEntity<ParcelModels.Parcel> receive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody ParcelModels.Receive request) {
  return mutation(service.receive(account(jwt),tenantId,assurance(jwt),key,request));
 }
 @PostMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/handoff")
 public ResponseEntity<ParcelModels.Parcel> deliver(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody ParcelModels.Deliver request) {
  return mutation(service.deliver(account(jwt),tenantId,assurance(jwt),parcelId,key,request));
 }
 /** Registro indevido: anula a encomenda ainda a retirar, pela versão lida e com motivo. */
 @PostMapping("/v1/tenants/{tenantId}/parcels/{parcelId}/void")
 public ResponseEntity<ParcelModels.Parcel> voidParcel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID parcelId,
     @RequestHeader("Idempotency-Key") String key,@RequestBody ParcelModels.VoidRequest request) {
  return mutation(service.voidParcel(account(jwt),tenantId,assurance(jwt),parcelId,key,request));
 }
 private static ResponseEntity<ParcelModels.Parcel> mutation(ParcelModels.Mutation result) {
  return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
   .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.parcel());
 }
 private static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 private static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
