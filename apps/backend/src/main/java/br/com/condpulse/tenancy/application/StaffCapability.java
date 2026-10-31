package br.com.condpulse.tenancy.application;

import java.util.EnumSet;
import java.util.Set;

/**
 * Capacidades de equipe por papel. Todas exigem AAL2. SUPPORT permanece sem capacidade até existir
 * concessão temporária auditada; papéis residenciais nunca herdam autoridade de equipe.
 */
public enum StaffCapability {
 MANAGE_UNITS("units.manage"),
 MANAGE_MEMBERSHIPS("memberships.manage"),
 MANAGE_AMENITIES("amenities.manage"),
 DECIDE_RESERVATIONS("reservations.decide"),
 READ_AUDIT("audit.read"),
 PUBLISH_ANNOUNCEMENTS("announcements.publish"),
 MANAGE_REQUESTS("requests.manage"),
 OPERATE_CONCIERGE("concierge.operate"),
 /** Financeiro condominial com Asaas (ADR-0008). */
 MANAGE_FINANCE("finance.manage"),
 /** Acesso facial (AC-004, V041): áreas lógicas, nome, tipo, hierarquia e interdição; nunca canal, relé ou segredo. */
 MANAGE_AREAS("area.manage"),
 /** Acesso facial (AC-007/AC-008, V043): cria revisões de regra, valida e simula, sem publicar. */
 EDIT_POLICIES("policy.edit"),
 /** Acesso facial (V043): aprova a revisão validada (publicação por destino na V045). */
 PUBLISH_POLICIES("policy.publish"),
 /**
  * Zeladoria (MAINTENANCE, V045): lê e registra o andamento somente dos pedidos atribuídos ao próprio vínculo; nenhum
  * financeiro, cadastro, vínculo, encomenda, visita ou acesso.
  */
 OPERATE_MAINTENANCE("maintenance.operate");

 private final String key;
 StaffCapability(String key) { this.key=key; }
 /** Identificador estável usado no contexto transacional do banco. */
 public String key() { return key; }

 public static Set<StaffCapability> forRole(String role) {
  if(role==null) return EnumSet.noneOf(StaffCapability.class);
  return switch(role) {
   case "MANAGER" -> EnumSet.of(MANAGE_UNITS,MANAGE_MEMBERSHIPS,MANAGE_AMENITIES,DECIDE_RESERVATIONS,READ_AUDIT,PUBLISH_ANNOUNCEMENTS,
    MANAGE_REQUESTS,MANAGE_FINANCE,MANAGE_AREAS,EDIT_POLICIES,PUBLISH_POLICIES);
   case "CONCIERGE" -> EnumSet.of(OPERATE_CONCIERGE);
   case "MAINTENANCE" -> EnumSet.of(OPERATE_MAINTENANCE);
   default -> EnumSet.noneOf(StaffCapability.class);
  };
 }
}
