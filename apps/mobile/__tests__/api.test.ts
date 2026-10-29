import { createApi, identity, type Fetcher, type TokenSource } from '../src/api/client';
import { readConfig } from '../src/config';

const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const me = { accountId: ACCOUNT, memberships: [{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: '00000000-0000-4000-8000-000000000001',
  tenantName: 'CONDOMÍNIO FICTÍCIO', timeZone: 'America/Sao_Paulo', unitId: '20000000-0000-4000-8000-000000000001', role: 'RESIDENT' }] };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

function tokens(overrides: Partial<TokenSource> = {}) {
  const calls = { refresh: 0, expire: 0 };
  const source: TokenSource = {
    accessToken: async () => 'synthetic-access-1',
    refresh: async () => { calls.refresh += 1; return 'synthetic-access-2'; },
    expire: async () => { calls.expire += 1; },
    ...overrides,
  };
  return { source, calls };
}

describe('API no aplicativo', () => {
  it('envia o token como Bearer, sem cookie, e valida a identidade', async () => {
    const fetch = jest.fn<Promise<Response>, Parameters<Fetcher>>(async () => json(me));
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: tokens().source, fetch });
    expect((await api.me()).memberships[0]?.tenantName).toBe('CONDOMÍNIO FICTÍCIO');
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe('https://api.example.invalid/v1/me');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer synthetic-access-1');
    expect(init).toMatchObject({ redirect: 'error', cache: 'no-store' });
  });

  it('401 renova uma vez e repete; recusa de novo encerra a sessão', async () => {
    const fetch = jest.fn<Promise<Response>, Parameters<Fetcher>>()
      .mockResolvedValueOnce(json({ code: 'AUTHENTICATION_REQUIRED' }, 401)).mockResolvedValueOnce(json(me));
    const ok = tokens();
    await createApi({ baseUrl: 'https://api.example.invalid', tokens: ok.source, fetch }).me();
    expect(ok.calls.refresh).toBe(1);
    expect(new Headers(fetch.mock.calls[1]![1]?.headers).get('Authorization')).toBe('Bearer synthetic-access-2');
    const denied = tokens();
    const always401 = jest.fn<Promise<Response>, Parameters<Fetcher>>(async () => json({ code: 'AUTHENTICATION_REQUIRED' }, 401));
    await expect(createApi({ baseUrl: 'https://api.example.invalid', tokens: denied.source, fetch: always401 }).me())
      .rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' });
    expect(denied.calls.expire).toBe(1);
    expect(always401).toHaveBeenCalledTimes(2);
  });

  it('sem sessão liberada não chama a API', async () => {
    const fetch = jest.fn<Promise<Response>, Parameters<Fetcher>>();
    await expect(createApi({ baseUrl: 'https://api.example.invalid', tokens: tokens({ accessToken: async () => null }).source, fetch }).me())
      .rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('resposta inválida, erro conhecido e rede fora viram mensagens', async () => {
    expect(() => identity({ ...me, accountId: 'x' })).toThrow();
    expect(() => identity({ ...me, memberships: [{ ...me.memberships[0], role: 'ADMIN' }] })).toThrow();
    const source = tokens().source;
    await expect(createApi({ baseUrl: 'https://api.example.invalid', tokens: source, fetch: async () => json({ code: 'ACCOUNT_INACTIVE' }, 403) }).me())
      .rejects.toMatchObject({ code: 'ACCOUNT_INACTIVE', message: 'Seu acesso precisa ser validado pela gestão.' });
    await expect(createApi({ baseUrl: 'https://api.example.invalid', tokens: source, fetch: async () => { throw new TypeError('rede'); } }).me())
      .rejects.toMatchObject({ code: 'NETWORK_UNAVAILABLE' });
    await expect(createApi({ baseUrl: 'https://api.example.invalid', tokens: source, fetch: async () => new Response('<html>', { status: 200 }) }).me())
      .rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });

  it('configuração aceita só https, projeto Supabase e chave publicável', () => {
    const good = { EXPO_PUBLIC_SUPABASE_URL: 'https://abcdefghijklmnopqrst.supabase.co',
      EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_SYNTHETIC_TEST_ONLY_1234567890', EXPO_PUBLIC_API_URL: 'https://api.example.invalid/' };
    expect(readConfig(good)).toEqual({ supabaseUrl: good.EXPO_PUBLIC_SUPABASE_URL, publishableKey: good.EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY,
      apiUrl: 'https://api.example.invalid' });
    expect(readConfig({ ...good, EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY: 'sb_secret_SYNTHETIC_TEST_ONLY_1234567890' })).toBeNull();
    expect(readConfig({ ...good, EXPO_PUBLIC_API_URL: 'http://api.example.invalid' })).toBeNull();
    expect(readConfig({ ...good, EXPO_PUBLIC_SUPABASE_URL: 'https://evil.example.invalid' })).toBeNull();
    expect(readConfig({})).toBeNull();
    expect(() => createApi({ baseUrl: 'http://api.example.invalid', tokens: tokens().source })).toThrow('API_URL_INVALID');
  });
});
