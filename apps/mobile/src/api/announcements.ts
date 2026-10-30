import { ContractError } from './reservations';

/**
 * Avisos do condomínio no aplicativo (CP-032). A lista da unidade nunca traz o texto; abrir devolve o texto e registra
 * a primeira abertura com a conta. Abertura não é ciência jurídica nem confirmação de recebimento.
 */
export interface UnitAnnouncement { announcementId: string; title: string; publishedAt: string; openedAt: string | null }
export interface OpenedAnnouncement { announcementId: string; title: string; body: string; publishedAt: string; openedAt: string }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const title = (value: unknown): value is string => typeof value === 'string' && [...value].length >= 3 && [...value].length <= 160;
const body = (value: unknown): value is string => typeof value === 'string' && [...value].length >= 1 && [...value].length <= 5000;
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

export function unitAnnouncement(value: unknown): UnitAnnouncement {
  // Lista com corpo é recusada: o texto só chega pela abertura, que fica registrada.
  check(record(value) && !('body' in value) && typeof value.announcementId === 'string' && UUID.test(value.announcementId) && title(value.title)
    && date(value.publishedAt) && (value.openedAt === null || date(value.openedAt)));
  return { announcementId: value.announcementId as string, title: value.title as string, publishedAt: value.publishedAt as string, openedAt: value.openedAt as string | null };
}
export function announcementPage(value: unknown): { announcements: UnitAnnouncement[]; truncated: boolean } {
  check(record(value) && typeof value.truncated === 'boolean' && Array.isArray(value.announcements) && value.announcements.length <= 100);
  return { announcements: (value.announcements as unknown[]).map(unitAnnouncement), truncated: value.truncated as boolean };
}
export function openedAnnouncement(value: unknown, announcementId: string): OpenedAnnouncement {
  check(record(value) && value.announcementId === announcementId && title(value.title) && body(value.body) && date(value.publishedAt) && date(value.openedAt));
  return { announcementId, title: value.title as string, body: value.body as string, publishedAt: value.publishedAt as string, openedAt: value.openedAt as string };
}
