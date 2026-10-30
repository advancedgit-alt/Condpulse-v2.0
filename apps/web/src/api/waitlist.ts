import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, oneOf, record, reservation } from './client';
import type { ISODateTime, Reservation, UUID } from './types';

/**
 * Fila de espera de reservas (CP-040). Quando o período é liberado, o primeiro da fila recebe uma oferta: uma retenção
 * de até 15 minutos em nome de quem entrou. Aceitar confirma (ou vira pedido, se a área exige aprovação); recusar ou
 * deixar vencer passa a vez.
 */
export type WaitlistState = 'WAITING' | 'OFFERED' | 'ACCEPTED' | 'LEFT' | 'EXPIRED' | 'CLOSED';
export interface WaitlistEntry {
  waitlistId: UUID; unitId: UUID; amenityId: UUID; state: WaitlistState; startsAt: ISODateTime; endsAt: ISODateTime;
  occupiedFrom: ISODateTime; occupiedUntil: ISODateTime; acceptedPolicyVersion: number; approvalRequired: boolean; feeCents: number;
  offerReservationId: UUID | null; offeredAt: ISODateTime | null; offerExpiresAt: ISODateTime | null; closedAt: ISODateTime | null;
  createdByMe: boolean; position: number | null; version: number; createdAt: ISODateTime;
}
export interface WaitlistPage { entries: WaitlistEntry[]; truncated: boolean }
export interface JoinWaitlistBody { unitId: string; amenityId: string; startsAt: string; endsAt: string; acceptedPolicyVersion: number }
export interface WaitlistChangeBody { unitId: string; expectedVersion: number }

const optionalInstant = (value: unknown) => value === null || isDate(value);
export function waitlistEntry(value: unknown, uncertain = false): WaitlistEntry {
  if (!record(value) || !isUUID(value.waitlistId) || !isUUID(value.unitId) || !isUUID(value.amenityId)
      || !oneOf(value.state, ['WAITING', 'OFFERED', 'ACCEPTED', 'LEFT', 'EXPIRED', 'CLOSED'])
      || !isDate(value.startsAt) || !isDate(value.endsAt) || !isDate(value.occupiedFrom) || !isDate(value.occupiedUntil)
      || !isInteger(value.acceptedPolicyVersion) || typeof value.approvalRequired !== 'boolean' || !isInteger(value.feeCents)
      || !(value.offerReservationId === null || isUUID(value.offerReservationId)) || !optionalInstant(value.offeredAt)
      || !optionalInstant(value.offerExpiresAt) || !optionalInstant(value.closedAt) || typeof value.createdByMe !== 'boolean'
      || !(value.position === null || (isInteger(value.position) && value.position >= 1)) || !isInteger(value.version) || !isDate(value.createdAt))
    throw invalidResponse(uncertain);
  // Oferta sem retenção ou prazo, ou entrada aguardando com oferta, é resposta inconsistente: falha fechada.
  const offered = value.state === 'OFFERED';
  if ((offered && (value.offerReservationId === null || value.offerExpiresAt === null))
      || (value.state === 'WAITING' && value.offerReservationId !== null)) throw invalidResponse(uncertain);
  return { waitlistId: value.waitlistId, unitId: value.unitId, amenityId: value.amenityId, state: value.state,
    startsAt: value.startsAt, endsAt: value.endsAt, occupiedFrom: value.occupiedFrom, occupiedUntil: value.occupiedUntil,
    acceptedPolicyVersion: value.acceptedPolicyVersion, approvalRequired: value.approvalRequired, feeCents: value.feeCents,
    offerReservationId: value.offerReservationId as UUID | null, offeredAt: value.offeredAt as ISODateTime | null,
    offerExpiresAt: value.offerExpiresAt as ISODateTime | null, closedAt: value.closedAt as ISODateTime | null,
    createdByMe: value.createdByMe, position: value.position as number | null, version: value.version, createdAt: value.createdAt };
}
function waitlistPage(value: unknown): WaitlistPage {
  if (!record(value) || !Array.isArray(value.entries) || value.entries.length > 50 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { entries: value.entries.map((item) => waitlistEntry(item)), truncated: value.truncated };
}
function acceptance(value: unknown): { entry: WaitlistEntry; reservation: Reservation } {
  if (!record(value)) throw invalidResponse(true);
  return { entry: waitlistEntry(value.entry, true), reservation: reservation(value.reservation, true) };
}

export function createWaitlistApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  return {
    unitEntries: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/waitlist`, waitlistPage, signal ? { signal } : {}),
    join: async (tenantId: string, key: string, body: JoinWaitlistBody) =>
      client.call(`${base(tenantId)}/waitlist`, (value, response) => ({ entry: waitlistEntry(value, true),
        replayed: response.headers.get('Idempotency-Replayed') === 'true' }), { method: 'POST', body, key }),
    leave: async (tenantId: string, waitlistId: string, body: WaitlistChangeBody) =>
      client.call(`${base(tenantId)}/waitlist/${identifier(waitlistId)}/leave`, (value) => waitlistEntry(value, true), { method: 'POST', body }),
    accept: async (tenantId: string, waitlistId: string, key: string, body: WaitlistChangeBody) =>
      client.call(`${base(tenantId)}/waitlist/${identifier(waitlistId)}/accept`, acceptance, { method: 'POST', body, key }),
  };
}
export const waitlist = createWaitlistApi();
