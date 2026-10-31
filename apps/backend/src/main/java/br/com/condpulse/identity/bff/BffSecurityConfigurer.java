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
  // O webhook do Asaas e as rotas do gateway (V048) não usam cookie de sessão (autenticação pelo cabeçalho): fora do CSRF.
  http.csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
   .ignoringRequestMatchers("/v1/finance/asaas/webhook","/v1/edge/identity","/v1/edge/sync-commands/lease","/v1/edge/sync-commands/*/result"));
  http.addFilterBefore(origin,CsrfFilter.class);
  http.addFilterBefore(cookies,BearerTokenAuthenticationFilter.class);
 }
}
