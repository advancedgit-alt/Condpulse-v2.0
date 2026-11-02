import { api, CondPulseApi, identifier, invalidResponse, isInteger, oneOf, record } from './client';
import { stayKinds, type StayKind } from './stays';

export interface StayRule {
  kind: StayKind; maxMinutes: number; l1AfterMinutes: number; l2AfterMinutes: number;
  source: 'TENANT_RULE' | 'PLATFORM_DEFAULT'; version: number | null;
  enabled: boolean; startsLocal: string | null; endsLocal: string | null;
}
export interface StayRuleChange {
  maxMinutes: number; l1AfterMinutes: number; l2AfterMinutes: number; version: number | null;
  enabled: boolean; scheduleEnabled: boolean; startsLocal: string | null; endsLocal: string | null;
}
const clock = (value: unknown): value is string => typeof value === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d(?::00)?$/.test(value);
export function stayRules(value: unknown, uncertain = false): StayRule[] {
  if (!record(value) || !Array.isArray(value.rules) || value.rules.length !== stayKinds.length) throw invalidResponse(uncertain);
  const rules = value.rules.map((row): StayRule => {
    if (!record(row) || !oneOf(row.kind, stayKinds) || !isInteger(row.maxMinutes) || row.maxMinutes < 5 || row.maxMinutes > 1440
        || !isInteger(row.l1AfterMinutes) || row.l1AfterMinutes < 0 || row.l1AfterMinutes > 720 || !isInteger(row.l2AfterMinutes)
        || row.l2AfterMinutes <= row.l1AfterMinutes || row.l2AfterMinutes > 1440 || !oneOf(row.source, ['TENANT_RULE', 'PLATFORM_DEFAULT'])
        || !(row.version === null || (isInteger(row.version) && row.version >= 0)) || typeof row.enabled !== 'boolean'
        || !(row.startsLocal === null || clock(row.startsLocal)) || !(row.endsLocal === null || clock(row.endsLocal))
        || (row.startsLocal === null) !== (row.endsLocal === null)
        || (row.startsLocal !== null && row.endsLocal !== null && row.startsLocal.slice(0, 5) >= row.endsLocal.slice(0, 5))) throw invalidResponse(uncertain);
    return { kind: row.kind, maxMinutes: row.maxMinutes, l1AfterMinutes: row.l1AfterMinutes, l2AfterMinutes: row.l2AfterMinutes,
      source: row.source, version: row.version, enabled: row.enabled,
      startsLocal: row.startsLocal === null ? null : row.startsLocal.slice(0, 5), endsLocal: row.endsLocal === null ? null : row.endsLocal.slice(0, 5) };
  });
  if (new Set(rules.map((row) => row.kind)).size !== stayKinds.length) throw invalidResponse(uncertain);
  return rules;
}
export function createStayRulesApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/stay-rules`;
  return {
    list: (tenantId: string, signal?: AbortSignal) => client.call(base(tenantId), (value) => stayRules(value), signal ? { signal } : {}),
    update: (tenantId: string, kind: StayKind, body: StayRuleChange) =>
      client.call(`${base(tenantId)}/${kind}`, (value) => stayRules(value, true), { method: 'PUT', body }),
  };
}
export const stayRulesApi = createStayRulesApi();
