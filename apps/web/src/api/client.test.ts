// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi, newOperationKey } from './client';
import type { CreateReservation, CurrentIdentity, Reservation, Session } from './types';

const account = '10000000-0000-4000-8000-000000000001';
const tenant = '20000000-0000-4000-8000-000000000001';
const unit = '30000000-0000-4000-8000-000000000001';
const amenity = '40000000-0000-4000-8000-000000000001';
const session: Session = { accountId: account, expiresAt: '2030-01-01T20:00:00Z' };
const identity: CurrentIdentity = { accountId: account, memberships: [] };
const create: CreateReservation = { unitId: unit, amenityId: amenity, startsAt: '2030-01-01T15:00:00Z',
  endsAt: '2030-01-01T16:00:00Z', acceptedPolicyVersion: 1 };
const reservation: Reservation = { reservationId: '50000000-0000-4000-8000-000000000001', unitId: unit,
  amenityId: amenity, status: 'CONFIRMED', startsAt: create.startsAt, endsAt: create.endsAt,
  occupiedFrom: create.startsAt, occupiedUntil: '2030-01-01T16:15:00Z', acceptedPolicyVersion: 1,
  feeCents: 0, version: 0, decisionReason: null };
const key = 'web:synthetic-intention-000001';
const csrf = (token = 'synthetic-csrf') => json({ headerName: 'X-CSRF-Token', token });
const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const problem = (status: number, code: string) => json({ code, detail: 'PRIVATE_PROVIDER_BODY_MUST_NOT_BE_ECHOED' }, status);
const mockFetch = () => vi.fn<typeof globalThis.fetch>();
function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

const policy = { schemaVersion: 1, weekDays: [1, 2, 3, 4, 5, 6, 7], opensAt: '08:00', closesAt: '22:00',
  minimumLeadMinutes: 0, maximumAdvanceDays: 30, maximumDurationMinutes: 240, maximumActivePerUnit: 2, cancellationLeadMinutes: 60 };
const area = (bookingState: string, approvalRequired: boolean) => ({ amenityId: amenity, name: 'Salão sintético', capacity: 20,
  policyVersion: 3, feeCents: 5000, bufferMinutes: 30, approvalRequired, bookingState, policy });

describe('pedidos de área com aprovação', () => {
  it('preserva estado de pedido e motivo de decisão; rejeita estado de catálogo removido', async () => {
    const fetcher = mockFetch();
    fetcher.mockImplementation(async (input) => {
      const path = String(input);
      if (path.endsWith('/amenities')) return json([area('AVAILABLE_FOR_APPROVAL_REQUEST', true)]);
      return json({ reservations: [{ ...reservation, status: 'PENDING_APPROVAL' },
        { ...reservation, reservationId: '50000000-0000-4000-8000-000000000002', status: 'REJECTED', decisionReason: 'Manutenção da área' },
        { ...reservation, reservationId: '50000000-0000-4000-8000-000000000003', decisionReason: undefined }], truncated: false });
    });
    const client = new CondPulseApi({ fetch: fetcher });
    expect((await client.amenities(tenant))[0]?.bookingState).toBe('AVAILABLE_FOR_APPROVAL_REQUEST');
    const page = await client.reservations(tenant, unit, { from: '2030-01-01T00:00:00Z', until: '2030-01-31T00:00:00Z' });
    expect(page.reservations.map(item => [item.status, item.decisionReason])).toEqual([
      ['PENDING_APPROVAL', null], ['REJECTED', 'Manutenção da área'], ['CONFIRMED', null]]);
    fetcher.mockImplementation(async () => json([area('APPROVAL_UNAVAILABLE', true)]));
    await expect(client.amenities(tenant)).rejects.toBeInstanceOf(ApiError);
    fetcher.mockImplementation(async () => json({ reservations: [{ ...reservation, decisionReason: 42 }], truncated: false }));
    await expect(client.reservations(tenant, unit, { from: '2030-01-01T00:00:00Z', until: '2030-01-31T00:00:00Z' }))
      .rejects.toBeInstanceOf(ApiError);
  });
});

