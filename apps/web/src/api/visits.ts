import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Visitas (CP-025) e chegada sem convite (CP-026). Convite é autorização temporal da unidade, não prova de identidade
 * nem de entrada; a chegada espera a primeira decisão válida até o prazo, e sem resposta nada é liberado.
 */
export type PermitState = 'SCHEDULED' | 'ACTIVE' | 'REVOKED' | 'EXPIRED';
export type ArrivalState = 'WAITING' | 'APPROVED' | 'DENIED' | 'EXPIRED' | 'CANCELLED';
export type DecisionSource = 'APP' | 'INTERCOM' | 'IN_PERSON' | 'CONCIERGE';
export interface VisitPermit {
  permitId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  purpose: string | null; validFrom: ISODateTime; validUntil: ISODateTime; state: PermitState; createdByMe: boolean;
  revokedAt: ISODateTime | null; revokeReason: string | null; version: number; createdAt: ISODateTime;
  /** Link compartilhável em vigor (CP-037); a portaria sempre recebe false. */
  shareLinkActive: boolean;
}
/** Token devolvido uma única vez; a web o leva no fragmento (/visita#token). */
export interface PermitLink { token: string; validUntil: ISODateTime }
export interface Arrival {
  arrivalId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  permitId: UUID | null; state: ArrivalState; expiresAt: ISODateTime; decidedAt: ISODateTime | null; decisionSource: DecisionSource | null;
  decisionNote: string | null; decidedByMe: boolean; version: number; createdAt: ISODateTime;
}
export interface PermitPage { permits: VisitPermit[]; truncated: boolean }
export interface ArrivalPage { arrivals: Arrival[]; truncated: boolean }
export interface PermitMutation { permit: VisitPermit; replayed: boolean }
/**
 * Registro manual de entrada e saída (CP-028): a entrada é sempre MANUAL_IN da portaria (nunca passagem detectada por
 * equipamento); saída e correção são eventos adicionais e nada é apagado.
 */
export type AccessEvidence = 'DOCUMENT_CHECKED' | 'RECOGNIZED_BY_UNIT';
export interface AccessCorrection { eventId: UUID; correctedAt: ISODateTime; reason: string; recordedByMe: boolean }
export interface AccessExit { eventId: UUID; exitedAt: ISODateTime; note: string | null; recordedByMe: boolean; correction: AccessCorrection | null }
/**
 * linkedPermitId: convite ao qual a chegada da entrada foi vinculada (pedido de confirmação a partir do convite). note e
 * exit.note são observações internas da portaria: na visão da unidade chegam null.
 */
export interface AccessRecord {
  entryId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  arrivalId: UUID | null; permitId: UUID | null; linkedPermitId: UUID | null; eventType: 'MANUAL_IN'; source: 'CONCIERGE';
  evidence: AccessEvidence; note: string | null; enteredAt: ISODateTime; recordedByMe: boolean; correction: AccessCorrection | null;
  exit: AccessExit | null;
}
export interface AccessRecordPage { records: AccessRecord[]; truncated: boolean }
export interface AccessMutation { record: AccessRecord; replayed: boolean }
export interface AccessEntryBody { arrivalId: string | null; permitId: string | null; evidence: AccessEvidence; note: string | null }
export interface ArrivalMutation { arrival: Arrival; replayed: boolean }
export interface CreatePermitBody { visitorName: string; documentLast4: string | null; purpose: string | null; validFrom: string; validUntil: string }
export interface RegisterArrivalBody { unitId: string; visitorName: string; documentLast4: string | null; permitId: string | null }

const text = (value: unknown, min: number, max: number): value is string => isString(value) && codePoints(value) >= min && codePoints(value) <= max;
const optional = (value: unknown, max: number) => value === null || text(value, 1, max);
const document = (value: unknown) => value === null || (isString(value) && /^[0-9A-Z]{2,4}$/.test(value));
const instant = (value: unknown): value is ISODateTime => isDate(value);

