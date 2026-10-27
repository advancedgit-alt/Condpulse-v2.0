package br.com.condpulse.identity.infrastructure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import static org.junit.jupiter.api.Assertions.*;
class IdentityClaimsValidatorTest {
 private final IdentityClaimsValidator validator=new IdentityClaimsValidator();
 private Jwt token(String subject,List<String> audience,String issuer,Instant start,Instant end) {
  return Jwt.withTokenValue("synthetic-test-token").header("alg","ES256").subject(subject)
   .audience(audience).issuer(issuer).issuedAt(start).expiresAt(end).build();
 }
 private Jwt token(String sub,List<String> aud) {
  return token(sub,aud,"https://auth.example.invalid",Instant.now(),Instant.now().plusSeconds(300));
 }
 @Test void expectedAudienceAndCanonicalSubjectAreAccepted() {
  assertFalse(validator.validate(token("11111111-1111-4111-8111-111111111111",List.of("authenticated"))).hasErrors());
 }
 @Test void differentAudienceIsRejected() {
  assertTrue(validator.validate(token("11111111-1111-4111-8111-111111111111",List.of("other"))).hasErrors());
 }
 @Test void malformedSubjectIsRejected() {
  assertTrue(validator.validate(token("not-an-account",List.of("authenticated"))).hasErrors());
 }
 @Test void abbreviatedUuidIsRejected() {
  assertTrue(validator.validate(token("1-1-1-1-1",List.of("authenticated"))).hasErrors());
 }
 @Test void issuerFromDifferentProjectIsRejected() {
  assertTrue(JwtValidators.createDefaultWithIssuer("https://expected.example.invalid")
   .validate(token("11111111-1111-4111-8111-111111111111",List.of("authenticated"))).hasErrors());
 }
 @Test void expiredTokenIsRejected() {
  var token=token("11111111-1111-4111-8111-111111111111",List.of("authenticated"),
    "https://auth.example.invalid",Instant.now().minusSeconds(3600),Instant.now().minusSeconds(300));
  assertTrue(JwtValidators.createDefaultWithIssuer("https://auth.example.invalid").validate(token).hasErrors());
 }
}
