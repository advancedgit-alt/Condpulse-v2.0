// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi, passwordProblem } from './client';

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const csrf = (token: string) => json({ headerName: 'X-CSRF-Token', token });

describe('recuperação de acesso no cliente', () => {
  it('aplica a mesma política local de senha do servidor', () => {
    expect(passwordProblem('Frase-sintetica-2030')).toBeNull();
    expect(passwordProblem('curta-11')).toMatch(/12 a 72/);
    expect(passwordProblem('aaaaaaaaaaaaaaaa')).not.toBeNull();
    expect(passwordProblem('ç'.repeat(40))).not.toBeNull();
    expect(passwordProblem('x'.repeat(73))).not.toBeNull();
    expect(passwordProblem('ação-sintética-1')).toBeNull();
  });

  it('pedido usa CSRF e só aceita a resposta neutra 202', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? csrf('synthetic-csrf')
      : json({ status: 'RECOVERY_REQUESTED' }, 202));
    const api = new CondPulseApi({ fetch: fetcher });
    await api.requestRecovery('pessoa@example.invalid');
    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(new Headers(post[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(post[1]?.body))).toEqual({ email: 'pessoa@example.invalid' });
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? csrf('synthetic-csrf') : json({ status: 'OK' }, 200));
    await expect(api.requestRecovery('pessoa@example.invalid')).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });

  it('link e senha inválidos param antes da rede e cada envio usa CSRF próprio', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(); let tokens = 0;
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return csrf(`synthetic-csrf-${++tokens}`);
      if (path === '/auth/recovery/verify') return json({ mfaRequired: true, expiresAt: '2030-01-01T12:10:00Z' });
      if (path === '/auth/recovery/complete') return json({ status: 'PASSWORD_CHANGED', otherSessionsEnded: false });
      return json({ status: 'RECOVERY_REQUESTED' }, 202);
    });
    const api = new CondPulseApi({ fetch: fetcher });
    await expect(api.verifyRecovery('curto')).rejects.toMatchObject({ code: 'RECOVERY_LINK_INVALID' });
    await expect(api.completeRecovery('curta', null)).rejects.toMatchObject({ code: 'PASSWORD_INVALID' });
    await expect(api.completeRecovery('Frase-sintetica-2030', '12ab56')).rejects.toMatchObject({ code: 'MFA_CODE_FORMAT' });
    expect(fetcher).not.toHaveBeenCalled();
    expect(await api.verifyRecovery('SYNTHETIC_link_hash_0123456789')).toEqual({ mfaRequired: true, expiresAt: '2030-01-01T12:10:00Z' });
    expect(await api.completeRecovery('Frase-sintetica-2030', '123456')).toEqual({ status: 'PASSWORD_CHANGED', otherSessionsEnded: false });
    await api.requestRecovery('pessoa@example.invalid');
    const posts = fetcher.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts.map(([path, init]) => [String(path), new Headers(init?.headers).get('X-CSRF-Token')])).toEqual([
      ['/auth/recovery/verify', 'synthetic-csrf-1'], ['/auth/recovery/complete', 'synthetic-csrf-2'], ['/auth/recovery', 'synthetic-csrf-3']]);
  });

  it('recusa da recuperação não encerra a sessão autenticada do aplicativo', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? csrf('synthetic-csrf')
      : json({ code: 'RECOVERY_SESSION_EXPIRED', status: 401 }, 401));
    const api = new CondPulseApi({ fetch: fetcher });
    const expired = vi.fn();
    api.onSessionExpired(expired);
    const failure = await api.completeRecovery('Frase-sintetica-2030', null).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ code: 'RECOVERY_SESSION_EXPIRED', status: 401 });
    expect(expired).not.toHaveBeenCalled();
    expect(fetcher.mock.calls.filter(([path]) => String(path) === '/auth/refresh')).toHaveLength(0);
  });

  it('resultado incerto da troca ou do link vira código terminal próprio da recuperação', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    let mode: 'invalid' | 'offline' = 'invalid';
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/auth/csrf') return csrf('synthetic-csrf');
      if (mode === 'offline') throw new TypeError('rede sintética indisponível');
      return json({ status: 'OK' });
    });
    const api = new CondPulseApi({ fetch: fetcher });
    const invalid = await api.completeRecovery('Frase-sintetica-2030', null).catch((error: unknown) => error);
    expect(invalid).toMatchObject({ code: 'RECOVERY_OUTCOME_UNKNOWN', uncertain: true });
    expect((invalid as ApiError).message).toMatch(/nova senha/);
    expect((invalid as ApiError).message).not.toMatch(/agenda/);
    await expect(api.verifyRecovery('SYNTHETIC_link_hash_0123456789')).rejects.toMatchObject({ code: 'RECOVERY_LINK_UNCERTAIN' });
    mode = 'offline';
    await expect(api.completeRecovery('Frase-sintetica-2030', null)).rejects.toMatchObject({ code: 'RECOVERY_OUTCOME_UNKNOWN' });
    await expect(api.verifyRecovery('SYNTHETIC_link_hash_0123456789')).rejects.toMatchObject({ code: 'RECOVERY_LINK_UNCERTAIN' });
  });
});