describe('cliente BFF same-origin', () => {
  it('envia cookies/CSRF, bloqueia redirects e busca CSRF novo após login, sem headerOrigin/Authorization', async () => {
    const fetcher = mockFetch(); let tokens = 0;
    fetcher.mockImplementation(async (input) => {
      if (input === '/auth/csrf') return csrf(`synthetic-csrf-${++tokens}`);
      if (input === '/auth/login') return json(session);
      return json(reservation, 201, { 'Idempotency-Replayed': 'false' });
    });
    const client = new CondPulseApi({ fetch: fetcher });
    await client.login({ email: 'resident@example.invalid', password: 'synthetic-only-password' });
    expect(await client.createReservation(tenant, create, key)).toEqual({ data: reservation, replayed: false });
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      '/auth/csrf', '/auth/login', '/auth/csrf', `/v1/tenants/${tenant}/reservations`,
    ]);
    for (const [path, init] of fetcher.mock.calls) {
      expect(String(path).startsWith('/')).toBe(true);
      expect(init?.credentials).toBe('same-origin'); expect(init?.cache).toBe('no-store');
      expect(init?.redirect).toBe('error');
      const headers = new Headers(init?.headers);
      expect(headers.has('Origin')).toBe(false); expect(headers.has('Authorization')).toBe(false);
    }
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf-1');
    expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf-2');
    expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get('Idempotency-Key')).toBe(key);
  });

  it('zeladoria e papel ainda desconhecido em /v1/me não derrubam os demais vínculos', async () => {
    const base = { tenantId: tenant, tenantName: 'CONDOMÍNIO FICTÍCIO', timeZone: 'America/Sao_Paulo' };
    const resident = { ...base, membershipId: '51000000-0000-4000-8000-000000000001', unitId: unit, role: 'RESIDENT' };
    const maintenance = { ...base, membershipId: '51000000-0000-4000-8000-000000000002', unitId: null, role: 'MAINTENANCE' };
    const future = { ...base, membershipId: '51000000-0000-4000-8000-000000000003', unitId: null, role: 'FUTURE_STAFF' };
    const fetcher = mockFetch();
    fetcher.mockImplementation(async () => json({ accountId: account, memberships: [resident, maintenance, future] }));
    const parsed = await new CondPulseApi({ fetch: fetcher }).currentIdentity();
    expect(parsed.memberships.map((item) => item.role)).toEqual(['RESIDENT', 'MAINTENANCE']);
    fetcher.mockImplementation(async () => json({ accountId: account, memberships: [{ ...resident, role: 42 }] }));
    await expect(new CondPulseApi({ fetch: fetcher }).currentIdentity()).rejects.toBeInstanceOf(ApiError);
  });

  it('faz refresh singleflight para consultas concorrentes e repete cada consulta uma vez', async () => {
    const fetcher = mockFetch(); const refreshed = deferred<Response>(); let reads = 0; let refreshes = 0;
    fetcher.mockImplementation(async (path) => {
      if (path === '/auth/csrf') return csrf();
      if (path === '/auth/refresh') { refreshes++; return refreshed.promise; }
      reads++; return reads <= 2 ? problem(401, 'SESSION_REFRESH_REQUIRED') : json(identity);
    });
    const client = new CondPulseApi({ fetch: fetcher });
    const first = client.currentIdentity(); const second = client.currentIdentity();
    await vi.waitFor(() => { expect(reads).toBe(2); expect(refreshes).toBe(1); });
    refreshed.resolve(json(session));
    expect(await Promise.all([first, second])).toEqual([identity, identity]);
    expect(refreshes).toBe(1); expect(reads).toBe(4);
  });

  it('repete comando apenas após rejeição conhecida do filtro, preservando body/key e renovando CSRF', async () => {
    const fetcher = mockFetch(); let tokens = 0; let commands = 0;
    fetcher.mockImplementation(async (path) => {
      if (path === '/auth/csrf') return csrf(`synthetic-csrf-${++tokens}`);
      if (path === '/auth/refresh') return json(session);
      commands++; return commands === 1 ? problem(401, 'SESSION_REFRESH_REQUIRED') : json(reservation, 201);
    });
    const client = new CondPulseApi({ fetch: fetcher });
    await client.createReservation(tenant, create, key);
    const attempts = fetcher.mock.calls.filter(([path]) => String(path).startsWith('/v1/'));
    expect(attempts).toHaveLength(2);
    expect(attempts[0]?.[1]?.body).toBe(JSON.stringify(create));
    expect(attempts[1]?.[1]?.body).toBe(attempts[0]?.[1]?.body);
    for (const [, init] of attempts) expect(new Headers(init?.headers).get('Idempotency-Key')).toBe(key);
    expect(new Headers(attempts[0]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf-1');
    expect(new Headers(attempts[1]?.[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf-3');
  });

  it('não entra em loop quando a consulta continua exigindo refresh', async () => {
    const fetcher = mockFetch(); let refreshes = 0; const expired = vi.fn();
    fetcher.mockImplementation(async (path) => {
      if (path === '/auth/csrf') return csrf();
      if (path === '/auth/refresh') { refreshes++; return json(session); }
      return problem(401, 'SESSION_REFRESH_REQUIRED');
    });
    const client = new CondPulseApi({ fetch: fetcher }); client.onSessionExpired(expired);
    await expect(client.currentIdentity()).rejects.toMatchObject({ status: 401, code: 'SESSION_REFRESH_REQUIRED' });
    expect(refreshes).toBe(1); expect(expired).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls.filter(([path]) => path === '/v1/me')).toHaveLength(2);
  });

  it('409 de refresh em andamento não repete Auth nem encerra a sessão local', async () => {
    const fetcher = mockFetch(); const expired = vi.fn();
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf() : problem(409, 'SESSION_REFRESH_IN_PROGRESS'));
    const client = new CondPulseApi({ fetch: fetcher }); client.onSessionExpired(expired);
    await expect(client.refresh()).rejects.toMatchObject({ status: 409, code: 'SESSION_REFRESH_IN_PROGRESS' });
    expect(fetcher).toHaveBeenCalledTimes(2); expect(expired).not.toHaveBeenCalled();
  });

  it('não repete login rejeitado e não reflete corpo do provedor', async () => {
    const fetcher = mockFetch(); const expired = vi.fn();
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf() : problem(401, 'AUTH_CREDENTIALS_REJECTED'));
    const client = new CondPulseApi({ fetch: fetcher }); client.onSessionExpired(expired);
    const error = await client.login({ email: 'resident@example.invalid', password: 'synthetic-only-password' }).catch((value: unknown) => value);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ code: 'AUTH_CREDENTIALS_REJECTED', uncertain: false });
    expect(String(error)).not.toContain('PRIVATE_PROVIDER_BODY');
    expect(String(error)).not.toContain('synthetic-only-password');
    expect(fetcher).toHaveBeenCalledTimes(2); expect(expired).not.toHaveBeenCalled();
  });

  it('comando com rede perdida conserva resultado incerto e não envia outra tentativa', async () => {
    const fetcher = mockFetch();
    fetcher.mockImplementation(async (path) => {
      if (path === '/auth/csrf') return csrf();
      throw new TypeError('PRIVATE_NETWORK_DETAILS');
    });
    const error = await new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, key).catch((value: unknown) => value);
    expect(error).toMatchObject({ code: 'COMMAND_OUTCOME_UNKNOWN', uncertain: true });
    expect(String(error)).not.toContain('PRIVATE_NETWORK_DETAILS');
    expect(fetcher.mock.calls.filter(([path]) => String(path).startsWith('/v1/'))).toHaveLength(1);
  });

  it('5xx com JSON inválido após comando continua incerto, sem retry automático', async () => {
    const fetcher = mockFetch();
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf()
      : new Response('{PRIVATE_UNFINISHED', { status: 503, headers: { 'Content-Type': 'application/json' } }));
    const error = await new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, key).catch((value: unknown) => value);
    expect(error).toMatchObject({ code: 'INVALID_API_RESPONSE', uncertain: true });
    expect(String(error)).not.toContain('PRIVATE_UNFINISHED');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('cancelar uma espera não cancela o refresh compartilhado nem a outra consulta', async () => {
    const fetcher = mockFetch(); const refreshed = deferred<Response>(); const abort = new AbortController(); let reads = 0;
    fetcher.mockImplementation(async (path) => {
      if (path === '/auth/csrf') return csrf();
      if (path === '/auth/refresh') return refreshed.promise;
      reads++; return reads <= 2 ? problem(401, 'SESSION_REFRESH_REQUIRED') : json(identity);
    });
    const client = new CondPulseApi({ fetch: fetcher });
    const first = client.currentIdentity(abort.signal); const second = client.currentIdentity();
    await vi.waitFor(() => expect(fetcher.mock.calls.some(([path]) => path === '/auth/refresh')).toBe(true));
    abort.abort(); const rejected = expect(first).rejects.toMatchObject({ name: 'AbortError' });
    refreshed.resolve(json(session));
    await rejected; expect(await second).toEqual(identity);
    expect(reads).toBe(3);
    const init = fetcher.mock.calls.find(([path]) => path === '/auth/refresh')?.[1];
    expect(init?.signal).toBeUndefined();
  });

  it('descarta consulta atrasada após limpeza de sessão', async () => {
    const fetcher = mockFetch(); const reply = deferred<Response>(); fetcher.mockReturnValue(reply.promise);
    const client = new CondPulseApi({ fetch: fetcher }); const pending = client.currentIdentity();
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    client.clearSession(); reply.resolve(json(identity));
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('aborto após envio do comando não é apresentado como operação cancelada', async () => {
    const fetcher = mockFetch(); const reply = deferred<Response>(); const abort = new AbortController();
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf() : reply.promise);
    const pending = new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, key, abort.signal);
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    abort.abort(); reply.resolve(json(reservation, 201));
    await expect(pending).rejects.toMatchObject({ code: 'COMMAND_OUTCOME_UNKNOWN', uncertain: true });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('troca de sessão durante leitura do body de comando também permanece incerta', async () => {
    const fetcher = mockFetch(); const body = deferred<unknown>(); const response = json(reservation, 201);
    const parse = vi.spyOn(response, 'json').mockImplementation(() => body.promise);
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf() : response);
    const client = new CondPulseApi({ fetch: fetcher }); const pending = client.createReservation(tenant, create, key);
    await vi.waitFor(() => expect(parse).toHaveBeenCalledTimes(1));
    client.clearSession(); body.resolve(reservation);
    await expect(pending).rejects.toMatchObject({ code: 'COMMAND_OUTCOME_UNKNOWN', uncertain: true });
  });

  it('signal já abortado não envia CSRF nem comando', async () => {
    const fetcher = mockFetch(); const abort = new AbortController(); abort.abort();
    await expect(new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, key, abort.signal))
      .rejects.toMatchObject({ name: 'AbortError' });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('pedido de refresh já abortado não inicia uma operação Auth independente', async () => {
    const fetcher = mockFetch(); const abort = new AbortController(); abort.abort();
    await expect(new CondPulseApi({ fetch: fetcher }).refresh(abort.signal)).rejects.toMatchObject({ name: 'AbortError' });
    await Promise.resolve(); expect(fetcher).not.toHaveBeenCalled();
  });

  it('HTML de sucesso não vira sessão falsa nem aparece na mensagem de erro', async () => {
    const fetcher = mockFetch();
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf()
      : new Response('<html>PRIVATE_PASSWORD</html>', { headers: { 'Content-Type': 'text/html' } }));
    const error = await new CondPulseApi({ fetch: fetcher }).login({ email: 'resident@example.invalid', password: 'synthetic' })
      .catch((value: unknown) => value);
    expect(error).toMatchObject({ code: 'INVALID_API_RESPONSE', uncertain: true });
    expect(String(error)).not.toContain('PRIVATE_PASSWORD'); expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('401 com JSON inválido ainda invalida sessão, sem exibir o conteúdo', async () => {
    const fetcher = mockFetch(); const expired = vi.fn();
    fetcher.mockResolvedValue(new Response('{PRIVATE_SESSION', { status: 401, headers: { 'Content-Type': 'application/json' } }));
    const client = new CondPulseApi({ fetch: fetcher }); client.onSessionExpired(expired);
    await expect(client.currentIdentity()).rejects.toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' });
    expect(expired).toHaveBeenCalledTimes(1); expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('identificador com host externo não pode alterar o destino', async () => {
    const fetcher = mockFetch();
    await expect(new CondPulseApi({ fetch: fetcher }).units('https://outside.example.invalid'))
      .rejects.toMatchObject({ code: 'INVALID_RESOURCE_ID' });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('CSRF inválido não chega ao header nem revela seu conteúdo na mensagem', async () => {
    const fetcher = mockFetch(); fetcher.mockResolvedValue(csrf('PRIVATE_CSRF\r\nheader'));
    const error = await new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, key)
      .catch((value: unknown) => value);
    expect(error).toMatchObject({ code: 'INVALID_API_RESPONSE', uncertain: false });
    expect(String(error)).not.toContain('PRIVATE_CSRF'); expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('chave inválida é rejeitada antes de enviar qualquer pedido', async () => {
    const fetcher = mockFetch();
    await expect(new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, 'bad\r\nkey'))
      .rejects.toMatchObject({ code: 'INVALID_IDEMPOTENCY_KEY', uncertain: false });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('resposta com número fora da precisão segura não confirma reserva', async () => {
    const fetcher = mockFetch();
    fetcher.mockImplementation(async (path) => path === '/auth/csrf' ? csrf()
      : json({ ...reservation, feeCents: Number.MAX_SAFE_INTEGER + 1 }, 201));
    await expect(new CondPulseApi({ fetch: fetcher }).createReservation(tenant, create, key))
      .rejects.toMatchObject({ code: 'INVALID_API_RESPONSE', uncertain: true });
  });

  it('gera chave por intenção sem reutilizar o mesmo identificador', () => {
    const first = newOperationKey(); const second = newOperationKey();
    expect(first).toMatch(/^web:[0-9a-f-]{36}$/); expect(second).not.toBe(first);
  });
});
