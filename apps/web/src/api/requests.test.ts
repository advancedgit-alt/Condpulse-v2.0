// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createRequestsApi, managedRequest, messagePage, requestMessage, unitRequest, unitRequestSummary } from './requests';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const requestId = '96000000-0000-4000-8000-000000000001';
const staffMessage = { messageId: '97000000-0000-4000-8000-000000000001', authorRole: 'STAFF', authorName: null, mine: false,
  visibility: 'RESIDENT', body: 'Equipe a caminho.', createdAt: '2030-01-01T12:05:00Z' };
const opened = {
  requestId, unitId: unit, category: 'MAINTENANCE', title: 'Vazamento fictício', description: 'Água no teto.', state: 'OPEN', assigned: false,
  dueOn: null, resolution: null, requesterName: 'PESSOA FICTÍCIA', openedByMe: true, createdAt: '2030-01-01T12:00:00Z',
  updatedAt: '2030-01-01T12:00:00Z', resolvedAt: null, closedAt: null, messages: [staffMessage], messagesTruncated: false,
};
const managed = {
  requestId, unitId: unit, unitLabel: 'FICTÍCIA 101', category: 'MAINTENANCE', title: 'Vazamento fictício', description: 'Água no teto.',
  state: 'TRIAGED', assigneeMembershipId: '98000000-0000-4000-8000-000000000001', assigneeName: 'PORTARIA FICTÍCIA', dueOn: '2030-01-05',
  resolution: null, requesterName: 'PESSOA FICTÍCIA', createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-01T12:10:00Z', resolvedAt: null,
  closedAt: null, version: 1, messages: [{ ...staffMessage, authorName: 'GESTÃO FICTÍCIA', visibility: 'INTERNAL', mine: true }],
  messagesTruncated: false, team: [{ membershipId: '98000000-0000-4000-8000-000000000001', name: 'PORTARIA FICTÍCIA', role: 'CONCIERGE' }],
};

