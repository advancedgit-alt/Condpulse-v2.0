import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, list, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Credenciais de acesso com estado desejado versionado (CP-046). A situação por equipamento vem do servidor, derivada da
 * versão e da ação desejadas atuais; a tela nunca afirma "bloqueado" enquanto um equipamento não confirmar a revogação.
 */
export const credentialStatuses = ['ACTIVE', 'SYNCING', 'PARTIAL', 'FAILED', 'EXPIRED', 'REVOKING', 'REVOKED', 'NO_DEVICES'] as const;
export type CredentialStatus = typeof credentialStatuses[number];
export type CredentialKind = 'CARD' | 'QR';
const directions = ['ENTRY', 'EXIT', 'BOTH', 'UNKNOWN'] as const;
const outcomes = ['ACCEPTED', 'APPLIED', 'REJECTED', 'RATE_LIMITED', 'UNKNOWN', 'UNAVAILABLE'] as const;
export interface DeviceSync {
  deviceId: UUID; deviceLabel: string; direction: typeof directions[number]; state: 'CONFIRMED' | 'PENDING' | 'FAILED';
  lastOutcome: typeof outcomes[number] | null; confirmedAt: ISODateTime | null; lastReason: string | null; lastAttemptAt: ISODateTime | null;
}
export interface Credential {
  credentialId: UUID; connectorId: UUID; membershipId: UUID; unitId: UUID; unitLabel: string; holderName: string | null; kind: CredentialKind;
  label: string; desiredState: 'ACTIVE' | 'REVOKED'; desiredVersion: number; validUntil: ISODateTime | null; status: CredentialStatus; revokeReason: string | null;
  revokedAt: ISODateTime | null; createdAt: ISODateTime; version: number; devices: DeviceSync[];
}
export interface CredentialPage { credentials: Credential[]; truncated: boolean }
export interface AccessConnector {
  connectorId: UUID; provider: 'SIMULATED' | 'PARTNER'; label: string; status: 'ACTIVE' | 'DISABLED';
  devices: { deviceId: UUID; label: string; direction: typeof directions[number]; status: 'ACTIVE' | 'RETIRED' }[];
}
export interface CreateCredentialBody { connectorId: UUID; membershipId: UUID; kind: CredentialKind; label: string }

const text = (value: unknown, minimum: number, maximum: number): value is string =>
  typeof value === 'string' && codePoints(value) >= minimum && codePoints(value) <= maximum;
const optionalDate = (value: unknown) => value === null || isDate(value);

