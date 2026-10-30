import { ContractError } from './reservations';

/**
 * Encomendas da unidade e retirada por terceiro com código (CP-029/CP-041) no aplicativo, validadas como na web:
 * "entregue" só com registro de retirada, "anulada" só com motivo; o código de 8 dígitos existe só na resposta da criação.
 */
export type ParcelStatus = 'RECEIVED' | 'DELIVERED' | 'VOIDED';
export interface ParcelHandoff { recipientName: string; deliveredAt: string; evidenceKind: 'ASSISTED' | 'DELEGATION' }
export interface Parcel {
  parcelId: string; unitId: string; quantity: number; description: string; storageLocation: string; status: ParcelStatus;
  receivedAt: string; version: number; handoff: ParcelHandoff | null;
}
export interface ParcelPage { parcels: Parcel[]; truncated: boolean }
export type DelegationState = 'ACTIVE' | 'USED' | 'REVOKED' | 'LOCKED' | 'CLOSED' | 'EXPIRED';
export interface Delegation {
  delegationId: string; parcelId: string; unitId: string; delegateName: string; documentLast4: string | null; validUntil: string;
  state: DelegationState; attempts: number; attemptsLeft: number; createdByMe: boolean; createdAt: string; usedAt: string | null;
  closedAt: string | null; version: number;
}
export interface CreateDelegation { delegateName: string; documentLast4: string | null; validUntil: string | null }
export interface DelegationIssued { delegation: Delegation; code: string | null; replayed: boolean }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const int = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const text = (value: unknown, min: number, max: number): value is string =>
  typeof value === 'string' && [...value].length >= min && [...value].length <= max;
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

function handoff(value: unknown): ParcelHandoff | null {
  if (value === null) return null;
  check(record(value) && text(value.recipientName, 1, 160) && date(value.deliveredAt) && ['ASSISTED', 'DELEGATION'].includes(value.evidenceKind as string));
  return { recipientName: value.recipientName as string, deliveredAt: value.deliveredAt as string, evidenceKind: value.evidenceKind as ParcelHandoff['evidenceKind'] };
}
export function parcel(value: unknown): Parcel {
  check(record(value) && uuid(value.parcelId) && uuid(value.unitId) && int(value.quantity) && value.quantity >= 1 && value.quantity <= 1000
    && text(value.description, 0, 500) && text(value.storageLocation, 1, 160) && ['RECEIVED', 'DELIVERED', 'VOIDED'].includes(value.status as string)
    && date(value.receivedAt) && int(value.version) && 'handoff' in value
    && (value.voidReason === undefined || value.voidReason === null || text(value.voidReason, 1, 500)));
  const recorded = handoff(value.handoff);
  check((value.status === 'DELIVERED') === (recorded !== null) && (value.status === 'VOIDED') === (typeof value.voidReason === 'string')
    && (value.status === 'RECEIVED' || (value.version as number) >= 1));
  return { parcelId: value.parcelId as string, unitId: value.unitId as string, quantity: value.quantity as number,
    description: value.description as string, storageLocation: value.storageLocation as string, status: value.status as ParcelStatus,
    receivedAt: value.receivedAt as string, version: value.version as number, handoff: recorded };
}
export function parcelPage(value: unknown, unitId: string): ParcelPage {
  check(record(value) && Array.isArray(value.parcels) && value.parcels.length <= 100 && typeof value.truncated === 'boolean');
  const parcels = (value.parcels as unknown[]).map(parcel);
  check(parcels.every((item) => item.unitId.toLowerCase() === unitId.toLowerCase()));
  return { parcels, truncated: value.truncated as boolean };
}
export function delegation(value: unknown): Delegation {
  check(record(value) && uuid(value.delegationId) && uuid(value.parcelId) && uuid(value.unitId) && text(value.delegateName, 2, 160)
    && (value.documentLast4 === null || (typeof value.documentLast4 === 'string' && /^[0-9A-Z]{2,4}$/.test(value.documentLast4)))
    && date(value.validUntil) && ['ACTIVE', 'USED', 'REVOKED', 'LOCKED', 'CLOSED', 'EXPIRED'].includes(value.state as string)
    && int(value.attempts) && value.attempts <= 5 && int(value.attemptsLeft) && value.attemptsLeft <= 5 && typeof value.createdByMe === 'boolean'
    && date(value.createdAt) && (value.usedAt === null || date(value.usedAt)) && (value.closedAt === null || date(value.closedAt)) && int(value.version));
  check((value.state === 'USED') === (value.usedAt !== null) && !(value.state === 'ACTIVE' && (value.attempts as number) + (value.attemptsLeft as number) !== 5));
  return { delegationId: value.delegationId as string, parcelId: value.parcelId as string, unitId: value.unitId as string,
    delegateName: value.delegateName as string, documentLast4: value.documentLast4 as string | null, validUntil: value.validUntil as string,
    state: value.state as DelegationState, attempts: value.attempts as number, attemptsLeft: value.attemptsLeft as number,
    createdByMe: value.createdByMe as boolean, createdAt: value.createdAt as string, usedAt: value.usedAt as string | null,
    closedAt: value.closedAt as string | null, version: value.version as number };
}
export function currentDelegation(value: unknown, parcelId: string): Delegation | null {
  check(record(value) && 'delegation' in value);
  if (value.delegation === null) return null;
  const current = delegation(value.delegation);
  check(current.parcelId.toLowerCase() === parcelId.toLowerCase());
  return current;
}
/** Criação nova sempre traz o código; repetição pela mesma chave nunca traz. */
export function issued(value: unknown, parcelId: string, replayed: boolean): DelegationIssued {
  check(record(value) && (value.code === null || (typeof value.code === 'string' && /^[0-9]{8}$/.test(value.code))) && replayed === (value.code === null));
  const created = delegation(value.delegation);
  check(created.parcelId.toLowerCase() === parcelId.toLowerCase() && (replayed || created.state === 'ACTIVE'));
  return { delegation: created, code: value.code as string | null, replayed };
}
/** Final do documento como o servidor aceita: 2 a 4 letras maiúsculas ou números; vazio é sem documento; '' é inválido. */
export function normalizeDocument(value: string): string | null {
  const document = value.replace(/\s+/g, '').toUpperCase();
  if (!document) return null;
  return /^[0-9A-Z]{2,4}$/.test(document) ? document : '';
}
