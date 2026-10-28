// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';

const accountId = '10000000-0000-4000-8000-000000000001';
const session = { accountId, expiresAt: '2030-01-01T20:00:00Z' };
const credentials = { email: 'synthetic@example.invalid', password: 'SYNTHETIC_ONLY' };
const json = (value: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json', ...headers },
});
const csrf = () => json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
const rate = () => json({ code: 'AUTH_RATE_LIMITED', detail: 'PRIVATE_ERROR_BODY' }, 429, { 'Retry-After': '60' });

describe('limitação BFF sem repetição automática', () => {
  it('conserva espera de login ao limpar sessão e não envia outra tentativa até o prazo', async () => {
    let now = 1_000;
    const fetcher = vi.fn<typeof globalThis.fetch>(); let logins = 0;
    fetcher.mockImplementation(async path => path === '/auth/csrf' ? csrf() : ++logins === 1 ? rate() : json(session));
    const client = new CondPulseApi({ fetch: fetcher, now: () => now });
    const error = await client.login(credentials).catch((value: unknown) => value);
    expect(error).toMatchObject({ status: 429, code: 'AUTH_RATE_LIMITED', uncertain: false, retryAfterSeconds: 60, retryUntil: 61_000 });
    expect(String(error)).not.toMatch(/PRIVATE_ERROR_BODY|SYNTHETIC_ONLY/);
    client.clearSession(); now += 30_000;
    await expect(client.login(credentials)).rejects.toMatchObject({ status: 429, retryAfterSeconds: 30, retryUntil: 61_000 });
    expect(logins).toBe(1); expect(fetcher).toHaveBeenCalledTimes(2);
    now = 61_000;
    await expect(client.login(credentials)).resolves.toEqual(session);
    expect(logins).toBe(2); expect(fetcher).toHaveBeenCalledTimes(4);
  });
  it('429 de refresh preserva sessão, bloqueia repetição Auth e permite logout', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(); const expired = vi.fn(); let refreshes = 0;
    fetcher.mockImplementation(async path => path === '/auth/csrf' ? csrf() : path === '/auth/logout'
      ? new Response(null, { status: 204 }) : (++refreshes, rate()));
    const client = new CondPulseApi({ fetch: fetcher, now: () => 1_000 }); client.onSessionExpired(expired);
    await expect(client.refresh()).rejects.toMatchObject({ status: 429, code: 'AUTH_RATE_LIMITED' });
    await expect(client.refresh()).rejects.toMatchObject({ status: 429 });
    expect(refreshes).toBe(1); expect(expired).not.toHaveBeenCalled();
    await expect(client.logout()).resolves.toBeUndefined();
    expect(fetcher.mock.calls.filter(([path]) => path === '/auth/logout')).toHaveLength(1);
  });
  it('503 do limitador antes do Auth preserva sessão e não repete a tentativa', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(); const expired = vi.fn();
    fetcher.mockImplementation(async path => path === '/auth/csrf' ? csrf() : json({ code: 'AUTH_RATE_LIMIT_UNAVAILABLE' }, 503));
    const client = new CondPulseApi({ fetch: fetcher }); client.onSessionExpired(expired);
    await expect(client.refresh()).rejects.toMatchObject({ status: 503, code: 'AUTH_RATE_LIMIT_UNAVAILABLE' });
    expect(fetcher).toHaveBeenCalledTimes(2); expect(expired).not.toHaveBeenCalled();
  });
  it('falha do provedor depois de refresh continua invalidando a sessão', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(); const expired = vi.fn();
    fetcher.mockImplementation(async path => path === '/auth/csrf' ? csrf() : json({ code: 'AUTH_PROVIDER_UNAVAILABLE' }, 503));
    const client = new CondPulseApi({ fetch: fetcher }); client.onSessionExpired(expired);
    await expect(client.refresh()).rejects.toMatchObject({ status: 503, code: 'AUTH_PROVIDER_UNAVAILABLE' });
    expect(expired).toHaveBeenCalledTimes(1);
    expect(expired).toHaveBeenCalledWith(expect.objectContaining({ status: 503, code: 'AUTH_PROVIDER_UNAVAILABLE' }));
  });
  it('resposta antiga não impõe espera a uma sessão nova', async () => {
    let finish!: (value: Response) => void;
    const pending = new Promise<Response>(resolve => { finish = resolve; });
    const fetcher = vi.fn<typeof globalThis.fetch>(); let logins = 0;
    fetcher.mockImplementation(async path => path === '/auth/csrf' ? csrf() : ++logins === 1 ? pending : json(session));
    const client = new CondPulseApi({ fetch: fetcher }); const old = client.login(credentials);
    await vi.waitFor(() => expect(logins).toBe(1)); client.clearSession(); finish(rate());
    await expect(old).rejects.toMatchObject({ code: 'COMMAND_OUTCOME_UNKNOWN' });
    await expect(client.login(credentials)).resolves.toEqual(session);
    expect(logins).toBe(2);
  });
  it('429 sem JSON usa espera segura sem devolver texto de proxy nem enviar retry', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async path => path === '/auth/csrf' ? csrf() : new Response('<h1>PRIVATE_PROXY_TOKEN</h1>', { status: 429 }));
    const error = await new CondPulseApi({ fetch: fetcher }).login(credentials).catch((value: unknown) => value);
    expect(error).toMatchObject({ status: 429, code: 'AUTH_RATE_LIMITED', retryAfterSeconds: 60, uncertain: false });
    expect(String(error)).not.toContain('PRIVATE_PROXY_TOKEN'); expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
