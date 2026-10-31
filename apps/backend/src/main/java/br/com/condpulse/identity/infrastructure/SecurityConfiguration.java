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
    // Despacho de erro do contêiner (ex.: 404 de módulo desligado) não carrega o cookie autenticado: negar aqui
    // transformava o 404 em 401 e o navegador encerrava a sessão. O despacho só relata o erro já decidido.
    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
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
    // Zeladoria (V045): maintenance.operate e AAL2 no serviço; pedido atribuído ao próprio vínculo no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/maintenance/requests","/v1/tenants/*/maintenance/requests/*").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/maintenance/requests/*/state","/v1/tenants/*/maintenance/requests/*/messages").authenticated()
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
    // Acesso facial (AC-003, V037): plataforma (technical_grants.manage, AAL2), concessão técnica vigente (AAL2) ou gestão
    // (memberships.manage, AAL2), no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/platform/tenants/*/technical-grants","/v1/tenants/*/technical/session",
      "/v1/tenants/*/management/technical-grants").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/platform/tenants/*/technical-grants","/v1/platform/tenants/*/technical-grants/*/revoke").authenticated()
    // Sujeitos de acesso (AC-005, V038): gestão (memberships.manage, AAL2) ou portaria (concierge.operate, AAL2).
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/access-subjects").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/access-subjects/materialize","/v1/tenants/*/concierge/access-subjects/materialize",
      "/v1/tenants/*/management/access-subjects/*/block","/v1/tenants/*/management/access-subjects/*/unblock",
      "/v1/tenants/*/management/access-subjects/*/functional-authorizations","/v1/tenants/*/management/subject-contexts/*/revoke",
      "/v1/tenants/*/management/subject-contexts/*/reauthorize").authenticated()
    // Parâmetros do acesso facial e evidência de idade (AC-006, V039): gestão (memberships.manage, AAL2).
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/facial-settings","/v1/tenants/*/management/access-subjects/*/age-evidence",
      "/v1/tenants/*/management/access-subjects/*/age-check").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/facial-settings").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/access-subjects/*/age-evidence","/v1/tenants/*/management/age-evidence/*/revoke").authenticated()
    // Catálogo e homologação (AC-019, V040): plataforma (access_catalog.manage/homologation.approve, AAL2); gestão consulta.
    .requestMatchers(HttpMethod.GET,"/v1/platform/access-models","/v1/platform/access-models/*","/v1/platform/access-homologations",
      "/v1/platform/access-homologations/*","/v1/tenants/*/management/access-homologations").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/platform/access-homologations","/v1/platform/access-homologations/*/decisions").authenticated()
    // Áreas e pontos (AC-004, V041): gestão (area.manage, AAL2) e MASTER de implantação (installation.*, AAL2).
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/access-areas","/v1/tenants/*/management/access-points",
      "/v1/tenants/*/technical/access-areas","/v1/tenants/*/technical/access-points").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/access-areas","/v1/tenants/*/technical/access-points").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/access-areas/*","/v1/tenants/*/technical/access-areas/*/physical",
      "/v1/tenants/*/technical/access-points/*").authenticated()
    // Políticas de ingresso (AC-007/AC-008, V043): gestão com policy.edit/policy.publish (AAL2).
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/access-policies","/v1/tenants/*/management/access-policies/*/versions").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/access-policies","/v1/tenants/*/management/access-policies/*/versions",
      "/v1/tenants/*/management/access-policies/*/validate","/v1/tenants/*/management/access-policies/*/simulate",
      "/v1/tenants/*/management/access-policies/*/versions/*/approve","/v1/tenants/*/management/access-policies/*/versions/*/reject").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/access-policies/*").authenticated()
    // Direitos derivados e revogações (AC-009/AC-015, V044): gestão com memberships.manage + policy.publish (AAL2).
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/access-grants","/v1/tenants/*/management/access-subjects/*/revocation-markers").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/access-policies/*/grants/derive",
      "/v1/tenants/*/management/access-grants/*/revoke").authenticated()
    // Instalação física (AC-004 parte 2, V042): MASTER de implantação (installation.*, AAL2); gestão assina as regras.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/technical/access-devices","/v1/tenants/*/technical/device-channels",
      "/v1/tenants/*/technical/point-bindings","/v1/tenants/*/technical/installation-acceptances",
      "/v1/tenants/*/management/installation-acceptances").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/technical/access-devices","/v1/tenants/*/technical/device-channels",
      "/v1/tenants/*/technical/point-bindings","/v1/tenants/*/technical/point-bindings/*/submit","/v1/tenants/*/technical/point-bindings/*/discard",
      "/v1/tenants/*/technical/installation-acceptances","/v1/tenants/*/technical/installation-acceptances/*/technical-signature",
      "/v1/tenants/*/technical/installation-acceptances/*/status","/v1/tenants/*/management/installation-acceptances/*/rules-signature").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/technical/access-devices/*","/v1/tenants/*/technical/device-channels/*",
      "/v1/tenants/*/technical/installation-acceptances/*/results").authenticated()
    // Proprietário × inquilino (V034): gestão (AAL2) ou vínculo OWNER com locação em vigor, no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/rentals","/v1/tenants/*/units/*/rental").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/units/*/rental","/v1/tenants/*/management/units/*/rental/end",
      "/v1/tenants/*/units/*/rental/invitations","/v1/tenants/*/units/*/rental/invitations/*/revoke",
      "/v1/tenants/*/units/*/rental/occupants/*/remove").authenticated()
    // Financeiro condominial (ADR-0008): gestão (finance.manage, AAL2) ou vínculo da unidade no serviço e no banco.
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/management/finance/units","/v1/tenants/*/management/finance/charges",
      "/v1/tenants/*/management/finance/recurring-fees","/v1/tenants/*/management/finance/delinquency",
      "/v1/tenants/*/management/finance/report","/v1/tenants/*/units/*/finance/charges",
      "/v1/tenants/*/units/*/finance/charges/*/payment").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/tenants/*/management/finance/units/*/payer").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/management/finance/charges","/v1/tenants/*/management/finance/charges/issue-pending",
      "/v1/tenants/*/management/finance/charges/*/issue","/v1/tenants/*/management/finance/charges/*/cancel",
      "/v1/tenants/*/management/finance/charges/*/sync","/v1/tenants/*/management/finance/recurring-fees",
      "/v1/tenants/*/management/finance/recurring-fees/*/end","/v1/tenants/*/management/finance/recurring-fees/generate").authenticated()
    // Webhook do Asaas: sem sessão; o token do cabeçalho asaas-access-token é conferido no serviço antes de qualquer leitura.
    .requestMatchers(HttpMethod.POST,"/v1/finance/asaas/webhook").permitAll()
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
    .requestMatchers(HttpMethod.GET,"/v1/platform/tenants/*").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/platform/tenants","/v1/platform/tenants/*/manager-invitations",
      "/v1/platform/tenants/*/manager-invitations/*/revoke","/v1/manager-invitations/accept").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/platform/billing/plans","/v1/platform/billing/subscriptions",
      "/v1/platform/billing/charges").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/platform/billing/plans","/v1/platform/billing/subscriptions",
      "/v1/platform/billing/subscriptions/*/end","/v1/platform/billing/subscriptions/*/charges",
      "/v1/platform/billing/charges/*/cancel","/v1/platform/billing/charges/*/payments",
      "/v1/platform/billing/payments/*/void").authenticated()
    .requestMatchers(HttpMethod.PUT,"/v1/platform/billing/plans/*").authenticated()
    .requestMatchers(HttpMethod.GET,"/auth/csrf","/auth/mfa").permitAll()
    .requestMatchers(HttpMethod.POST,"/auth/login","/auth/refresh","/auth/logout","/auth/mfa/enroll","/auth/mfa/verify",
      "/auth/recovery","/auth/recovery/verify","/auth/recovery/complete","/auth/invitation/accept","/auth/manager-invitation/accept",
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

