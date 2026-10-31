package br.com.condpulse.tenancy.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Isenção explícita de módulo liberável para rotas de condomínio (/v1/tenants/**): cadastro e vínculos da gestão,
 * auditoria e canais transversais. Toda rota de condomínio declara {@link RequiresTenantModule} ou esta isenção, com
 * o motivo; um teste de arquitetura recusa rota sem nenhuma das duas.
 */
@Documented @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.METHOD})
public @interface TenantModuleExempt {
 /** Motivo da isenção (não vazio). */
 String value();
}
