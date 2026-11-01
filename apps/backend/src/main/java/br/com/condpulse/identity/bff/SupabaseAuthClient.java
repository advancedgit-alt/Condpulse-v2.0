package br.com.condpulse.identity.bff;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
   return new TotpEnrollment(requireUuid(result.id()),uri,secret,qrDataUri(qr));
  } catch(RestClientResponseException ex) { throw mfaProblem(ex); }
  catch(RestClientException ex) { throw unavailable(); }
 }
 /** O GoTrue gera um retângulo por módulo do QR (centenas de KiB); acima deste limite o QR é omitido e o segredo continua disponível. */
 static final int MAX_QR_SVG_BYTES=524_288;
 private static final String BASE64_PREFIX="data:image/svg+xml;base64,";
 /**
  * O GoTrue devolve o SVG cru em totp.qr_code (o SDK JS é que prefixa "data:image/svg+xml;utf-8,"). Aceita SVG cru ou já em
  * data URI e normaliza para base64, que funciona em img sem depender de escapar "#", espaços e quebras de linha. O QR só é
  * exibido como imagem (sem execução de script); conteúdo que não pareça um documento SVG é omitido.
  */
 static String qrDataUri(String qr) {
  if(qr==null || qr.length()>MAX_QR_SVG_BYTES*2) return null;
  String svg;
  if(qr.startsWith(BASE64_PREFIX)) {
   try { svg=new String(Base64.getDecoder().decode(qr.substring(BASE64_PREFIX.length())),StandardCharsets.UTF_8); }
   catch(IllegalArgumentException invalid) { return null; }
  } else if(qr.startsWith("data:image/svg+xml,") || qr.startsWith("data:image/svg+xml;utf-8,")
     || qr.startsWith("data:image/svg+xml;utf8,") || qr.startsWith("data:image/svg+xml;charset=utf-8,")) {
   svg=qr.substring(qr.indexOf(',')+1);
   if(svg.regionMatches(true,0,"%3C",0,3)) {
    try { svg=URLDecoder.decode(svg.replace("+","%2B"),StandardCharsets.UTF_8); }
    catch(IllegalArgumentException invalid) { return null; }
   }
  } else if(qr.startsWith("data:")) return null;
  else svg=qr;
  svg=svg.strip();
  if(svg.isEmpty() || !svg.startsWith("<") || !svg.endsWith(">") || !svg.contains("<svg")) return null;
  byte[] bytes=svg.getBytes(StandardCharsets.UTF_8);
  return bytes.length>MAX_QR_SVG_BYTES?null:BASE64_PREFIX+Base64.getEncoder().encodeToString(bytes);
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
  // O provedor usa 403 também para sessão encerrada. Não é erro de código TOTP:
  // invalida o contexto local e pede login novo, sem repetir o desafio com o mesmo token.
  if((status==400 || status==401 || status==403 || status==422) && providerSessionEnded(ex))
   return new BusinessProblem("SESSION_EXPIRED",401,"A sessão de autenticação terminou. Entre novamente para continuar.");
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

 private static boolean providerSessionEnded(RestClientResponseException ex) {
  try {
   ProviderError body=ex.getResponseBodyAs(ProviderError.class);
   if(body==null) return false;
   return endedSessionCode(body.error_code()) || endedSessionCode(body.code());
  } catch(RuntimeException invalidBody) {
   // Corpo ausente, inválido ou incompatível mantém a classificação pelo HTTP; nunca expor detalhes.
   return false;
  }
 }
 private static boolean endedSessionCode(String code) {
  return "session_not_found".equals(code) || "session_expired".equals(code);
 }
 @JsonIgnoreProperties(ignoreUnknown=true)
 public record ProviderError(String code,String error_code) {
  @Override public String toString() { return "ProviderError[REDACTED]"; }
 }

 /**
  * Fatores da conta para a recuperação: o TOTP verificado (se houver) e se existe fator verificado de outro tipo
  * (telefone, webauthn), que a web não sabe exigir. A conta devolvida pelo provedor é conferida com o token.
  */
 public RecoveryFactors recoveryFactors(String accessToken) {
  try {
   UserResponse user=client.get().uri(settings.authBase()+"/user")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .retrieve().body(UserResponse.class);
   if(user==null || (user.factors()!=null && user.factors().size()>20)) throw unavailable();
   UUID account=requireUuid(user.id());
   UUID totp=null; boolean other=false;
   for(var factor:user.factors()==null?List.<FactorResponse>of():user.factors()) {
    if(factor==null) throw unavailable();
    if(!"verified".equals(factor.status())) continue;
    if("totp".equals(factor.factor_type())) { if(totp==null) totp=requireUuid(factor.id()); }
    else other=true;
   }
   return new RecoveryFactors(account,totp,other);
  } catch(RestClientResponseException ex) { throw mfaProblem(ex); }
  catch(RestClientException ex) { throw unavailable(); }
 }
 public record RecoveryFactors(UUID accountId,UUID verifiedTotp,boolean otherVerified) {}
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
 /** Resultado do pedido de e-mail de recuperação, usado só para decidir a resposta neutra. */
 public enum RecoveryDispatch { ACCEPTED, REFUSED, RATE_LIMITED, UNAVAILABLE }
 /**
  * Pede ao provedor o e-mail de recuperação. Nunca lança por resposta do provedor: conta inexistente, envio
  * recusado ou limite do provedor não podem mudar a resposta devolvida ao navegador.
  */
 public RecoveryDispatch recover(String email) {
  try {
   client.post().uri(settings.authBase()+"/recover").header("apikey",settings.publishableKey())
    .contentType(MediaType.APPLICATION_JSON).body(Map.of("email",email)).retrieve().toBodilessEntity();
   return RecoveryDispatch.ACCEPTED;
  } catch(RestClientResponseException ex) {
   int status=ex.getStatusCode().value();
   if(status==429) return RecoveryDispatch.RATE_LIMITED;
   return status>=500?RecoveryDispatch.UNAVAILABLE:RecoveryDispatch.REFUSED;
  } catch(RestClientException ex) { return RecoveryDispatch.UNAVAILABLE; }
 }
 /** Troca o hash do link de recuperação por uma sessão do provedor (uso único no provedor). */
 public TokenResponse verifyRecovery(String tokenHash) {
  try {
   TokenResponse result=client.post().uri(settings.authBase()+"/verify").header("apikey",settings.publishableKey())
    .contentType(MediaType.APPLICATION_JSON).body(Map.of("type","recovery","token_hash",tokenHash))
    .retrieve().body(TokenResponse.class);
   return requireTokens(result);
  } catch(RestClientResponseException ex) {
   int status=ex.getStatusCode().value();
   if(status==429) throw new BusinessProblem("AUTH_RATE_LIMITED",429,"Aguarde antes de tentar novamente.");
   if(status>=400 && status<500)
    throw new BusinessProblem("RECOVERY_LINK_INVALID",400,"Link de recuperação inválido, expirado ou já utilizado. Solicite um novo link.");
   throw unavailable();
  } catch(RestClientException ex) { throw unavailable(); }
 }
 /**
  * Define a nova senha com a sessão de recuperação. Só 200 com a mesma conta confirma a troca; qualquer outra
  * resposta sem erro (2xx diferente, 3xx) é resultado incerto. Recusa de política é distinguida de resultado incerto.
  */
 public void updatePassword(String accessToken,String password,UUID expectedAccount) {
  try {
   var response=client.put().uri(settings.authBase()+"/user").header("apikey",settings.publishableKey())
    .header("Authorization","Bearer "+accessToken).contentType(MediaType.APPLICATION_JSON)
    .body(Map.of("password",password)).retrieve().toEntity(UserResponse.class);
   if(response.getStatusCode().value()!=200 || response.getBody()==null || response.getBody().id()==null
      || !expectedAccount.toString().equalsIgnoreCase(response.getBody().id()))
    throw unavailable();
  } catch(RestClientResponseException ex) {
   int status=ex.getStatusCode().value();
   if(status==400 || status==422)
    throw new BusinessProblem("PASSWORD_REJECTED",422,"A senha não foi aceita. Use uma senha diferente da anterior e mais difícil de adivinhar.");
   if(status==401)
    throw new BusinessProblem("RECOVERY_SESSION_EXPIRED",401,"O prazo para definir a nova senha terminou. Solicite um novo link.");
   if(status==403)
    throw new BusinessProblem("RECOVERY_NOT_ALLOWED",403,"A troca de senha por este link não foi permitida. Solicite um novo link ou procure a gestão.");
   if(status==429) throw new BusinessProblem("AUTH_RATE_LIMITED",429,"Aguarde antes de tentar novamente.");
   throw unavailable();
  } catch(RestClientException ex) { throw unavailable(); }
 }
 /** Encerra todas as sessões do usuário no provedor. Devolve se o provedor confirmou o encerramento. */
 public boolean signOutGlobal(String accessToken) {
  try {
   int status=client.post().uri(settings.authBase()+"/logout?scope=global")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .retrieve().toBodilessEntity().getStatusCode().value();
   return status==204 || status==200;
  } catch(RestClientException ex) { return false; }
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
 public record UserResponse(String id,List<FactorResponse> factors) {}
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
