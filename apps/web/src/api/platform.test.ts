// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createPlatformApi, platformSession, platformTenant, tenantChange } from './platform';
import { tenantChangeOutcome } from '../ui/platform/PlatformPage';

const account = '10000000-0000-4000-8000-000000000001';
const tenantId = '20000000-0000-4000-8000-000000000001';
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const tenant = {
  tenantId, name: 'Condomínio Fictício', timeZone: 'America/Sao_Paulo', active: true, version: 2,
  createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-02T12:00:00Z',
  onboarding: { operationId: '30000000-0000-4000-8000-000000000001', accounts: 3, units: 2, memberships: 3, amenities: 1,
    completedAt: '2030-01-01T12:00:00Z' },
};

describe('contratos do painel master', () => {
  it('sessão sem AAL2 não traz capacidades e capacidade desconhecida é recusada', () => {
    expect(platformSession({ accountId: account, role: 'PLATFORM_ADMIN', capabilities: [], mfaRequired: true }).mfaRequired).toBe(true);
    expect(() => platformSession({ accountId: account, role: 'PLATFORM_ADMIN', capabilities: ['tenants.read'], mfaRequired: true })).toThrow();
    expect(() => platformSession({ accountId: account, role: 'PLATFORM_ADMIN', capabilities: ['tenants.delete'], mfaRequired: false })).toThrow();
    expect(() => platformSession({ accountId: account, role: 'MANAGER', capabilities: [], mfaRequired: false })).toThrow();
    expect(() => platformSession({ accountId: account, role: 'PLATFORM_ADMIN', capabilities: ['tenants.read', 'tenants.read'], mfaRequired: false })).toThrow();
  });
  it('condomínio exige recibo explícito (ou nulo) e campos coerentes', () => {
    expect(platformTenant(tenant).onboarding?.units).toBe(2);
    expect(platformTenant({ ...tenant, onboarding: null }).onboarding).toBeNull();
    expect(() => platformTenant({ ...tenant, onboarding: undefined })).toThrow();
    expect(() => platformTenant({ ...tenant, name: '' })).toThrow();
    expect(() => platformTenant({ ...tenant, version: -1 })).toThrow();
    expect(() => tenantChange({ tenant, applied: 'yes' })).toThrow();
  });
  it('alteração usa PUT com CSRF, versão lida e motivo; auditoria pagina por cursor', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (init?.method === 'PUT') return json({ tenant: { ...tenant, active: false, version: 3 }, applied: true });
      if (path.startsWith('/v1/platform/audit-events')) return json({ events: [], nextCursor: null });
      return json({ tenants: [tenant], truncated: false });
    });
    const client = createPlatformApi(new CondPulseApi({ fetch: fetcher }));
    expect((await client.tenants()).tenants).toHaveLength(1);
    const change = await client.updateTenant(tenantId, { name: 'Condomínio Fictício', active: false, expectedVersion: 2, reason: 'Contrato encerrado' });
    expect(change).toMatchObject({ applied: true, tenant: { active: false, version: 3 } });
    const put = fetcher.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    expect(String(put[0])).toBe(`/v1/platform/tenants/${tenantId}`);
    expect(new Headers(put[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(put[1]?.body))).toEqual({ name: 'Condomínio Fictício', active: false, expectedVersion: 2, reason: 'Contrato encerrado' });
    await client.audit('abc_DEF-1');
    expect(fetcher.mock.calls.some(([path]) => String(path) === '/v1/platform/audit-events?limit=30&cursor=abc_DEF-1')).toBe(true);
    await expect(client.updateTenant('nao-e-uuid', { name: 'X', active: true, expectedVersion: 0, reason: 'Y' })).rejects.toBeTruthy();
  });
  it('mensagem do resultado segue o que o servidor fez, inclusive repetição natural', () => {
    const before = { name: 'Condomínio Fictício', active: true };
    expect(tenantChangeOutcome({ applied: false, tenant: platformTenant(tenant) }, before))
      .toEqual({ tone: 'info', text: 'O condomínio já estava neste estado. Nada foi alterado e o seu motivo não foi registrado.' });
    const suspended = tenantChangeOutcome({ applied: true, tenant: platformTenant({ ...tenant, active: false, version: 3 }) }, before);
    expect(suspended.tone).toBe('success'); expect(suspended.text).toMatch(/suspenso/); expect(suspended.text).toMatch(/Nenhum dado foi apagado/);
    const renamed = tenantChangeOutcome({ applied: true, tenant: platformTenant({ ...tenant, name: 'Novo Nome', version: 3 }) }, before);
    expect(renamed.text).toBe('Nome atualizado para "Novo Nome".');
    const reactivated = tenantChangeOutcome({ applied: true, tenant: platformTenant(tenant) }, { name: tenant.name, active: false });
    expect(reactivated.text).toMatch(/reativado/);
  });
});
