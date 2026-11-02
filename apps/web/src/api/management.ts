import { api, identifier, invalidResponse, isDate, isInteger, isString, isUUID, list, oneOf, record, type CondPulseApi } from './client';
import type { ISODateTime, ReservationPolicy, UUID } from './types';

/**
 * Contratos da gestão local (CP-014/CP-018/CP-020/CP-021/CP-023). Toda resposta é validada antes da interface e
 * toda falha, inclusive de identificador inválido, chega como Promise rejeitada.
 */
export type MembershipRole = 'RESIDENT' | 'RESPONSIBLE' | 'DEPENDENT' | 'OWNER' | 'CONCIERGE' | 'MAINTENANCE' | 'MANAGER' | 'SUPPORT';
export type MembershipStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
export type InvitationRole = 'RESIDENT' | 'RESPONSIBLE' | 'DEPENDENT' | 'OWNER' | 'CONCIERGE' | 'MAINTENANCE' | 'MANAGER';
export type ReservationStatus = 'HELD' | 'PENDING_APPROVAL' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

export interface ManagedBlock { blockId: UUID; name: string }
export interface ManagedUnit { unitId: UUID; label: string; blockId: UUID | null; blockName: string | null; status: 'ACTIVE' | 'INACTIVE'; version: number }
export interface ManagedUnitPage { units: ManagedUnit[]; truncated: boolean }
export interface AmenityPolicyInput {
  schemaVersion: 1; weekDays: number[]; opensAt: string; closesAt: string; minimumLeadMinutes: number;
  maximumAdvanceDays: number; maximumDurationMinutes: number; maximumActivePerUnit: number; cancellationLeadMinutes: number;
}
export interface AmenityInput {
  name: string; capacity: number; approvalRequired: boolean; feeCents: number; bufferMinutes: number;
  active: boolean | null; policy: AmenityPolicyInput; expectedVersion: number | null; reason: string | null;
}
export interface ManagedAmenity {
  amenityId: UUID; name: string; capacity: number; approvalRequired: boolean; feeCents: number; bufferMinutes: number;
  active: boolean; policyVersion: number; version: number; policyState: 'CONFIGURED' | 'POLICY_NOT_CONFIGURED';
  policy: ReservationPolicy | null;
}
export interface ManagedMembership {
  membershipId: UUID; unitId: UUID | null; unitLabel: string | null; displayName: string; role: MembershipRole;
  status: MembershipStatus; validFrom: ISODateTime; validUntil: ISODateTime | null; version: number;
}
export interface ManagedMembershipPage { memberships: ManagedMembership[]; truncated: boolean }
export interface ManagedInvitation {
  invitationId: UUID; unitId: UUID | null; unitLabel: string | null; role: string; displayName: string;
  status: 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'; expiresAt: ISODateTime; createdAt: ISODateTime; acceptedAt: ISODateTime | null;
}
/** O token aparece uma única vez; a interface nunca o guarda em cache de consultas. */
export interface InvitationCreated { invitationId: UUID; token: string; expiresAt: ISODateTime }
export interface CreateInvitation { unitId: UUID | null; role: InvitationRole; displayName: string; email: string; validDays: number }
export interface ManagedReservation {
  reservationId: UUID; kind: 'BOOKING' | 'BLOCK'; unitId: UUID | null; unitLabel: string | null; amenityId: UUID;
  amenityName: string; status: ReservationStatus; startsAt: ISODateTime; endsAt: ISODateTime; occupiedFrom: ISODateTime;
  occupiedUntil: ISODateTime; expiresAt: ISODateTime | null; acceptedPolicyVersion: number; feeCents: number; version: number;
  decisionReason: string | null; requesterName: string | null; createdAt: ISODateTime;
}
export interface ManagedReservationPage { reservations: ManagedReservation[]; truncated: boolean }
export interface BlockResult { reservation: ManagedReservation; replayed: boolean }
/** Decisão de pedido: `applied` vem do servidor (false = estado já existente, motivo não registrado; null = sem a indicação). */
export interface DecisionResult { reservation: ManagedReservation; applied: boolean | null }
export interface AuditEvent {
  eventId: UUID; actorId: UUID; action: string; resourceType: string; resourceId: UUID; reason: string | null; createdAt: ISODateTime;
}
export interface AuditEventPage { events: AuditEvent[]; nextCursor: string | null }

