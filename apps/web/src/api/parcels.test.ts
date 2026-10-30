// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createParcelsApi, delegation, parcel } from './parcels';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const received = {
  parcelId: '40000000-0000-4000-8000-000000000001', unitId: unit, unitLabel: '101', blockName: null, quantity: 1,
  description: '', storageLocation: 'Prateleira B', status: 'RECEIVED', receivedAt: '2030-01-01T12:00:00Z', version: 0, handoff: null,
  voidReason: null, voidedAt: null,
};
const member = '50000000-0000-4000-8000-000000000001';
const handoff = { recipientName: 'Pessoa Fictícia', deliveredAt: '2030-01-01T15:00:00Z', evidenceKind: 'ASSISTED' };

describe('contratos das encomendas', () => {
  it('retirada só aparece com registro e versão avançada; evidência desconhecida é recusada', () => {
    expect(parcel(received).handoff).toBeNull();
    expect(parcel({ ...received, status: 'DELIVERED', version: 1, handoff }).handoff?.recipientName).toBe('Pessoa Fictícia');
    expect(() => parcel({ ...received, status: 'DELIVERED', handoff: null })).toThrow();
    expect(() => parcel({ ...received, status: 'DELIVERED', version: 0, handoff })).toThrow();
    expect(() => parcel({ ...received, handoff })).toThrow();
    expect(() => parcel({ ...received, status: 'DELIVERED', version: 1, handoff: { ...handoff, evidenceKind: 'CODE' } })).toThrow();
    expect(() => parcel({ ...received, quantity: 0 })).toThrow();
    expect(() => parcel({ ...received, storageLocation: '' })).toThrow();
    expect(() => parcel({ ...received, handoff: undefined })).toThrow();
  });
  it('retirada por delegação é aceita; resumo da autorização é coerente e nunca traz código', () => {
    expect(parcel({ ...received, status: 'DELIVERED', version: 1, handoff: { ...handoff, evidenceKind: 'DELEGATION' } }).handoff?.evidenceKind).toBe('DELEGATION');
    const active = { delegationId: '41000000-0000-4000-8000-000000000001', parcelId: received.parcelId, unitId: unit, delegateName: 'Terceiro',
      documentLast4: null, validUntil: '2030-01-02T12:00:00Z', state: 'ACTIVE', attempts: 2, attemptsLeft: 3, createdByMe: true,
      createdAt: '2030-01-01T12:00:00Z', usedAt: null, closedAt: null, version: 2 };
    expect(delegation(active).attemptsLeft).toBe(3);
    expect(() => delegation({ ...active, attemptsLeft: 5 })).toThrow();
    expect(() => delegation({ ...active, state: 'USED' })).toThrow();
    expect(() => delegation({ ...active, documentLast4: '123456' })).toThrow();
    expect(delegation({ ...active, state: 'USED', usedAt: '2030-01-01T15:00:00Z', attemptsLeft: 0 }).state).toBe('USED');
  });
  it('anulada só com motivo, horário e versão avançada; motivo nunca aparece fora da anulação', () => {
    const voided = { ...received, status: 'VOIDED', version: 1, voidReason: 'Unidade errada', voidedAt: '2030-01-01T13:00:00Z' };
    expect(parcel(voided).voidReason).toBe('Unidade errada');
    expect(() => parcel({ ...voided, voidReason: null })).toThrow();
    expect(() => parcel({ ...voided, voidedAt: null })).toThrow();
    expect(() => parcel({ ...voided, version: 0 })).toThrow();
    expect(() => parcel({ ...voided, handoff })).toThrow();
    expect(() => parcel({ ...received, voidReason: 'Unidade errada' })).toThrow();
    expect(() => parcel({ ...received, voidReason: undefined })).toThrow();
  });
  it('recebimento leva a chave e o CSRF; repetição é sinalizada; resposta incoerente vira resultado incerto', async () => {
    let reply = () => json(received, 201, { 'Idempotency-Replayed': 'true' });
    let people = () => json([{ membershipId: member, displayName: 'Pessoa Fictícia', role: 'OWNER' }]);
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input, init) => {
      if (String(input) === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (init?.method === 'POST') return reply();
      if (String(input).endsWith('/recipients')) return people();
      return json({ parcels: [received], truncated: false });
    });
    const client = createParcelsApi(new CondPulseApi({ fetch: fetcher }));
    const body = { unitId: unit, quantity: 1, description: null, storageLocation: 'Prateleira B' };
    const result = await client.receive(tenant, 'web:synthetic-key-0001', body);
    expect(result.replayed).toBe(true);
    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(String(post[0])).toBe(`/v1/tenants/${tenant}/parcels`);
    const headers = new Headers(post[1]?.headers);
    expect(headers.get('Idempotency-Key')).toBe('web:synthetic-key-0001');
    expect(headers.get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(post[1]?.body))).toEqual(body);
    reply = () => json({ ...received, status: 'DELIVERED', version: 1, handoff: null }, 200);
    await expect(client.deliver(tenant, received.parcelId, 'web:synthetic-key-0002',
      { recipientMembershipId: member, identityChecked: true, expectedVersion: 0 })).rejects.toMatchObject({ uncertain: true });
    // Proprietário não aparece entre quem retira: resposta assim é recusada.
    await expect(client.recipients(tenant, received.parcelId)).rejects.toBeTruthy();
    people = () => json([{ membershipId: member, displayName: 'Pessoa Fictícia', role: 'RESIDENT' }]);
    expect(await client.recipients(tenant, received.parcelId)).toEqual([{ membershipId: member, displayName: 'Pessoa Fictícia', role: 'RESIDENT' }]);
    reply = () => json({ ...received, status: 'VOIDED', version: 1, voidReason: 'Duplicado', voidedAt: '2030-01-01T13:00:00Z' });
    expect((await client.voidParcel(tenant, received.parcelId, 'web:synthetic-key-0003', { reason: 'Duplicado', expectedVersion: 0 })).parcel.status).toBe('VOIDED');
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/parcels/${received.parcelId}/void`);
    await client.list(tenant, { status: 'RECEIVED', location: 'Prateleira 100%' });
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/parcels?status=RECEIVED&location=Prateleira+100%25`);
    await client.unitParcels(tenant, unit, null);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/units/${unit}/parcels`);
  });
});
