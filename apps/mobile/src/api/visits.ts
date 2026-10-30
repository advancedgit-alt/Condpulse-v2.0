import { ContractError } from './reservations';

/**
 * Visitas no aplicativo (CP-025, CP-026 e CP-037), validadas como na web antes de chegar à tela. Convite é autorização
 * temporal da unidade, não prova de identidade nem de entrada; a chegada espera a primeira decisão válida até o prazo.
 */
export type PermitState = 'SCHEDULED' | 'ACTIVE' | 'REVOKED' | 'EXPIRED';
export type ArrivalState = 'WAITING' | 'APPROVED' | 'DENIED' | 'EXPIRED' | 'CANCELLED';
export type DecisionSource = 'APP' | 'INTERCOM' | 'IN_PERSON' | 'CONCIERGE';
export interface Permit {
  permitId: string; unitId: string; visitorName: string; documentLast4: string | null; purpose: string | null; validFrom: string; validUntil: string;
  state: PermitState; createdByMe: boolean; revokedAt: string | null; revokeReason: string | null; version: number; shareLinkActive: boolean;
  reservationId: string | null; ruleId: string | null;
}
export interface Arrival {
  arrivalId: string; unitId: string; visitorName: string; documentLast4: string | null; permitId: string | null; state: ArrivalState; expiresAt: string;
  decidedAt: string | null; decisionSource: DecisionSource | null; decisionNote: string | null; decidedByMe: boolean; version: number; createdAt: string;
}
export interface PermitLink { token: string; validUntil: string }
export interface CreatePermit { visitorName: string; documentLast4: string | null; purpose: string | null; validFrom: string; validUntil: string }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const optionalDate = (value: unknown) => value === null || date(value);
const text = (value: unknown, min: number, max: number): value is string => typeof value === 'string' && [...value].length >= min && [...value].length <= max;
const optional = (value: unknown, max: number) => value === null || text(value, 1, max);
const document = (value: unknown) => value === null || (typeof value === 'string' && /^[0-9A-Z]{2,4}$/.test(value));
const int = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function permit(value: unknown, unitId: string): Permit {
  check(record(value) && uuid(value.permitId) && uuid(value.unitId) && value.unitId.toLowerCase() === unitId.toLowerCase() && text(value.visitorName, 2, 160)
    && document(value.documentLast4) && optional(value.purpose, 200) && date(value.validFrom) && date(value.validUntil)
    && Date.parse(value.validUntil as string) > Date.parse(value.validFrom as string)
    && ['SCHEDULED', 'ACTIVE', 'REVOKED', 'EXPIRED'].includes(value.state as string) && typeof value.createdByMe === 'boolean'
    && optionalDate(value.revokedAt) && optional(value.revokeReason, 500) && int(value.version) && typeof value.shareLinkActive === 'boolean'
    && (value.reservationId === null || uuid(value.reservationId)) && (value.ruleId === null || uuid(value.ruleId)));
  // Revogado somente com motivo e horário, e com a versão já avançada.
  const revoked = value.state === 'REVOKED';
  check(revoked === (value.revokedAt !== null) && revoked === (value.revokeReason !== null) && !(revoked && (value.version as number) < 1));
  return { permitId: value.permitId as string, unitId: value.unitId as string, visitorName: value.visitorName as string,
    documentLast4: value.documentLast4 as string | null, purpose: value.purpose as string | null, validFrom: value.validFrom as string,
    validUntil: value.validUntil as string, state: value.state as PermitState, createdByMe: value.createdByMe as boolean,
    revokedAt: value.revokedAt as string | null, revokeReason: value.revokeReason as string | null, version: value.version as number,
    shareLinkActive: value.shareLinkActive as boolean, reservationId: value.reservationId as string | null, ruleId: value.ruleId as string | null };
}
export function arrival(value: unknown, unitId: string): Arrival {
  check(record(value) && uuid(value.arrivalId) && uuid(value.unitId) && value.unitId.toLowerCase() === unitId.toLowerCase() && text(value.visitorName, 2, 160)
    && document(value.documentLast4) && (value.permitId === null || uuid(value.permitId))
    && ['WAITING', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED'].includes(value.state as string) && date(value.expiresAt) && optionalDate(value.decidedAt)
    && (value.decisionSource === null || ['APP', 'INTERCOM', 'IN_PERSON', 'CONCIERGE'].includes(value.decisionSource as string))
    && optional(value.decisionNote, 300) && typeof value.decidedByMe === 'boolean' && int(value.version) && date(value.createdAt));
  // Decidida/encerrada somente com origem e horário; aguardando ou vencida, sem nada disso.
  const closed = ['APPROVED', 'DENIED', 'CANCELLED'].includes(value.state as string);
  const assisted = value.decisionSource === 'INTERCOM' || value.decisionSource === 'IN_PERSON';
  check(closed === (value.decidedAt !== null) && closed === (value.decisionSource !== null) && !(closed && (value.version as number) < 1)
    && (value.state === 'CANCELLED') === (value.decisionSource === 'CONCIERGE') && !(assisted && value.decisionNote === null)
    && !(!closed && value.decidedByMe));
  return { arrivalId: value.arrivalId as string, unitId: value.unitId as string, visitorName: value.visitorName as string,
    documentLast4: value.documentLast4 as string | null, permitId: value.permitId as string | null, state: value.state as ArrivalState,
    expiresAt: value.expiresAt as string, decidedAt: value.decidedAt as string | null, decisionSource: value.decisionSource as DecisionSource | null,
    decisionNote: value.decisionNote as string | null, decidedByMe: value.decidedByMe as boolean, version: value.version as number, createdAt: value.createdAt as string };
}
export function permitPage(value: unknown, unitId: string): { permits: Permit[]; truncated: boolean } {
  check(record(value) && Array.isArray(value.permits) && value.permits.length <= 100 && typeof value.truncated === 'boolean');
  return { permits: (value.permits as unknown[]).map((item) => permit(item, unitId)), truncated: value.truncated as boolean };
}
export function arrivalPage(value: unknown, unitId: string): { arrivals: Arrival[]; truncated: boolean } {
  check(record(value) && Array.isArray(value.arrivals) && value.arrivals.length <= 100 && typeof value.truncated === 'boolean');
  return { arrivals: (value.arrivals as unknown[]).map((item) => arrival(item, unitId)), truncated: value.truncated as boolean };
}
/** Token devolvido uma única vez; vai no fragmento do link (/visita#token), que o servidor nunca recebe pela barra. */
export function permitLink(value: unknown): PermitLink {
  check(record(value) && typeof value.token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value.token) && date(value.validUntil) && Object.keys(value).length === 2);
  return { token: value.token as string, validUntil: value.validUntil as string };
}
/** Documento: só os 2 a 4 últimos caracteres, maiúsculos; '' = inválido; null = não informado. */
export function normalizeDocument(value: string): string | null {
  const normalized = value.replace(/\s+/g, '').toUpperCase();
  if (!normalized) return null;
  return /^[0-9A-Z]{2,4}$/.test(normalized) ? normalized : '';
}
