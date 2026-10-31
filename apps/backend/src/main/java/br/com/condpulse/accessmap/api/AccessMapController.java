package br.com.condpulse.accessmap.api;

import java.util.UUID;
import br.com.condpulse.accessmap.application.MapModels;
import br.com.condpulse.accessmap.application.MapService;
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
import org.springframework.web.bind.annotation.RestController;

/** Áreas (gestão) e pontos físicos (MASTER de implantação) — AC-004, V041. */
@RestController @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessMapController {
 private final MapService map;
 public AccessMapController(MapService map) { this.map=map; }

 @GetMapping("/v1/tenants/{tenantId}/management/access-areas")
 public ResponseEntity<MapModels.AreaPage> areas(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(map.areas(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/management/access-areas")
 public ResponseEntity<MapModels.Area> createArea(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@RequestBody MapModels.CreateArea request) {
  return ok(map.createArea(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/management/access-areas/{areaId}")
 public ResponseEntity<MapModels.Area> updateArea(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID areaId,
   @RequestBody MapModels.UpdateArea request) {
  return ok(map.updateArea(account(jwt),tenantId,assurance(jwt),areaId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/management/access-points")
 public ResponseEntity<MapModels.PointSummaryPage> pointSummaries(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(map.pointSummaries(account(jwt),tenantId,assurance(jwt)));
 }
 @GetMapping("/v1/tenants/{tenantId}/technical/access-areas")
 public ResponseEntity<MapModels.AreaPage> technicalAreas(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(map.technicalAreas(account(jwt),tenantId,assurance(jwt)));
 }
 @PutMapping("/v1/tenants/{tenantId}/technical/access-areas/{areaId}/physical")
 public ResponseEntity<MapModels.Area> physical(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID areaId,
   @RequestBody MapModels.AreaPhysical request) {
  return ok(map.setPhysical(account(jwt),tenantId,assurance(jwt),areaId,request));
 }
 @GetMapping("/v1/tenants/{tenantId}/technical/access-points")
 public ResponseEntity<MapModels.PointPage> technicalPoints(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
  return ok(map.technicalPoints(account(jwt),tenantId,assurance(jwt)));
 }
 @PostMapping("/v1/tenants/{tenantId}/technical/access-points")
 public ResponseEntity<MapModels.Point> createPoint(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@RequestBody MapModels.CreatePoint request) {
  return ok(map.createPoint(account(jwt),tenantId,assurance(jwt),request));
 }
 @PutMapping("/v1/tenants/{tenantId}/technical/access-points/{pointId}")
 public ResponseEntity<MapModels.Point> updatePoint(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID pointId,
   @RequestBody MapModels.UpdatePoint request) {
  return ok(map.updatePoint(account(jwt),tenantId,assurance(jwt),pointId,request));
 }
 private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
 static UUID account(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
 static AssuranceLevel assurance(Jwt jwt) { return AssuranceLevel.fromClaim(jwt.getClaims().get("aal")); }
}
