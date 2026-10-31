package br.com.condpulse.accesscatalog.api;

import br.com.condpulse.accesscatalog.application.CatalogService;
import br.com.condpulse.accesscatalog.infrastructure.CatalogStore;
import br.com.condpulse.platform.application.PlatformTransactionExecutor;
import br.com.condpulse.platform.infrastructure.PlatformStore;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Catálogo técnico e homologações (AC-019, V040) atrás de CP_FACIAL_ACCESS_ENABLED=false. */
@Configuration @Profile("api")
@ConditionalOnProperty(name="condpulse.facial-access.enabled",havingValue="true")
public class AccessCatalogConfiguration {
 @Bean CatalogStore catalogStore(JdbcTemplate jdbc) { return new CatalogStore(jdbc); }
 @Bean CatalogService catalogService(PlatformTransactionExecutor platform,TenantTransactionExecutor tenants,CatalogStore store,PlatformStore platformStore) {
  return new CatalogService(platform,tenants,store,platformStore);
 }
}
