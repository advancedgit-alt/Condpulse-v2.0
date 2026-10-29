package br.com.condpulse.identity.bff;

import java.time.Duration;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

public final class BffCookies {
 public static final String SESSION="__Host-cp_session";
 /** Cookie próprio da recuperação de acesso: nunca autentica a API e expira com a tentativa. */
 public static final String RECOVERY="__Host-cp_recovery";
 private BffCookies() {}
 public static String read(HttpServletRequest request) {
  return single(request,SESSION,"INVALID_SESSION_COOKIE","Entre novamente para continuar.");
 }
 public static String readRecovery(HttpServletRequest request) {
  return single(request,RECOVERY,"RECOVERY_SESSION_EXPIRED","O prazo para definir a nova senha terminou. Solicite um novo link.");
 }
 private static String single(HttpServletRequest request,String name,String duplicateCode,String message) {
  String result=null;
  if(request.getCookies()==null) return null;
  for(Cookie cookie:request.getCookies()) {
   if(name.equals(cookie.getName())) {
    if(result!=null) throw new BusinessProblem(duplicateCode,401,message);
    result=cookie.getValue();
   }
  }
  return result;
 }
 public static void set(HttpServletResponse response,String value,Duration age) {
  response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(SESSION,value)
   .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(age.isNegative()?Duration.ZERO:age).build().toString());
 }
 public static void clear(HttpServletResponse response) { set(response,"",Duration.ZERO); }
 public static void setRecovery(HttpServletResponse response,String value,Duration age) {
  response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(RECOVERY,value)
   .secure(true).httpOnly(true).sameSite("Strict").path("/").maxAge(age.isNegative()?Duration.ZERO:age).build().toString());
 }
 public static void clearRecovery(HttpServletResponse response) { setRecovery(response,"",Duration.ZERO); }
}
