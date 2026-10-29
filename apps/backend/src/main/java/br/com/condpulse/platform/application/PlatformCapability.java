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
 READ_AUDIT("platform.audit.read"),
 /** Financeiro da assinatura CondPulse (fase 5): planos, assinaturas, cobranças e pagamentos manuais. */
 READ_BILLING("billing.read"),
 MANAGE_BILLING("billing.manage");

 private final String key;
 PlatformCapability(String key) { this.key=key; }
 /** Identificador estável publicado em app.platform_capabilities. */
 public String key() { return key; }

 public static Set<PlatformCapability> forRole(String role) {
  return "PLATFORM_ADMIN".equals(role) ? EnumSet.allOf(PlatformCapability.class) : EnumSet.noneOf(PlatformCapability.class);
 }
}
