import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isUUID, list, oneOf, record } from './client';
import { codePoints } from './text';
import type { ISODateTime, UUID } from './types';

/**
 * Avisos (CP-032). A gestão cria rascunho, publica para o condomínio, blocos ou unidades e arquiva; publicado não muda
 * de texto. A unidade recebe a lista sem o texto e o texto só na abertura autenticada, registrada uma vez — abertura
 * não é ciência jurídica nem confirmação de recebimento. Texto simples: a tela nunca interpreta como marcação.
 */
export type AnnouncementAudience = 'TENANT' | 'BLOCKS' | 'UNITS';
export type AnnouncementState = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export interface ManagedAnnouncement {
  announcementId: UUID; title: string; body: string; audience: AnnouncementAudience; audienceIds: UUID[]; state: AnnouncementState;
  authoredByMe: boolean; createdAt: ISODateTime; updatedAt: ISODateTime; publishedAt: ISODateTime | null; archivedAt: ISODateTime | null;
  version: number; opens: number;
}
export interface ManagedAnnouncementPage { announcements: ManagedAnnouncement[]; truncated: boolean }
export interface AnnouncementDraftBody { title: string; body: string; audience: AnnouncementAudience; audienceIds: UUID[] }
export interface DraftMutation { announcement: ManagedAnnouncement; replayed: boolean }
export interface UnitAnnouncement { announcementId: UUID; title: string; publishedAt: ISODateTime; openedAt: ISODateTime | null }
export interface UnitAnnouncementPage { announcements: UnitAnnouncement[]; truncated: boolean }
export interface OpenedAnnouncement { announcementId: UUID; title: string; body: string; publishedAt: ISODateTime; openedAt: ISODateTime }

const title = (value: unknown): value is string => typeof value === 'string' && codePoints(value) >= 3 && codePoints(value) <= 160;
const body = (value: unknown): value is string => typeof value === 'string' && codePoints(value) >= 1 && codePoints(value) <= 5000;
const nullableDate = (value: unknown): value is string | null => value === null || isDate(value);

export function managedAnnouncement(value: unknown, uncertain = false): ManagedAnnouncement {
  if (!record(value) || !isUUID(value.announcementId) || !title(value.title) || !body(value.body)
      || !oneOf(value.audience, ['TENANT', 'BLOCKS', 'UNITS']) || !Array.isArray(value.audienceIds) || value.audienceIds.length > 200
      || !value.audienceIds.every(isUUID) || (value.audience === 'TENANT') !== (value.audienceIds.length === 0)
      || !oneOf(value.state, ['DRAFT', 'PUBLISHED', 'ARCHIVED']) || typeof value.authoredByMe !== 'boolean' || !isDate(value.createdAt)
      || !isDate(value.updatedAt) || !nullableDate(value.publishedAt) || !nullableDate(value.archivedAt) || !isInteger(value.version)
      || !isInteger(value.opens)) throw invalidResponse(uncertain);
  // Publicado sempre com horário; rascunho nunca; arquivado com horário de arquivamento; abertura só depois de publicar.
  if ((value.state === 'PUBLISHED' && value.publishedAt === null) || (value.state === 'DRAFT' && value.publishedAt !== null)
      || (value.state === 'ARCHIVED') !== (value.archivedAt !== null) || (value.publishedAt === null && value.opens > 0)) throw invalidResponse(uncertain);
  return { announcementId: value.announcementId, title: value.title, body: value.body, audience: value.audience,
    audienceIds: value.audienceIds as string[], state: value.state, authoredByMe: value.authoredByMe, createdAt: value.createdAt,
    updatedAt: value.updatedAt, publishedAt: value.publishedAt as string | null, archivedAt: value.archivedAt as string | null,
    version: value.version, opens: value.opens };
}
function managedPage(value: unknown): ManagedAnnouncementPage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.announcements) || value.announcements.length > 100) throw invalidResponse();
  return { announcements: list(value.announcements, (item) => managedAnnouncement(item)), truncated: value.truncated };
}
export function unitAnnouncement(value: unknown): UnitAnnouncement {
  // A lista da unidade nunca traz o texto: resposta com corpo é recusada.
  if (!record(value) || 'body' in value || !isUUID(value.announcementId) || !title(value.title) || !isDate(value.publishedAt)
      || !nullableDate(value.openedAt)) throw invalidResponse();
  return { announcementId: value.announcementId, title: value.title, publishedAt: value.publishedAt, openedAt: value.openedAt as string | null };
}
function unitPage(value: unknown): UnitAnnouncementPage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.announcements) || value.announcements.length > 100) throw invalidResponse();
  return { announcements: list(value.announcements, unitAnnouncement), truncated: value.truncated };
}
export function openedAnnouncement(value: unknown): OpenedAnnouncement {
  if (!record(value) || !isUUID(value.announcementId) || !title(value.title) || !body(value.body) || !isDate(value.publishedAt)
      || !isDate(value.openedAt)) throw invalidResponse(true);
  return { announcementId: value.announcementId, title: value.title, body: value.body, publishedAt: value.publishedAt, openedAt: value.openedAt };
}

