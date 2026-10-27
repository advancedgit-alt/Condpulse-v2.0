package br.com.condpulse.identity.infrastructure;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class IdentityClaimsValidator implements OAuth2TokenValidator<Jwt> {
 @Override public OAuth2TokenValidatorResult validate(Jwt token) {
  if(token.getAudience()==null || !token.getAudience().contains("authenticated") || !validSubject(token.getSubject())
    || token.getExpiresAt()==null || token.getIssuedAt()==null) {
   return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Identidade inválida.",null));
  }
  return OAuth2TokenValidatorResult.success();
 }
 private boolean validSubject(String subject) {
  if(subject==null) return false;
  try { return UUID.fromString(subject).toString().equalsIgnoreCase(subject); }
  catch(IllegalArgumentException ex) { return false; }
 }
}

