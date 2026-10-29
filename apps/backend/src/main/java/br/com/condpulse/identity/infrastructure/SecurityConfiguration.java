package br.com.condpulse.identity.infrastructure;
import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
    .requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
    .requestMatchers("/v1/me").authenticated()
    .requestMatchers(HttpMethod.GET,"/v1/tenants/*/units","/v1/tenants/*/amenities",
      "/v1/tenants/*/amenities/*/availability","/v1/tenants/*/reservations").authenticated()
    .requestMatchers(HttpMethod.POST,"/v1/tenants/*/reservations","/v1/tenants/*/reservations/*/cancel").authenticated()
    .requestMatchers(HttpMethod.GET,"/auth/csrf","/auth/mfa").permitAll()
    .requestMatchers(HttpMethod.POST,"/auth/login","/auth/refresh","/auth/logout","/auth/mfa/enroll","/auth/mfa/verify").permitAll()
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

