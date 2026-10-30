import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, list, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Mudança e entrega de móveis (CP-042). A gestão publica as regras (texto, dias, horário local, antecedência e duração);
 * a unidade (morador ou responsável) pede um período aceitando a versão vigente e pode retirar o pedido antes do início;
 * a gestão aprova ou recusa (com motivo) e a portaria vê as aprovadas. A aprovação não reserva elevador nem área.
 */
export const moveKinds = ['MOVE_IN', 'MOVE_OUT', 'FURNITURE_DELIVERY'] as const;
export const moveStates = ['REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'] as const;
export type MoveKind = typeof moveKinds[number];
export type MoveState = typeof moveStates[number];

export interface MoveRules {
  rulesText: string; weekDays: number[]; startTime: string; endTime: string; minNoticeHours: number; maxDurationMinutes: number;
  version: number; updatedAt: ISODateTime;
}
export interface MoveRulesUpdate {
  rulesText: string; weekDays: number[]; startTime: string; endTime: string; minNoticeHours: number; maxDurationMinutes: number;
  expectedVersion: number | null;
}
export interface Move {
  moveId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; kind: MoveKind; startsAt: ISODateTime; endsAt: ISODateTime;
  providerName: string; vehiclePlate: string | null; items: string | null; rulesVersion: number; state: MoveState;
  decisionNote: string | null; decidedAt: ISODateTime | null; createdByMe: boolean; createdAt: ISODateTime; version: number;
}
export interface MovePage { moves: Move[]; truncated: boolean }
export interface CreateMoveBody {
  kind: MoveKind; startsAt: string; endsAt: string; providerName: string; vehiclePlate: string | null; items: string | null;
  acceptedRulesVersion: number;
}
export interface CreateMoveResult { move: Move; replayed: boolean }

const text = (minimum: number, maximum: number) => (value: unknown): value is string =>
  typeof value === 'string' && codePoints(value) >= minimum && codePoints(value) <= maximum;
const label = text(1, 160);
const provider = text(2, 160);
const note = text(1, 500);
const rulesText = text(1, 2000);
const time = (value: unknown): value is string => typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
const nullable = <T>(check: (value: unknown) => value is T) => (value: unknown): value is T | null => value === null || check(value);

/** Placa como o servidor grava: só letras maiúsculas e números, 5 a 8 caracteres. */
export function normalizePlate(value: string): string | null {
  const plate = value.replace(/[\s-]/g, '').toUpperCase();
  if (!plate) return null;
  return /^[A-Z0-9]{5,8}$/.test(plate) ? plate : '';
}

export function moveRules(value: unknown, uncertain = false): MoveRules {
  if (!record(value) || !rulesText(value.rulesText) || !Array.isArray(value.weekDays) || value.weekDays.length < 1 || value.weekDays.length > 7
      || !value.weekDays.every((day, index, days) => Number.isInteger(day) && day >= 1 && day <= 7 && (index === 0 || day > days[index - 1]))
      || !time(value.startTime) || !time(value.endTime) || value.startTime >= value.endTime
      || !isInteger(value.minNoticeHours) || value.minNoticeHours > 720
      || !isInteger(value.maxDurationMinutes) || value.maxDurationMinutes < 30 || value.maxDurationMinutes > 720
      || !isInteger(value.version) || value.version < 1 || !isDate(value.updatedAt)) throw invalidResponse(uncertain);
  return { rulesText: value.rulesText, weekDays: [...value.weekDays] as number[], startTime: value.startTime, endTime: value.endTime,
    minNoticeHours: value.minNoticeHours, maxDurationMinutes: value.maxDurationMinutes, version: value.version, updatedAt: value.updatedAt };
}
function currentRules(value: unknown): MoveRules | null {
  if (!record(value) || !('rules' in value)) throw invalidResponse();
  return value.rules === null ? null : moveRules(value.rules);
}

