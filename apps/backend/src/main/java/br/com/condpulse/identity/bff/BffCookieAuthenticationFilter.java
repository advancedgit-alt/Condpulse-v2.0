package br.com.condpulse.identity.bff;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import br.com.condpulse.shared.application.BusinessProblem;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BffCookieAuthenticationFilter extends OncePerRequestFilter {
 private final WebSessionService sessions;
 public BffCookieAuthenticationFilter(WebSessionService sessions) { this.sessions=sessions; }
 @Override protected boolean shouldNotFilter(HttpServletRequest request) {
  return !request.getServletPath().startsWith("/v1/") || request.getHeader("Authorization")!=null;
 }
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
    throws ServletException,IOException {
  try {
   String raw=BffCookies.read(request);
   if(raw!=null) {
    var jwt=sessions.authenticate(raw);
    var context=SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new JwtAuthenticationToken(jwt,java.util.List.of()));
    SecurityContextHolder.setContext(context);
   }
  } catch(BusinessProblem ex) {
   SecurityContextHolder.clearContext(); BffCookies.clear(response);
   writeProblem(response,ex.status(),ex.code()); return;
  } catch(DataAccessException ex) {
   SecurityContextHolder.clearContext();
   writeProblem(response,503,"DEPENDENCY_UNAVAILABLE"); return;
  } catch(JwtException ex) {
   SecurityContextHolder.clearContext();
   // Preservar cookie para POST /auth/refresh; nunca renovar por GET.
   writeProblem(response,401,"SESSION_REFRESH_REQUIRED"); return;
  }
  chain.doFilter(request,response);
 }
 static void writeProblem(HttpServletResponse response,int status,String code) throws IOException {
  response.setStatus(status); response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
  response.setHeader("Cache-Control","no-store");
  response.getWriter().write("""
   {"type":"urn:condpulse:problem:%s","title":"Não foi possível validar a sessão.","status":%d,"code":"%s","traceId":"%s"}
   """.formatted(code,status,code,UUID.randomUUID()));
 }
}
