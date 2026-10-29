import type { Interval, Policy } from '../reservations/slots';

/** Contratos de reservas no aplicativo (CP-024), validados como na web antes de chegar à tela. */
export interface Amenity {
  amenityId: string; name: string; capacity: number; policyVersion: number; feeCents: number; bufferMinutes: number;
  approvalRequired: boolean; bookingState: 'POLICY_NOT_CONFIGURED' | 'AVAILABLE_FOR_APPROVAL_REQUEST' | 'AVAILABLE_FOR_REQUEST';
  policy: Policy | null;
}
export type ReservationStatus = 'HELD' | 'PENDING_APPROVAL' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';
export interface Reservation {
  reservationId: string; unitId: string; amenityId: string; status: ReservationStatus; startsAt: string; endsAt: string;
  occupiedFrom: string; occupiedUntil: string; acceptedPolicyVersion: number; feeCents: number; version: number; decisionReason: string | null;
}
export interface Availability { amenityId: string; from: string; until: string; observedAt: string; blockedIntervals: Interval[] }
export interface CreateReservation { unitId: string; amenityId: string; startsAt: string; endsAt: string; acceptedPolicyVersion: number }

export class ContractError extends Error { constructor() { super('INVALID_API_RESPONSE'); this.name = 'ContractError'; } }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const int = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const time = (value: unknown): value is string => typeof value === 'string' && /^\d{2}:\d{2}(:\d{2})?$/.test(value);
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function identifier(value: string): string { check(uuid(value)); return value; }
export function operationKey(value: string): string { check(/^[A-Za-z0-9:_-]{16,128}$/.test(value)); return value; }
export function list<T>(value: unknown, decode: (item: unknown) => T, maximum: number): T[] {
  check(Array.isArray(value) && value.length <= maximum);
  return (value as unknown[]).map(decode);
}

function policy(value: unknown): Policy {
  check(record(value) && value.schemaVersion === 1 && Array.isArray(value.weekDays) && value.weekDays.length > 0
    && value.weekDays.every((day) => Number.isInteger(day) && day >= 1 && day <= 7) && time(value.opensAt) && time(value.closesAt)
    && int(value.minimumLeadMinutes) && int(value.maximumAdvanceDays) && int(value.maximumDurationMinutes)
    && int(value.maximumActivePerUnit) && int(value.cancellationLeadMinutes));
  const v = value as Record<string, unknown>;
  return { weekDays: [...(v.weekDays as number[])], opensAt: v.opensAt as string, closesAt: v.closesAt as string,
    minimumLeadMinutes: v.minimumLeadMinutes as number, maximumAdvanceDays: v.maximumAdvanceDays as number,
    maximumDurationMinutes: v.maximumDurationMinutes as number, maximumActivePerUnit: v.maximumActivePerUnit as number,
    cancellationLeadMinutes: v.cancellationLeadMinutes as number };
}
export function amenity(value: unknown): Amenity {
  check(record(value) && uuid(value.amenityId) && typeof value.name === 'string' && value.name.length > 0 && int(value.capacity)
    && int(value.policyVersion) && int(value.feeCents) && int(value.bufferMinutes) && typeof value.approvalRequired === 'boolean'
    && ['POLICY_NOT_CONFIGURED', 'AVAILABLE_FOR_APPROVAL_REQUEST', 'AVAILABLE_FOR_REQUEST'].includes(value.bookingState as string));
  const v = value as Record<string, unknown>;
  return { amenityId: v.amenityId as string, name: v.name as string, capacity: v.capacity as number, policyVersion: v.policyVersion as number,
    feeCents: v.feeCents as number, bufferMinutes: v.bufferMinutes as number, approvalRequired: v.approvalRequired as boolean,
    bookingState: v.bookingState as Amenity['bookingState'], policy: v.policy === null || v.policy === undefined ? null : policy(v.policy) };
}
export function reservation(value: unknown): Reservation {
  check(record(value) && uuid(value.reservationId) && uuid(value.unitId) && uuid(value.amenityId)
    && ['HELD', 'PENDING_APPROVAL', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'EXPIRED'].includes(value.status as string)
    && date(value.startsAt) && date(value.endsAt) && date(value.occupiedFrom) && date(value.occupiedUntil)
    && int(value.acceptedPolicyVersion) && int(value.feeCents) && int(value.version)
    && (value.decisionReason === undefined || value.decisionReason === null || typeof value.decisionReason === 'string'));
  const v = value as Record<string, unknown>;
  return { reservationId: v.reservationId as string, unitId: v.unitId as string, amenityId: v.amenityId as string,
    status: v.status as ReservationStatus, startsAt: v.startsAt as string, endsAt: v.endsAt as string,
    occupiedFrom: v.occupiedFrom as string, occupiedUntil: v.occupiedUntil as string, acceptedPolicyVersion: v.acceptedPolicyVersion as number,
    feeCents: v.feeCents as number, version: v.version as number, decisionReason: typeof v.decisionReason === 'string' ? v.decisionReason : null };
}
/** Disponibilidade precisa ser exatamente da consulta pedida (condomínio, unidade, área e janela). */
export function availability(value: unknown, expected: { tenantId: string; unitId: string; amenityId: string; from: string; until: string }): Availability {
  check(record(value) && typeof value.tenantId === 'string' && value.tenantId.toLowerCase() === expected.tenantId.toLowerCase()
    && typeof value.unitId === 'string' && value.unitId.toLowerCase() === expected.unitId.toLowerCase()
    && typeof value.amenityId === 'string' && value.amenityId.toLowerCase() === expected.amenityId.toLowerCase()
    && date(value.from) && date(value.until) && Date.parse(value.from as string) === Date.parse(expected.from)
    && Date.parse(value.until as string) === Date.parse(expected.until) && date(value.observedAt)
    && Array.isArray(value.blockedIntervals) && value.blockedIntervals.length <= 1000);
  const v = value as Record<string, unknown>;
  const blockedIntervals = (v.blockedIntervals as unknown[]).map((item): Interval => {
    check(record(item) && date(item.occupiedFrom) && date(item.occupiedUntil) && Date.parse(item.occupiedFrom as string) < Date.parse(item.occupiedUntil as string));
    return { occupiedFrom: (item as Record<string, string>).occupiedFrom!, occupiedUntil: (item as Record<string, string>).occupiedUntil! };
  });
  return { amenityId: v.amenityId as string, from: v.from as string, until: v.until as string, observedAt: v.observedAt as string, blockedIntervals };
}