function device(value: unknown): DeviceSync {
  if (!record(value) || !isUUID(value.deviceId) || !text(value.deviceLabel, 1, 120) || !oneOf(value.direction, directions)
      || !oneOf(value.state, ['CONFIRMED', 'PENDING', 'FAILED'] as const) || !(value.lastOutcome === null || oneOf(value.lastOutcome, outcomes))
      || !optionalDate(value.confirmedAt) || !(value.lastReason === null || text(value.lastReason, 1, 200)) || !optionalDate(value.lastAttemptAt))
    throw invalidResponse();
  // Confirmado sempre tem horário; pendente ou recusado nunca mostra confirmação.
  if ((value.state === 'CONFIRMED') !== (value.confirmedAt !== null)) throw invalidResponse();
  return { deviceId: value.deviceId, deviceLabel: value.deviceLabel, direction: value.direction, state: value.state,
    lastOutcome: value.lastOutcome as DeviceSync['lastOutcome'], confirmedAt: value.confirmedAt as string | null,
    lastReason: value.lastReason as string | null, lastAttemptAt: value.lastAttemptAt as string | null };
}
export function credential(value: unknown, uncertain = false): Credential {
  if (!record(value) || !isUUID(value.credentialId) || !isUUID(value.connectorId) || !isUUID(value.membershipId) || !isUUID(value.unitId)
      || !text(value.unitLabel, 1, 160) || !(value.holderName === null || text(value.holderName, 1, 160)) || !oneOf(value.kind, ['CARD', 'QR'] as const)
      || !text(value.label, 1, 80) || !oneOf(value.desiredState, ['ACTIVE', 'REVOKED'] as const) || !isInteger(value.desiredVersion)
      || value.desiredVersion < 1 || !optionalDate(value.validUntil) || !oneOf(value.status, credentialStatuses) || !(value.revokeReason === null || text(value.revokeReason, 1, 300))
      || !optionalDate(value.revokedAt) || !isDate(value.createdAt) || !isInteger(value.version) || !Array.isArray(value.devices))
    throw invalidResponse(uncertain);
  let devices: DeviceSync[];
  try { devices = value.devices.map(device); } catch { throw invalidResponse(uncertain); }
  const revoked = value.desiredState === 'REVOKED';
  // Revogada tem motivo, horário e versão nova; situação de revogação só para revogada (e vice-versa).
  if (revoked !== (value.revokeReason !== null) || revoked !== (value.revokedAt !== null) || (revoked && value.desiredVersion < 2)
      || (!revoked && value.desiredVersion !== 1)
      || (value.status === 'REVOKING' || value.status === 'REVOKED') !== (revoked && devices.length > 0)
      || (value.status === 'NO_DEVICES') !== (devices.length === 0)
      || (value.status === 'EXPIRED' && (revoked || value.validUntil === null))
      || (value.status !== 'EXPIRED' && (value.status === 'REVOKED' || value.status === 'ACTIVE') !== (devices.length > 0 && devices.every((item) => item.state === 'CONFIRMED'))))
    throw invalidResponse(uncertain);
  return { credentialId: value.credentialId, connectorId: value.connectorId, membershipId: value.membershipId, unitId: value.unitId,
    unitLabel: value.unitLabel, holderName: value.holderName as string | null, kind: value.kind, label: value.label, desiredState: value.desiredState,
    desiredVersion: value.desiredVersion, validUntil: value.validUntil as string | null, status: value.status, revokeReason: value.revokeReason as string | null,
    revokedAt: value.revokedAt as string | null, createdAt: value.createdAt, version: value.version, devices };
}
export function credentialPage(value: unknown): CredentialPage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.credentials) || value.credentials.length > 100) throw invalidResponse();
  return { credentials: list(value.credentials, (item) => credential(item)), truncated: value.truncated };
}
function connectors(value: unknown): AccessConnector[] {
  if (!record(value) || !Array.isArray(value.connectors)) throw invalidResponse();
  return list(value.connectors, (item) => {
    if (!record(item) || !isUUID(item.connectorId) || !oneOf(item.provider, ['SIMULATED', 'PARTNER'] as const) || !text(item.label, 1, 120)
        || !oneOf(item.status, ['ACTIVE', 'DISABLED'] as const) || !Array.isArray(item.devices)) throw invalidResponse();
    return { connectorId: item.connectorId, provider: item.provider, label: item.label, status: item.status, devices: list(item.devices, (entry) => {
      if (!record(entry) || !isUUID(entry.deviceId) || !text(entry.label, 1, 120) || !oneOf(entry.direction, directions)
          || !oneOf(entry.status, ['ACTIVE', 'RETIRED'] as const)) throw invalidResponse();
      return { deviceId: entry.deviceId, label: entry.label, direction: entry.direction, status: entry.status };
    }) };
  });
}

/** Evento do equipamento (CP-049): tipos distintos para autorização, recusa, abertura e passagem; nada inferido. */
export const deviceEventKinds = ['AUTHORIZED', 'DENIED', 'RELAY_TRIGGERED', 'DOOR_OPENED', 'PASSAGE_DETECTED', 'CREDENTIAL_APPLIED',
  'CREDENTIAL_REJECTED', 'CREDENTIAL_REVOKED', 'DEVICE_ONLINE', 'DEVICE_OFFLINE'] as const;
