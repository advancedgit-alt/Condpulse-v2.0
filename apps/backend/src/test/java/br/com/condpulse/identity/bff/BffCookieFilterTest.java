package br.com.condpulse.identity.bff;

import java.time.Instant;
import jakarta.servlet.http.Cookie;
import br.com.condpulse.shared.application.BusinessProblem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BffCookieFilterTest {
 private final WebSessionService sessions=mock(WebSessionService.class);
 @AfterEach void clear() { SecurityContextHolder.clearContext(); }
 private MockHttpServletRequest request(String raw) {
  var request=new MockHttpServletRequest("GET","/v1/me"); request.setServletPath("/v1/me");
  request.setCookies(new Cookie(BffCookies.SESSION,raw)); return request;
 }
 @Test void verifiedCookieCreatesAuthenticatedJwtPrincipal() throws Exception {
  String raw=SessionSecrets.newToken();
  var jwt=Jwt.withTokenValue("synthetic-access").header("alg","ES256")
   .subject("11111111-1111-4111-8111-111111111111")
   .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
  when(sessions.authenticate(raw)).thenReturn(jwt);
  new BffCookieAuthenticationFilter(sessions).doFilter(request(raw),new MockHttpServletResponse(),(req,res)->{
   var auth=SecurityContextHolder.getContext().getAuthentication();
   assertNotNull(auth); assertTrue(auth.isAuthenticated()); assertSame(jwt,auth.getPrincipal());
  });
 }
 @Test void bearerHeaderNeverFallsBackToCookieIdentity() throws Exception {
  var request=request(SessionSecrets.newToken()); request.addHeader("Authorization","Bearer synthetic-invalid");
  new BffCookieAuthenticationFilter(sessions).doFilter(request,new MockHttpServletResponse(),(req,res)->
   assertNull(SecurityContextHolder.getContext().getAuthentication()));
  verifyNoInteractions(sessions);
 }
 @Test void expiredAccessPreservesOpaqueCookieForCsrfProtectedRefresh() throws Exception {
  String raw=SessionSecrets.newToken(); when(sessions.authenticate(raw)).thenThrow(new JwtException("synthetic-expired"));
  var response=new MockHttpServletResponse();
  new BffCookieAuthenticationFilter(sessions).doFilter(request(raw),response,(req,res)->fail());
  assertEquals(401,response.getStatus()); assertTrue(response.getContentAsString().contains("SESSION_REFRESH_REQUIRED"));
  assertNull(response.getHeader("Set-Cookie"));
 }
 @Test void revokedAccountDoesNotReachController() throws Exception {
  String raw=SessionSecrets.newToken();
  when(sessions.authenticate(raw)).thenThrow(new BusinessProblem("ACCOUNT_INACTIVE",403,"Conta suspensa."));
  var response=new MockHttpServletResponse();
  new BffCookieAuthenticationFilter(sessions).doFilter(request(raw),response,(req,res)->fail());
  assertEquals(403,response.getStatus()); assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
 }
}
