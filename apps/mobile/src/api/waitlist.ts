import { ContractError, reservation, type Reservation } from './reservations';

/**
 * Fila de espera de reservas no aplicativo (CP-040), validada como na web: oferta sem retenção ou prazo, ou entrada
 * aguardando com retenção, é resposta inconsistente e falha fechada.
 */
export type WaitlistState = 'WAITING' | 'OFFERED' | 'ACCEPTED' | 'LEFT' | 'EXPIRED' | 'CLOSED';
export interface WaitlistEntry {
  waitlistId: string; unitId: string; amenityId: string; state: WaitlistState; startsAt: string; endsAt: string;
  occupiedFrom: string; occupiedUntil: string; acceptedPolicyVersion: number; approvalRequired: boolean; feeCents: number;
  offerReservationId: string | null; offeredAt: string | null; offerExpiresAt: string | null; closedAt: string | null;
  createdByMe: boolean; position: number | null; version: number; createdAt: string;
}
export interface WaitlistPage { entries: WaitlistEntry[]; truncated: boolean }
export interface JoinWaitlist { unitId: string; amenityId: string; startsAt: string; endsAt: string; acceptedPolicyVersion: number }
export interface WaitlistChange { unitId: string; expectedVersion: number }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATES: readonly WaitlistState[] = ['WAITING', 'OFFERED', 'ACCEPTED', 'LEFT', 'EXPIRED', 'CLOSED'];
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const int = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const optionalDate = (value: unknown) => value === null || date(value);
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function waitlistEntry(value: unknown): WaitlistEntry {
  check(record(value) && uuid(value.waitlistId) && uuid(value.unitId) && uuid(value.amenityId) && STATES.includes(value.state as WaitlistState)
    && date(value.startsAt) && date(value.endsAt) && date(value.occupiedFrom) && date(value.occupiedUntil)
    && int(value.acceptedPolicyVersion) && typeof value.approvalRequired === 'boolean' && int(value.feeCents)
    && (value.offerReservationId === null || uuid(value.offerReservationId)) && optionalDate(value.offeredAt)
    && optionalDate(value.offerExpiresAt) && optionalDate(value.closedAt) && typeof value.createdByMe === 'boolean'
    && (value.position === null || (int(value.position) && (value.position as number) >= 1)) && int(value.version) && date(value.createdAt));
  const v = value as Record<string, unknown>;
  check(!(v.state === 'OFFERED' && (v.offerReservationId === null || v.offerExpiresAt === null)));
  check(!(v.state === 'WAITING' && v.offerReservationId !== null));
  return { waitlistId: v.waitlistId as string, unitId: v.unitId as string, amenityId: v.amenityId as string, state: v.state as WaitlistState,
    startsAt: v.startsAt as string, endsAt: v.endsAt as string, occupiedFrom: v.occupiedFrom as string, occupiedUntil: v.occupiedUntil as string,
    acceptedPolicyVersion: v.acceptedPolicyVersion as number, approvalRequired: v.approvalRequired as boolean, feeCents: v.feeCents as number,
    offerReservationId: v.offerReservationId as string | null, offeredAt: v.offeredAt as string | null,
    offerExpiresAt: v.offerExpiresAt as string | null, closedAt: v.closedAt as string | null, createdByMe: v.createdByMe as boolean,
    position: v.position as number | null, version: v.version as number, createdAt: v.createdAt as string };
}
export function waitlistPage(value: unknown): WaitlistPage {
  check(record(value) && Array.isArray(value.entries) && value.entries.length <= 50 && typeof value.truncated === 'boolean');
  return { entries: (value.entries as unknown[]).map(waitlistEntry), truncated: value.truncated as boolean };
}
export function acceptance(value: unknown): { entry: WaitlistEntry; reservation: Reservation } {
  check(record(value));
  return { entry: waitlistEntry(value.entry), reservation: reservation(value.reservation) };
}