export interface DeviceEvent {
  eventId: UUID; kind: typeof deviceEventKinds[number]; direction: 'ENTRY' | 'EXIT' | 'UNKNOWN' | null; evidence: string | null; occurredAt: ISODateTime;
  deviceLabel: string | null; credentialLabel: string | null; unitLabel: string | null; state: 'ACCEPTED' | 'QUARANTINED';
  quarantineReason: string | null; exitRecorded: boolean | null;
}
const code = (value: unknown) => value === null || (typeof value === 'string' && /^[A-Z0-9_]{1,60}$/.test(value));
export function deviceEvent(value: unknown): DeviceEvent {
  if (!record(value) || !isUUID(value.eventId) || !oneOf(value.kind, deviceEventKinds) || !(value.direction === null || oneOf(value.direction, ['ENTRY', 'EXIT', 'UNKNOWN'] as const))
      || !code(value.evidence) || !isDate(value.occurredAt) || !(value.deviceLabel === null || text(value.deviceLabel, 1, 120))
      || !(value.credentialLabel === null || text(value.credentialLabel, 1, 80)) || !(value.unitLabel === null || text(value.unitLabel, 1, 160))
      || !oneOf(value.state, ['ACCEPTED', 'QUARANTINED'] as const) || !code(value.quarantineReason)
      || !(value.exitRecorded === null || typeof value.exitRecorded === 'boolean')) throw invalidResponse();
  // Quarentena tem motivo e não tem equipamento; "saída registrada" só existe para passagem de entrada.
  if ((value.state === 'QUARANTINED') !== (value.quarantineReason !== null) || (value.state === 'QUARANTINED' && value.deviceLabel !== null)
      || (value.exitRecorded !== null && !(value.kind === 'PASSAGE_DETECTED' && value.direction === 'ENTRY'))) throw invalidResponse();
  return { eventId: value.eventId, kind: value.kind, direction: value.direction as DeviceEvent['direction'], evidence: value.evidence as string | null,
    occurredAt: value.occurredAt, deviceLabel: value.deviceLabel as string | null, credentialLabel: value.credentialLabel as string | null,
    unitLabel: value.unitLabel as string | null, state: value.state, quarantineReason: value.quarantineReason as string | null,
    exitRecorded: value.exitRecorded as boolean | null };
}
function eventPage(value: unknown): { events: DeviceEvent[]; truncated: boolean } {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.events) || value.events.length > 100) throw invalidResponse();
  return { events: list(value.events, deviceEvent), truncated: value.truncated };
}
/** Saúde dos equipamentos (CP-048): "no ar" só com observação recente; sem informação recente nunca vira "no ar". */
export const deviceHealthStatuses = ['ONLINE', 'OFFLINE', 'UNKNOWN', 'RETIRED'] as const;
const deviceHealthReasons = ['HEARTBEAT_STALE', 'NOT_REPORTED', 'STALE', 'NEVER_CHECKED', 'CONNECTOR_UNREACHABLE', 'CONNECTOR_DISABLED'] as const;
export interface DeviceHealth {
  deviceId: UUID; label: string; direction: typeof directions[number]; status: typeof deviceHealthStatuses[number];
  reason: typeof deviceHealthReasons[number] | null; observedAt: ISODateTime | null; stateSince: ISODateTime | null; lastHeartbeat: ISODateTime | null;
  clockSkewSeconds: number | null; clockDrift: boolean; pendingApply: number; pendingRevoke: number; rejected: number; oldestPendingRevocation: ISODateTime | null;
}
export interface ConnectorHealth {
  connectorId: UUID; provider: 'SIMULATED' | 'PARTNER'; label: string; status: 'OK' | 'UNREACHABLE' | 'UNKNOWN' | 'DISABLED'; reason: string | null;
  checkedAt: ISODateTime | null; lastReachableAt: ISODateTime | null; pendingCommands: number | null; observedDelaySeconds: number | null; devices: DeviceHealth[];
}
export interface AccessHealth { generatedAt: ISODateTime; staleAfterSeconds: number; connectors: ConnectorHealth[] }
const count = (value: unknown): value is number => isInteger(value) && value >= 0;
const optionalCount = (value: unknown) => value === null || count(value);
function deviceHealth(value: unknown, connector: ConnectorHealth['status']): DeviceHealth {
  if (!record(value) || !isUUID(value.deviceId) || !text(value.label, 1, 120) || !oneOf(value.direction, directions) || !oneOf(value.status, deviceHealthStatuses)
      || !(value.reason === null || oneOf(value.reason, deviceHealthReasons)) || !optionalDate(value.observedAt) || !optionalDate(value.stateSince)
      || !optionalDate(value.lastHeartbeat) || !(value.clockSkewSeconds === null || isInteger(value.clockSkewSeconds)) || typeof value.clockDrift !== 'boolean'
      || !count(value.pendingApply) || !count(value.pendingRevoke) || !count(value.rejected) || !optionalDate(value.oldestPendingRevocation)) throw invalidResponse();
  // "No ar" exige conector respondendo e observação; sem informação sempre tem motivo; revogação pendente tem data.
  if ((value.status === 'ONLINE' && (connector !== 'OK' || value.observedAt === null || value.reason !== null))
      || (value.status === 'UNKNOWN' && value.reason === null) || (value.status === 'OFFLINE' && value.observedAt === null)
      || ((value.pendingRevoke > 0) !== (value.oldestPendingRevocation !== null))) throw invalidResponse();
  return { deviceId: value.deviceId, label: value.label, direction: value.direction, status: value.status, reason: value.reason as DeviceHealth['reason'],
    observedAt: value.observedAt as string | null, stateSince: value.stateSince as string | null, lastHeartbeat: value.lastHeartbeat as string | null,
    clockSkewSeconds: value.clockSkewSeconds as number | null, clockDrift: value.clockDrift, pendingApply: value.pendingApply, pendingRevoke: value.pendingRevoke,
    rejected: value.rejected, oldestPendingRevocation: value.oldestPendingRevocation as string | null };
}
export function accessHealth(value: unknown): AccessHealth {
  if (!record(value) || !isDate(value.generatedAt) || !count(value.staleAfterSeconds) || !Array.isArray(value.connectors)) throw invalidResponse();
  return { generatedAt: value.generatedAt, staleAfterSeconds: value.staleAfterSeconds, connectors: list(value.connectors, (item) => {
    if (!record(item) || !isUUID(item.connectorId) || !oneOf(item.provider, ['SIMULATED', 'PARTNER'] as const) || !text(item.label, 1, 120)
        || !oneOf(item.status, ['OK', 'UNREACHABLE', 'UNKNOWN', 'DISABLED'] as const) || !code(item.reason) || !optionalDate(item.checkedAt)
        || !optionalDate(item.lastReachableAt) || !optionalCount(item.pendingCommands) || !optionalCount(item.observedDelaySeconds) || !Array.isArray(item.devices)) throw invalidResponse();
    if ((item.status === 'OK' && (item.checkedAt === null || item.reason !== null)) || (item.status === 'UNREACHABLE' && item.reason === null)) throw invalidResponse();
    const status = item.status;
    return { connectorId: item.connectorId, provider: item.provider, label: item.label, status, reason: item.reason as string | null,
      checkedAt: item.checkedAt as string | null, lastReachableAt: item.lastReachableAt as string | null, pendingCommands: item.pendingCommands as number | null,
      observedDelaySeconds: item.observedDelaySeconds as number | null, devices: list(item.devices, (entry) => deviceHealth(entry, status)) };
  }) };
}
const residentKinds: readonly DeviceEvent['kind'][] = ['AUTHORIZED', 'DENIED', 'RELAY_TRIGGERED', 'DOOR_OPENED', 'PASSAGE_DETECTED'];

