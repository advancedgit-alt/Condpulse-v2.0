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
 OPERATE_CONCIERGE("concierge.operate");

 private final String key;
 StaffCapability(String key) { this.key=key; }
 /** Identificador estável usado no contexto transacional do banco. */
 public String key() { return key; }

 public static Set<StaffCapability> forRole(String role) {
  if(role==null) return EnumSet.noneOf(StaffCapability.class);
  return switch(role) {
   case "MANAGER" -> EnumSet.of(MANAGE_UNITS,MANAGE_MEMBERSHIPS,MANAGE_AMENITIES,DECIDE_RESERVATIONS,READ_AUDIT,PUBLISH_ANNOUNCEMENTS);
   case "CONCIERGE" -> EnumSet.of(OPERATE_CONCIERGE);
   default -> EnumSet.noneOf(StaffCapability.class);
  };
 }
}
