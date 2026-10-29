// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createAnnouncementsApi, managedAnnouncement, openedAnnouncement, unitAnnouncement } from './announcements';
import { multilineText } from './text';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const draft = {
  announcementId: '95000000-0000-4000-8000-000000000001', title: 'Assembleia fictícia', body: 'Pauta:\n\n- obras', audience: 'TENANT',
  audienceIds: [], state: 'DRAFT', authoredByMe: true, createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-01T12:00:00Z',
  publishedAt: null, archivedAt: null, version: 0, opens: 0,
};

describe('contrato dos avisos', () => {
  it('estados coerentes: rascunho sem publicação, publicado com horário, aberturas só depois de publicar', () => {
    expect(managedAnnouncement(draft).state).toBe('DRAFT');
    const published = { ...draft, state: 'PUBLISHED', publishedAt: '2030-01-01T12:05:00Z', version: 1, opens: 3 };
    expect(managedAnnouncement(published).opens).toBe(3);
    expect(() => managedAnnouncement({ ...draft, publishedAt: '2030-01-01T12:05:00Z' })).toThrow();
    expect(() => managedAnnouncement({ ...published, publishedAt: null })).toThrow();
    expect(() => managedAnnouncement({ ...draft, opens: 1 })).toThrow();
    expect(() => managedAnnouncement({ ...draft, state: 'ARCHIVED' })).toThrow();
    expect(managedAnnouncement({ ...draft, state: 'ARCHIVED', archivedAt: '2030-01-01T12:10:00Z', version: 1 }).state).toBe('ARCHIVED');
    expect(() => managedAnnouncement({ ...draft, audienceIds: [unit] })).toThrow();
    expect(() => managedAnnouncement({ ...draft, audience: 'UNITS' })).toThrow();
    expect(managedAnnouncement({ ...draft, audience: 'UNITS', audienceIds: [unit] }).audienceIds).toEqual([unit]);
    expect(() => managedAnnouncement({ ...draft, title: 'Oi' })).toThrow();
  });
  it('lista da unidade sem texto; abertura com texto e horário', () => {
    const listed = { announcementId: draft.announcementId, title: draft.title, publishedAt: '2030-01-01T12:05:00Z', openedAt: null };
    expect(unitAnnouncement(listed).openedAt).toBeNull();
    expect(() => unitAnnouncement({ ...listed, body: 'vazou' })).toThrow();
    expect(openedAnnouncement({ ...listed, body: 'Texto', openedAt: '2030-01-01T12:06:00Z' }).body).toBe('Texto');
    expect(() => openedAnnouncement({ ...listed, body: 'Texto' })).toThrow();
  });
  it('texto de várias linhas segue a regra do servidor', () => {
    expect(multilineText('  Assembleia   ficticia \r\n\r\n\n\n Pauta:\tobras \n\n', 5000)).toBe('Assembleia ficticia\n\nPauta: obras');
    expect(multilineText('Linha um Linha dois', 5000)).toBe('Linha um\nLinha dois');
    expect(multilineText(' \n \r\n ', 5000)).toBeNull();
    expect(multilineText('Texto​escondido', 5000)).toBeNull();
    expect(multilineText('abc\ndef', 6)).toBeNull();
    expect(multilineText('abc\nde', 6)).toBe('abc\nde');
  });
  it('chave de operação no rascunho, versão lida nas transições e abertura por POST com CSRF', async () => {
    let reply: () => Response = () => json(draft, 201, { 'Idempotency-Replayed': 'false' });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createAnnouncementsApi(new CondPulseApi({ fetch: fetcher }));
    const body = { title: draft.title, body: draft.body, audience: 'TENANT' as const, audienceIds: [] };
    const created = await client.createDraft(tenant, 'web:synthetic-key-0007', body);
    expect(created.replayed).toBe(false);
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/management/announcements`);
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('web:synthetic-key-0007');
    // Criação nova que não volta como rascunho é resultado incerto.
    reply = () => json({ ...draft, state: 'PUBLISHED', publishedAt: '2030-01-01T12:05:00Z', version: 1 }, 201, { 'Idempotency-Replayed': 'false' });
    await expect(client.createDraft(tenant, 'web:synthetic-key-0008', body)).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ ...draft, state: 'PUBLISHED', publishedAt: '2030-01-01T12:05:00Z', version: 1 });
    expect((await client.publish(tenant, draft.announcementId, 0)).state).toBe('PUBLISHED');
    expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1]?.body))).toEqual({ expectedVersion: 0 });
    await expect(client.publish(tenant, draft.announcementId, 3)).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ announcementId: draft.announcementId, title: draft.title, body: draft.body, publishedAt: '2030-01-01T12:05:00Z', openedAt: '2030-01-01T12:06:00Z' });
    expect((await client.open(tenant, draft.announcementId)).body).toBe(draft.body);
    const open = fetcher.mock.calls.at(-1)!;
    expect(String(open[0])).toBe(`/v1/tenants/${tenant}/announcements/${draft.announcementId}/open`);
    expect(open[1]?.method).toBe('POST');
    expect(new Headers(open[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
  });
});
