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
/** Uso agregado, sem conteúdo residencial: unidades ativas, vínculos vigentes, gestores vigentes e última atividade. */
export interface TenantUsage { activeUnits: number; activeMemberships: number; activeManagers: number; lastActivityAt: ISODateTime }
/** Assinatura CondPulse (fase 5); null quando o financeiro está desligado ou sem billing.read. */
export interface TenantBilling { activeSubscription: boolean; openCharges: number; overdueCharges: number }
export interface PlatformTenant {
  tenantId: UUID; name: string; timeZone: string; active: boolean; version: number; createdAt: ISODateTime;
  updatedAt: ISODateTime; onboarding: OnboardingReceipt | null; origin: 'ONBOARDING' | 'PLATFORM' | null;
  /** null: API anterior a V035 (a web publica antes da API): a tela mostra "não informado". */
  usage: TenantUsage | null;
  billing: TenantBilling | null;
}
export interface PlatformManager { membershipId: UUID; displayName: string; status: 'PENDING' | 'ACTIVE' | 'SUSPENDED'; since: ISODateTime }
export interface ManagerInvitation {
  invitationId: UUID; displayName: string; status: 'PENDING' | 'EXPIRED' | 'ACCEPTED' | 'REVOKED'; createdAt: ISODateTime;
  expiresAt: ISODateTime; acceptedAt: ISODateTime | null; revokedAt: ISODateTime | null;
}
export interface PlatformTenantDetail {
  tenant: PlatformTenant; managers: PlatformManager[]; managerInvitations: ManagerInvitation[]; recentEvents: PlatformAuditEvent[];
}
/** Área inicial opcional, com as mesmas regras da gestão local. */
export interface InitialAmenity {
  name: string; capacity: number; approvalRequired: boolean; feeCents: number; bufferMinutes: number;
  policy: { schemaVersion: 1; weekDays: number[]; opensAt: string; closesAt: string; minimumLeadMinutes: number; maximumAdvanceDays: number;
    maximumDurationMinutes: number; maximumActivePerUnit: number; cancellationLeadMinutes: number };
}
export interface CreateTenantInput {
  name: string; timeZone: string; units: string[]; manager: { displayName: string; email: string; validDays: number };
  amenity: InitialAmenity | null; reason: string;
}
/** applied=false: repetição da mesma chave; o token do convite não volta (emitir outro convite revoga o anterior). */
export type TenantCreated = { tenant: PlatformTenant; applied: true; invitationId: UUID; invitationToken: string; invitationExpiresAt: ISODateTime }
  | { tenant: PlatformTenant; applied: false; invitationId: null; invitationToken: null; invitationExpiresAt: null };
