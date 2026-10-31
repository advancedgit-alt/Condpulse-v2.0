package br.com.condpulse.tenancy.application;

import java.util.Arrays;
import java.util.Optional;

/**
 * Módulos que a central administradora libera ou suspende por condomínio (platform.tenant_modules, V044). Cada módulo
 * corresponde a uma flag global existente: o módulo só vale com a flag ligada E o condomínio liberado. Sem linha no
 * banco o condomínio segue liberado (estado atual).
 */
public enum TenantModule {
 RESERVATIONS("reservations","condpulse.reservations.enabled"),
 PARCELS("parcels","condpulse.parcels.enabled"),
 VISITS("visits","condpulse.visits.enabled"),
 ANNOUNCEMENTS("announcements","condpulse.announcements.enabled"),
 REQUESTS("requests","condpulse.requests.enabled"),
 FINANCE("finance","condpulse.finance.enabled"),
 ACCESS_CREDENTIALS("access_credentials","condpulse.access-credentials.enabled"),
 FACIAL_ACCESS("facial_access","condpulse.facial-access.enabled");

 private final String key;
 private final String flagProperty;
 TenantModule(String key,String flagProperty) { this.key=key; this.flagProperty=flagProperty; }
 /** Identificador estável gravado em platform.tenant_modules.module e devolvido pela API. */
 public String key() { return key; }
 /** Propriedade da flag global que libera as rotas do módulo. */
 public String flagProperty() { return flagProperty; }
 public static Optional<TenantModule> fromKey(String key) {
  return Arrays.stream(values()).filter(m->m.key.equals(key)).findFirst();
 }
}
