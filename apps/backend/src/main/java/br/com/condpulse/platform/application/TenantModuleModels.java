package br.com.condpulse.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Módulos por condomínio na central (V045). */
public final class TenantModuleModels {
 private TenantModuleModels() {}
 /**
  * Estado de um módulo: {@code tenantEnabled} é a decisão da central (padrão liberado, {@code defaultState=true} e
  * versão 0 quando nunca alterado); {@code enabled} é o efeito real (flag global ligada E condomínio liberado).
  */
 public record TenantModuleState(String module,boolean globallyEnabled,boolean tenantEnabled,boolean enabled,boolean defaultState,
   long version,String reason,UUID updatedBy,Instant updatedAt) {}
 public record TenantModulePage(UUID tenantId,List<TenantModuleState> modules) {
  public TenantModulePage { modules=List.copyOf(modules); }
 }
 /** Mudança pela versão lida (0 para o padrão nunca alterado), com motivo de 3 a 500 caracteres. */
 public record ChangeTenantModule(String module,Boolean enabled,Long expectedVersion,String reason) {}
 public record TenantModuleChange(boolean applied,TenantModuleState module) {}
}