const RESERVATION_STATUSES: readonly ReservationStatus[] = ['HELD', 'PENDING_APPROVAL', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'EXPIRED'];
const nullableString = (value: unknown): value is string | null => value === null || isString(value);
const nullableUuid = (value: unknown): value is string | null => value === null || isUUID(value);
const nullableDate = (value: unknown): value is string | null => value === null || isDate(value);
const optional = <T>(value: unknown, check: (item: unknown) => item is T): T | null => (value === undefined || value === null ? null : check(value) ? value : (() => { throw invalidResponse(); })());

function block(value: unknown): ManagedBlock {
  if (!record(value) || !isUUID(value.blockId) || !isString(value.name)) throw invalidResponse();
  return { blockId: value.blockId, name: value.name };
}
function unit(value: unknown): ManagedUnit {
  if (!record(value) || !isUUID(value.unitId) || !isString(value.label) || !nullableUuid(value.blockId ?? null)
      || !nullableString(value.blockName ?? null) || !oneOf(value.status, ['ACTIVE', 'INACTIVE']) || !isInteger(value.version))
    throw invalidResponse();
  return { unitId: value.unitId, label: value.label, blockId: (value.blockId ?? null) as string | null,
    blockName: (value.blockName ?? null) as string | null, status: value.status, version: value.version };
}
function policy(value: unknown): ReservationPolicy | null {
  if (value === null || value === undefined) return null;
  if (!record(value) || value.schemaVersion !== 1 || !Array.isArray(value.weekDays)
      || !value.weekDays.every((day) => isInteger(day) && day >= 1 && day <= 7) || !isString(value.opensAt) || !isString(value.closesAt)
      || !isInteger(value.minimumLeadMinutes) || !isInteger(value.maximumAdvanceDays) || !isInteger(value.maximumDurationMinutes)
      || !isInteger(value.maximumActivePerUnit) || !isInteger(value.cancellationLeadMinutes)) throw invalidResponse();
  return { schemaVersion: 1, weekDays: [...value.weekDays] as number[], opensAt: value.opensAt, closesAt: value.closesAt,
    minimumLeadMinutes: value.minimumLeadMinutes, maximumAdvanceDays: value.maximumAdvanceDays,
    maximumDurationMinutes: value.maximumDurationMinutes, maximumActivePerUnit: value.maximumActivePerUnit,
    cancellationLeadMinutes: value.cancellationLeadMinutes };
}
function amenity(value: unknown): ManagedAmenity {
  if (!record(value) || !isUUID(value.amenityId) || !isString(value.name) || !isInteger(value.capacity)
      || typeof value.approvalRequired !== 'boolean' || !isInteger(value.feeCents) || !isInteger(value.bufferMinutes)
      || typeof value.active !== 'boolean' || !isInteger(value.policyVersion) || !isInteger(value.version)
      || !oneOf(value.policyState, ['CONFIGURED', 'POLICY_NOT_CONFIGURED'])) throw invalidResponse();
  return { amenityId: value.amenityId, name: value.name, capacity: value.capacity, approvalRequired: value.approvalRequired,
    feeCents: value.feeCents, bufferMinutes: value.bufferMinutes, active: value.active, policyVersion: value.policyVersion,
    version: value.version, policyState: value.policyState, policy: policy(value.policy) };
}
function membership(value: unknown): ManagedMembership {
  if (!record(value) || !isUUID(value.membershipId) || !nullableUuid(value.unitId ?? null) || !nullableString(value.unitLabel ?? null)
      || !isString(value.displayName) || !oneOf(value.role, ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MAINTENANCE', 'MANAGER', 'SUPPORT'])
      || !oneOf(value.status, ['PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED']) || !isDate(value.validFrom)
      || !nullableDate(value.validUntil ?? null) || !isInteger(value.version)) throw invalidResponse();
  return { membershipId: value.membershipId, unitId: (value.unitId ?? null) as string | null, unitLabel: (value.unitLabel ?? null) as string | null,
    displayName: value.displayName, role: value.role, status: value.status, validFrom: value.validFrom,
    validUntil: (value.validUntil ?? null) as string | null, version: value.version };
}
export function invitation(value: unknown): ManagedInvitation {
  if (!record(value) || !isUUID(value.invitationId) || !nullableUuid(value.unitId ?? null) || !nullableString(value.unitLabel ?? null)
      || !isString(value.role) || !isString(value.displayName) || !oneOf(value.status, ['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'])
      || !isDate(value.expiresAt) || !isDate(value.createdAt) || !nullableDate(value.acceptedAt ?? null)) throw invalidResponse();
  return { invitationId: value.invitationId, unitId: (value.unitId ?? null) as string | null, unitLabel: (value.unitLabel ?? null) as string | null,
    role: value.role, displayName: value.displayName, status: value.status, expiresAt: value.expiresAt, createdAt: value.createdAt,
    acceptedAt: (value.acceptedAt ?? null) as string | null };
}
export function invitationCreated(value: unknown): InvitationCreated {
  if (!record(value) || !isUUID(value.invitationId) || !isString(value.token) || !/^[A-Za-z0-9_-]{43}$/.test(value.token)
      || !isDate(value.expiresAt)) throw invalidResponse(true);
  return { invitationId: value.invitationId, token: value.token, expiresAt: value.expiresAt };
}
export function managedReservation(value: unknown, uncertain = false): ManagedReservation {
  if (!record(value) || !isUUID(value.reservationId) || !oneOf(value.kind, ['BOOKING', 'BLOCK'])
      || !nullableUuid(value.unitId) || !nullableString(value.unitLabel) || !isUUID(value.amenityId) || !isString(value.amenityName)
      || !oneOf(value.status, RESERVATION_STATUSES) || !isDate(value.startsAt) || !isDate(value.endsAt)
      || !isDate(value.occupiedFrom) || !isDate(value.occupiedUntil) || !nullableDate(value.expiresAt)
      || !isInteger(value.acceptedPolicyVersion) || !isInteger(value.feeCents) || !isInteger(value.version)
      || !nullableString(value.decisionReason) || !nullableString(value.requesterName) || !isDate(value.createdAt)
      || (value.kind === 'BOOKING') !== (value.unitId !== null)) throw invalidResponse(uncertain);
  return { reservationId: value.reservationId, kind: value.kind, unitId: value.unitId, unitLabel: value.unitLabel,
    amenityId: value.amenityId, amenityName: value.amenityName, status: value.status, startsAt: value.startsAt, endsAt: value.endsAt,
    occupiedFrom: value.occupiedFrom, occupiedUntil: value.occupiedUntil, expiresAt: value.expiresAt,
    acceptedPolicyVersion: value.acceptedPolicyVersion, feeCents: value.feeCents, version: value.version,
    decisionReason: value.decisionReason, requesterName: value.requesterName, createdAt: value.createdAt };
}
function auditEvent(value: unknown): AuditEvent {
  if (!record(value) || !isUUID(value.eventId) || !isUUID(value.actorId) || !isString(value.action) || !isString(value.resourceType)
      || !isUUID(value.resourceId) || !nullableString(value.reason ?? null) || !isDate(value.createdAt)) throw invalidResponse();
  return { eventId: value.eventId, actorId: value.actorId, action: value.action, resourceType: value.resourceType,
    resourceId: value.resourceId, reason: (value.reason ?? null) as string | null, createdAt: value.createdAt };
}
/** Fila de espera vista pela gestão (CP-040), só leitura. */
export const waitlistStates = ['WAITING', 'OFFERED', 'ACCEPTED', 'LEFT', 'EXPIRED', 'CLOSED'] as const;
export interface ManagedWaitlistEntry {
  waitlistId: UUID; unitId: UUID; unitLabel: string; amenityId: UUID; amenityName: string; state: typeof waitlistStates[number];
  startsAt: ISODateTime; endsAt: ISODateTime; position: number | null; offerExpiresAt: ISODateTime | null; closedAt: ISODateTime | null; createdAt: ISODateTime;
}
export function managedWaitlistEntry(value: unknown): ManagedWaitlistEntry {
  if (!record(value) || !isUUID(value.waitlistId) || !isUUID(value.unitId) || !isString(value.unitLabel) || !value.unitLabel || !isUUID(value.amenityId)
      || !isString(value.amenityName) || !value.amenityName || !oneOf(value.state, waitlistStates) || !isDate(value.startsAt) || !isDate(value.endsAt)
      || Date.parse(value.endsAt) <= Date.parse(value.startsAt) || !(value.position === null || (isInteger(value.position) && value.position >= 1))
      || !(value.offerExpiresAt === null || isDate(value.offerExpiresAt)) || !(value.closedAt === null || isDate(value.closedAt)) || !isDate(value.createdAt))
    throw invalidResponse();
  // Posição só para quem aguarda; prazo só na oferta em vigor.
  if ((value.position !== null) !== (value.state === 'WAITING') || (value.state === 'OFFERED' && value.offerExpiresAt === null)) throw invalidResponse();
  return { waitlistId: value.waitlistId, unitId: value.unitId, unitLabel: value.unitLabel, amenityId: value.amenityId, amenityName: value.amenityName,
    state: value.state, startsAt: value.startsAt, endsAt: value.endsAt, position: value.position as number | null,
    offerExpiresAt: value.offerExpiresAt as string | null, closedAt: value.closedAt as string | null, createdAt: value.createdAt };
}
const page = <T, K extends string>(key: K, decode: (item: unknown) => T) => (value: unknown): { [P in K]: T[] } & { truncated: boolean } => {
  if (!record(value) || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { [key]: list(value[key], decode), truncated: value.truncated } as { [P in K]: T[] } & { truncated: boolean };
};
const mutationOf = <T>(decode: (value: unknown, uncertain?: boolean) => T) => (value: unknown) => decode(value, true);

export function createManagementApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/management`;
  return {
    blocks: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/blocks`, (value) => list(value, block), signal ? { signal } : {}),
    createBlock: async (tenantId: string, name: string) =>
      client.call(`${base(tenantId)}/blocks`, mutationOf(block), { method: 'POST', body: { name } }),
    units: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units`, page('units', unit), signal ? { signal } : {}) as Promise<ManagedUnitPage>,
    createUnit: async (tenantId: string, body: { label: string; blockId: string | null }) =>
      client.call(`${base(tenantId)}/units`, mutationOf(unit), { method: 'POST', body }),
    updateUnit: async (tenantId: string, unitId: string, body: { label: string; blockId: string | null; status: 'ACTIVE' | 'INACTIVE'; expectedVersion: number; reason: string | null }) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}`, mutationOf(unit), { method: 'PUT', body }),
    amenities: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/amenities`, (value) => list(value, amenity), signal ? { signal } : {}),
    createAmenity: async (tenantId: string, body: AmenityInput) =>
      client.call(`${base(tenantId)}/amenities`, mutationOf(amenity), { method: 'POST', body }),
    updateAmenity: async (tenantId: string, amenityId: string, body: AmenityInput) =>
      client.call(`${base(tenantId)}/amenities/${identifier(amenityId)}`, mutationOf(amenity), { method: 'PUT', body }),
    memberships: async (tenantId: string, status: MembershipStatus | null, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/memberships${status ? `?status=${status}` : ''}`, page('memberships', membership),
        signal ? { signal } : {}) as Promise<ManagedMembershipPage>,
    decideMembership: async (tenantId: string, membershipId: string, body: { decision: 'APPROVE' | 'REJECT'; expectedVersion: number; reason: string | null }) =>
      client.call(`${base(tenantId)}/memberships/${identifier(membershipId)}/decision`, mutationOf(membership), { method: 'POST', body }),
    revokeMembership: async (tenantId: string, membershipId: string, body: { expectedVersion: number; reason: string }) =>
      client.call(`${base(tenantId)}/memberships/${identifier(membershipId)}/revoke`, mutationOf(membership), { method: 'POST', body }),
    invitations: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/invitations`, (value) => list(value, invitation), signal ? { signal } : {}),
    createInvitation: async (tenantId: string, body: CreateInvitation) =>
      client.call(`${base(tenantId)}/invitations`, invitationCreated, { method: 'POST', body }),
    revokeInvitation: async (tenantId: string, invitationId: string) =>
      client.call(`${base(tenantId)}/invitations/${identifier(invitationId)}/revoke`, () => undefined, { method: 'POST', body: {} }),
    reservations: async (tenantId: string, filter: { status: ReservationStatus | null; from: string | null; until: string | null }, signal?: AbortSignal) => {
      const query = new URLSearchParams();
      if (filter.status) query.set('status', filter.status);
      if (filter.from && filter.until) { query.set('from', filter.from); query.set('until', filter.until); }
      const suffix = query.size ? `?${query}` : '';
      return client.call(`${base(tenantId)}/reservations${suffix}`, page('reservations', (item) => managedReservation(item)),
        signal ? { signal } : {}) as Promise<ManagedReservationPage>;
    },
    waitlist: async (tenantId: string, open: boolean, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/waitlist?open=${open}`, (value) => {
        const result = page('entries', managedWaitlistEntry)(value);
        if (open && result.entries.some((item) => item.state !== 'WAITING' && item.state !== 'OFFERED')) throw invalidResponse();
        return result;
      }, signal ? { signal } : {}),
    decideReservation: async (tenantId: string, reservationId: string, body: { decision: 'APPROVE' | 'REJECT'; expectedVersion: number; reason: string | null }) =>
      client.call(`${base(tenantId)}/reservations/${identifier(reservationId)}/decision`, (value, response): DecisionResult => {
        const header = response.headers.get('Reservation-Decision-Applied');
        return { reservation: managedReservation(value, true), applied: header === 'true' ? true : header === 'false' ? false : null };
      }, { method: 'POST', body }),
    cancelReservation: async (tenantId: string, reservationId: string, body: { expectedVersion: number; reason: string }) =>
      client.call(`${base(tenantId)}/reservations/${identifier(reservationId)}/cancel`, mutationOf(managedReservation), { method: 'POST', body }),
    createMaintenanceBlock: async (tenantId: string, amenityId: string, key: string, body: { startsAt: string; endsAt: string; reason: string }) =>
      client.call(`${base(tenantId)}/amenities/${identifier(amenityId)}/maintenance-blocks`, (value, response): BlockResult => ({
        reservation: managedReservation(value, true), replayed: response.headers.get('Idempotency-Replayed') === 'true' }),
      { method: 'POST', body, key }),
    audit: async (tenantId: string, cursor: string | null, signal?: AbortSignal) => {
      const query = new URLSearchParams({ limit: '30' });
      if (cursor) query.set('cursor', cursor);
      return client.call(`${base(tenantId)}/audit-events?${query}`, (value): AuditEventPage => {
        if (!record(value)) throw invalidResponse();
        const next = optional(value.nextCursor, isString);
        if (next !== null && !/^[A-Za-z0-9_-]{1,120}$/.test(next)) throw invalidResponse();
        return { events: list(value.events, auditEvent), nextCursor: next };
      }, signal ? { signal } : {});
    },
  };
}

export const management = createManagementApi();
export type ManagementApi = ReturnType<typeof createManagementApi>;
