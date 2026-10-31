// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const token = 'G'.repeat(43);
const accepted = { tenantId: '20000000-0000-4000-8000-000000000001', membershipId: '52000000-0000-4000-8000-000000000001', status: 'ACTIVE' };

describe('convite de primeiro gestor no cliente', () => {
  it('primeiro acesso usa a rota própria com CSRF e exige vínculo ativo na resposta', async () => {
    let reply = () => json({ ...accepted, sessionStarted: true, accountId: '10000000-0000-4000-8000-000000000001', expiresAt: '2030-01-01T20:00:00Z' }, 201);
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const api = new CondPulseApi({ fetch: fetcher });
    const result = await api.acceptInvitationWithLogin({ email: 'g@example.invalid', password: 'Senha-sintetica', token, kind: 'manager' });
    expect(result).toMatchObject({ status: 'ACTIVE', sessionStarted: true });
    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(String(post[0])).toBe('/auth/manager-invitation/accept');
    expect(new Headers(post[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    // Vínculo pendente na rota do gestor é resposta incoerente: resultado incerto, sem repetir.
    reply = () => json({ ...accepted, status: 'PENDING', sessionStarted: false, accountId: null, expiresAt: null }, 201);
    await expect(api.acceptInvitationWithLogin({ email: 'g@example.invalid', password: 'x', token, kind: 'manager' }))
      .rejects.toMatchObject({ code: 'INVITATION_FIRST_ACCESS_UNKNOWN', uncertain: true });
  });
  it('aceite com sessão já aberta usa /v1/manager-invitations/accept; o convite da gestão continua na rota antiga', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      return json(path === '/v1/manager-invitations/accept' ? accepted : { ...accepted, status: 'PENDING' }, 201);
    });
    const api = new CondPulseApi({ fetch: fetcher });
    expect(await api.acceptInvitation(token, undefined, 'manager')).toEqual(accepted);
    expect(await api.acceptInvitation(token)).toMatchObject({ status: 'PENDING' });
    const paths = fetcher.mock.calls.filter(([, init]) => init?.method === 'POST').map(([path]) => String(path));
    expect(paths).toEqual(['/v1/manager-invitations/accept', '/v1/invitations/accept']);
    await expect(api.acceptInvitation('curto', undefined, 'manager')).rejects.toMatchObject({ code: 'INVITATION_NOT_AVAILABLE' });
  });
});
