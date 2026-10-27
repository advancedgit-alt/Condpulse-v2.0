package br.com.condpulse.identity.bff;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import static org.junit.jupiter.api.Assertions.*;

class BffProtocolTest {
 private BffSettings settings() {
  return new BffSettings("https://condpulse.example.invalid","https://auth.example.invalid/auth/v1","synthetic-publishable-key",8);
 }
 private MockHttpServletRequest request(String method,String path) {
  var request=new MockHttpServletRequest(method,path); request.setServletPath(path); return request;
 }
 @Test void originMismatchIsRejectedEvenWhenBearerHeaderIsPresent() throws Exception {
  var request=request("POST","/auth/login");
  request.addHeader("Origin","https://attacker.example.invalid");
  request.addHeader("Authorization","Bearer synthetic");
  var response=new MockHttpServletResponse();
  new BffOriginFilter(settings()).doFilter(request,response,(req,res)->fail("Não deve executar autenticação."));
  assertEquals(403,response.getStatus());
  assertTrue(response.getContentAsString().contains("CSRF_ORIGIN_REJECTED"));
 }
 @Test void missingOriginOnMutationFailsClosed() throws Exception {
  var response=new MockHttpServletResponse();
  new BffOriginFilter(settings()).doFilter(request("POST","/auth/logout"),response,(req,res)->fail());
  assertEquals(403,response.getStatus());
 }
 @Test void csrfCookieIsSecureHttpOnlyAndStateChangeRequiresMatchingToken() throws Exception {
  var repository=new BffConfiguration().bffCsrfRepository();
  var filter=new CsrfFilter(repository);
  filter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
  var token=new AtomicReference<CsrfToken>();
  var get=request("GET","/auth/csrf");
  var getResponse=new MockHttpServletResponse();
  filter.doFilter(get,getResponse,(req,res)->{
   var value=(CsrfToken)req.getAttribute(CsrfToken.class.getName());
   value.getToken(); token.set(value);
  });
  String cookie=getResponse.getHeader("Set-Cookie");
  assertNotNull(cookie);
  assertTrue(cookie.contains("__Host-cp_csrf="));
  assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("HttpOnly"));
  // MockHttpServletResponse não serializa SameSite de Cookie Servlet comum no header.
  assertEquals("Lax",getResponse.getCookie("__Host-cp_csrf").getAttribute("SameSite"));
  assertTrue(cookie.contains("Path=/"));
  assertFalse(cookie.contains("Domain="));
  String raw=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
  var rejected=request("POST","/auth/logout"); rejected.setCookies(new Cookie("__Host-cp_csrf",raw));
  var rejectedResponse=new MockHttpServletResponse();
  filter.doFilter(rejected,rejectedResponse,(req,res)->fail("CSRF ausente não deve passar."));
  assertEquals(403,rejectedResponse.getStatus());
  var accepted=request("POST","/auth/logout"); accepted.setCookies(new Cookie("__Host-cp_csrf",raw));
  accepted.addHeader(token.get().getHeaderName(),token.get().getToken());
  var acceptedResponse=new MockHttpServletResponse();
  var invoked=new boolean[1];
  filter.doFilter(accepted,acceptedResponse,(req,res)->invoked[0]=true);
  assertTrue(invoked[0]);
 }
 @Test void sessionCookieHasNoDomainAndExpiresExplicitly() {
  var response=new MockHttpServletResponse();
  BffCookies.set(response,SessionSecrets.newToken(),Duration.ofHours(8));
  String cookie=response.getHeader("Set-Cookie");
  assertTrue(cookie.startsWith("__Host-cp_session=")); assertTrue(cookie.contains("HttpOnly"));
  assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("SameSite=Lax"));
  assertFalse(cookie.contains("Domain="));
  var cleared=new MockHttpServletResponse(); BffCookies.clear(cleared);
  assertTrue(cleared.getHeader("Set-Cookie").contains("Max-Age=0"));
 }
 @Test void opaqueTokensAreRandomAndOnlyTheirHashIsStored() {
  String first=SessionSecrets.newToken(),second=SessionSecrets.newToken();
  assertEquals(43,first.length()); assertNotEquals(first,second);
  assertEquals(64,SessionSecrets.hash(first).length());
  assertNotEquals(first,SessionSecrets.hash(first));
  assertThrows(IllegalArgumentException.class,()->SessionSecrets.hash("invalid"));
 }
}
