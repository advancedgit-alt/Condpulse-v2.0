import { ContractError } from './reservations';

/**
 * Credenciais e histórico dos equipamentos no aplicativo (CP-046/CP-049), validados como na web antes de chegar à tela.
 * A situação vem do servidor; o aplicativo nunca afirma bloqueio sem confirmação nem presença por falta de saída.
 */
export type CredentialStatus = 'ACTIVE' | 'SYNCING' | 'PARTIAL' | 'FAILED' | 'EXPIRED' | 'REVOKING' | 'REVOKED' | 'NO_DEVICES';
const statuses: readonly CredentialStatus[] = ['ACTIVE', 'SYNCING', 'PARTIAL', 'FAILED', 'EXPIRED', 'REVOKING', 'REVOKED', 'NO_DEVICES'];
export interface DeviceSync { deviceId: string; deviceLabel: string; state: 'CONFIRMED' | 'PENDING' | 'FAILED'; confirmedAt: string | null }
export interface Credential {
  credentialId: string; unitId: string; kind: 'CARD' | 'QR'; label: string; desiredState: 'ACTIVE' | 'REVOKED'; validUntil: string | null;
  status: CredentialStatus; revokedAt: string | null; devices: DeviceSync[];
}
export type DeviceEventKind = 'AUTHORIZED' | 'DENIED' | 'RELAY_TRIGGERED' | 'DOOR_OPENED' | 'PASSAGE_DETECTED';
const residentKinds: readonly DeviceEventKind[] = ['AUTHORIZED', 'DENIED', 'RELAY_TRIGGERED', 'DOOR_OPENED', 'PASSAGE_DETECTED'];
export interface DeviceEvent {
  eventId: string; kind: DeviceEventKind; direction: 'ENTRY' | 'EXIT' | 'UNKNOWN' | null; occurredAt: string; deviceLabel: string | null;
  credentialLabel: string | null; exitRecorded: boolean | null;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const optionalDate = (value: unknown) => value === null || date(value);
const text = (value: unknown, max: number): value is string => typeof value === 'string' && value.length > 0 && [...value].length <= max;
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

function device(value: unknown): DeviceSync {
  check(record(value) && uuid(value.deviceId) && text(value.deviceLabel, 120) && ['CONFIRMED', 'PENDING', 'FAILED'].includes(value.state as string)
    && optionalDate(value.confirmedAt) && (value.state === 'CONFIRMED') === (value.confirmedAt !== null));
  return { deviceId: value.deviceId as string, deviceLabel: value.deviceLabel as string, state: value.state as DeviceSync['state'], confirmedAt: value.confirmedAt as string | null };
}
export function credential(value: unknown, unitId: string): Credential {
  check(record(value) && uuid(value.credentialId) && uuid(value.unitId) && value.unitId.toLowerCase() === unitId.toLowerCase()
    && ['CARD', 'QR'].includes(value.kind as string) && text(value.label, 80) && ['ACTIVE', 'REVOKED'].includes(value.desiredState as string)
    && optionalDate(value.validUntil) && statuses.includes(value.status as CredentialStatus) && optionalDate(value.revokedAt) && Array.isArray(value.devices));
  const devices = (value.devices as unknown[]).map(device);
  const revoked = value.desiredState === 'REVOKED';
  const allConfirmed = devices.length > 0 && devices.every((item) => item.state === 'CONFIRMED');
  // Mesma coerência da web: revogação só para revogada; "ativa/revogada em todos" só com todas as confirmações.
  check(revoked === (value.revokedAt !== null) && (value.status === 'REVOKING' || value.status === 'REVOKED') === (revoked && devices.length > 0)
    && (value.status === 'NO_DEVICES') === (devices.length === 0) && !(value.status === 'EXPIRED' && (revoked || value.validUntil === null))
    && (value.status === 'EXPIRED' || (value.status === 'ACTIVE' || value.status === 'REVOKED') === allConfirmed));
  return { credentialId: value.credentialId as string, unitId: value.unitId as string, kind: value.kind as Credential['kind'], label: value.label as string,
    desiredState: value.desiredState as Credential['desiredState'], validUntil: value.validUntil as string | null, status: value.status as CredentialStatus,
    revokedAt: value.revokedAt as string | null, devices };
}
export function credentialPage(value: unknown, unitId: string): { credentials: Credential[]; truncated: boolean } {
  check(record(value) && typeof value.truncated === 'boolean' && Array.isArray(value.credentials) && value.credentials.length <= 100);
  return { credentials: (value.credentials as unknown[]).map((item) => credential(item, unitId)), truncated: value.truncated as boolean };
}
export function deviceEvent(value: unknown): DeviceEvent {
  check(record(value) && uuid(value.eventId) && residentKinds.includes(value.kind as DeviceEventKind) && value.state === 'ACCEPTED'
    && (value.direction === null || ['ENTRY', 'EXIT', 'UNKNOWN'].includes(value.direction as string)) && date(value.occurredAt)
    && (value.deviceLabel === null || text(value.deviceLabel, 120)) && (value.credentialLabel === null || text(value.credentialLabel, 80))
    && (value.exitRecorded === null || typeof value.exitRecorded === 'boolean')
    && (value.exitRecorded === null || (value.kind === 'PASSAGE_DETECTED' && value.direction === 'ENTRY')));
  return { eventId: value.eventId as string, kind: value.kind as DeviceEventKind, direction: value.direction as DeviceEvent['direction'],
    occurredAt: value.occurredAt as string, deviceLabel: value.deviceLabel as string | null, credentialLabel: value.credentialLabel as string | null,
    exitRecorded: value.exitRecorded as boolean | null };
}
export function eventPage(value: unknown): { events: DeviceEvent[]; truncated: boolean } {
  check(record(value) && typeof value.truncated === 'boolean' && Array.isArray(value.events) && value.events.length <= 100);
  return { events: (value.events as unknown[]).map(deviceEvent), truncated: value.truncated as boolean };
}
/** Descrição sem inferir nada além do que o equipamento informou. */
export function describeEvent(event: DeviceEvent): string {
  switch (event.kind) {
    case 'AUTHORIZED': return 'Acesso autorizado pelo equipamento';
    case 'DENIED': return 'Acesso negado pelo equipamento';
    case 'RELAY_TRIGGERED': return 'Abertura acionada';
    case 'DOOR_OPENED': return 'Porta aberta';
    case 'PASSAGE_DETECTED': return event.direction === 'ENTRY' ? 'Passagem de entrada' : event.direction === 'EXIT' ? 'Passagem de saída' : 'Passagem sem direção registrada';
  }
}