export function visitPermit(value: unknown, uncertain = false): VisitPermit {
  if (!record(value) || !isUUID(value.permitId) || !isUUID(value.unitId) || !isString(value.unitLabel)
      || !(value.blockName === null || isString(value.blockName)) || !text(value.visitorName, 2, 160) || !document(value.documentLast4)
      || !optional(value.purpose, 200) || !instant(value.validFrom) || !instant(value.validUntil)
      || !oneOf(value.state, ['SCHEDULED', 'ACTIVE', 'REVOKED', 'EXPIRED']) || typeof value.createdByMe !== 'boolean'
      || !(value.revokedAt === null || instant(value.revokedAt)) || !optional(value.revokeReason, 500) || !isInteger(value.version)
      || !instant(value.createdAt) || Date.parse(value.validUntil) <= Date.parse(value.validFrom)
      || typeof value.shareLinkActive !== 'boolean') throw invalidResponse(uncertain);
  // Revogado somente com motivo e horário, e com a versão já avançada.
  const revoked = value.state === 'REVOKED';
  if (revoked !== (value.revokedAt !== null) || revoked !== (value.revokeReason !== null) || (revoked && value.version < 1)) throw invalidResponse(uncertain);
  return { permitId: value.permitId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null,
    visitorName: value.visitorName, documentLast4: value.documentLast4 as string | null, purpose: value.purpose as string | null,
    validFrom: value.validFrom, validUntil: value.validUntil, state: value.state, createdByMe: value.createdByMe,
    revokedAt: value.revokedAt as ISODateTime | null, revokeReason: value.revokeReason as string | null, version: value.version, createdAt: value.createdAt,
    shareLinkActive: value.shareLinkActive };
}
export function permitLink(value: unknown): PermitLink {
  if (!record(value) || !isString(value.token) || !/^[A-Za-z0-9_-]{43}$/.test(value.token) || !instant(value.validUntil)
      || Object.keys(value).length !== 2) throw invalidResponse(true);
  return { token: value.token, validUntil: value.validUntil };
}
export function arrival(value: unknown, uncertain = false): Arrival {
  if (!record(value) || !isUUID(value.arrivalId) || !isUUID(value.unitId) || !isString(value.unitLabel)
      || !(value.blockName === null || isString(value.blockName)) || !text(value.visitorName, 2, 160) || !document(value.documentLast4)
      || !(value.permitId === null || isUUID(value.permitId)) || !oneOf(value.state, ['WAITING', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED'])
      || !instant(value.expiresAt) || !(value.decidedAt === null || instant(value.decidedAt))
      || !(value.decisionSource === null || oneOf(value.decisionSource, ['APP', 'INTERCOM', 'IN_PERSON', 'CONCIERGE']))
      || !optional(value.decisionNote, 300) || typeof value.decidedByMe !== 'boolean' || !isInteger(value.version)
      || !instant(value.createdAt)) throw invalidResponse(uncertain);
  // Decidida/encerrada somente com origem e horário; aguardando ou vencida, sem nada disso. Confirmação assistida anotada.
  const closed = ['APPROVED', 'DENIED', 'CANCELLED'].includes(value.state);
  const assisted = value.decisionSource === 'INTERCOM' || value.decisionSource === 'IN_PERSON';
  if (closed !== (value.decidedAt !== null) || closed !== (value.decisionSource !== null) || (closed && value.version < 1)
      || (value.state === 'CANCELLED') !== (value.decisionSource === 'CONCIERGE') || (assisted && value.decisionNote === null)
      || (!closed && value.decidedByMe)) throw invalidResponse(uncertain);
  return { arrivalId: value.arrivalId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null,
    visitorName: value.visitorName, documentLast4: value.documentLast4 as string | null, permitId: value.permitId as string | null,
    state: value.state, expiresAt: value.expiresAt, decidedAt: value.decidedAt as ISODateTime | null,
    decisionSource: value.decisionSource as DecisionSource | null, decisionNote: value.decisionNote as string | null,
    decidedByMe: value.decidedByMe, version: value.version, createdAt: value.createdAt };
}
function accessCorrection(value: unknown, uncertain: boolean): AccessCorrection | null {
  if (value === null) return null;
  if (!record(value) || !isUUID(value.eventId) || !instant(value.correctedAt) || !text(value.reason, 1, 300)
      || typeof value.recordedByMe !== 'boolean') throw invalidResponse(uncertain);
  return { eventId: value.eventId, correctedAt: value.correctedAt, reason: value.reason, recordedByMe: value.recordedByMe };
}
export function accessRecord(value: unknown, uncertain = false): AccessRecord {
  if (!record(value) || !isUUID(value.entryId) || !isUUID(value.unitId) || !isString(value.unitLabel)
      || !(value.blockName === null || isString(value.blockName)) || !text(value.visitorName, 2, 160) || !document(value.documentLast4)
      || !(value.arrivalId === null || isUUID(value.arrivalId)) || !(value.permitId === null || isUUID(value.permitId))
      || (value.arrivalId === null) === (value.permitId === null) || !(value.linkedPermitId === null || isUUID(value.linkedPermitId))
      || (value.linkedPermitId !== null && value.arrivalId === null) || value.eventType !== 'MANUAL_IN' || value.source !== 'CONCIERGE'
      || !oneOf(value.evidence, ['DOCUMENT_CHECKED', 'RECOGNIZED_BY_UNIT']) || !optional(value.note, 300) || !instant(value.enteredAt)
      || typeof value.recordedByMe !== 'boolean' || value.correction === undefined || value.exit === undefined) throw invalidResponse(uncertain);
  const correction = accessCorrection(value.correction, uncertain);
  let exit: AccessExit | null = null;
  if (value.exit !== null) {
    const raw = value.exit;
    if (!record(raw) || !isUUID(raw.eventId) || !instant(raw.exitedAt) || !optional(raw.note, 300) || typeof raw.recordedByMe !== 'boolean'
        || raw.correction === undefined) throw invalidResponse(uncertain);
    exit = { eventId: raw.eventId, exitedAt: raw.exitedAt, note: raw.note as string | null, recordedByMe: raw.recordedByMe,
      correction: accessCorrection(raw.correction, uncertain) };
  }
  return { entryId: value.entryId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null,
    visitorName: value.visitorName, documentLast4: value.documentLast4 as string | null, arrivalId: value.arrivalId as string | null,
    permitId: value.permitId as string | null, linkedPermitId: value.linkedPermitId as string | null, eventType: 'MANUAL_IN',
    source: 'CONCIERGE', evidence: value.evidence,
    note: value.note as string | null, enteredAt: value.enteredAt, recordedByMe: value.recordedByMe, correction, exit };
}
function accessPage(value: unknown): AccessRecordPage {
  if (!record(value) || !Array.isArray(value.records) || value.records.length > 100 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { records: value.records.map((item) => accessRecord(item)), truncated: value.truncated };
}
function permitPage(value: unknown): PermitPage {
  if (!record(value) || !Array.isArray(value.permits) || value.permits.length > 100 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { permits: value.permits.map((item) => visitPermit(item)), truncated: value.truncated };
}
function arrivalPage(value: unknown): ArrivalPage {
  if (!record(value) || !Array.isArray(value.arrivals) || value.arrivals.length > 100 || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { arrivals: value.arrivals.map((item) => arrival(item)), truncated: value.truncated };
}
const replayed = (response: Response) => response.headers.get('Idempotency-Replayed') === 'true';
const accessMutation = (value: unknown, response: Response): AccessMutation => ({ record: accessRecord(value, true), replayed: replayed(response) });

export function createVisitsApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  return {
    unitPermits: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/permits`, permitPage, options(signal)),
    createPermit: async (tenantId: string, unitId: string, key: string, body: CreatePermitBody) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/permits`,
        (value, response): PermitMutation => ({ permit: visitPermit(value, true), replayed: replayed(response) }), { method: 'POST', body, key }),
    revokePermit: async (tenantId: string, permitId: string, body: { reason: string; expectedVersion: number }) =>
      client.call(`${base(tenantId)}/permits/${identifier(permitId)}/revoke`, (value) => visitPermit(value, true), { method: 'POST', body }),
    shareLink: async (tenantId: string, permitId: string) =>
      client.call(`${base(tenantId)}/permits/${identifier(permitId)}/link`, permitLink, { method: 'POST' }),
    revokeLink: async (tenantId: string, permitId: string) =>
      client.call(`${base(tenantId)}/permits/${identifier(permitId)}/link/revoke`, (value) => visitPermit(value, true), { method: 'POST' }),
    unitArrivals: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/arrivals`, arrivalPage, options(signal)),
    decide: async (tenantId: string, arrivalId: string, decision: 'APPROVE' | 'DENY') =>
      client.call(`${base(tenantId)}/arrivals/${identifier(arrivalId)}/decision`, (value) => arrival(value, true), { method: 'POST', body: { decision } }),
    expected: async (tenantId: string, filter: { unitId?: string | null; q?: string | null }, signal?: AbortSignal) => {
      const query = new URLSearchParams();
      if (filter.unitId) query.set('unitId', identifier(filter.unitId));
      if (filter.q) query.set('q', filter.q);
      return client.call(`${base(tenantId)}/concierge/permits${query.size ? `?${query}` : ''}`, permitPage, options(signal));
    },
    queue: async (tenantId: string, status: ArrivalState | null, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/arrivals${status ? `?status=${status}` : ''}`, arrivalPage, options(signal)),
    register: async (tenantId: string, key: string, body: RegisterArrivalBody) =>
      client.call(`${base(tenantId)}/arrivals`,
        (value, response): ArrivalMutation => ({ arrival: arrival(value, true), replayed: replayed(response) }), { method: 'POST', body, key }),
    assisted: async (tenantId: string, arrivalId: string, body: { decision: 'APPROVE' | 'DENY'; source: 'INTERCOM' | 'IN_PERSON'; note: string }) =>
      client.call(`${base(tenantId)}/arrivals/${identifier(arrivalId)}/assisted-decision`, (value) => arrival(value, true), { method: 'POST', body }),
    cancel: async (tenantId: string, arrivalId: string, reason: string) =>
      client.call(`${base(tenantId)}/arrivals/${identifier(arrivalId)}/cancel`, (value) => arrival(value, true), { method: 'POST', body: { reason } }),
    records: async (tenantId: string, signal?: AbortSignal) => client.call(`${base(tenantId)}/access-events`, accessPage, options(signal)),
    unitRecords: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/access-events`, accessPage, options(signal)),
    recordEntry: async (tenantId: string, key: string, body: AccessEntryBody) =>
      client.call(`${base(tenantId)}/access-events/entries`, accessMutation, { method: 'POST', body, key }),
    recordExit: async (tenantId: string, entryId: string, key: string, note: string | null) =>
      client.call(`${base(tenantId)}/access-events/${identifier(entryId)}/exit`, accessMutation, { method: 'POST', body: { note }, key }),
    correct: async (tenantId: string, eventId: string, key: string, reason: string) =>
      client.call(`${base(tenantId)}/access-events/${identifier(eventId)}/correction`, accessMutation, { method: 'POST', body: { reason }, key }),
  };
}
export const visits = createVisitsApi();
