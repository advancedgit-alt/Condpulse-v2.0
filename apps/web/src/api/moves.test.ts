// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createMovesApi, move, moveRules, normalizePlate } from './moves';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const moveId = '9a000000-0000-4000-8000-000000000001';
const rules = { rulesText: 'Use o elevador de serviço com proteção.', weekDays: [1, 2, 3, 4, 5], startTime: '08:00', endTime: '18:00',
  minNoticeHours: 24, maxDurationMinutes: 240, version: 2, updatedAt: '2030-01-01T12:00:00Z' };
const requested = { moveId, unitId: unit, unitLabel: 'FICTÍCIA 101', blockName: null, kind: 'MOVE_IN', startsAt: '2030-01-03T12:00:00Z',
  endsAt: '2030-01-03T15:00:00Z', providerName: 'Transportadora Fictícia', vehiclePlate: 'ABC1D23', items: 'Sofá e caixas', rulesVersion: 2,
  state: 'REQUESTED', decisionNote: null, decidedAt: null, createdByMe: true, createdAt: '2030-01-01T12:05:00Z', version: 0 };

describe('contrato das mudanças', () => {
  it('regras com dias ordenados, horário local válido e limites', () => {
    expect(moveRules(rules).weekDays).toEqual([1, 2, 3, 4, 5]);
    expect(() => moveRules({ ...rules, weekDays: [] })).toThrow();
    expect(() => moveRules({ ...rules, weekDays: [2, 1] })).toThrow();
    expect(() => moveRules({ ...rules, weekDays: [8] })).toThrow();
    expect(() => moveRules({ ...rules, startTime: '18:00', endTime: '08:00' })).toThrow();
    expect(() => moveRules({ ...rules, startTime: '8:00' })).toThrow();
    expect(() => moveRules({ ...rules, maxDurationMinutes: 10 })).toThrow();
    expect(() => moveRules({ ...rules, minNoticeHours: 721 })).toThrow();
  });
  it('decisão coerente com o estado; placa só no formato gravado', () => {
    expect(move(requested).state).toBe('REQUESTED');
    expect(() => move({ ...requested, decisionNote: 'Antes da decisão' })).toThrow();
    expect(() => move({ ...requested, state: 'APPROVED' })).toThrow();
    expect(move({ ...requested, state: 'APPROVED', decidedAt: '2030-01-01T13:00:00Z' }).decisionNote).toBeNull();
    expect(() => move({ ...requested, state: 'REJECTED', decidedAt: '2030-01-01T13:00:00Z' })).toThrow();
    expect(() => move({ ...requested, vehiclePlate: 'abc-1d23' })).toThrow();
    expect(() => move({ ...requested, endsAt: requested.startsAt })).toThrow();
    expect(() => move({ ...requested, kind: 'PARTY' })).toThrow();
    expect(normalizePlate(' abc-1d23 ')).toBe('ABC1D23');
    expect(normalizePlate('   ')).toBeNull();
    expect(normalizePlate('AB')).toBe('');
  });
  it('pedido com chave de operação; cancelamento, decisão e portaria conferidos', async () => {
    let reply: () => Response = () => json(requested, 201, { 'Idempotency-Replayed': 'false' });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createMovesApi(new CondPulseApi({ fetch: fetcher }));
    const body = { kind: 'MOVE_IN' as const, startsAt: requested.startsAt, endsAt: requested.endsAt, providerName: requested.providerName,
      vehiclePlate: 'ABC1D23', items: 'Sofá e caixas', acceptedRulesVersion: 2 };
    expect((await client.create(tenant, unit, 'web:synthetic-key-0201', body)).replayed).toBe(false);
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/units/${unit}/moves`);
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('web:synthetic-key-0201');
    // Pedido novo com outro período ou outra versão das regras é resultado incerto.
    await expect(client.create(tenant, unit, 'web:synthetic-key-0202', { ...body, acceptedRulesVersion: 3 })).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ ...requested, state: 'APPROVED', decidedAt: '2030-01-01T13:00:00Z', version: 1 }, 201, { 'Idempotency-Replayed': 'true' });
    expect((await client.create(tenant, unit, 'web:synthetic-key-0201', body)).move.state).toBe('APPROVED');
    reply = () => json({ ...requested, state: 'CANCELLED', version: 1 });
    expect((await client.cancel(tenant, moveId, 0)).state).toBe('CANCELLED');
    expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1]?.body))).toEqual({ note: null, expectedVersion: 0 });
    await expect(client.decide(tenant, moveId, 'APPROVE', null, 0)).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ ...requested, state: 'REJECTED', decisionNote: 'Sem elevador nesse dia.', decidedAt: '2030-01-01T13:00:00Z', version: 1 });
    expect((await client.decide(tenant, moveId, 'REJECT', 'Sem elevador nesse dia.', 0)).decisionNote).toBe('Sem elevador nesse dia.');
    reply = () => json({ moves: [requested], truncated: false });
    await expect(client.concierge(tenant, '2030-01-03T00:00:00Z', '2030-01-04T00:00:00Z')).rejects.toThrow();
    reply = () => json({ moves: [{ ...requested, state: 'APPROVED', decidedAt: '2030-01-01T13:00:00Z' }], truncated: false });
    expect((await client.concierge(tenant, '2030-01-03T00:00:00Z', '2030-01-04T00:00:00Z')).moves).toHaveLength(1);
    await expect(client.concierge(tenant, '2030-01-04T00:00:00Z', '2030-01-05T00:00:00Z')).rejects.toThrow();
    reply = () => json({ rules: null });
    expect(await client.rules(tenant)).toBeNull();
    reply = () => json({ ...rules, version: 3 });
    expect((await client.updateRules(tenant, { ...rules, expectedVersion: 2 })).version).toBe(3);
    await expect(client.updateRules(tenant, { ...rules, expectedVersion: 1 })).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ moves: [], truncated: false });
    await client.managed(tenant, 'REQUESTED');
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/management/moves?state=REQUESTED`);
  });
});