export function createAccessCredentialsApi(client: CondPulseApi = api) {
  const tenant = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  return {
    connectors: async (tenantId: string, signal?: AbortSignal) => client.call(`${tenant(tenantId)}/access-connectors`, connectors, options(signal)),
    managed: async (tenantId: string, pending: boolean, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/management/access-credentials${pending ? '?pending=true' : ''}`, credentialPage, options(signal)),
    pendingRevocations: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/concierge/pending-revocations`, (value) => {
        const page = credentialPage(value);
        if (page.credentials.some((item) => item.status !== 'REVOKING')) throw invalidResponse();
        return page;
      }, options(signal)),
    unitCredentials: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/units/${identifier(unitId)}/access-credentials`, (value) => {
        const page = credentialPage(value);
        if (page.credentials.some((item) => item.unitId !== unitId)) throw invalidResponse();
        return page;
      }, options(signal)),
    unitEvents: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/units/${identifier(unitId)}/device-events`, (value) => {
        const page = eventPage(value);
        // A unidade só recebe autorização, recusa, abertura e passagem aceitas.
        if (page.events.some((item) => !residentKinds.includes(item.kind) || item.state !== 'ACCEPTED')) throw invalidResponse();
        return page;
      }, options(signal)),
    health: async (tenantId: string, signal?: AbortSignal) => client.call(`${tenant(tenantId)}/access-health`, accessHealth, options(signal)),
    recentEvents: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/concierge/device-events`, eventPage, options(signal)),
    create: async (tenantId: string, key: string, body: CreateCredentialBody) =>
      client.call(`${tenant(tenantId)}/management/access-credentials`, (value, response) => {
        const created = credential(value, true);
        const replayed = response.headers.get('Idempotency-Replayed') === 'true';
        // Nova nasce ativa na versão 1, para o vínculo e o tipo enviados; repetição devolve o estado atual.
        if (created.membershipId !== body.membershipId || created.connectorId !== body.connectorId
            || (!replayed && (created.desiredState !== 'ACTIVE' || created.kind !== body.kind || created.label !== body.label))) throw invalidResponse(true);
        return { credential: created, replayed };
      }, { method: 'POST', body, key }),
    revoke: async (tenantId: string, credentialId: string, body: { reason: string; expectedVersion: number }) =>
      client.call(`${tenant(tenantId)}/management/access-credentials/${identifier(credentialId)}/revoke`, (value) => {
        const revoked = credential(value, true);
        if (revoked.credentialId !== credentialId || revoked.desiredState !== 'REVOKED') throw invalidResponse(true);
        return revoked;
      }, { method: 'POST', body }),
  };
}
export const accessCredentials = createAccessCredentialsApi();