export interface ManagerInvitationIssued { invitationId: UUID; token: string; expiresAt: ISODateTime }
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
function count(value: unknown): value is number { return isInteger(value) && value >= 0; }
const INVITATION_TOKEN = /^[A-Za-z0-9_-]{43}$/;

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
function usage(value: unknown, uncertain: boolean): TenantUsage | null {
  if (value === undefined || value === null) return null;
  if (!record(value) || !count(value.activeUnits) || !count(value.activeMemberships) || !count(value.activeManagers)
      || value.activeManagers > value.activeMemberships || !isDate(value.lastActivityAt)) throw invalidResponse(uncertain);
  return { activeUnits: value.activeUnits, activeMemberships: value.activeMemberships, activeManagers: value.activeManagers,
    lastActivityAt: value.lastActivityAt };
}
function billingSummary(value: unknown, uncertain: boolean): TenantBilling | null {
  if (value === undefined || value === null) return null;
  if (!record(value) || typeof value.activeSubscription !== 'boolean' || !count(value.openCharges) || !count(value.overdueCharges)
      || value.overdueCharges > value.openCharges) throw invalidResponse(uncertain);
  return { activeSubscription: value.activeSubscription, openCharges: value.openCharges, overdueCharges: value.overdueCharges };
}
export function platformTenant(value: unknown, uncertain = false): PlatformTenant {
  // Comprimento em code points, como o banco: nome com emoji não derruba a lista inteira.
  if (!record(value) || !isUUID(value.tenantId) || !isString(value.name) || value.name.length < 1 || codePoints(value.name) > 160
      || !isString(value.timeZone) || typeof value.active !== 'boolean' || !isInteger(value.version) || !isDate(value.createdAt)
      || !isDate(value.updatedAt) || !('onboarding' in value)
      || !(value.origin === undefined || value.origin === null || oneOf(value.origin, ['ONBOARDING', 'PLATFORM'] as const))) throw invalidResponse(uncertain);
  return { tenantId: value.tenantId, name: value.name, timeZone: value.timeZone, active: value.active, version: value.version,
    createdAt: value.createdAt, updatedAt: value.updatedAt, onboarding: receipt(value.onboarding),
    origin: (value.origin ?? null) as PlatformTenant['origin'], usage: usage(value.usage, uncertain), billing: billingSummary(value.billing, uncertain) };
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

function manager(value: unknown): PlatformManager {
  if (!record(value) || !isUUID(value.membershipId) || !isString(value.displayName) || !oneOf(value.status, ['PENDING', 'ACTIVE', 'SUSPENDED'] as const)
      || !isDate(value.since)) throw invalidResponse();
  return { membershipId: value.membershipId, displayName: value.displayName, status: value.status, since: value.since };
}
function managerInvitation(value: unknown): ManagerInvitation {
  if (!record(value) || !isUUID(value.invitationId) || !isString(value.displayName)
      || !oneOf(value.status, ['PENDING', 'EXPIRED', 'ACCEPTED', 'REVOKED'] as const) || !isDate(value.createdAt) || !isDate(value.expiresAt)
      || !(value.acceptedAt === null || isDate(value.acceptedAt)) || !(value.revokedAt === null || isDate(value.revokedAt))
      || (value.status === 'ACCEPTED') !== (value.acceptedAt !== null) || (value.status === 'REVOKED') !== (value.revokedAt !== null)) throw invalidResponse();
  return { invitationId: value.invitationId, displayName: value.displayName, status: value.status, createdAt: value.createdAt,
    expiresAt: value.expiresAt, acceptedAt: value.acceptedAt as string | null, revokedAt: value.revokedAt as string | null };
}
export function tenantDetail(value: unknown): PlatformTenantDetail {
  if (!record(value) || !Array.isArray(value.managers) || value.managers.length > 50 || !Array.isArray(value.managerInvitations)
      || value.managerInvitations.length > 20 || !Array.isArray(value.recentEvents) || value.recentEvents.length > 10) throw invalidResponse();
  return { tenant: platformTenant(value.tenant), managers: value.managers.map(manager), managerInvitations: value.managerInvitations.map(managerInvitation),
    recentEvents: value.recentEvents.map(auditEvent) };
}
export function tenantCreated(value: unknown): TenantCreated {
  if (!record(value) || typeof value.applied !== 'boolean') throw invalidResponse(true);
  const tenant = platformTenant(value.tenant, true);
  if (value.applied) {
    if (!isUUID(value.invitationId) || !isString(value.invitationToken) || !INVITATION_TOKEN.test(value.invitationToken)
        || !isDate(value.invitationExpiresAt)) throw invalidResponse(true);
    return { tenant, applied: true, invitationId: value.invitationId, invitationToken: value.invitationToken, invitationExpiresAt: value.invitationExpiresAt };
  }
  if (value.invitationId !== null || value.invitationToken !== null || value.invitationExpiresAt !== null) throw invalidResponse(true);
  return { tenant, applied: false, invitationId: null, invitationToken: null, invitationExpiresAt: null };
}
export function invitationIssued(value: unknown): ManagerInvitationIssued {
  if (!record(value) || !isUUID(value.invitationId) || !isString(value.token) || !INVITATION_TOKEN.test(value.token) || !isDate(value.expiresAt))
    throw invalidResponse(true);
  return { invitationId: value.invitationId, token: value.token, expiresAt: value.expiresAt };
}
/** Link do convite de gestor: o token vai só no fragmento (não chega a servidores nem a logs de acesso). */
export function managerInvitationLink(origin: string, token: string): string {
  if (!INVITATION_TOKEN.test(token)) throw new Error('Token de convite inválido.');
  return `${origin}/convite-gestor#token=${token}`;
}

export function createPlatformApi(client: CondPulseApi = api) {
  return {
    session: async (signal?: AbortSignal) => client.call('/v1/platform/session', platformSession, signal ? { signal } : {}),
    /** Busca opcional por trecho do nome ou id exato (o servidor trata % e _ como texto). */
    tenants: async (search: string | null, signal?: AbortSignal) =>
      client.call(`/v1/platform/tenants${search ? `?${new URLSearchParams({ q: search })}` : ''}`, tenantPage, signal ? { signal } : {}),
    tenant: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}`, tenantDetail, signal ? { signal } : {}),
    /** Criação com chave de operação: quem repete por resultado incerto conserva a mesma chave e os mesmos dados. */
    createTenant: async (body: CreateTenantInput, key: string) =>
      client.call('/v1/platform/tenants', tenantCreated, { method: 'POST', body, key }),
    inviteManager: async (tenantId: string, body: { displayName: string; email: string; validDays: number; reason: string }) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}/manager-invitations`, invitationIssued, { method: 'POST', body }),
    revokeManagerInvitation: async (tenantId: string, invitationId: string, reason: string) =>
      client.call(`/v1/platform/tenants/${identifier(tenantId)}/manager-invitations/${identifier(invitationId)}/revoke`, (value) => {
        const detail = tenantDetail(value);
        if (detail.tenant.tenantId !== tenantId) throw invalidResponse(true);
        return detail;
      }, { method: 'POST', body: { reason } }),
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
        if (task.taskId !== taskId || task.state !== 'PENDING' || task.lastError !== null || task.processedAt !== null) throw invalidResponse(true);
        return task;
      }, { method: 'POST', body: { reason } }),
  };
}
export const platform = createPlatformApi();
