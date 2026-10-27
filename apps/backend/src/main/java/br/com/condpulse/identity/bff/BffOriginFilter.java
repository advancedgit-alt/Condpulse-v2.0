package br.com.condpulse.identity.bff;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** Complementa o CSRF do Spring: nunca usa X-Forwarded-* ou Host enviados pelo cliente como origem confiável. */
public final class BffOriginFilter extends OncePerRequestFilter {
 private final BffSettings settings;
 public BffOriginFilter(BffSettings settings) { this.settings=settings; }
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
    throws ServletException,IOException {
  String path=request.getServletPath();
  if(path.startsWith("/auth/")) {
   response.setHeader("Cache-Control","no-store");
   response.setHeader("Pragma","no-cache");
   if(!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
     && !settings.origin().equals(request.getHeader("Origin"))) {
    BffCookieAuthenticationFilter.writeProblem(response,403,"CSRF_ORIGIN_REJECTED"); return;
   }
  }
  chain.doFilter(request,response);
 }
}
