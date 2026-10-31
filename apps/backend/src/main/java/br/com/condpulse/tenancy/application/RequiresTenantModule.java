package br.com.condpulse.tenancy.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca as rotas de um módulo liberável por condomínio. A rota responde 403 MODULE_DISABLED quando nenhum dos módulos
 * indicados está liberado no condomínio do caminho (conferido pelo executor, na mesma transação e depois do vínculo).
 * Mais de um módulo significa "qualquer um deles" (ex.: unidades da portaria servem encomendas e visitas).
 * Na classe vale para todas as rotas; no método substitui a da classe.
 */
@Documented @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.METHOD})
public @interface RequiresTenantModule {
 TenantModule[] value();
}