export function createAnnouncementsApi(client: CondPulseApi = api) {
  const management = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/management/announcements`;
  const mutation = (expected: (item: ManagedAnnouncement) => boolean) => (value: unknown) => {
    const item = managedAnnouncement(value, true);
    if (!expected(item)) throw invalidResponse(true);
    return item;
  };
  return {
    managed: async (tenantId: string, signal?: AbortSignal) => client.call(management(tenantId), managedPage, signal ? { signal } : {}),
    createDraft: async (tenantId: string, key: string, draft: AnnouncementDraftBody) =>
      client.call(management(tenantId), (value, response): DraftMutation => {
        // Repetição devolve o estado atual do rascunho (pode já ter sido editado ou publicado); criação nova é rascunho.
        const item = managedAnnouncement(value, true);
        const replayed = response.headers.get('Idempotency-Replayed') === 'true';
        if (!replayed && (item.state !== 'DRAFT' || item.version !== 0 || item.title !== draft.title)) throw invalidResponse(true);
        return { announcement: item, replayed };
      }, { method: 'POST', body: draft, key }),
    editDraft: async (tenantId: string, announcementId: string, body: AnnouncementDraftBody & { expectedVersion: number }) =>
      client.call(`${management(tenantId)}/${identifier(announcementId)}`,
        mutation((item) => item.announcementId === announcementId && item.state === 'DRAFT' && item.version === body.expectedVersion + 1),
        { method: 'PUT', body }),
    publish: async (tenantId: string, announcementId: string, expectedVersion: number) =>
      client.call(`${management(tenantId)}/${identifier(announcementId)}/publish`,
        mutation((item) => item.announcementId === announcementId && item.state === 'PUBLISHED' && item.version === expectedVersion + 1),
        { method: 'POST', body: { expectedVersion } }),
    archive: async (tenantId: string, announcementId: string, expectedVersion: number, reason: string | null) =>
      client.call(`${management(tenantId)}/${identifier(announcementId)}/archive`,
        mutation((item) => item.announcementId === announcementId && item.state === 'ARCHIVED' && item.version === expectedVersion + 1),
        { method: 'POST', body: { expectedVersion, reason } }),
    unit: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(`/v1/tenants/${identifier(tenantId)}/units/${identifier(unitId)}/announcements`, unitPage, signal ? { signal } : {}),
    /** Abertura autenticada: devolve o texto e registra a primeira abertura (repetir não muda o horário). */
    open: async (tenantId: string, announcementId: string) =>
      client.call(`/v1/tenants/${identifier(tenantId)}/announcements/${identifier(announcementId)}/open`, (value) => {
        const opened = openedAnnouncement(value);
        if (opened.announcementId !== announcementId) throw invalidResponse(true);
        return opened;
      }, { method: 'POST' }),
  };
}
export const announcements = createAnnouncementsApi();
