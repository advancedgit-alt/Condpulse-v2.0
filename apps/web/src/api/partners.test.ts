// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, CondPulseApi } from './client';
import { createPartnersApi, operatorSchedule, partnerContract, partnerMember } from './partners';
import { createSecurityLogApi, securityLogExport, securityLogIntegrity, securityLogPage } from './securityLog';

const partnerId = '30000000-0000-4000-8000-000000000001';
const other = '30000000-0000-4000-8000-000000000009';
const account = '10000000-0000-4000-8000-000000000002';
const tenant = '20000000-0000-4000-8000-000000000001';
const memberId = '60000000-0000-4000-8000-000000000001';
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const member = { id: memberId, partnerId, accountId: account, role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', status: 'ACTIVE', version: 0,
  createdAt: '2026-10-31T12:00:00Z', revokedAt: null, revokeReason: null };
const contract = { id: '70000000-0000-4000-8000-000000000001', partnerId, tenantId: tenant, tenantName: null, service: 'SECURITY_OPERATOR', status: 'ACTIVE',
  version: 0, startsAt: '2026-10-01T00:00:00Z', endsAt: null, endedAt: null, endReason: null };
const schedule = { id: '80000000-0000-4000-8000-000000000001', partnerId, tenantId: tenant, memberId, accountId: account, status: 'ACTIVE', version: 0,
  startsAt: '2026-11-01T09:00:00Z', endsAt: '2026-11-01T21:00:00Z', cancelledAt: null };
const h = (n: number) => n.toString(16).padStart(64, '0');
const event = (seq: number) => ({ eventId: `90000000-0000-4000-8000-${String(seq).padStart(12, '0')}`, seq, occurredAt: '2026-10-31T12:00:00Z',
  recordedAt: '2026-10-31T12:00:00Z', clockSkewMs: null, eventType: 'ACCESS_ENTRY', severity: 'INFO', source: 'CONCIERGE', partnerId: null, actorAccountId: null,
  actorRole: 'CONCIERGE', subjectType: null, subjectId: null, unitId: null, pointId: null, deviceId: null, reason: null, evidenceRef: null, correlationId: null,
  traceId: null, correctsId: null, details: {}, prevHash: h(seq - 1), hash: h(seq) });

