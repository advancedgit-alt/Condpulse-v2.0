import { api, identifier, invalidResponse, isDate, isInteger, isUUID, oneOf, record } from './client';
export interface IdentityCheck { checkId: string; visitorId: string; canonicalVisitorId: string; state: 'ACTIVE' | 'VOIDED'; version: number; checkedAt: string }
export interface IdentityCandidate { checkId: string; visitorId: string; name: string; documentLast4: string | null; version: number }
export interface IdentityBasis { arrivalId: string | null; permitId: string | null }
function check(value: unknown, uncertain = false): IdentityCheck {
  if (!record(value) || !isUUID(value.checkId) || !isUUID(value.visitorId) || !isUUID(value.canonicalVisitorId)
    || !oneOf(value.state, ['ACTIVE', 'VOIDED']) || !isInteger(value.version) || value.version < 0 || !isDate(value.checkedAt)) throw invalidResponse(uncertain);
  return { checkId: value.checkId, visitorId: value.visitorId, canonicalVisitorId: value.canonicalVisitorId, state: value.state, version: value.version, checkedAt: value.checkedAt };
}
const base = (tenant: string) => `/v1/tenants/${identifier(tenant)}/stay-identities`;
export const stayIdentities = {
  source: (tenant: string, basis: IdentityBasis, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (basis.arrivalId) params.set('arrivalId', basis.arrivalId);
    if (basis.permitId) params.set('permitId', basis.permitId);
    return api.call(`${base(tenant)}/source?${params}`, (value) => {
      if (!record(value)) throw invalidResponse();
      return value.check === null ? null : check(value.check);
    }, signal ? { signal } : {});
  },
  search: (tenant: string, prefix: string) => api.call(`${base(tenant)}?${new URLSearchParams({ prefix })}`, (value): IdentityCandidate[] => {
    if (!record(value) || !Array.isArray(value.candidates) || value.candidates.length > 20) throw invalidResponse();
    return value.candidates.map((row) => {
      if (!record(row) || !isUUID(row.checkId) || !isUUID(row.visitorId) || typeof row.name !== 'string' || !row.name.trim()
        || !(row.documentLast4 === null || typeof row.documentLast4 === 'string') || !isInteger(row.version) || row.version < 0) throw invalidResponse();
      return { checkId: row.checkId, visitorId: row.visitorId, name: row.name, documentLast4: row.documentLast4, version: row.version };
    });
  }),
  verify: (tenant: string, body: IdentityBasis & { requestId: string; canonicalVisitorId: string | null; reason: string }) =>
    api.call(base(tenant), (value) => check(value, true), { method: 'POST', body }),
};
