package br.com.condpulse.identity.bff;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recuperação de acesso desligada por padrão (CP_BFF_RECOVERY_ENABLED=false) até homologar e-mail e modelo do link. */
@Configuration @Profile("api")
@ConditionalOnProperty(name={"condpulse.bff.enabled","condpulse.bff.recovery-enabled"},havingValue="true")
public class BffRecoveryConfiguration {
 /**
  * Prazo fixo do pedido de recuperação (mais variação de até JITTER_MILLIS), para não diferenciar conta existente de
  * inexistente pelo tempo: a resposta não espera o provedor além dele.
  */
 static final Duration MINIMUM_REQUEST=Duration.ofMillis(1500);
 static final int JITTER_MILLIS=300;
 @Bean RecoveryStore bffRecoveryStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
  var tx=new TransactionTemplate(manager); tx.setTimeout(5);
  return new RecoveryStore(jdbc,tx);
 }
 @Bean(destroyMethod="close") RecoveryDispatcher bffRecoveryDispatcher() { return new RecoveryDispatcher(); }
 @Bean RecoveryService bffRecoveryService(RecoveryStore store,SupabaseAuthClient auth,TokenCipher cipher,
    JwtDecoder decoder,Clock clock,RecoveryDispatcher dispatcher) {
  return new RecoveryService(store,auth,cipher,decoder,clock,MINIMUM_REQUEST,
   ()->Duration.ofMillis(ThreadLocalRandom.current().nextLong(JITTER_MILLIS+1L)),dispatcher,duration->Thread.sleep(duration));
 }
}
