// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const token = 'T'.repeat(43);
const accepted = { tenantId: '20000000-0000-4000-8000-000000000001', membershipId: '52000000-0000-4000-8000-000000000001', status: 'PENDING' };

describe('primeiro acesso por convite no cliente', () => {
  it('token malformado para antes da rede; envio usa CSRF e não repete', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' })
      : json({ ...accepted, sessionStarted: true, accountId: '10000000-0000-4000-8000-000000000001', expiresAt: '2030-01-01T20:00:00Z' }, 201));
    const api = new CondPulseApi({ fetch: fetcher });
    await expect(api.acceptInvitationWithLogin({ email: 'a@example.invalid', password: 'x', token: 'curto' }))
      .rejects.toMatchObject({ code: 'INVITATION_NOT_AVAILABLE' });
    expect(fetcher).not.toHaveBeenCalled();
    const result = await api.acceptInvitationWithLogin({ email: 'a@example.invalid', password: 'Senha-sintetica', token });
    expect(result).toMatchObject({ status: 'PENDING', sessionStarted: true });
    const posts = fetcher.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(String(posts[0]![0])).toBe('/auth/invitation/accept');
    expect(new Headers(posts[0]![1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(posts[0]![1]?.body))).toEqual({ email: 'a@example.invalid', password: 'Senha-sintetica', token });
  });
  it('aceite sem sessão é válido; resposta incoerente ou perdida vira resultado incerto próprio', async () => {
    let reply: () => Promise<Response> = async () => json({ ...accepted, sessionStarted: false, accountId: null, expiresAt: null }, 201);
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const api = new CondPulseApi({ fetch: fetcher });
    expect(await api.acceptInvitationWithLogin({ email: 'a@example.invalid', password: 'x', token }))
      .toMatchObject({ sessionStarted: false, accountId: null, expiresAt: null });
    reply = async () => json({ ...accepted, sessionStarted: true, accountId: null, expiresAt: null }, 201);
    await expect(api.acceptInvitationWithLogin({ email: 'a@example.invalid', password: 'x', token }))
      .rejects.toMatchObject({ code: 'INVITATION_FIRST_ACCESS_UNKNOWN', uncertain: true });
    reply = async () => { throw new TypeError('rede sintética indisponível'); };
    await expect(api.acceptInvitationWithLogin({ email: 'a@example.invalid', password: 'x', token }))
      .rejects.toMatchObject({ code: 'INVITATION_FIRST_ACCESS_UNKNOWN' });
    reply = async () => json({ status: 404, code: 'INVITATION_NOT_AVAILABLE' }, 404);
    await expect(api.acceptInvitationWithLogin({ email: 'a@example.invalid', password: 'x', token }))
      .rejects.toMatchObject({ code: 'INVITATION_NOT_AVAILABLE', status: 404 });
  });
});
