package br.com.condpulse.identity.bff;

import java.util.Map;
import br.com.condpulse.shared.application.BusinessProblem;
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
   if(result==null || result.access_token()==null || result.refresh_token()==null
      || result.access_token().isBlank() || result.refresh_token().isBlank()
      || result.access_token().length()>16384 || result.refresh_token().length()>16384)
    throw new BusinessProblem("AUTH_PROVIDER_UNAVAILABLE",503,"Não foi possível validar o acesso.");
   return result;
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
 public void signOutBestEffort(String accessToken) {
  try {
   client.post().uri(settings.authBase()+"/logout?scope=local")
    .header("apikey",settings.publishableKey()).header("Authorization","Bearer "+accessToken)
    .retrieve().toBodilessEntity();
  } catch(RestClientException ex) {
   // A sessão local já foi removida. Não expor nem repetir indefinidamente credencial.
  }
 }
 public record TokenResponse(String access_token,String refresh_token,long expires_in) {
  @Override public String toString() { return "TokenResponse[REDACTED]"; }
 }
}
