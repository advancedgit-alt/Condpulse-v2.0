package br.com.condpulse.identity.api;
import java.util.UUID;
import br.com.condpulse.identity.application.CurrentIdentity;
import br.com.condpulse.identity.application.CurrentIdentityQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("api")
public class CurrentIdentityController {
 private final CurrentIdentityQuery query;
 public CurrentIdentityController(CurrentIdentityQuery query) { this.query=query; }
 @GetMapping("/v1/me") public CurrentIdentity me(@AuthenticationPrincipal Jwt jwt) {
  return query.execute(UUID.fromString(jwt.getSubject()));
 }
}
