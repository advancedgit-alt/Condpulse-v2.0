// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createRequestsApi, maintenanceRequest, maintenanceRequestSummary, maintenanceTransitions, managedRequest, messagePage, requestMessage, unitRequest,
  unitRequestSummary } from './requests';

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

  describe('zeladoria', () => {
    const assigned = {
      requestId, unitId: unit, unitLabel: 'FICTÍCIA 101', category: 'MAINTENANCE', title: 'Vazamento fictício', description: 'Água no teto.',
      state: 'TRIAGED', dueOn: '2030-01-05', resolution: null, createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-01T12:10:00Z',
      resolvedAt: null, version: 1, messages: [{ ...staffMessage, authorName: 'GESTÃO FICTÍCIA', visibility: 'INTERNAL' }], messagesTruncated: true,
    };
    const { description: _d, resolution: _r, createdAt: _c, resolvedAt: _rv, messages: _m, messagesTruncated: _t, ...summary } = assigned;
    it('nunca aceita o nome de quem abriu, a equipe ou o responsável; lista sem texto e sem encerrados', () => {
      expect(maintenanceRequestSummary(summary).unitLabel).toBe('FICTÍCIA 101');
      expect(() => maintenanceRequestSummary({ ...summary, description: 'vazou' })).toThrow();
      expect(() => maintenanceRequestSummary({ ...summary, state: 'CLOSED' })).toThrow();
      expect(() => maintenanceRequestSummary({ ...summary, requesterName: 'PESSOA FICTÍCIA' })).toThrow();
      expect(maintenanceRequest(assigned).messages[0]!.visibility).toBe('INTERNAL');
      expect(() => maintenanceRequest({ ...assigned, requesterName: 'PESSOA FICTÍCIA' })).toThrow();
      expect(() => maintenanceRequest({ ...assigned, team: [] })).toThrow();
      expect(() => maintenanceRequest({ ...assigned, state: 'RESOLVED' })).toThrow();
      expect(maintenanceRequest({ ...assigned, state: 'RESOLVED', resolution: 'Vedação trocada.', resolvedAt: '2030-01-02T12:00:00Z' }).state).toBe('RESOLVED');
      expect(() => maintenanceRequest({ ...assigned, resolution: 'sem conclusão' })).toThrow();
    });
    it('andamentos da zeladoria: só a partir de triagem, execução ou aguardando a unidade', () => {
      expect(maintenanceTransitions('TRIAGED')).toEqual(['IN_PROGRESS', 'WAITING_RESIDENT', 'RESOLVED']);
      expect(maintenanceTransitions('IN_PROGRESS')).toEqual(['WAITING_RESIDENT', 'RESOLVED']);
      expect(maintenanceTransitions('WAITING_RESIDENT')).toEqual(['IN_PROGRESS', 'RESOLVED']);
      for (const state of ['OPEN', 'RESOLVED', 'CLOSED'] as const) expect(maintenanceTransitions(state)).toEqual([]);
    });
    it('rotas, chave de operação, páginas anteriores e andamento pela versão lida', async () => {
      let reply: () => Response = () => json({ requests: [summary], truncated: false });
      const fetcher = vi.fn<typeof globalThis.fetch>();
      fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
      const client = createRequestsApi(new CondPulseApi({ fetch: fetcher }));
      expect((await client.maintenance(tenant)).requests).toHaveLength(1);
      expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/maintenance/requests`);
      reply = () => json({ requests: [summary, summary], truncated: false });
      await expect(client.maintenance(tenant)).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
      reply = () => json(assigned);
      expect((await client.maintenanceRequest(tenant, requestId)).messagesTruncated).toBe(true);
      expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/maintenance/requests/${requestId}`);
      reply = () => json({ ...assigned, requestId: '96000000-0000-4000-8000-000000000002' });
      await expect(client.maintenanceRequest(tenant, requestId)).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
      const before = '97000000-0000-4000-8000-000000000009';
      reply = () => json({ messages: [{ ...staffMessage, authorName: 'GESTÃO FICTÍCIA', visibility: 'INTERNAL' }], truncated: true });
      expect((await client.maintenanceOlderMessages(tenant, requestId, before)).truncated).toBe(true);
      expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/maintenance/requests/${requestId}/messages?before=${before}`);
      reply = () => json({ messageId: '97000000-0000-4000-8000-000000000003', authorRole: 'STAFF', authorName: 'ZELADOR FICTÍCIO', mine: true,
        visibility: 'RESIDENT', body: 'Chego às 14h.', createdAt: '2030-01-01T12:06:00Z' }, 201, { 'Idempotency-Replayed': 'false' });
      expect((await client.maintenanceMessage(tenant, requestId, 'web:synthetic-key-0201', 'Chego às 14h.', 'RESIDENT')).replayed).toBe(false);
      const call = fetcher.mock.calls.at(-1)!;
      expect(String(call[0])).toBe(`/v1/tenants/${tenant}/maintenance/requests/${requestId}/messages`);
      expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('web:synthetic-key-0201');
      await expect(client.maintenanceMessage(tenant, requestId, 'web:synthetic-key-0202', 'Chego às 14h.', 'INTERNAL')).rejects.toMatchObject({ uncertain: true });
      reply = () => json({ ...assigned, state: 'IN_PROGRESS', version: 2 });
      const updated = await client.maintenanceUpdate(tenant, requestId, { state: 'IN_PROGRESS', resolution: null, expectedVersion: 1 });
      expect(updated.version).toBe(2);
      expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/maintenance/requests/${requestId}/state`);
      expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1]?.body))).toEqual({ state: 'IN_PROGRESS', resolution: null, expectedVersion: 1 });
      await expect(client.maintenanceUpdate(tenant, requestId, { state: 'WAITING_RESIDENT', resolution: null, expectedVersion: 1 }))
        .rejects.toMatchObject({ uncertain: true });
    });
  });
});
