import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, oneOf, record } from './client';
import { technicalScopes, type TechnicalScope } from './accessInstall';
import type { ISODateTime, UUID } from './types';

/**
 * Concessões técnicas pelo painel master (AC-003, V037): a plataforma emite e revoga o MASTER de implantação por
 * condomínio, sempre para outra conta, com prazo curto e motivo na auditoria. Nenhum segredo ou e-mail trafega aqui.
 */
export const grantRoles = ['MASTER_IMPLANTACAO', 'TECHNICAL_SUPPORT'] as const;
export type GrantRole = typeof grantRoles[number];
/** Padrão do MASTER de implantação (TechnicalScope.implantationDefaults): sem pessoas, abertura nem exportação. */
export const implantationDefaults: readonly TechnicalScope[] = ['connector.rotate_secret', 'gateway.manage', 'homologation.request', 'installation.bind_channel',
  'installation.commission', 'installation.manage', 'installation.read'];
/** Escopos do MASTER de implantação que só entram por pedido específico. */
export const restrictedScopes: readonly TechnicalScope[] = ['person.review', 'access.request_open', 'audit.export'];
export const HARD_MAX_DAYS = 90;

export interface TechnicalGrant {
  grantId: UUID; tenantId: UUID; accountId: UUID; roleCode: GrantRole; scopes: TechnicalScope[]; validFrom: ISODateTime; validUntil: ISODateTime;
  grantedVia: 'PLATFORM_API' | 'OPERATIONS'; status: 'ACTIVE' | 'REVOKED'; current: boolean; revokedAt: ISODateTime | null; version: number;
}
export interface IssueGrantBody { grantId: UUID; accountId: UUID; roleCode: GrantRole; scopes: TechnicalScope[]; validDays: number; reason: string }

export function technicalGrant(value: unknown, tenantId: string, uncertain = false): TechnicalGrant {
  if (!record(value) || !isUUID(value.grantId) || !isUUID(value.tenantId) || value.tenantId.toLowerCase() !== tenantId.toLowerCase() || !isUUID(value.accountId)
      || !oneOf(value.roleCode, grantRoles) || !Array.isArray(value.scopes) || value.scopes.length < 1 || value.scopes.length > 16
      || !value.scopes.every((item) => oneOf(item, technicalScopes)) || new Set(value.scopes).size !== value.scopes.length
      || !isDate(value.validFrom) || !isDate(value.validUntil) || Date.parse(value.validUntil) <= Date.parse(value.validFrom)
      || !oneOf(value.grantedVia, ['PLATFORM_API', 'OPERATIONS'] as const) || !oneOf(value.status, ['ACTIVE', 'REVOKED'] as const)
      || typeof value.current !== 'boolean' || !(value.revokedAt === null || isDate(value.revokedAt)) || !isInteger(value.version))
    throw invalidResponse(uncertain);
  // Revogada tem horário e nunca está vigente; vigente é ativa.
  if ((value.status === 'REVOKED') !== (value.revokedAt !== null) || (value.current && value.status !== 'ACTIVE')) throw invalidResponse(uncertain);
  return { grantId: value.grantId, tenantId: value.tenantId, accountId: value.accountId, roleCode: value.roleCode, scopes: [...value.scopes] as TechnicalScope[],
    validFrom: value.validFrom, validUntil: value.validUntil, grantedVia: value.grantedVia, status: value.status, current: value.current,
    revokedAt: value.revokedAt as string | null, version: value.version };
}

export function createTechnicalGrantsApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/platform/tenants/${identifier(tenantId)}/technical-grants`;
  return {
    list: async (tenantId: string, signal?: AbortSignal) => client.call(base(tenantId), (value) => {
      if (!record(value) || !Array.isArray(value.grants) || value.grants.length > 500) throw invalidResponse();
      return value.grants.map((item) => technicalGrant(item, tenantId));
    }, signal ? { signal } : {}),
    /** Repetir o mesmo pedido (mesmo grantId e dados) devolve a concessão gravada com applied=false. */
    issue: async (tenantId: string, body: IssueGrantBody) => client.call(base(tenantId), (value) => {
      if (!record(value) || typeof value.applied !== 'boolean') throw invalidResponse(true);
      const grant = technicalGrant(value.grant, tenantId, true);
      if (grant.grantId !== body.grantId.toLowerCase() || grant.accountId !== body.accountId.toLowerCase() || grant.roleCode !== body.roleCode) throw invalidResponse(true);
      return { grant, applied: value.applied };
    }, { method: 'POST', body: { ...body, grantId: identifier(body.grantId), accountId: identifier(body.accountId) } }),
    revoke: async (tenantId: string, grantId: string, body: { expectedVersion: number; reason: string }) =>
      client.call(`${base(tenantId)}/${identifier(grantId)}/revoke`, (value) => {
        const grant = technicalGrant(value, tenantId, true);
        if (grant.grantId !== grantId || grant.status !== 'REVOKED') throw invalidResponse(true);
        return grant;
      }, { method: 'POST', body }),
  };
}
export const technicalGrants = createTechnicalGrantsApi();
