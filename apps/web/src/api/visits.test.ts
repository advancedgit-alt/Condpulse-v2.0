// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { arrival, createVisitsApi, visitPermit } from './visits';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const permit = {
  permitId: '70000000-0000-4000-8000-000000000001', unitId: unit, unitLabel: '101', blockName: null, visitorName: 'Visita Fictícia',
  documentLast4: '12AB', purpose: null, validFrom: '2030-01-01T12:00:00Z', validUntil: '2030-01-01T15:00:00Z', state: 'ACTIVE',
  createdByMe: true, revokedAt: null, revokeReason: null, version: 0, createdAt: '2030-01-01T11:00:00Z',
};
const waiting = {
  arrivalId: '80000000-0000-4000-8000-000000000001', unitId: unit, unitLabel: '101', blockName: 'Bloco 1', visitorName: 'Pessoa Fictícia',
  documentLast4: null, permitId: null, state: 'WAITING', expiresAt: '2030-01-01T12:05:00Z', decidedAt: null, decisionSource: null,
  decisionNote: null, decidedByMe: false, version: 0, createdAt: '2030-01-01T12:00:00Z',
};
const decided = { decidedAt: '2030-01-01T12:02:00Z', version: 1 };

describe('contratos das visitas', () => {
  it('convite: agendado, em vigor, revogado só com motivo e horário, e documento só pelo final', () => {
    expect(visitPermit(permit).state).toBe('ACTIVE');
    expect(visitPermit({ ...permit, state: 'SCHEDULED' }).state).toBe('SCHEDULED');
    const revoked = { ...permit, state: 'REVOKED', version: 1, revokedAt: '2030-01-01T12:30:00Z', revokeReason: 'Visita desmarcada' };
    expect(visitPermit(revoked).revokeReason).toBe('Visita desmarcada');
    expect(() => visitPermit({ ...revoked, revokeReason: null })).toThrow();
    expect(() => visitPermit({ ...revoked, version: 0 })).toThrow();
    expect(() => visitPermit({ ...permit, revokeReason: 'Sem revogação' })).toThrow();
    expect(() => visitPermit({ ...permit, state: 'USED' })).toThrow();
    expect(() => visitPermit({ ...permit, documentLast4: '123456789' })).toThrow();
    expect(() => visitPermit({ ...permit, validUntil: permit.validFrom })).toThrow();
    expect(() => visitPermit({ ...permit, visitorName: 'A' })).toThrow();
    expect(() => visitPermit({ ...permit, createdByMe: undefined })).toThrow();
  });
  it('chegada: decisão só com origem e horário; confirmação assistida sempre anotada; encerramento só pela portaria', () => {
    expect(arrival(waiting).state).toBe('WAITING');
    expect(arrival({ ...waiting, state: 'EXPIRED' }).state).toBe('EXPIRED');
    expect(arrival({ ...waiting, ...decided, state: 'APPROVED', decisionSource: 'APP', decidedByMe: true }).decidedByMe).toBe(true);
    expect(arrival({ ...waiting, ...decided, state: 'DENIED', decisionSource: 'INTERCOM', decisionNote: 'Moradora recusou' }).decisionNote).toBe('Moradora recusou');
    expect(arrival({ ...waiting, ...decided, state: 'CANCELLED', decisionSource: 'CONCIERGE', decisionNote: 'Foi embora' }).state).toBe('CANCELLED');
    expect(() => arrival({ ...waiting, ...decided, state: 'APPROVED', decisionSource: 'INTERCOM', decisionNote: null })).toThrow();
    expect(() => arrival({ ...waiting, ...decided, state: 'APPROVED', decisionSource: 'CONCIERGE' })).toThrow();
    expect(() => arrival({ ...waiting, ...decided, state: 'CANCELLED', decisionSource: 'APP' })).toThrow();
    expect(() => arrival({ ...waiting, state: 'APPROVED', decisionSource: 'APP' })).toThrow();
    expect(() => arrival({ ...waiting, decisionSource: 'APP', decidedAt: decided.decidedAt })).toThrow();
    expect(() => arrival({ ...waiting, decidedByMe: true })).toThrow();
    expect(() => arrival({ ...waiting, ...decided, state: 'APPROVED', decisionSource: 'APP', version: 0 })).toThrow();
    expect(() => arrival({ ...waiting, state: 'AUTO_APPROVED' })).toThrow();
  });
  it('convite e chegada levam a chave e o CSRF; decisões sem chave; resposta incoerente vira resultado incerto', async () => {
    let reply = () => json(permit, 201, { 'Idempotency-Replayed': 'true' });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input, init) => {
      if (String(input) === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (init?.method === 'POST') return reply();
      if (String(input).includes('/permits')) return json({ permits: [permit], truncated: false });
      return json({ arrivals: [waiting], truncated: false });
    });
    const client = createVisitsApi(new CondPulseApi({ fetch: fetcher }));
    const body = { visitorName: 'Visita Fictícia', documentLast4: '12AB', purpose: null, validFrom: permit.validFrom, validUntil: permit.validUntil };
    const created = await client.createPermit(tenant, unit, 'web:synthetic-key-0001', body);
    expect(created.replayed).toBe(true);
    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(String(post[0])).toBe(`/v1/tenants/${tenant}/units/${unit}/permits`);
    const headers = new Headers(post[1]?.headers);
    expect(headers.get('Idempotency-Key')).toBe('web:synthetic-key-0001');
    expect(headers.get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(post[1]?.body))).toEqual(body);
    reply = () => json(waiting, 201, { 'Idempotency-Replayed': 'false' });
    const registered = await client.register(tenant, 'web:synthetic-key-0002', { unitId: unit, visitorName: 'Pessoa Fictícia', documentLast4: null, permitId: null });
    expect(registered).toEqual({ arrival: arrival(waiting), replayed: false });
    expect(new Headers(fetcher.mock.calls.at(-1)![1]?.headers).get('Idempotency-Key')).toBe('web:synthetic-key-0002');
    // Decisão da unidade e confirmação assistida não usam chave: a mudança é condicional e a repetição recebe o estado final.
    reply = () => json({ ...waiting, ...decided, state: 'APPROVED', decisionSource: 'APP', decidedByMe: true });
    expect((await client.decide(tenant, waiting.arrivalId, 'APPROVE')).state).toBe('APPROVED');
    const decision = fetcher.mock.calls.at(-1)!;
    expect(String(decision[0])).toBe(`/v1/tenants/${tenant}/arrivals/${waiting.arrivalId}/decision`);
    expect(new Headers(decision[1]?.headers).get('Idempotency-Key')).toBeNull();
    expect(JSON.parse(String(decision[1]?.body))).toEqual({ decision: 'APPROVE' });
    // Resposta sem a origem da decisão: resultado incerto, nunca sucesso.
    reply = () => json({ ...waiting, ...decided, state: 'APPROVED', decisionSource: null });
    await expect(client.assisted(tenant, waiting.arrivalId, { decision: 'APPROVE', source: 'INTERCOM', note: 'Interfone' }))
      .rejects.toMatchObject({ uncertain: true });
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/arrivals/${waiting.arrivalId}/assisted-decision`);
    reply = () => json({ ...waiting, ...decided, state: 'CANCELLED', decisionSource: 'CONCIERGE', decisionNote: 'Foi embora' });
    expect((await client.cancel(tenant, waiting.arrivalId, 'Foi embora')).state).toBe('CANCELLED');
    expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1]?.body))).toEqual({ reason: 'Foi embora' });
    await client.expected(tenant, { unitId: unit, q: 'Ana 100%' });
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/concierge/permits?unitId=${unit}&q=Ana+100%25`);
    await client.queue(tenant, 'EXPIRED');
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/arrivals?status=EXPIRED`);
    await client.unitArrivals(tenant, unit);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/units/${unit}/arrivals`);
  });
});
