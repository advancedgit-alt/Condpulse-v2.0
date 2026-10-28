// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json' },
});

describe('disponibilidade do contrato de login', () => {
  it('confere GET same-origin sem guardar o token e busca outro CSRF para a mutação', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(); let tokens = 0;
    fetcher.mockImplementation(async path => path === '/auth/csrf'
      ? json({ headerName: 'X-CSRF-Token', token: `synthetic-${++tokens}` })
      : json({ accountId: '10000000-0000-4000-8000-000000000001', expiresAt: '2030-01-01T20:00:00Z' }));
    const client = new CondPulseApi({ fetch: fetcher });
    await expect(client.verifyLoginAvailable()).resolves.toBeUndefined();
    await client.login({ email: 'synthetic@example.invalid', password: 'SYNTHETIC_ONLY' });
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual(['/auth/csrf', '/auth/csrf', '/auth/login']);
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).has('X-CSRF-Token')).toBe(false);
    expect(new Headers(fetcher.mock.calls[2]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-2');
  });

  it.each([403, 404, 503])('recusa o probe HTTP %i sem refletir conteúdo nem repetir a chamada', async status => {
    const fetcher = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json({ detail: 'PRIVATE_SERVICE_DETAIL' }, status));
    const failure = await new CondPulseApi({ fetch: fetcher }).verifyLoginAvailable().catch((error: unknown) => error);
    expect(failure).toMatchObject({ status, uncertain: false });
    expect(String(failure)).not.toContain('PRIVATE_SERVICE_DETAIL');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it.each([
    { headerName: 'Authorization', token: 'synthetic' },
    { headerName: 'X-CSRF-Token' },
    { headerName: 'X-CSRF-Token', token: '' },
    { headerName: 'X-CSRF-Token', token: 'synthetic\ninvalid' },
    { headerName: 'X-CSRF-Token', token: 'x'.repeat(513) },
  ])('recusa resposta 200 que não cumpre o contrato CSRF (%#)', async body => {
    const fetcher = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(body));
    await expect(new CondPulseApi({ fetch: fetcher }).verifyLoginAvailable()).rejects.toMatchObject({
      status: 502, code: 'INVALID_API_RESPONSE', uncertain: false,
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('recusa HTML 200 e indisponibilidade de rede sem tratar como acesso anônimo', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(new Response('<h1>PRIVATE_PROXY_PAGE</h1>', { status: 200 }))
      .mockRejectedValueOnce(new Error('PRIVATE_CONNECTION_DETAIL'));
    const client = new CondPulseApi({ fetch: fetcher });
    await expect(client.verifyLoginAvailable()).rejects.toMatchObject({ status: 502, code: 'INVALID_API_RESPONSE' });
    const failure = await client.verifyLoginAvailable().catch((error: unknown) => error);
    expect(failure).toMatchObject({ status: 0, code: 'NETWORK_UNAVAILABLE' });
    expect(String(failure)).not.toMatch(/PRIVATE_/);
  });

  it('descarta uma resposta de disponibilidade de outra geração de sessão', async () => {
    let finish!: (response: Response) => void;
    const pending = new Promise<Response>(resolve => { finish = resolve; });
    const fetcher = vi.fn<typeof globalThis.fetch>().mockReturnValue(pending);
    const client = new CondPulseApi({ fetch: fetcher });
    const probe = client.verifyLoginAvailable();
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    client.clearSession(); finish(json({ headerName: 'X-CSRF-Token', token: 'synthetic-old' }));
    await expect(probe).rejects.toMatchObject({ name: 'AbortError' });
  });
});