describe('contrato dos pedidos', () => {
  it('a unidade nunca recebe comentário interno nem o nome da pessoa da equipe', () => {
    expect(requestMessage(staffMessage, false).authorName).toBeNull();
    expect(() => requestMessage({ ...staffMessage, authorName: 'GESTÃO FICTÍCIA' }, false)).toThrow();
    expect(() => requestMessage({ ...staffMessage, visibility: 'INTERNAL' }, false)).toThrow();
    expect(() => requestMessage({ ...staffMessage, authorRole: 'UNIT', visibility: 'INTERNAL', authorName: 'X' }, true)).toThrow();
    expect(requestMessage({ ...staffMessage, authorName: 'GESTÃO FICTÍCIA', visibility: 'INTERNAL' }, true).visibility).toBe('INTERNAL');
    expect(() => requestMessage({ ...staffMessage, authorRole: 'UNIT', authorName: null }, false)).toThrow();
  });
  it('andamento e horários coerentes; lista da unidade sem texto', () => {
    expect(unitRequest(opened).state).toBe('OPEN');
    expect(() => unitRequest({ ...opened, state: 'RESOLVED' })).toThrow();
    expect(unitRequest({ ...opened, state: 'RESOLVED', resolution: 'Vedação trocada.', resolvedAt: '2030-01-02T12:00:00Z' }).resolution).toBe('Vedação trocada.');
    expect(() => unitRequest({ ...opened, state: 'CLOSED' })).toThrow();
    expect(() => unitRequest({ ...opened, resolution: 'Resolução sem conclusão' })).toThrow();
    expect(() => unitRequest({ ...opened, dueOn: '2030-02-30' })).toThrow();
    const summary = { requestId, unitId: unit, category: 'NOISE', title: 'Barulho fictício', state: 'OPEN', assigned: false, dueOn: null,
      createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-01T12:00:00Z' };
    expect(unitRequestSummary(summary).category).toBe('NOISE');
    expect(() => unitRequestSummary({ ...summary, description: 'vazou' })).toThrow();
    expect(() => unitRequestSummary({ ...summary, category: 'PLUMBING' })).toThrow();
    expect(managedRequest(managed).team).toHaveLength(1);
    expect(() => managedRequest({ ...managed, assigneeMembershipId: null })).toThrow();
  });
  it('mensagens anteriores: em ordem de conversa, sem a referência e sem comentário interno para a unidade', async () => {
    const before = '97000000-0000-4000-8000-000000000009';
    const older = [{ ...staffMessage, createdAt: '2030-01-01T11:00:00Z' },
      { ...staffMessage, messageId: '97000000-0000-4000-8000-000000000002', createdAt: '2030-01-01T11:30:00Z' }];
    expect(messagePage({ messages: older, truncated: true }, false, before).messages).toHaveLength(2);
    expect(() => messagePage({ messages: [...older].reverse(), truncated: false }, false, before)).toThrow();
    expect(() => messagePage({ messages: [{ ...older[0], messageId: before }], truncated: false }, false, before)).toThrow();
    expect(() => messagePage({ messages: [{ ...older[0], visibility: 'INTERNAL' }], truncated: false }, false, before)).toThrow();
    expect(() => messagePage({ messages: older, truncated: 'no' }, false, before)).toThrow();
    expect(() => messagePage({ messages: Array.from({ length: 201 }, () => older[0]), truncated: true }, false, before)).toThrow();
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async () => json({ messages: older, truncated: false }));
    const client = createRequestsApi(new CondPulseApi({ fetch: fetcher }));
    expect((await client.olderMessages(tenant, requestId, before)).truncated).toBe(false);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/requests/${requestId}/messages?before=${before}`);
    fetcher.mockImplementation(async () => json({ messages: [{ ...older[0], authorName: 'GESTÃO FICTÍCIA', visibility: 'INTERNAL' }], truncated: false }));
    expect((await client.staffOlderMessages(tenant, requestId, before)).messages[0]!.visibility).toBe('INTERNAL');
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/management/requests/${requestId}/messages?before=${before}`);
  });
  it('chave de operação na abertura e na mensagem; atualização pela versão lida', async () => {
    let reply: () => Response = () => json(opened, 201, { 'Idempotency-Replayed': 'false' });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createRequestsApi(new CondPulseApi({ fetch: fetcher }));
    const body = { category: 'MAINTENANCE' as const, title: opened.title, description: opened.description };
    const created = await client.open(tenant, unit, 'web:synthetic-key-0101', body);
    expect(created.replayed).toBe(false);
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/units/${unit}/requests`);
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('web:synthetic-key-0101');
    // Pedido novo que não volta como enviado é resultado incerto.
    reply = () => json({ ...opened, state: 'TRIAGED' }, 201, { 'Idempotency-Replayed': 'false' });
    await expect(client.open(tenant, unit, 'web:synthetic-key-0102', body)).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ messageId: '97000000-0000-4000-8000-000000000002', authorRole: 'UNIT', authorName: 'PESSOA FICTÍCIA', mine: true,
      visibility: 'RESIDENT', body: 'Pode vir à tarde.', createdAt: '2030-01-01T12:06:00Z' }, 201, { 'Idempotency-Replayed': 'true' });
    expect((await client.unitMessage(tenant, requestId, 'web:synthetic-key-0103', 'Pode vir à tarde.')).replayed).toBe(true);
    await expect(client.unitMessage(tenant, requestId, 'web:synthetic-key-0104', 'Outro texto.')).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ ...managed, version: 2, state: 'IN_PROGRESS' });
    const updated = await client.update(tenant, requestId, { state: 'IN_PROGRESS', assigneeMembershipId: managed.assigneeMembershipId,
      dueOn: managed.dueOn, resolution: null, expectedVersion: 1 });
    expect(updated.version).toBe(2);
    await expect(client.update(tenant, requestId, { state: 'WAITING_RESIDENT', assigneeMembershipId: managed.assigneeMembershipId,
      dueOn: managed.dueOn, resolution: null, expectedVersion: 1 })).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ requests: [], truncated: false });
    await client.managed(tenant, 'ACTIVE');
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/management/requests?state=ACTIVE`);
  });
});
