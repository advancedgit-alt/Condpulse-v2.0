import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Ocorrências da portaria (CP-027): registro interno da equipe (portaria e gestão) do que precisa de verificação.
 * Autoria, nomes e horários vêm do servidor; encerrar exige resolução e a versão lida. Nunca exibido a moradores.
 */
export const occurrenceCategories = ['EQUIPMENT', 'SECURITY', 'MAINTENANCE', 'CLEANING', 'NOISE', 'OTHER'] as const;
export type OccurrenceCategory = typeof occurrenceCategories[number];
export interface Occurrence {
  occurrenceId: UUID; category: OccurrenceCategory; title: string; description: string; unitId: UUID | null; unitLabel: string | null;
  state: 'OPEN' | 'RESOLVED'; createdByName: string; createdByMe: boolean; createdAt: ISODateTime; resolution: string | null;
  resolvedByName: string | null; resolvedAt: ISODateTime | null; version: number;
}
export interface OccurrencePage { occurrences: Occurrence[]; open: number; truncated: boolean }
export interface CreateOccurrence { occurrenceId: UUID; category: OccurrenceCategory; title: string; description: string; unitId: UUID | null }

const text = (value: unknown, min: number, max: number): value is string => isString(value) && codePoints(value) >= min && codePoints(value) <= max;
export function occurrence(value: unknown, uncertain = false): Occurrence {
  if (!record(value) || !isUUID(value.occurrenceId) || !oneOf(value.category, occurrenceCategories) || !text(value.title, 3, 120)
      || !text(value.description, 1, 2000) || !(value.unitId === null || isUUID(value.unitId)) || !(value.unitLabel === null || isString(value.unitLabel))
      || (value.unitId === null) !== (value.unitLabel === null) || !oneOf(value.state, ['OPEN', 'RESOLVED'] as const) || !text(value.createdByName, 1, 160)
      || typeof value.createdByMe !== 'boolean' || !isDate(value.createdAt) || !isInteger(value.version) || value.version < 0
      || !(value.resolution === null || text(value.resolution, 1, 1000)) || !(value.resolvedByName === null || text(value.resolvedByName, 1, 160))
      || !(value.resolvedAt === null || isDate(value.resolvedAt))
      || (value.state === 'RESOLVED') !== (value.resolution !== null && value.resolvedByName !== null && value.resolvedAt !== null)
      || (value.state === 'OPEN' && (value.resolution !== null || value.resolvedByName !== null || value.resolvedAt !== null))) throw invalidResponse(uncertain);
  return { occurrenceId: value.occurrenceId, category: value.category, title: value.title, description: value.description,
    unitId: value.unitId as string | null, unitLabel: value.unitLabel as string | null, state: value.state, createdByName: value.createdByName,
    createdByMe: value.createdByMe, createdAt: value.createdAt, resolution: value.resolution as string | null,
    resolvedByName: value.resolvedByName as string | null, resolvedAt: value.resolvedAt as string | null, version: value.version };
}
function page(value: unknown): OccurrencePage {
  if (!record(value) || !Array.isArray(value.occurrences) || value.occurrences.length > 100 || !isInteger(value.open) || value.open < 0
      || typeof value.truncated !== 'boolean') throw invalidResponse();
  const occurrences = value.occurrences.map((item) => occurrence(item));
  if (value.open < occurrences.filter((item) => item.state === 'OPEN').length) throw invalidResponse();
  return { occurrences, open: value.open, truncated: value.truncated };
}

export function createOccurrencesApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/concierge/occurrences`;
  return {
    list: async (tenantId: string, resolved: boolean, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}${resolved ? '?resolved=true' : ''}`, page, signal ? { signal } : {}),
    /** O identificador gerado pela tela é a chave: repetir o envio devolve o mesmo registro. */
    create: async (tenantId: string, body: CreateOccurrence) =>
      client.call(base(tenantId), (value, response) => {
        const created = occurrence(value, true);
        if (created.occurrenceId !== body.occurrenceId || created.title !== body.title || created.category !== body.category
          || created.unitId !== body.unitId) throw invalidResponse(true);
        return { occurrence: created, replayed: response.headers.get('Idempotency-Replayed') === 'true' };
      }, { method: 'POST', body }),
    resolve: async (tenantId: string, occurrenceId: string, resolution: string, expectedVersion: number) =>
      client.call(`${base(tenantId)}/${identifier(occurrenceId)}/resolve`, (value) => {
        const resolved = occurrence(value, true);
        if (resolved.occurrenceId !== occurrenceId || resolved.state !== 'RESOLVED') throw invalidResponse(true);
        return resolved;
      }, { method: 'POST', body: { resolution, expectedVersion } }),
  };
}
export const occurrences = createOccurrencesApi();
