package br.com.condpulse.platform.application;

import java.util.EnumSet;
import java.util.Set;

/**
 * Capacidades da administração da plataforma (painel master). Todas exigem AAL2 e papel de plataforma vigente.
 * Nenhuma dá acesso a dados de negócio dos condomínios nem permite agir em nome de outra conta.
 */
public enum PlatformCapability {
 READ_TENANTS("tenants.read"),
 MANAGE_TENANTS("tenants.manage"),
 /** Módulos por condomínio (V044): liga e desliga módulos de um condomínio, com motivo, versão e trilha. */
 MANAGE_TENANT_MODULES("tenant_modules.manage"),
 READ_AUDIT("platform.audit.read"),
 /** Financeiro da assinatura CondPulse (fase 5): planos, assinaturas, cobranças e pagamentos manuais. */
 READ_BILLING("billing.read"),
 MANAGE_BILLING("billing.manage"),
 /** Fila de tarefas (CP-034): estado e erro saneado de todos os condomínios, sem conteúdo; devolve FAILED/SKIPPED à fila. */
 OPERATE_OUTBOX("outbox.operate"),
 /** Acesso facial (AC-003, V037): emite e revoga o MASTER de implantação por condomínio; não dá uso físico de áreas. */
 MANAGE_TECHNICAL_GRANTS("technical_grants.manage"),
 /** Acesso facial (AC-019, V040): cria dossiê de homologação da combinação técnica e consulta o catálogo. */
 MANAGE_ACCESS_CATALOG("access_catalog.manage"),
 /** Acesso facial (AC-019, V040): decide ensaio, bancada, piloto, produção, reprovação e suspensão. */
 APPROVE_HOMOLOGATION("homologation.approve");

 /** Capacidades que só aparecem na sessão do painel com CP_FACIAL_ACCESS_ENABLED ligada. */
 public boolean facialAccess() { return this==MANAGE_TECHNICAL_GRANTS || this==MANAGE_ACCESS_CATALOG || this==APPROVE_HOMOLOGATION; }

 private final String key;
 PlatformCapability(String key) { this.key=key; }
 /** Identificador estável publicado em app.platform_capabilities. */
 public String key() { return key; }

 public static Set<PlatformCapability> forRole(String role) {
  return "PLATFORM_ADMIN".equals(role) ? EnumSet.allOf(PlatformCapability.class) : EnumSet.noneOf(PlatformCapability.class);
 }
}
