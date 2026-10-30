package br.com.condpulse.identity.infrastructure;
import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jakarta.servlet.DispatcherType;
import br.com.condpulse.identity.bff.BffSecurityConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration @Profile("api")
public class SecurityConfiguration {
 @Bean JwtDecoder jwtDecoder(@Value("${condpulse.auth.issuer}") String issuer,
    @Value("${condpulse.auth.jwk-set-uri}") String jwkSetUri) {
  requireHttps(issuer); requireHttps(jwkSetUri);
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
    .jwsAlgorithm(SignatureAlgorithm.ES256).jwsAlgorithm(SignatureAlgorithm.RS256).build();
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
    JwtValidators.createDefaultWithIssuer(issuer),new IdentityClaimsValidator()));
  return decoder;
 }
 private void requireHttps(String value) {
  URI uri=URI.create(value);
  if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
      || uri.getQuery()!=null || uri.getFragment()!=null)
   throw new IllegalArgumentException("Auth exige endpoint HTTPS válido.");
 }
 private void problem(HttpServletResponse response,int status) throws IOException {
  String code=status==401?"AUTHENTICATION_REQUIRED":"ACTION_FORBIDDEN";
  response.setStatus(status);
  response.setContentType("application/problem+json");
  response.setCharacterEncoding("UTF-8");
  if(status==401) response.setHeader("WWW-Authenticate","Bearer");
  response.getWriter().write("""
   {"type":"urn:condpulse:problem:%s","title":"Acesso não autorizado.","status":%d,"code":"%s","traceId":"%s"}
   """.formatted(code,status,code,UUID.randomUUID()));
 }
 @Bean SecurityFilterChain apiSecurity(HttpSecurity http,ObjectProvider<BffSecurityConfigurer> bff) throws Exception {
  var configurer=bff.getIfAvailable();
  if(configurer!=null) configurer.configure(http);
  // BFF é opcional e permanece desligado até configurar/homologar sua origem e segredos.
  return http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .requestCache(c->c.disable())
   .authorizeHttpRequests(a->a
    // Despacho assíncrono só existe depois de uma requisição já autorizada (canal SSE): não autenticar de novo nele.
    .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
    .requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
    .requestMatchers("/v1/me").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units","/v1/tenants/*/amenities",
      "/v1/tenants/*/amenities/*/availability","/v1/tenants/*/reservations").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/reservations","/v1/tenants/*/reservations/*/cancel").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units/*/waitlist").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/waitlist","/v1/tenants/*/waitlist/*/leave","/v1/tenants/*/waitlist/*/accept").authenticated()
    // Gestão local: autenticação aqui; papel, vínculo vigente e AAL2 são exigidos no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/blocks","/v1/tenants/*/management/units",
      "/v1/tenants/*/management/amenities","/v1/tenants/*/management/audit-events",
      "/v1/tenants/*/management/memberships","/v1/tenants/*/management/invitations",
      "/v1/tenants/*/management/reservations","/v1/tenants/*/management/waitlist").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/blocks","/v1/tenants/*/management/units",
      "/v1/tenants/*/management/amenities","/v1/tenants/*/management/memberships/*/decision",
      "/v1/tenants/*/management/memberships/*/revoke","/v1/tenants/*/management/invitations",
      "/v1/tenants/*/management/invitations/*/revoke","/v1/invitations/accept",
      "/v1/tenants/*/management/reservations/*/decision","/v1/tenants/*/management/reservations/*/cancel",
      "/v1/tenants/*/management/amenities/*/maintenance-blocks").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/units/*","/v1/tenants/*/management/amenities/*").authenticated()
    // Encomendas: autenticação aqui; capacidade da portaria (AAL2) ou vínculo residencial no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/parcels","/v1/tenants/*/parcels/*","/v1/tenants/*/units/*/parcels",
      "/v1/tenants/*/concierge/units","/v1/tenants/*/parcels/*/recipients","/v1/tenants/*/parcels/*/delegation").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/parcels","/v1/tenants/*/parcels/*/handoff","/v1/tenants/*/parcels/*/void",
      "/v1/tenants/*/parcels/*/delegation","/v1/tenants/*/parcels/*/delegation/revoke","/v1/tenants/*/parcels/*/handoff-code").authenticated()
    // Visitas e chegadas: autenticação aqui; vínculo da unidade (com permissão do dependente) ou capacidade da
    // portaria (AAL2) no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units/*/permits","/v1/tenants/*/units/*/arrivals","/v1/tenants/*/concierge/permits",
      "/v1/tenants/*/reservations/*/guests","/v1/tenants/*/units/*/visit-rules",
      "/v1/tenants/*/arrivals").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/units/*/permits","/v1/tenants/*/permits/*/revoke",
      "/v1/tenants/*/permits/*/link","/v1/tenants/*/permits/*/link/revoke","/v1/tenants/*/reservations/*/guests",
      "/v1/tenants/*/units/*/visit-rules","/v1/tenants/*/visit-rules/*/revoke","/v1/tenants/*/arrivals",
      "/v1/tenants/*/arrivals/*/decision","/v1/tenants/*/arrivals/*/assisted-decision","/v1/tenants/*/arrivals/*/cancel").authenticated()
    // Registro manual de entrada e saída: capacidade da portaria (AAL2) ou vínculo da unidade no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/access-events","/v1/tenants/*/units/*/access-events").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/access-events/entries","/v1/tenants/*/access-events/*/exit",
      "/v1/tenants/*/access-events/*/correction").authenticated()
    // Avisos da gestão: autenticação aqui; capacidade com AAL2 (gestão) ou público e vínculo (unidade) no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/announcements","/v1/tenants/*/units/*/announcements").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/announcements","/v1/tenants/*/management/announcements/*/publish",
      "/v1/tenants/*/management/announcements/*/archive","/v1/tenants/*/announcements/*/open").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/announcements/*").authenticated()
    // Anexos privados de pedido: autenticação aqui; visibilidade do pedido no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/requests/*/attachments").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/uploads/intents","/v1/tenants/*/attachments/*/complete",
      "/v1/tenants/*/attachments/*/download").authenticated()
    // Importação de unidades em staging: autenticação aqui; units.manage com AAL2 no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/imports","/v1/tenants/*/management/imports/*").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/imports","/v1/tenants/*/management/imports/*/approve",
      "/v1/tenants/*/management/imports/*/discard").authenticated()
    // Pedidos da unidade: autenticação aqui; vínculo da unidade ou capacidade com AAL2 (gestão) no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units/*/requests","/v1/tenants/*/requests/*","/v1/tenants/*/management/requests",
      "/v1/tenants/*/management/requests/*","/v1/tenants/*/requests/*/messages","/v1/tenants/*/management/requests/*/messages").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/units/*/requests","/v1/tenants/*/requests/*/messages",
      "/v1/tenants/*/management/requests/*/messages","/v1/tenants/*/management/requests/*/state").authenticated()
    // Mudança e entrega de móveis (CP-042): vínculo adulto da unidade, gestão (AAL2) ou portaria (AAL2) no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/move-rules","/v1/tenants/*/units/*/moves","/v1/tenants/*/management/moves",
      "/v1/tenants/*/concierge/moves").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/move-rules").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/units/*/moves","/v1/tenants/*/moves/*/cancel",
      "/v1/tenants/*/management/moves/*/decision").authenticated()
    // Credenciais de acesso (CP-046): gestão (AAL2), portaria (AAL2) ou o próprio vínculo no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/access-connectors","/v1/tenants/*/management/access-credentials",
      "/v1/tenants/*/concierge/pending-revocations","/v1/tenants/*/units/*/access-credentials","/v1/tenants/*/units/*/device-events",
      "/v1/tenants/*/concierge/device-events","/v1/tenants/*/access-health").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/concierge/occurrences").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/concierge/occurrences","/v1/tenants/*/concierge/occurrences/*/resolve").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/access-credentials",
      "/v1/tenants/*/management/access-credentials/*/revoke").authenticated()
    // Caixa interna da unidade: autenticação aqui; somente os próprios avisos, com vínculo vigente, no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units/*/notifications").authenticated()
    // Canal em tempo real da unidade (CP-036): autenticação aqui; vínculo residencial vigente no serviço e no banco, a cada passada.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units/*/events").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/notifications/*/open").authenticated()
    // Painel master: autenticação aqui; papel de plataforma vigente e AAL2 são exigidos no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/platform/session","/v1/platform/tenants","/v1/platform/audit-events").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/platform/outbox","/v1/platform/outbox/tasks").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/platform/outbox/tasks/*/replay").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/platform/tenants/*").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/platform/billing/plans","/v1/platform/billing/subscriptions",
      "/v1/platform/billing/charges").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/platform/billing/plans","/v1/platform/billing/subscriptions",
      "/v1/platform/billing/subscriptions/*/end","/v1/platform/billing/subscriptions/*/charges",
      "/v1/platform/billing/charges/*/cancel","/v1/platform/billing/charges/*/payments",
      "/v1/platform/billing/payments/*/void").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/platform/billing/plans/*").authenticated()
    .requestMatchers(HttpMethod.GET,"/auth/csrf","/auth/mfa").permitAll()
    .requestMatchers(HttpMethod.POST,"/auth/login","/auth/refresh","/auth/logout","/auth/mfa/enroll","/auth/mfa/verify",
      "/auth/recovery","/auth/recovery/verify","/auth/recovery/complete","/auth/invitation/accept",
      "/auth/permit-links/view").permitAll()
    .anyRequest().denyAll())
   .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())
    .authenticationEntryPoint((req,res,ex)->problem(res,401))
    .accessDeniedHandler((req,res,ex)->problem(res,403)))
   .exceptionHandling(e->e
    .authenticationEntryPoint((req,res,ex)->problem(res,401))
    .accessDeniedHandler((req,res,ex)->problem(res,403)))
   .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
   .build();
 }
}

