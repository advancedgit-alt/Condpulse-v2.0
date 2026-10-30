// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createNotificationsApi, inboxNotification } from './notifications';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const notice = {
  notificationId: '93000000-0000-4000-8000-000000000001', unitId: unit, kind: 'PARCEL_RECEIVED', resourceId: '40000000-0000-4000-8000-000000000001',
  resourceState: 'RECEIVED', state: 'AVAILABLE', createdAt: '2030-01-01T12:00:00Z', readAt: null,
};

describe('contrato da caixa da unidade', () => {
  it('disponível na caixa não é leitura: leitura exige horário de abertura e vice-versa', () => {
    expect(inboxNotification(notice).state).toBe('AVAILABLE');
    expect(inboxNotification({ ...notice, state: 'READ_IN_APP', readAt: '2030-01-01T12:05:00Z' }).readAt).toBe('2030-01-01T12:05:00Z');
    expect(() => inboxNotification({ ...notice, state: 'READ_IN_APP' })).toThrow();
    expect(() => inboxNotification({ ...notice, readAt: '2030-01-01T12:05:00Z' })).toThrow();
    expect(() => inboxNotification({ ...notice, state: 'DELIVERED_BY_PROVIDER' })).toThrow();
    expect(() => inboxNotification({ ...notice, kind: 'PUSH' })).toThrow();
    // Situação do registro conforme o tipo; null quando não está mais visível.
    expect(inboxNotification({ ...notice, resourceState: null }).resourceState).toBeNull();
    expect(() => inboxNotification({ ...notice, resourceState: 'WAITING' })).toThrow();
    expect(inboxNotification({ ...notice, kind: 'ARRIVAL_WAITING', resourceState: 'EXPIRED' }).resourceState).toBe('EXPIRED');
    // Oferta da fila de espera (V030): situação da entrada da fila.
    expect(inboxNotification({ ...notice, kind: 'WAITLIST_OFFERED', resourceState: 'OFFERED' }).kind).toBe('WAITLIST_OFFERED');
    expect(inboxNotification({ ...notice, kind: 'WAITLIST_OFFERED', resourceState: 'ACCEPTED' }).resourceState).toBe('ACCEPTED');
    expect(() => inboxNotification({ ...notice, kind: 'WAITLIST_OFFERED', resourceState: 'RECEIVED' })).toThrow();
    // Pedido e mudança (V031).
    expect(inboxNotification({ ...notice, kind: 'REQUEST_UPDATED', resourceState: 'WAITING_RESIDENT' }).kind).toBe('REQUEST_UPDATED');
    expect(inboxNotification({ ...notice, kind: 'MOVE_DECIDED', resourceState: 'REJECTED' }).resourceState).toBe('REJECTED');
    expect(() => inboxNotification({ ...notice, kind: 'MOVE_DECIDED', resourceState: 'IN_PROGRESS' })).toThrow();
    expect(() => inboxNotification({ ...notice, kind: 'REQUEST_UPDATED', resourceState: 'APPROVED' })).toThrow();
  });
  it('lista sem cache de dados alheios e abertura só pelo POST, confirmando a leitura devolvida', async () => {
    let reply: () => Response = () => json({ items: [notice], unread: 1 });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf'
      ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createNotificationsApi(new CondPulseApi({ fetch: fetcher }));
    expect((await client.unit(tenant, unit)).unread).toBe(1);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/units/${unit}/notifications`);
    reply = () => json({ items: [notice], unread: 0 });
    await expect(client.unit(tenant, unit)).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
    reply = () => json({ ...notice, state: 'READ_IN_APP', readAt: '2030-01-01T12:05:00Z' });
    expect((await client.open(tenant, notice.notificationId)).state).toBe('READ_IN_APP');
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/notifications/${notice.notificationId}/open`);
    expect(call[1]?.method).toBe('POST');
    expect(new Headers(call[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    // Resposta de abertura sem leitura ou de outro aviso é resultado incerto, nunca sucesso.
    reply = () => json(notice);
    await expect(client.open(tenant, notice.notificationId)).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ ...notice, notificationId: '93000000-0000-4000-8000-000000000002', state: 'READ_IN_APP', readAt: '2030-01-01T12:05:00Z' });
    await expect(client.open(tenant, notice.notificationId)).rejects.toMatchObject({ uncertain: true });
  });
});
