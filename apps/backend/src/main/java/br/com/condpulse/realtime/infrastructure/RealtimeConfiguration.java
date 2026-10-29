package br.com.condpulse.realtime.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import br.com.condpulse.realtime.application.RealtimeHub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Canal em tempo real atrás de CP_REALTIME_ENABLED=false. Uma única linha de execução percorre os canais em sequência, cada
 * consulta numa transação curta: o pool de 2 conexões nunca fica preso a um canal aberto. Limites por instância e por
 * conta evitam que conexões longas consumam a API.
 */
@Configuration(proxyBeanMethods=false) @Profile("api")
@ConditionalOnProperty(name="condpulse.realtime.enabled",havingValue="true")
public class RealtimeConfiguration {
 @Bean(destroyMethod="close")
 RealtimeHub realtimeHub(@Value("${condpulse.realtime.tick-seconds:5}") long tick,
   @Value("${condpulse.realtime.lifetime-seconds:300}") long lifetime,
   @Value("${condpulse.realtime.max-streams:40}") int maxStreams,
   @Value("${condpulse.realtime.max-per-account:3}") int maxPerAccount) {
  if(tick<2 || tick>60 || lifetime<tick || lifetime>900 || maxStreams>200 || maxPerAccount>10)
   throw new IllegalStateException("REALTIME_SETTINGS_INVALID");
  return new RealtimeHub(new RealtimeHub.Settings(Duration.ofSeconds(tick),Duration.ofSeconds(lifetime),maxStreams,maxPerAccount),
   Clock.systemUTC());
 }
 @Bean(destroyMethod="shutdownNow")
 ScheduledExecutorService realtimeTicker(RealtimeHub hub) {
  var executor=Executors.newSingleThreadScheduledExecutor(runnable->{
   var thread=new Thread(runnable,"condpulse-realtime"); thread.setDaemon(true); return thread;
  });
  long period=hub.settings().tick().toMillis();
  executor.scheduleWithFixedDelay(hub::tick,period,period,TimeUnit.MILLISECONDS);
  return executor;
 }
}
