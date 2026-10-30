import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/** Painel master (OPS-01): somente dados da plataforma; nunca conteúdo dos moradores. */
export type PlatformCapability = 'billing.manage' | 'billing.read' | 'outbox.operate' | 'platform.audit.read' | 'tenants.manage' | 'tenants.read';
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
/** name null mantém o nome atual: suspender ou reativar nunca renomeia por normalização. */
export interface PlatformTenantUpdate { name: string | null; active: boolean; expectedVersion: number; reason: string }
export interface PlatformTenantChange { tenant: PlatformTenant; applied: boolean }
export interface PlatformAuditEvent {
  eventId: UUID; actorId: UUID; action: string; tenantId: UUID | null; reason: string | null; details: Record<string, string>;
  createdAt: ISODateTime;
}
export interface PlatformAuditPage { events: PlatformAuditEvent[]; nextCursor: string | null }
/** Fila de tarefas (CP-034): estado, tentativas, erro saneado e horários; nunca o conteúdo da tarefa. */
export interface OutboxTenantQueue {
  tenantId: UUID; tenantName: string | null; pending: number; ready: number; oldestReadySeconds: number | null; expiredLeases: number;
  failed: number; skipped: number; stale: boolean;
}
export interface OutboxSummary { tenants: OutboxTenantQueue[]; staleAfterSeconds: number; generatedAt: ISODateTime }
export type OutboxTaskState = 'PENDING' | 'LEASED' | 'DELIVERED' | 'SKIPPED' | 'FAILED';
export interface OutboxTask {
  taskId: UUID; tenantId: UUID; eventType: string; aggregateId: UUID; state: OutboxTaskState; attempts: number; lastError: string | null;
  createdAt: ISODateTime; availableAt: ISODateTime; processedAt: ISODateTime | null;
}
export interface OutboxTaskPage { tasks: OutboxTask[]; truncated: boolean }

const CAPABILITIES: readonly PlatformCapability[] = ['billing.manage', 'billing.read', 'outbox.operate', 'platform.audit.read', 'tenants.manage', 'tenants.read'];
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
  // Comprimento em code points, como o banco: nome com emoji não derruba a lista inteira.
  if (!record(value) || !isUUID(value.tenantId) || !isString(value.name) || value.name.length < 1 || codePoints(value.name) > 160
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

function count(value: unknown): value is number { return isInteger(value) && value >= 0; }
function tenantQueue(value: unknown): OutboxTenantQueue {
  if (!record(value) || !isUUID(value.tenantId) || !(value.tenantName === null || isString(value.tenantName)) || !count(value.pending) || !count(value.ready)
      || !(value.oldestReadySeconds === null || count(value.oldestReadySeconds)) || !count(value.expiredLeases) || !count(value.failed)
      || !count(value.skipped) || typeof value.stale !== 'boolean' || value.ready > value.pending
      || (value.ready > 0) !== (value.oldestReadySeconds !== null)) throw invalidResponse();
  return { tenantId: value.tenantId, tenantName: value.tenantName as string | null, pending: value.pending, ready: value.ready,
    oldestReadySeconds: value.oldestReadySeconds as number | null, expiredLeases: value.expiredLeases, failed: value.failed, skipped: value.skipped, stale: value.stale };
}
export function outboxSummary(value: unknown): OutboxSummary {
  if (!record(value) || !Array.isArray(value.tenants) || value.tenants.length > 200 || value.staleAfterSeconds !== 60 || !isDate(value.generatedAt)) throw invalidResponse();
  return { tenants: value.tenants.map(tenantQueue), staleAfterSeconds: 60, generatedAt: value.generatedAt };
}
const TASK_STATES: readonly OutboxTaskState[] = ['PENDING', 'LEASED', 'DELIVERED', 'SKIPPED', 'FAILED'];
export function outboxTask(value: unknown, uncertain = false): OutboxTask {
  if (!record(value) || !isUUID(value.taskId) || !isUUID(value.tenantId) || !isString(value.eventType) || !/^[A-Z][A-Z_]{1,63}$/.test(value.eventType)
      || !isUUID(value.aggregateId) || !oneOf(value.state, TASK_STATES) || !count(value.attempts)
      || !(value.lastError === null || (isString(value.lastError) && codePoints(value.lastError) <= 300)) || !isDate(value.createdAt)
      || !isDate(value.availableAt) || !(value.processedAt === null || isDate(value.processedAt))
      || (value.state === 'FAILED' && value.lastError === null)
      || (['DELIVERED', 'SKIPPED', 'FAILED'].includes(value.state)) !== (value.processedAt !== null)) throw invalidResponse(uncertain);
  return { taskId: value.taskId, tenantId: value.tenantId, eventType: value.eventType, aggregateId: value.aggregateId, state: value.state,
    attempts: value.attempts, lastError: value.lastError as string | null, createdAt: value.createdAt, availableAt: value.availableAt,
    processedAt: value.processedAt as string | null };
}
function taskPage(value: unknown): OutboxTaskPage {
  if (!record(value) || !Array.isArray(value.tasks) || value.tasks.length > 100 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { tasks: value.tasks.map((item) => outboxTask(item)), truncated: value.truncated };
}

export function createPlatformApi(client: CondPulseApi = api) {
  return {
    session: async (signal?: AbortSignal) => client.call('/v1/platform/session', platformSession, signal ? { signal } : {}),
    /** Busca opcional por trecho do nome ou id exato (o servidor trata % e _ como texto). */
    tenants: async (search: string | null, signal?: AbortSignal) =>
      client.call(`/v1/platform/tenants${search ? `?${new URLSearchParams({ q: search })}` : ''}`, tenantPage, signal ? { signal } : {}),
    updateTenant: async (tenantId: string, body: PlatformTenantUpdate) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}`, tenantChange, { method: 'PUT', body }),
    audit: async (cursor: string | null, signal?: AbortSignal) => {
      const query = new URLSearchParams({ limit: '30' });
      if (cursor) query.set('cursor', cursor);
      return client.call(`/v1/platform/audit-events?${query}`, auditPage, signal ? { signal } : {});
    },
    outbox: async (signal?: AbortSignal) => client.call('/v1/platform/outbox', outboxSummary, signal ? { signal } : {}),
    outboxTasks: async (state: 'FAILED' | 'SKIPPED', tenantId: string | null, signal?: AbortSignal) => {
      const query = new URLSearchParams({ state });
      if (tenantId) query.set('tenantId', identifier(tenantId));
      return client.call(`/v1/platform/outbox/tasks?${query}`, taskPage, signal ? { signal } : {});
    },
    /** Devolução com motivo; a resposta precisa ser a mesma tarefa, de volta à fila sem tentativa nem erro. */
    replayTask: async (taskId: string, reason: string) =>
      client.call(`/v1/platform/outbox/tasks/${identifier(taskId)}/replay`, (value) => {
        const task = outboxTask(value, true);
        if (task.taskId !== taskId || task.state !== 'PENDING' || task.attempts !== 0 || task.lastError !== null) throw invalidResponse(true);
        return task;
      }, { method: 'POST', body: { reason } }),
  };
}
export const platform = createPlatformApi();