describe('contratos do painel do parceiro', () => {
  it('membro, contrato e escala conferem parceiro e coerência de estado', () => {
    expect(partnerMember(member, partnerId).role).toBe('REMOTE_OPERATOR');
    expect(() => partnerMember(member, other)).toThrow(ApiError);
    expect(() => partnerMember({ ...member, status: 'REVOKED' }, partnerId)).toThrow(ApiError);
    expect(() => partnerMember({ ...member, role: 'SUPPORT' }, partnerId)).toThrow(ApiError);
    expect(partnerContract(contract, partnerId).tenantName).toBeNull();
    expect(() => partnerContract({ ...contract, status: 'ENDED' }, partnerId)).toThrow(ApiError);
    expect(operatorSchedule(schedule, partnerId).status).toBe('ACTIVE');
    expect(() => operatorSchedule({ ...schedule, endsAt: schedule.startsAt }, partnerId)).toThrow(ApiError);
  });

  it('rotas e corpos seguem o PartnerTeamController; 404 sem código conhecido é flag desligada', async () => {
    const calls: string[] = [];
    const fetcher = vi.fn<typeof globalThis.fetch>(async (input, init) => {
      const path = String(input); calls.push(`${init?.method ?? 'GET'} ${path}`);
      if (path === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (path.endsWith('/team') && init?.method === 'POST') return json(member, 201);
      if (path.endsWith('/team')) return json({ partnerId, members: [member] });
      if (path.endsWith('/portfolio')) return json({ partnerId, contracts: [contract] });
      if (path.endsWith('/revoke')) return json({ ...member, status: 'REVOKED', revokedAt: '2026-10-31T13:00:00Z', revokeReason: 'Saiu da equipe' });
      return json({ code: 'NOT_FOUND' }, 404);
    });
    const client = createPartnersApi(new CondPulseApi({ fetch: fetcher }));
    expect(await client.team(partnerId)).toHaveLength(1);
    expect(await client.portfolio(partnerId)).toHaveLength(1);
    expect((await client.addMember(partnerId, { accountId: account, role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', reason: 'Novo turno' })).id).toBe(memberId);
    expect((await client.revokeMember(partnerId, memberId, 'Saiu da equipe')).status).toBe('REVOKED');
    await expect(client.schedules(partnerId)).rejects.toMatchObject({ code: 'API_NOT_AVAILABLE', status: 404 });
    expect(calls).toContain(`POST /v1/partners/${partnerId}/team/${memberId}/revoke`);
  });

  it('404 do parceiro com código conhecido não é confundido com flag desligada', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>(async () => json({ code: 'PARTNER_NOT_FOUND', message: 'x' }, 404));
    await expect(createPartnersApi(new CondPulseApi({ fetch: fetcher })).team(partnerId)).rejects.toMatchObject({ code: 'PARTNER_NOT_FOUND' });
  });
});

describe('contratos do Log de Segurança', () => {
  it('página decrescente com cursor na menor sequência', () => {
    expect(securityLogPage({ events: [event(3), event(2)], nextBeforeSeq: 2 }).events.map((item) => item.seq)).toEqual([3, 2]);
    expect(() => securityLogPage({ events: [event(2), event(3)], nextBeforeSeq: null })).toThrow(ApiError);
    expect(() => securityLogPage({ events: [event(3), event(2)], nextBeforeSeq: 3 })).toThrow(ApiError);
  });
  it('integridade: íntegro sem problema, quebrado com problema', () => {
    const base = { intact: true, checkedEvents: 3, lastSeq: 3, lastHash: h(3), brokenSeq: null, problem: null, anchorsChecked: 1, checkedAt: '2026-10-31T12:00:00Z' };
    expect(securityLogIntegrity(base).intact).toBe(true);
    expect(() => securityLogIntegrity({ ...base, intact: false })).toThrow(ApiError);
    expect(securityLogIntegrity({ ...base, intact: false, problem: 'HASH_MISMATCH', brokenSeq: 2 }).brokenSeq).toBe(2);
  });
  it('exportação: manifesto confere condomínio, intervalo e encadeamento', () => {
    const manifest = { tenantId: tenant, fromSeq: 2, toSeq: 3, count: 2, firstPrevHash: h(1), firstHash: h(2), lastHash: h(3), exportedBy: account,
      exportedAt: '2026-10-31T12:00:00Z', reason: 'Pedido do condomínio', exportEventId: '90000000-0000-4000-8000-0000000000ff', canonicalization: 'cpsl1' };
    expect(securityLogExport({ manifest, events: [event(2), event(3)] }, tenant).manifest.count).toBe(2);
    expect(() => securityLogExport({ manifest, events: [event(2), { ...event(3), prevHash: h(9) }] }, tenant)).toThrow(ApiError);
    expect(() => securityLogExport({ manifest, events: [event(2), event(3)] }, '20000000-0000-4000-8000-000000000002')).toThrow(ApiError);
    expect(() => securityLogExport({ manifest: { ...manifest, count: 3 }, events: [event(2), event(3)] }, tenant)).toThrow(ApiError);
  });
  it('consulta envia filtros e cursor; 503 chega como indisponível', async () => {
    const paths: string[] = [];
    const fetcher = vi.fn<typeof globalThis.fetch>(async (input) => {
      const path = String(input); paths.push(path);
      if (path.includes('/integrity')) return json({ code: 'SECURITY_LOG_DEPENDENCY_UNAVAILABLE', message: 'x' }, 503);
      return json({ events: [event(5)], nextBeforeSeq: null });
    });
    const client = createSecurityLogApi(new CondPulseApi({ fetch: fetcher }));
    await client.page(tenant, { type: 'ACCESS_ENTRY', severity: 'CRITICAL', beforeSeq: 9 });
    expect(paths[0]).toBe(`/v1/tenants/${tenant}/security-log?type=ACCESS_ENTRY&severity=CRITICAL&beforeSeq=9&limit=50`);
    await expect(client.integrity(tenant)).rejects.toMatchObject({ status: 503, code: 'SECURITY_LOG_DEPENDENCY_UNAVAILABLE' });
  });
});
