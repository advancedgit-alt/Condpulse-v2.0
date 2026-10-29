import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Encomendas (CP-029/CP-030/CP-031). Recebimento, retirada e anulação pela portaria, com chave de idempotência;
 * consulta pela unidade. "Entregue" só é aceito com o registro de retirada e "anulada" só com motivo; resposta
 * incoerente vira resultado incerto.
 */
export type ParcelStatus = 'RECEIVED' | 'DELIVERED' | 'VOIDED';
export interface ParcelHandoff { recipientName: string; deliveredAt: ISODateTime; evidenceKind: 'ASSISTED' }
export interface Parcel {
  parcelId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; quantity: number; description: string;
  storageLocation: string; status: ParcelStatus; receivedAt: ISODateTime; version: number; handoff: ParcelHandoff | null;
  voidReason: string | null; voidedAt: ISODateTime | null;
}
export interface ParcelPage { parcels: Parcel[]; truncated: boolean }
export interface ParcelUnitOption { unitId: UUID; label: string; blockName: string | null }
export type RecipientRole = 'RESIDENT' | 'RESPONSIBLE' | 'DEPENDENT';
/** Pessoa que pode retirar: vínculo residencial vigente da unidade, escolhido pela portaria. */
export interface ParcelRecipient { membershipId: UUID; displayName: string; role: RecipientRole }
export interface ParcelMutation { parcel: Parcel; replayed: boolean }
export interface ReceiveBody { unitId: string; quantity: number; description: string | null; storageLocation: string; confirmDuplicate?: true }

function handoff(value: unknown): ParcelHandoff | null {
  if (value === null) return null;
  if (!record(value) || !isString(value.recipientName) || codePoints(value.recipientName) < 1 || codePoints(value.recipientName) > 160
      || !isDate(value.deliveredAt) || value.evidenceKind !== 'ASSISTED') throw invalidResponse();
  return { recipientName: value.recipientName, deliveredAt: value.deliveredAt, evidenceKind: 'ASSISTED' };
}
export function parcel(value: unknown, uncertain = false): Parcel {
  if (!record(value) || !isUUID(value.parcelId) || !isUUID(value.unitId) || !isString(value.unitLabel)
      || !(value.blockName === null || isString(value.blockName)) || !isInteger(value.quantity) || value.quantity < 1 || value.quantity > 1000
      || !isString(value.description) || codePoints(value.description) > 500 || !isString(value.storageLocation)
      || codePoints(value.storageLocation) < 1 || codePoints(value.storageLocation) > 160 || !oneOf(value.status, ['RECEIVED', 'DELIVERED', 'VOIDED'])
      || !isDate(value.receivedAt) || !isInteger(value.version) || !('handoff' in value)
      || !(value.voidReason === null || (isString(value.voidReason) && codePoints(value.voidReason) >= 1 && codePoints(value.voidReason) <= 500))
      || !(value.voidedAt === null || isDate(value.voidedAt))) throw invalidResponse(uncertain);
  let recorded: ParcelHandoff | null;
  try { recorded = handoff(value.handoff); } catch { throw invalidResponse(uncertain); }
  // Entregue somente com registro de retirada; anulada somente com motivo e horário; ambos com a versão já avançada.
  const voided = value.status === 'VOIDED';
  if ((value.status === 'DELIVERED') !== (recorded !== null) || voided !== (value.voidReason !== null) || voided !== (value.voidedAt !== null)
      || (value.status !== 'RECEIVED' && value.version < 1)) throw invalidResponse(uncertain);
  return { parcelId: value.parcelId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null,
    quantity: value.quantity, description: value.description, storageLocation: value.storageLocation, status: value.status,
    receivedAt: value.receivedAt, version: value.version, handoff: recorded, voidReason: value.voidReason as string | null,
    voidedAt: value.voidedAt as ISODateTime | null };
}
function parcelPage(value: unknown): ParcelPage {
  if (!record(value) || !Array.isArray(value.parcels) || value.parcels.length > 100 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { parcels: value.parcels.map((item) => parcel(item)), truncated: value.truncated };
}
function unitOptions(value: unknown): ParcelUnitOption[] {
  if (!Array.isArray(value) || value.length > 500) throw invalidResponse();
  return value.map((item) => {
    if (!record(item) || !isUUID(item.unitId) || !isString(item.label) || !(item.blockName === null || isString(item.blockName))) throw invalidResponse();
    return { unitId: item.unitId, label: item.label, blockName: item.blockName as string | null };
  });
}
function recipients(value: unknown): ParcelRecipient[] {
  if (!Array.isArray(value) || value.length > 100) throw invalidResponse();
  return value.map((item) => {
    if (!record(item) || !isUUID(item.membershipId) || !isString(item.displayName) || codePoints(item.displayName) < 1
        || codePoints(item.displayName) > 160 || !oneOf(item.role, ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT'])) throw invalidResponse();
    return { membershipId: item.membershipId, displayName: item.displayName, role: item.role };
  });
}
const mutation = (value: unknown, response: Response): ParcelMutation =>
  ({ parcel: parcel(value, true), replayed: response.headers.get('Idempotency-Replayed') === 'true' });

export function createParcelsApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  return {
    list: async (tenantId: string, filter: { status?: ParcelStatus | null; unitId?: string | null; location?: string | null }, signal?: AbortSignal) => {
      const query = new URLSearchParams();
      if (filter.status) query.set('status', filter.status);
      if (filter.unitId) query.set('unitId', identifier(filter.unitId));
      if (filter.location) query.set('location', filter.location);
      const suffix = query.size ? `?${query}` : '';
      return client.call(`${base(tenantId)}/parcels${suffix}`, parcelPage, signal ? { signal } : {});
    },
    units: async (tenantId: string, search: string | null, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/concierge/units${search ? `?${new URLSearchParams({ q: search })}` : ''}`, unitOptions, signal ? { signal } : {}),
    unitParcels: async (tenantId: string, unitId: string, status: 'RECEIVED' | 'DELIVERED' | null, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/parcels${status ? `?status=${status}` : ''}`, parcelPage, signal ? { signal } : {}),
    recipients: async (tenantId: string, parcelId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/parcels/${identifier(parcelId)}/recipients`, recipients, signal ? { signal } : {}),
    receive: async (tenantId: string, key: string, body: ReceiveBody) =>
      client.call(`${base(tenantId)}/parcels`, mutation, { method: 'POST', body, key }),
    deliver: async (tenantId: string, parcelId: string, key: string, body: { recipientMembershipId: string; identityChecked: true; expectedVersion: number }) =>
      client.call(`${base(tenantId)}/parcels/${identifier(parcelId)}/handoff`, mutation, { method: 'POST', body, key }),
    voidParcel: async (tenantId: string, parcelId: string, key: string, body: { reason: string; expectedVersion: number }) =>
      client.call(`${base(tenantId)}/parcels/${identifier(parcelId)}/void`, mutation, { method: 'POST', body, key }),
  };
}
export const parcels = createParcelsApi();
