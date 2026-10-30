// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createAccessCredentialsApi, credential, deviceEvent } from './accessCredentials';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const id = '45000000-0000-4000-8000-000000000001';
const connector = '43000000-0000-4000-8000-000000000001';
const holder = '51000000-0000-4000-8000-0000000000a1';
const device = (state: string, confirmedAt: string | null = null) => ({ deviceId: '44000000-0000-4000-8000-000000000001', deviceLabel: 'Entrada',
  direction: 'ENTRY', state, lastOutcome: confirmedAt ? 'APPLIED' : null, confirmedAt, lastReason: null, lastAttemptAt: null });
const active = { credentialId: id, connectorId: connector, membershipId: holder, unitId: '20000000-0000-4000-8000-000000000001', unitLabel: '101',
  holderName: 'Morador Fictício', kind: 'CARD', label: 'Cartão final 1234', desiredState: 'ACTIVE', desiredVersion: 1, validUntil: null, status: 'SYNCING',
  revokeReason: null, revokedAt: null, createdAt: '2030-01-01T12:00:00Z', version: 0, devices: [device('PENDING')] };
const revoked = { ...active, desiredState: 'REVOKED', desiredVersion: 2, status: 'REVOKING', revokeReason: 'Perdido', revokedAt: '2030-01-02T12:00:00Z', version: 1 };

describe('contrato das credenciais de acesso', () => {
  it('situação coerente com o estado desejado e as confirmações por equipamento', () => {
    expect(credential(active).status).toBe('SYNCING');
    expect(credential(revoked).status).toBe('REVOKING');
    expect(credential({ ...revoked, status: 'REVOKED', devices: [device('CONFIRMED', '2030-01-02T12:01:00Z')] }).status).toBe('REVOKED');
    // Revogada não pode aparecer ativa nem "revogada" sem confirmação de todos os equipamentos.
    expect(() => credential({ ...revoked, status: 'ACTIVE' })).toThrow();
    expect(() => credential({ ...revoked, status: 'REVOKED' })).toThrow();
    expect(() => credential({ ...active, status: 'ACTIVE' })).toThrow();
    expect(() => credential({ ...active, status: 'REVOKING' })).toThrow();
    expect(() => credential({ ...revoked, revokeReason: null })).toThrow();
    expect(() => credential({ ...revoked, desiredVersion: 1 })).toThrow();
    expect(() => credential({ ...active, desiredVersion: 2 })).toThrow();
    expect(() => credential({ ...active, devices: [device('CONFIRMED')] })).toThrow();
    expect(() => credential({ ...active, devices: [] })).toThrow();
    expect(credential({ ...active, status: 'NO_DEVICES', devices: [] }).status).toBe('NO_DEVICES');
    expect(() => credential({ ...active, kind: 'FACE' })).toThrow();
    // Vínculo vencido: situação própria, só com validade e sem revogação.
    expect(credential({ ...active, status: 'EXPIRED', validUntil: '2030-01-01T00:00:00Z', devices: [device('CONFIRMED', '2029-12-31T12:00:00Z')] }).status).toBe('EXPIRED');
    expect(() => credential({ ...active, status: 'EXPIRED' })).toThrow();
    expect(() => credential({ ...revoked, status: 'EXPIRED', validUntil: '2030-01-01T00:00:00Z' })).toThrow();
  });
  it('cadastro com chave; repetição devolve o estado atual; revogação confere a resposta', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    let reply = () => json(active, 201, { 'Idempotency-Replayed': 'false' });
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createAccessCredentialsApi(new CondPulseApi({ fetch: fetcher }));
    const body = { connectorId: connector, membershipId: holder, kind: 'CARD' as const, label: 'Cartão final 1234' };
    expect((await client.create(tenant, 'web:credential-key-0001', body)).replayed).toBe(false);
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/management/access-credentials`);
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('web:credential-key-0001');
    reply = () => json(revoked, 201, { 'Idempotency-Replayed': 'true' });
    expect((await client.create(tenant, 'web:credential-key-0001', body)).credential.desiredState).toBe('REVOKED');
    reply = () => json({ ...active, label: 'Outro' }, 201, { 'Idempotency-Replayed': 'false' });
    await expect(client.create(tenant, 'web:credential-key-0002', body)).rejects.toMatchObject({ uncertain: true });
    reply = () => json(revoked);
    expect((await client.revoke(tenant, id, { reason: 'Perdido', expectedVersion: 0 })).status).toBe('REVOKING');
    expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1]?.body))).toEqual({ reason: 'Perdido', expectedVersion: 0 });
    reply = () => json(active);
    await expect(client.revoke(tenant, id, { reason: 'Perdido', expectedVersion: 0 })).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ credentials: [active], truncated: false });
    await expect(client.pendingRevocations(tenant)).rejects.toThrow();
  });
  it('evento do equipamento: quarentena sem equipamento, saída registrada só para passagem de entrada', () => {
    const passage = { eventId: '46000000-0000-4000-8000-000000000001', kind: 'PASSAGE_DETECTED', direction: 'ENTRY', evidence: 'TURNSTILE_SENSOR',
      occurredAt: '2030-01-01T12:00:00Z', deviceLabel: 'Entrada', credentialLabel: 'Cartão', unitLabel: '101', state: 'ACCEPTED', quarantineReason: null, exitRecorded: false };
    expect(deviceEvent(passage).exitRecorded).toBe(false);
    expect(deviceEvent({ ...passage, direction: null, exitRecorded: null }).direction).toBeNull();
    expect(() => deviceEvent({ ...passage, direction: 'EXIT' })).toThrow();
    expect(() => deviceEvent({ ...passage, kind: 'AUTHORIZED' })).toThrow();
    expect(() => deviceEvent({ ...passage, state: 'QUARANTINED' })).toThrow();
    expect(() => deviceEvent({ ...passage, state: 'QUARANTINED', quarantineReason: 'UNKNOWN_DEVICE' })).toThrow();
    expect(deviceEvent({ ...passage, state: 'QUARANTINED', quarantineReason: 'UNKNOWN_DEVICE', deviceLabel: null, exitRecorded: null }).state).toBe('QUARANTINED');
    expect(() => deviceEvent({ ...passage, evidence: 'sensor livre' })).toThrow();
    expect(() => deviceEvent({ ...passage, kind: 'PRESENCE' })).toThrow();
  });
  it('a unidade só recebe eventos de acesso aceitos', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async () => json({ events: [{ eventId: '46000000-0000-4000-8000-000000000002', kind: 'CREDENTIAL_APPLIED', direction: null,
      evidence: null, occurredAt: '2030-01-01T12:00:00Z', deviceLabel: 'Entrada', credentialLabel: 'Cartão', unitLabel: '101', state: 'ACCEPTED',
      quarantineReason: null, exitRecorded: null }], truncated: false }));
    const client = createAccessCredentialsApi(new CondPulseApi({ fetch: fetcher }));
    await expect(client.unitEvents(tenant, '20000000-0000-4000-8000-000000000001')).rejects.toThrow();
    expect((await client.recentEvents(tenant)).events).toHaveLength(1);
  });
});
