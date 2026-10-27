package br.com.condpulse.identity.bff;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.bff.enabled",havingValue="true")
public class BffConfiguration {
 @Bean BffSettings bffSettings(@Value("${condpulse.bff.origin}") String origin,
    @Value("${condpulse.auth.issuer}") String issuer,
    @Value("${condpulse.bff.publishable-key}") String publishable,
    @Value("${condpulse.bff.session-hours:8}") long hours) {
  return new BffSettings(origin,issuer,publishable,hours);
 }
 @Bean TokenCipher bffTokenCipher(@Value("${condpulse.bff.encryption-key}") String key) { return new TokenCipher(key); }
 @Bean Clock bffClock() { return Clock.systemUTC(); }
 @Bean SupabaseAuthClient bffAuthClient(BffSettings settings) {
  var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
   .followRedirects(HttpClient.Redirect.NEVER).build();
  var factory=new JdkClientHttpRequestFactory(http);
  factory.setReadTimeout(Duration.ofSeconds(5));
  return new SupabaseAuthClient(RestClient.builder().requestFactory(factory).build(),settings);
 }
 @Bean WebSessionStore bffSessionStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  var tx=new TransactionTemplate(manager); tx.setTimeout(5);
  return new WebSessionStore(jdbc,tx);
 }
 @Bean WebSessionService bffSessions(WebSessionStore store,SupabaseAuthClient auth,TokenCipher cipher,
    JwtDecoder decoder,BffSettings settings,Clock clock) {
  return new WebSessionService(store,auth,cipher,decoder,settings,clock);
 }
 @Bean CsrfTokenRepository bffCsrfRepository() {
  var repository=new CookieCsrfTokenRepository();
  repository.setCookieName("__Host-cp_csrf"); repository.setCookiePath("/");
  repository.setHeaderName("X-CSRF-Token");
  repository.setCookieCustomizer(cookie->cookie.secure(true).httpOnly(true).sameSite("Lax").path("/"));
  return repository;
 }
 @Bean BffSecurityConfigurer bffSecurityConfigurer(WebSessionService sessions,BffSettings settings,CsrfTokenRepository csrf) {
  return new BffSecurityConfigurer(sessions,settings,csrf);
 }
}
