package br.com.condpulse.identity.bff;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import br.com.condpulse.shared.application.BusinessProblem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class SupabaseAuthClient {
 private final RestClient client;
 private final BffSettings settings;
 public SupabaseAuthClient(RestClient client,BffSettings settings) { this.client=client; this.settings=settings; }
 public TokenResponse password(String email,String password) {
  return exchange("password",Map.of("email",email,"password",password));
 }
 public TokenResponse refresh(String token) { return exchange("refresh_token",Map.of("refresh_token",token)); }
 private TokenResponse exchange(String grant,Map<String,String> body) {
  try {
   TokenResponse result=client.post().uri(settings.authBase()+"/token?grant_type="+grant)
    .header("apikey",settings.publishableKey()).contentType(MediaType.APPLICATION_JSON)
    .body(body).retrieve().body(TokenResponse.class);
   return requireTokens(result);
  } catch(RestClientResponseException ex) {
   int status=ex.getStatusCode().value();
   if(status==400 || status==401 || status==403 || status==422)
    throw new BusinessProblem("AUTH_CREDENTIALS_REJECTED",401,"Não foi possível validar o acesso.");
   if(status==429)
    throw new BusinessProblem("AUTH_RATE_LIMITED",429,"Aguarde antes de tentar novamente.");
   throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Autenticação temporariamente indisponível.");
  } catch(RestClientException ex) {
   // Nunca propagar corpo/headers/credenciais do provedor.
   throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Autenticação temporariamente indisponível.");
  }
 }
 private static TokenResponse requireTokens(TokenResponse result) {
  if(result==null || result.access_token()==null || result.refresh_token()==null
     || result.access_token().isBlank() || result.refresh_token().isBlank()
     || result.access_token().length()>16384 || result.refresh_token().length()>16384)
   throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Não foi possível validar o acesso.");
  return result;
 }

 /** Fatores TOTP do próprio usuário. Outros tipos de fator não são oferecidos pelo CondPulse. */
 public List<Factor> totpFactors(String accessToken) {
  try {
   UserResponse user=client.get().uri(settings.authBase()+"/user")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .retrieve().body(UserResponse.class);
   if(user==null) throw unavailable();
   if(user.factors()==null) return List.of();
   if(user.factors().size()>20) throw unavailable();
   return user.factors().stream().filter(f->f!=null && "totp".equals(f.factor_type()))
    .map(f->new Factor(requireUuid(f.id()),"verified".equals(f.status())?"VERIFIED":"UNVERIFIED",
      f.friendly_name()==null?null:f.friendly_name().substring(0,Math.min(80,f.friendly_name().length()))))
    .toList();
  } catch(RestClientResponseException ex) { throw mfaProblem(ex); }
  catch(RestClientException ex) { throw unavailable(); }
 }
 public TotpEnrollment enrollTotp(String accessToken) {
  try {
   String friendly="CondPulse "+UUID.randomUUID().toString().substring(0,8);
   EnrollResponse result=client.post().uri(settings.authBase()+"/factors")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .contentType(MediaType.APPLICATION_JSON)
    .body(Map.of("factor_type","totp","friendly_name",friendly,"issuer","CondPulse"))
    .retrieve().body(EnrollResponse.class);
   if(result==null || result.totp()==null || !"totp".equals(result.type())) throw unavailable();
   String uri=result.totp().uri(),secret=result.totp().secret(),qr=result.totp().qr_code();
   if(uri==null || !uri.startsWith("otpauth://totp/") || uri.length()>2048
      || secret==null || !secret.matches("[A-Z2-7]{16,128}=*"))
    throw unavailable();
   String safeQr=qr!=null && qr.startsWith("data:image/svg+xml") && qr.length()<=65536?qr:null;
   return new TotpEnrollment(requireUuid(result.id()),uri,secret,safeQr);
  } catch(RestClientResponseException ex) { throw mfaProblem(ex); }
  catch(RestClientException ex) { throw unavailable(); }
 }
 /** Desafio e verificação no mesmo comando; código incorreto não altera a sessão local. */
 public TokenResponse verifyTotp(String accessToken,UUID factorId,String code) {
  try {
   ChallengeResponse challenge=client.post().uri(settings.authBase()+"/factors/"+factorId+"/challenge")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(ChallengeResponse.class);
   if(challenge==null) throw unavailable();
   UUID challengeId=requireUuid(challenge.id());
   TokenResponse result=client.post().uri(settings.authBase()+"/factors/"+factorId+"/verify")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .contentType(MediaType.APPLICATION_JSON)
    .body(Map.of("challenge_id",challengeId.toString(),"code",code)).retrieve().body(TokenResponse.class);
   return requireTokens(result);
  } catch(RestClientResponseException ex) { throw mfaProblem(ex); }
  catch(RestClientException ex) { throw unavailable(); }
 }
 private static BusinessProblem mfaProblem(RestClientResponseException ex) {
  int status=ex.getStatusCode().value();
  if(status==400 || status==422)
   return new BusinessProblem("MFA_CODE_REJECTED",422,"Código inválido, expirado ou fator não disponível.");
  if(status==401)
   return new BusinessProblem("SESSION_REFRESH_REQUIRED",401,"Renove a sessão para continuar.");
  if(status==403)
   return new BusinessProblem("MFA_OPERATION_REJECTED",403,"O provedor não permitiu esta operação de segundo fator.");
  if(status==404)
   return new BusinessProblem("MFA_FACTOR_NOT_FOUND",404,"Fator de autenticação não encontrado.");
  if(status==429)
   return new BusinessProblem("AUTH_RATE_LIMITED",429,"Aguarde antes de tentar novamente.");
  return unavailable();
 }
 private static BusinessProblem unavailable() {
  return new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Autenticação temporariamente indisponível.");
 }
 private static UUID requireUuid(String value) {
  try {
   UUID parsed=UUID.fromString(value);
   if(!parsed.toString().equalsIgnoreCase(value)) throw unavailable();
   return parsed;
  } catch(IllegalArgumentException | NullPointerException ex) { throw unavailable(); }
 }
 public void signOutBestEffort(String accessToken) {
  try {
   client.post().uri(settings.authBase()+"/logout?scope=local")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .retrieve().toBodilessEntity();
  } catch(RestClientException ex) {
   // A sessão local já foi removida. Não expor nem repetir indefinidamente credencial.
  }
 }
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record TokenResponse(String access_token,String refresh_token,long expires_in) {
  @Override public String toString() { return "TokenResponse[REDACTED]"; }
 }
 public record Factor(UUID factorId,String status,String friendlyName) {}
 public record TotpEnrollment(UUID factorId,String otpauthUri,String secret,String qrCodeDataUri) {
  @Override public String toString() { return "TotpEnrollment[factorId="+factorId+",secret=REDACTED]"; }
 }
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record UserResponse(List<FactorResponse> factors) {}
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record FactorResponse(String id,String factor_type,String status,String friendly_name) {}
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record EnrollResponse(String id,String type,TotpResponse totp) {}
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record TotpResponse(String qr_code,String secret,String uri) {
  @Override public String toString() { return "TotpResponse[REDACTED]"; }
 }
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record ChallengeResponse(String id) {}
}
