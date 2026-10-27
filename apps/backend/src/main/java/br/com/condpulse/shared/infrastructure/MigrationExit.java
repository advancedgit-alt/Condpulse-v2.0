package br.com.condpulse.shared.infrastructure;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("migrate")
public class MigrationExit implements ApplicationRunner {
 private final ConfigurableApplicationContext context;
 public MigrationExit(ConfigurableApplicationContext context) { this.context=context; }
 @Override public void run(ApplicationArguments args) { System.exit(SpringApplication.exit(context)); }
}
