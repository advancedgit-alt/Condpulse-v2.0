import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, record } from './client';
import { tenantModuleIds, type TenantModuleId } from './types';

/**
 * Módulos por condomínio (V045): decisão da central, flag global e efeito real. Só metadados de liberação: nenhuma
 * chave, segredo ou conteúdo de morador. A gravação vale pela versão lida, com motivo, e o servidor confere de novo.
 */
export { tenantModuleIds, type TenantModuleId };
export const tenantModuleLabels: Record<TenantModuleId, string> = {
  reservations: 'Reservas e áreas comuns', parcels: 'Encomendas', visits: 'Visitas e acessos', announcements: 'Avisos',
  requests: 'Pedidos e anexos', finance: 'Financeiro da unidade', access_credentials: 'Credenciais de acesso', facial_access: 'Acesso facial',
};
export interface TenantModuleState {
  module: TenantModuleId; globallyEnabled: boolean; tenantEnabled: boolean; enabled: boolean; defaultState: boolean;
  version: number; reason: string | null; updatedBy: string | null; updatedAt: string | null;
}
export interface TenantModulePage { tenantId: string; modules: TenantModuleState[] }
export interface TenantModuleChange { applied: boolean; module: TenantModuleState }

const isModule = (value: unknown): value is TenantModuleId => (tenantModuleIds as readonly unknown[]).includes(value);
export function tenantModuleState(value: unknown, uncertain = false): TenantModuleState {
  if (!record(value) || !isModule(value.module) || typeof value.globallyEnabled !== 'boolean' || typeof value.tenantEnabled !== 'boolean'
      || typeof value.enabled !== 'boolean' || typeof value.defaultState !== 'boolean' || !isInteger(value.version) || value.version < 0
      || value.enabled !== (value.globallyEnabled && value.tenantEnabled) || (value.defaultState && (value.version !== 0 || !value.tenantEnabled))
      || (value.reason !== null && !isString(value.reason)) || (value.updatedBy !== null && !isUUID(value.updatedBy))
      || (value.updatedAt !== null && !isDate(value.updatedAt))) throw invalidResponse(uncertain);
  return { module: value.module, globallyEnabled: value.globallyEnabled, tenantEnabled: value.tenantEnabled, enabled: value.enabled,
    defaultState: value.defaultState, version: value.version, reason: value.reason, updatedBy: value.updatedBy, updatedAt: value.updatedAt };
}
export function tenantModulePage(value: unknown, tenantId: string): TenantModulePage {
  if (!record(value) || value.tenantId !== tenantId || !Array.isArray(value.modules)) throw invalidResponse();
  const modules = value.modules.map((item) => tenantModuleState(item));
  if (new Set(modules.map((item) => item.module)).size !== modules.length) throw invalidResponse();
  return { tenantId, modules: tenantModuleIds.flatMap((id) => modules.filter((item) => item.module === id)) };
}
export function tenantModuleChange(value: unknown, module: TenantModuleId): TenantModuleChange {
  if (!record(value) || typeof value.applied !== 'boolean') throw invalidResponse(true);
  const state = tenantModuleState(value.module, true);
  if (state.module !== module) throw invalidResponse(true);
  return { applied: value.applied, module: state };
}

export function createTenantModulesApi(client: CondPulseApi = api) {
  return {
    list: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}/modules`, (value) => tenantModulePage(value, tenantId), signal ? { signal } : {}),
    change: async (tenantId: string, body: { module: TenantModuleId; enabled: boolean; expectedVersion: number; reason: string }) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}/modules`, (value) => tenantModuleChange(value, body.module), { method: 'PUT', body }),
  };
}
export const tenantModules = createTenantModulesApi();
