// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';

const tenantId = '10000000-0000-4000-8000-000000000001';
const unitId = '20000000-0000-4000-8000-000000000001';
const amenityId = '30000000-0000-4000-8000-000000000001';
const range = { from: '2030-01-01T03:00:00.000Z', until: '2030-01-02T03:00:00.000Z' };
const timeZone = 'America/Sao_Paulo';
const fixture = () => ({ tenantId, unitId, amenityId, timeZone,
  from: '2030-01-01T03:00:00Z', until: '2030-01-02T03:00:00Z', observedAt: '2029-12-30T12:00:00.123456Z',
  blockedIntervals: [{ occupiedFrom: '2030-01-01T15:00:00Z', occupiedUntil: '2030-01-01T16:15:00Z' }],
});
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status,
  headers: { 'Content-Type': 'application/json' } });
function clientFor(value: unknown, status = 200) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(value, status));
  const client = new CondPulseApi({ fetch });
  return { fetch, read: () => client.amenityAvailability(tenantId, unitId, amenityId, range, timeZone) };
}

describe('consulta anônima de ocupação da área', () => {
  it('consulta o contexto/range exatos com cookies same-origin e no-store, sem buscar CSRF', async () => {
    const { fetch, read } = clientFor(fixture());
    expect(await read()).toEqual(fixture());
    expect(fetch).toHaveBeenCalledTimes(1);
    const [path, options] = fetch.mock.calls[0]!;
    const url = new URL(String(path), 'https://example.invalid');
    expect(url.pathname).toBe(`/v1/tenants/${tenantId}/amenities/${amenityId}/availability`);
    expect(Object.fromEntries(url.searchParams)).toEqual({ unitId, ...range });
    expect(options).toMatchObject({ method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
    expect(new Headers(options?.headers).has('Authorization')).toBe(false);
  });

  it.each(['tenantId', 'unitId', 'amenityId'] as const)('rejeita resposta de outro %s inteira', async (field) => {
    await expect(clientFor({ ...fixture(), [field]: '90000000-0000-4000-8000-000000000009' }).read())
      .rejects.toMatchObject({ status: 502, code: 'INVALID_API_RESPONSE' });
  });

  it.each([
    { timeZone: 'UTC' }, { from: '2030-01-01T03:00:00.000001Z' }, { until: '2030-01-02T04:00:00Z' },
    { observedAt: '2030-02-30T12:00:00Z' }, { observedAt: 'not-an-instant' },
  ])('rejeita fuso, janela ou instante incompatível: %j', async (patch) => {
    await expect(clientFor({ ...fixture(), ...patch }).read()).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });

  it('não aceita metadados de moradores, reservas ou unidades alheias no contrato anônimo', async () => {
    for (const value of [
      { ...fixture(), reservations: [] },
      { ...fixture(), blockedIntervals: [{ ...fixture().blockedIntervals[0], reservationId: amenityId }] },
      { ...fixture(), blockedIntervals: [{ ...fixture().blockedIntervals[0], unitId }] },
    ]) await expect(clientFor(value).read()).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });

  it('rejeita intervalo fora da janela, invertido, não agregado ou resposta acima do limite', async () => {
    const interval = (occupiedFrom: string, occupiedUntil: string) => ({ occupiedFrom, occupiedUntil });
    const invalid = [
      [interval('2030-01-01T02:59:59Z', '2030-01-01T04:00:00Z')],
      [interval('2030-01-02T02:00:00Z', '2030-01-02T03:00:00.000001Z')],
      [interval('2030-01-01T16:00:00Z', '2030-01-01T15:00:00Z')],
      [interval('2030-01-01T15:00:00Z', '2030-01-01T16:00:00Z'), interval('2030-01-01T16:00:00Z', '2030-01-01T17:00:00Z')],
      [interval('2030-01-01T15:00:00Z', '2030-01-01T16:00:00Z'), interval('2030-01-01T15:30:00Z', '2030-01-01T17:00:00Z')],
      Array.from({ length: 1001 }, () => fixture().blockedIntervals[0]!),
    ];
    for (const blockedIntervals of invalid) await expect(clientFor({ ...fixture(), blockedIntervals }).read())
      .rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });

  it('preserva microssegundos e aceita separação real menor que um milissegundo', async () => {
    const snapshot = { ...fixture(), blockedIntervals: [
      { occupiedFrom: '2030-01-01T15:00:00Z', occupiedUntil: '2030-01-01T16:00:00.000001Z' },
      { occupiedFrom: '2030-01-01T16:00:00.000002Z', occupiedUntil: '2030-01-01T17:00:00Z' },
    ] };
    expect((await clientFor(snapshot).read()).blockedIntervals).toEqual(snapshot.blockedIntervals);
  });

  it('503 não vira uma lista vazia nem reflete detalhes do provedor', async () => {
    const error = await clientFor({ code: 'AMENITY_AVAILABILITY_UNAVAILABLE', detail: 'PRIVATE-DETAIL' }, 503)
      .read().catch((value: unknown) => value);
    expect(error).toMatchObject({ status: 503, code: 'AMENITY_AVAILABILITY_UNAVAILABLE', uncertain: false });
    expect(String(error)).not.toContain('PRIVATE-DETAIL');
  });

  it('descarta consulta em voo quando a sessão muda', async () => {
    let finish!: (response: Response) => void;
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const client = new CondPulseApi({ fetch });
    const pending = client.amenityAvailability(tenantId, unitId, amenityId, range, timeZone);
    await vi.waitFor(() => { expect(fetch).toHaveBeenCalledTimes(1); });
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    client.clearSession(); finish(json(fixture()));
    await rejected;
  });
});
