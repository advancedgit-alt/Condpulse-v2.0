import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Permanências (V055, CPX-015 a CPX-019, StayController). Toda entrada registrada abre uma permanência e a saída a fecha.
 * "Aberta" quer dizer sem registro de saída, não prova de que a pessoa continua no condomínio (R5.10): a tela mostra a
 * origem do dado. Painel e fechamento do dia: portaria ou parceiro com alcance, AAL2, cada leitura grava SENSITIVE_VIEWED.
 * Confirmação de saída: morador ou responsável da unidade. Rotas atrás de CP_VISITS_ENABLED (404 desligada).
 */
export const stayKinds = ['VISITOR', 'SERVICE_PROVIDER', 'DELIVERY', 'CONSTRUCTION', 'EVENT_GUEST'] as const;
export type StayKind = typeof stayKinds[number];
export const stayLevels = ['NONE', 'DUE', 'L1', 'L2'] as const;
export type StayLevel = typeof stayLevels[number];
const origins = ['MANUAL', 'FACIAL', 'QR', 'LINK'] as const;
const bases = ['ARRIVAL', 'PERMIT', 'RULE'] as const;
const expectedSources = ['PERMIT', 'RULE', 'TENANT_RULE', 'PLATFORM_DEFAULT'] as const;
const states = ['OPEN', 'CLOSED', 'VOIDED'] as const;
const closures = ['EXIT_RECORDED', 'RESIDENT_CONFIRMED', 'ENTRY_CORRECTED'] as const;
export const STAY_PAGE = 200;

export interface Stay {
  stayId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; visitorName: string | null; documentLast4: string | null;
  kind: StayKind; origin: typeof origins[number]; basis: typeof bases[number]; enteredAt: ISODateTime; expectedExitAt: ISODateTime;
  expectedSource: typeof expectedSources[number]; state: typeof states[number]; closure: typeof closures[number] | null; closedAt: ISODateTime | null;
  level: StayLevel; recordedLevel: StayLevel; overdue: boolean; justified: boolean; justification: string | null; justifiedAt: ISODateTime | null;
  entryEventId: UUID; exitEventId: UUID | null; reservationId?: UUID | null; amenityName?: string | null; controlEnabled?: boolean;
}
export interface StayPage { stays: Stay[]; truncated: boolean; generatedAt: ISODateTime }
export interface StayDayClose {
  date: string; timeZone: string; from: ISODateTime; until: ISODateTime; generatedAt: ISODateTime; entries: Stay[]; truncated: boolean;
  stillOpen: number; listHash: string;
}
export interface StayMutation { stay: Stay; replayed: boolean }

const nullable = <T>(value: unknown, check: (item: unknown) => item is T) => value === null || value === undefined || check(value);
const text = (value: unknown) => (value as string | null | undefined) ?? null;

export function stay(value: unknown, uncertain = false): Stay {
  if (!record(value) || !isUUID(value.stayId) || !isUUID(value.unitId) || !isString(value.unitLabel) || !nullable(value.blockName, isString)
      || !nullable(value.visitorName, isString) || !nullable(value.documentLast4, isString) || !oneOf(value.kind, stayKinds)
      || !oneOf(value.origin, origins) || !oneOf(value.basis, bases) || !isDate(value.enteredAt) || !isDate(value.expectedExitAt)
      || !oneOf(value.expectedSource, expectedSources) || !oneOf(value.state, states) || !nullable(value.closure, (item): item is string => oneOf(item, closures))
      || !nullable(value.closedAt, isDate) || !oneOf(value.level, stayLevels) || !oneOf(value.recordedLevel, stayLevels)
      || typeof value.overdue !== 'boolean' || typeof value.justified !== 'boolean' || !nullable(value.justification, isString)
      || !nullable(value.reservationId, isUUID) || !nullable(value.amenityName, isString)
      || !(value.controlEnabled === undefined || typeof value.controlEnabled === 'boolean')
      || !nullable(value.justifiedAt, isDate) || !isUUID(value.entryEventId) || !nullable(value.exitEventId, isUUID)) throw invalidResponse(uncertain);
  // Aberta não tem fechamento; fechada ou anulada sempre diz como e quando. Vencida só aberta.
  if ((value.state === 'OPEN') !== (value.closure === null || value.closure === undefined)
      || (value.state === 'OPEN') !== (value.closedAt === null || value.closedAt === undefined)
      || (value.overdue && value.state !== 'OPEN')) throw invalidResponse(uncertain);
  return { stayId: value.stayId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: text(value.blockName), visitorName: text(value.visitorName),
    documentLast4: text(value.documentLast4), kind: value.kind, origin: value.origin, basis: value.basis, enteredAt: value.enteredAt,
    expectedExitAt: value.expectedExitAt, expectedSource: value.expectedSource, state: value.state,
    closure: text(value.closure) as Stay['closure'], closedAt: text(value.closedAt), level: value.level, recordedLevel: value.recordedLevel,
    overdue: value.overdue, justified: value.justified, justification: text(value.justification), justifiedAt: text(value.justifiedAt),
    entryEventId: value.entryEventId, exitEventId: text(value.exitEventId), reservationId: text(value.reservationId),
    amenityName: text(value.amenityName), controlEnabled: value.controlEnabled !== false };
}

