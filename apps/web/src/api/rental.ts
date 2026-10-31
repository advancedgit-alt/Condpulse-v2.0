import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, list, oneOf, record } from './client';
import { invitation, invitationCreated, type InvitationCreated, type ManagedInvitation } from './management';
import type { ISODateTime, UUID } from './types';

/**
 * Proprietário × inquilino (V034). A gestão marca e desmarca a unidade como alugada; enquanto alugada, o proprietário só
 * vê os ocupantes e os convites de inquilino da unidade, convida inquilino (morador ou responsável) e remove ocupante.
 */
export const occupantRoles = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT'] as const;
export const tenantRoles = ['RESIDENT', 'RESPONSIBLE'] as const;
export type TenantRole = typeof tenantRoles[number];

export interface ManagedRental { unitId: UUID; unitLabel: string; startedAt: ISODateTime; owners: string[] }
export interface RentalOccupant {
  membershipId: UUID; displayName: string; role: typeof occupantRoles[number]; status: 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  validFrom: ISODateTime; version: number;
}
export interface OwnerRentalView {
  unitId: UUID; unitLabel: string | null; rented: boolean; rentedSince: ISODateTime | null; occupants: RentalOccupant[];
  invitations: ManagedInvitation[];
}
export interface TenantInvitationBody { role: TenantRole; displayName: string; email: string; validDays: number }

const name = (value: unknown): value is string => isString(value) && value.trim().length > 0 && value.length <= 160;

export function managedRental(value: unknown, uncertain = false): ManagedRental {
  if (!record(value) || !isUUID(value.unitId) || !isString(value.unitLabel) || !value.unitLabel || !isDate(value.startedAt)
      || !Array.isArray(value.owners) || value.owners.length < 1 || !value.owners.every(name)) throw invalidResponse(uncertain);
  return { unitId: value.unitId, unitLabel: value.unitLabel, startedAt: value.startedAt, owners: [...value.owners] as string[] };
}
export function rentalOccupant(value: unknown, uncertain = false): RentalOccupant {
  if (!record(value) || !isUUID(value.membershipId) || !name(value.displayName) || !oneOf(value.role, occupantRoles)
      || !oneOf(value.status, ['PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED'] as const) || !isDate(value.validFrom)
      || !isInteger(value.version) || value.version < 0) throw invalidResponse(uncertain);
  return { membershipId: value.membershipId, displayName: value.displayName, role: value.role, status: value.status,
    validFrom: value.validFrom, version: value.version };
}
/** Sem locação em vigor a resposta não traz ocupantes nem convites; com locação, só convites de inquilino da unidade. */
export function ownerRentalView(value: unknown, unitId: string): OwnerRentalView {
  if (!record(value) || value.unitId !== unitId || !(value.unitLabel === null || isString(value.unitLabel)) || typeof value.rented !== 'boolean'
      || !(value.rentedSince === null || isDate(value.rentedSince)) || value.rented !== (value.rentedSince !== null)) throw invalidResponse();
  const occupants = list(value.occupants, (item) => rentalOccupant(item));
  const invitations = list(value.invitations, invitation);
  if (!value.rented && (occupants.length > 0 || invitations.length > 0)) throw invalidResponse();
  if (invitations.some((item) => item.unitId !== unitId || !(tenantRoles as readonly string[]).includes(item.role))) throw invalidResponse();
  return { unitId, unitLabel: value.unitLabel as string | null, rented: value.rented, rentedSince: value.rentedSince as string | null,
    occupants, invitations };
}

export function createRentalApi(client: CondPulseApi = api) {
  const tenant = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const unit = (tenantId: string, unitId: string) => `${tenant(tenantId)}/units/${identifier(unitId)}/rental`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  return {
    // Gestão (memberships.manage, AAL2).
    active: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/management/rentals`, (value) => list(value, (item) => managedRental(item)), options(signal)),
    start: async (tenantId: string, unitId: string, reason: string) =>
      client.call(`${tenant(tenantId)}/management/units/${identifier(unitId)}/rental`, (value) => {
        const started = managedRental(value, true);
        if (started.unitId !== unitId) throw invalidResponse(true);
        return started;
      }, { method: 'POST', body: { reason } }),
    end: async (tenantId: string, unitId: string, reason: string) =>
      client.call(`${tenant(tenantId)}/management/units/${identifier(unitId)}/rental/end`, () => undefined, { method: 'POST', body: { reason } }),
    // Proprietário (vínculo OWNER da unidade).
    view: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(unit(tenantId, unitId), (value) => ownerRentalView(value, unitId), options(signal)),
    invite: async (tenantId: string, unitId: string, body: TenantInvitationBody): Promise<InvitationCreated> =>
      client.call(`${unit(tenantId, unitId)}/invitations`, invitationCreated, { method: 'POST', body }),
    revokeInvitation: async (tenantId: string, unitId: string, invitationId: string) =>
      client.call(`${unit(tenantId, unitId)}/invitations/${identifier(invitationId)}/revoke`, () => undefined, { method: 'POST', body: {} }),
    remove: async (tenantId: string, unitId: string, membershipId: string, expectedVersion: number, reason: string) =>
      client.call(`${unit(tenantId, unitId)}/occupants/${identifier(membershipId)}/remove`, (value) => {
        const removed = rentalOccupant(value, true);
        if (removed.membershipId !== membershipId || removed.status !== 'REVOKED') throw invalidResponse(true);
        return removed;
      }, { method: 'POST', body: { expectedVersion, reason } }),
  };
}

export const rental = createRentalApi();
