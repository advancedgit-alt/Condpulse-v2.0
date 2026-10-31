package br.com.condpulse.gateway.app;

import java.nio.file.Path;
import java.time.Clock;
import br.com.condpulse.gateway.central.HttpCentralApiClient;
import br.com.condpulse.gateway.config.ConfigLoader;
import br.com.condpulse.gateway.config.SecretProvider;
import br.com.condpulse.gateway.runtime.GatewayRuntime;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Processo do gateway de acesso (LOCAL no condomínio ou NUVEM como serviço central). Começa desligado: só sobe com
 * {@code CP_GATEWAY_ENABLED=true}, configuração ({@code CP_GATEWAY_CONFIG}), diretório de segredos
 * ({@code CP_GATEWAY_SECRETS_DIR}) e diretório de dados persistente ({@code CP_GATEWAY_DATA_DIR}). Sem servidor web
 * do Spring: a única porta é a de entrada dos equipamentos, quando configurada.
 */
@SpringBootApplication
public class GatewayApplication {
 public static void main(String[] args) {
  // Nenhuma repetição implícita de conexão pelo cliente HTTP do JDK (manual 16.2).
  System.setProperty("jdk.httpclient.disableRetryConnect","true");
  // Prazos do servidor de entrada (requisição incompleta é encerrada); antes de qualquer servidor HTTP do JDK.
  br.com.condpulse.gateway.inbound.InboundServer.configureJdkServerDeadlines();
  var app=new SpringApplication(GatewayApplication.class);
  app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
  app.run(args);
 }

 @Bean ApplicationRunner gatewayRunner(Environment env) {
  return args->{
   if(!"true".equals(env.getProperty("CP_GATEWAY_ENABLED"))) {
    System.getLogger(GatewayApplication.class.getName()).log(System.Logger.Level.WARNING,"Gateway desligado (CP_GATEWAY_ENABLED diferente de true).");
    return;
   }
   var config=ConfigLoader.load(Path.of(required(env,"CP_GATEWAY_CONFIG")));
   var secrets=new SecretProvider.FileSecretProvider(Path.of(required(env,"CP_GATEWAY_SECRETS_DIR")));
   var runtime=new GatewayRuntime(config,secrets,new HttpCentralApiClient(config,secrets),Path.of(required(env,"CP_GATEWAY_DATA_DIR")),Clock.systemUTC());
   Runtime.getRuntime().addShutdownHook(new Thread(runtime::close,"gateway-shutdown"));
   runtime.start();
  };
 }

 private static String required(Environment env,String name) {
  String value=env.getProperty(name);
  if(value==null || value.isBlank()) throw new IllegalStateException("Variável obrigatória ausente: "+name);
  return value;
 }
}
