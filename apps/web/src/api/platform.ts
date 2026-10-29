import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/** Painel master (OPS-01): somente dados da plataforma; nunca conteúdo dos moradores. */
export type PlatformCapability = 'platform.audit.read' | 'tenants.manage' | 'tenants.read';
export interface PlatformSession { accountId: UUID; role: 'PLATFORM_ADMIN'; capabilities: PlatformCapability[]; mfaRequired: boolean }
export interface OnboardingReceipt {
  operationId: UUID; accounts: number | null; units: number | null; memberships: number | null; amenities: number | null;
  completedAt: ISODateTime;
}
export interface PlatformTenant {
  tenantId: UUID; name: string; timeZone: string; active: boolean; version: number; createdAt: ISODateTime;
  updatedAt: ISODateTime; onboarding: OnboardingReceipt | null;
}
export interface PlatformTenantPage { tenants: PlatformTenant[]; truncated: boolean }
export interface PlatformTenantUpdate { name: string; active: boolean; expectedVersion: number; reason: string }
export interface PlatformTenantChange { tenant: PlatformTenant; applied: boolean }
export interface PlatformAuditEvent {
  eventId: UUID; actorId: UUID; action: string; tenantId: UUID | null; reason: string | null; details: Record<string, string>;
  createdAt: ISODateTime;
}
export interface PlatformAuditPage { events: PlatformAuditEvent[]; nextCursor: string | null }

const CAPABILITIES: readonly PlatformCapability[] = ['platform.audit.read', 'tenants.manage', 'tenants.read'];
const nullableCount = (value: unknown): value is number | null => value === null || isInteger(value);

export function platformSession(value: unknown): PlatformSession {
  if (!record(value) || !isUUID(value.accountId) || value.role !== 'PLATFORM_ADMIN' || !Array.isArray(value.capabilities)
      || !value.capabilities.every((item) => oneOf(item, CAPABILITIES)) || new Set(value.capabilities).size !== value.capabilities.length
      || typeof value.mfaRequired !== 'boolean' || (value.mfaRequired && value.capabilities.length > 0)) throw invalidResponse();
  return { accountId: value.accountId, role: 'PLATFORM_ADMIN', capabilities: [...value.capabilities] as PlatformCapability[],
    mfaRequired: value.mfaRequired };
}
function receipt(value: unknown): OnboardingReceipt | null {
  if (value === null) return null;
  if (!record(value) || !isUUID(value.operationId) || !nullableCount(value.accounts) || !nullableCount(value.units)
      || !nullableCount(value.memberships) || !nullableCount(value.amenities) || !isDate(value.completedAt)) throw invalidResponse();
  return { operationId: value.operationId, accounts: value.accounts, units: value.units, memberships: value.memberships,
    amenities: value.amenities, completedAt: value.completedAt };
}
export function platformTenant(value: unknown, uncertain = false): PlatformTenant {
  if (!record(value) || !isUUID(value.tenantId) || !isString(value.name) || value.name.length < 1 || value.name.length > 160
      || !isString(value.timeZone) || typeof value.active !== 'boolean' || !isInteger(value.version) || !isDate(value.createdAt)
      || !isDate(value.updatedAt) || !('onboarding' in value)) throw invalidResponse(uncertain);
  return { tenantId: value.tenantId, name: value.name, timeZone: value.timeZone, active: value.active, version: value.version,
    createdAt: value.createdAt, updatedAt: value.updatedAt, onboarding: receipt(value.onboarding) };
}
function tenantPage(value: unknown): PlatformTenantPage {
  if (!record(value) || !Array.isArray(value.tenants) || value.tenants.length > 200 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { tenants: value.tenants.map((item) => platformTenant(item)), truncated: value.truncated };
}
export function tenantChange(value: unknown): PlatformTenantChange {
  if (!record(value) || typeof value.applied !== 'boolean') throw invalidResponse(true);
  return { tenant: platformTenant(value.tenant, true), applied: value.applied };
}
function auditEvent(value: unknown): PlatformAuditEvent {
  if (!record(value) || !isUUID(value.eventId) || !isUUID(value.actorId) || !isString(value.action)
      || !(value.tenantId === null || isUUID(value.tenantId)) || !(value.reason === null || isString(value.reason))
      || !record(value.details) || !Object.values(value.details).every(isString) || !isDate(value.createdAt)) throw invalidResponse();
  return { eventId: value.eventId, actorId: value.actorId, action: value.action, tenantId: value.tenantId as string | null,
    reason: value.reason as string | null, details: { ...value.details } as Record<string, string>, createdAt: value.createdAt };
}
function auditPage(value: unknown): PlatformAuditPage {
  if (!record(value) || !Array.isArray(value.events) || value.events.length > 100
      || !(value.nextCursor === null || (isString(value.nextCursor) && /^[A-Za-z0-9_-]{1,120}$/.test(value.nextCursor)))) throw invalidResponse();
  return { events: value.events.map(auditEvent), nextCursor: value.nextCursor as string | null };
}

export function createPlatformApi(client: CondPulseApi = api) {
  return {
    session: async (signal?: AbortSignal) => client.call('/v1/platform/session', platformSession, signal ? { signal } : {}),
    tenants: async (signal?: AbortSignal) => client.call('/v1/platform/tenants', tenantPage, signal ? { signal } : {}),
    updateTenant: async (tenantId: string, body: PlatformTenantUpdate) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}`, tenantChange, { method: 'PUT', body }),
    audit: async (cursor: string | null, signal?: AbortSignal) => {
      const query = new URLSearchParams({ limit: '30' });
      if (cursor) query.set('cursor', cursor);
      return client.call(`/v1/platform/audit-events?${query}`, auditPage, signal ? { signal } : {});
    },
  };
}
export const platform = createPlatformApi();