export function move(value: unknown, uncertain = false): Move {
  if (!record(value) || !isUUID(value.moveId) || !isUUID(value.unitId) || !label(value.unitLabel) || !nullable(label)(value.blockName)
      || !oneOf(value.kind, moveKinds) || !isDate(value.startsAt) || !isDate(value.endsAt) || Date.parse(value.startsAt) >= Date.parse(value.endsAt)
      || !provider(value.providerName) || !(value.vehiclePlate === null || (typeof value.vehiclePlate === 'string' && /^[A-Z0-9]{5,8}$/.test(value.vehiclePlate)))
      || !nullable(note)(value.items) || !isInteger(value.rulesVersion) || value.rulesVersion < 1 || !oneOf(value.state, moveStates)
      || !nullable(note)(value.decisionNote) || !nullable(isDate)(value.decidedAt) || typeof value.createdByMe !== 'boolean'
      || !isDate(value.createdAt) || !isInteger(value.version)) throw invalidResponse(uncertain);
  // Aprovado ou recusado sempre tem horário da decisão; recusado sempre tem motivo; pedido aguardando não tem decisão.
  if ((value.state === 'APPROVED' || value.state === 'REJECTED') && value.decidedAt === null) throw invalidResponse(uncertain);
  if (value.state === 'REJECTED' && value.decisionNote === null) throw invalidResponse(uncertain);
  if (value.state === 'REQUESTED' && (value.decidedAt !== null || value.decisionNote !== null)) throw invalidResponse(uncertain);
  return { moveId: value.moveId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null, kind: value.kind,
    startsAt: value.startsAt, endsAt: value.endsAt, providerName: value.providerName, vehiclePlate: value.vehiclePlate as string | null,
    items: value.items as string | null, rulesVersion: value.rulesVersion, state: value.state, decisionNote: value.decisionNote as string | null,
    decidedAt: value.decidedAt as string | null, createdByMe: value.createdByMe, createdAt: value.createdAt, version: value.version };
}
function page(value: unknown): MovePage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.moves) || value.moves.length > 100) throw invalidResponse();
  return { moves: list(value.moves, (item) => move(item)), truncated: value.truncated };
}

export function createMovesApi(client: CondPulseApi = api) {
  const tenant = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  return {
    rules: async (tenantId: string, signal?: AbortSignal) => client.call(`${tenant(tenantId)}/move-rules`, currentRules, options(signal)),
    updateRules: async (tenantId: string, body: MoveRulesUpdate) =>
      client.call(`${tenant(tenantId)}/management/move-rules`, (value) => {
        const rules = moveRules(value, true);
        if (rules.rulesText !== body.rulesText || rules.startTime !== body.startTime || rules.endTime !== body.endTime
          || (body.expectedVersion !== null && rules.version !== body.expectedVersion + 1)) throw invalidResponse(true);
        return rules;
      }, { method: 'PUT', body }),
    unit: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/units/${identifier(unitId)}/moves`, (value) => {
        const result = page(value);
        if (result.moves.some((item) => item.unitId !== unitId)) throw invalidResponse();
        return result;
      }, options(signal)),
    create: async (tenantId: string, unitId: string, key: string, body: CreateMoveBody) =>
      client.call(`${tenant(tenantId)}/units/${identifier(unitId)}/moves`, (value, response): CreateMoveResult => {
        // Repetição devolve o pedido como está agora; pedido novo nasce aguardando, com o período e as regras enviados.
        const created = move(value, true);
        const replayed = response.headers.get('Idempotency-Replayed') === 'true';
        if (created.unitId !== unitId || (!replayed && (created.state !== 'REQUESTED' || created.kind !== body.kind
          || Date.parse(created.startsAt) !== Date.parse(body.startsAt) || Date.parse(created.endsAt) !== Date.parse(body.endsAt)
          || created.rulesVersion !== body.acceptedRulesVersion || !created.createdByMe))) throw invalidResponse(true);
        return { move: created, replayed };
      }, { method: 'POST', body, key }),
    cancel: async (tenantId: string, moveId: string, expectedVersion: number, note: string | null = null) =>
      client.call(`${tenant(tenantId)}/moves/${identifier(moveId)}/cancel`, (value) => {
        const cancelled = move(value, true);
        if (cancelled.moveId !== moveId || cancelled.state !== 'CANCELLED') throw invalidResponse(true);
        return cancelled;
      }, { method: 'POST', body: { note, expectedVersion } }),
    managed: async (tenantId: string, state: MoveState | null, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/management/moves${state ? `?state=${encodeURIComponent(state)}` : ''}`, (value) => {
        const result = page(value);
        if (state && result.moves.some((item) => item.state !== state)) throw invalidResponse();
        return result;
      }, options(signal)),
    decide: async (tenantId: string, moveId: string, decision: 'APPROVE' | 'REJECT', note: string | null, expectedVersion: number) =>
      client.call(`${tenant(tenantId)}/management/moves/${identifier(moveId)}/decision`, (value) => {
        const decided = move(value, true);
        if (decided.moveId !== moveId || decided.state !== (decision === 'APPROVE' ? 'APPROVED' : 'REJECTED')) throw invalidResponse(true);
        return decided;
      }, { method: 'POST', body: { decision, note, expectedVersion } }),
    concierge: async (tenantId: string, from: string, until: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/concierge/moves?from=${encodeURIComponent(from)}&until=${encodeURIComponent(until)}`, (value) => {
        const result = page(value);
        // A portaria só recebe pedidos aprovados que se sobrepõem ao intervalo pedido.
        if (result.moves.some((item) => item.state !== 'APPROVED' || Date.parse(item.endsAt) <= Date.parse(from)
          || Date.parse(item.startsAt) >= Date.parse(until))) throw invalidResponse();
        return result;
      }, options(signal)),
  };
}
export const moves = createMovesApi();
