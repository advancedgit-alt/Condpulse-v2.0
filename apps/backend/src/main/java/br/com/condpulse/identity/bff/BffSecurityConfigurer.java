package br.com.condpulse.identity.bff;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** Filtros pertencem apenas à cadeia Spring Security, não são registrados como filtros Servlet globais. */
public final class BffSecurityConfigurer {
 private final BffOriginFilter origin;
 private final BffCookieAuthenticationFilter cookies;
 private final CsrfTokenRepository csrf;
 public BffSecurityConfigurer(WebSessionService sessions,BffSettings settings,CsrfTokenRepository csrf) {
  origin=new BffOriginFilter(settings); cookies=new BffCookieAuthenticationFilter(sessions); this.csrf=csrf;
 }
 public void configure(HttpSecurity http) throws Exception {
  http.csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
  http.addFilterBefore(origin,CsrfFilter.class);
  http.addFilterBefore(cookies,BearerTokenAuthenticationFilter.class);
 }
}