export function stayPage(value: unknown): StayPage {
  if (!record(value) || !Array.isArray(value.stays) || value.stays.length > STAY_PAGE || typeof value.truncated !== 'boolean' || !isDate(value.generatedAt))
    throw invalidResponse();
  const stays = value.stays.map((item) => stay(item));
  if (new Set(stays.map((item) => item.stayId)).size !== stays.length) throw invalidResponse();
  return { stays, truncated: value.truncated, generatedAt: value.generatedAt };
}

export function stayDayClose(value: unknown, date?: string): StayDayClose {
  if (!record(value) || !isString(value.date) || !/^\d{4}-\d{2}-\d{2}$/.test(value.date) || (date !== undefined && value.date !== date)
      || !isString(value.timeZone) || !isDate(value.from) || !isDate(value.until) || !isDate(value.generatedAt) || !Array.isArray(value.entries)
      || value.entries.length > 500 || typeof value.truncated !== 'boolean' || !isInteger(value.stillOpen) || value.stillOpen < 0
      || !isString(value.listHash) || !/^[0-9a-f]{64}$/.test(value.listHash)) throw invalidResponse();
  const entries = value.entries.map((item) => stay(item));
  // Só entradas do dia, sem anuladas; abertas contadas.
  const from = Date.parse(value.from); const until = Date.parse(value.until);
  if (entries.some((item) => item.state === 'VOIDED' || Date.parse(item.enteredAt) < from || Date.parse(item.enteredAt) >= until)
      || entries.filter((item) => item.state === 'OPEN').length !== value.stillOpen) throw invalidResponse();
  return { date: value.date, timeZone: value.timeZone, from: value.from, until: value.until, generatedAt: value.generatedAt, entries,
    truncated: value.truncated, stillOpen: value.stillOpen, listHash: value.listHash };
}

const replayed = (response: Response) => response.headers.get('Idempotency-Replayed') === 'true';

export function createStaysApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  return {
    open: async (tenantId: string, overdueOnly: boolean, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/stays${overdueOnly ? '?view=overdue' : ''}`, stayPage, options(signal)),
    dayClose: async (tenantId: string, date: string | null, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/stays/day-close${date ? `?date=${encodeURIComponent(date)}` : ''}`, (value) => stayDayClose(value, date ?? undefined), options(signal)),
    justify: async (tenantId: string, stayId: string, reason: string) =>
      client.call(`${base(tenantId)}/stays/${identifier(stayId)}/justification`,
        (value, response): StayMutation => ({ stay: stay(value, true), replayed: replayed(response) }), { method: 'POST', body: { reason } }),
    unitOpen: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/stays`, stayPage, options(signal)),
    confirmExit: async (tenantId: string, unitId: string, stayId: string) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/stays/${identifier(stayId)}/exit-confirmation`,
        (value, response): StayMutation => ({ stay: stay(value, true), replayed: replayed(response) }), { method: 'POST' }),
  };
}
export const stays = createStaysApi();

export const stayKindLabels: Record<StayKind, string> = { VISITOR: 'Visitante', SERVICE_PROVIDER: 'Prestador', DELIVERY: 'Entregador', CONSTRUCTION: 'Obra', EVENT_GUEST: 'Convidado de festa/área' };
export const stayLevelLabels: Record<StayLevel, string> = { NONE: 'No prazo', DUE: 'Saída prevista vencida', L1: 'Alerta N1', L2: 'Alerta N2' };
export const stayBasisLabels: Record<Stay['basis'], string> = { ARRIVAL: 'chegada autorizada pela unidade', PERMIT: 'convite', RULE: 'prestador recorrente' };
export const stayOriginLabels: Record<Stay['origin'], string> = { MANUAL: 'registro manual da portaria', FACIAL: 'facial', QR: 'QR', LINK: 'link do convite' };
export const expectedSourceLabels: Record<Stay['expectedSource'], string> = { PERMIT: 'fim do convite', RULE: 'fim da janela do prestador',
  TENANT_RULE: 'regra do condomínio', PLATFORM_DEFAULT: 'padrão provisório da plataforma' };
export const closureLabels: Record<NonNullable<Stay['closure']>, string> = { EXIT_RECORDED: 'saída registrada pela portaria',
  RESIDENT_CONFIRMED: 'saída confirmada pelo morador', ENTRY_CORRECTED: 'entrada corrigida' };
/** Origem do dado em uma frase (R5.10): como a entrada foi registrada e com qual autorização. */
export const stayOrigin = (item: Pick<Stay, 'origin' | 'basis'>) => `${stayOriginLabels[item.origin]}, por ${stayBasisLabels[item.basis]}`;
