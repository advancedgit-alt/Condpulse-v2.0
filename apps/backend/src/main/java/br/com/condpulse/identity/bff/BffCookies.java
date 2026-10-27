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
 private BffCookies() {}
 public static String read(HttpServletRequest request) {
  String result=null;
  if(request.getCookies()==null) return null;
  for(Cookie cookie:request.getCookies()) {
   if(SESSION.equals(cookie.getName())) {
    if(result!=null) throw new BusinessProblem("INVALID_SESSION_COOKIE",401,"Entre novamente para continuar.");
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
}
