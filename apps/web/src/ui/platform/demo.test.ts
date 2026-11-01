// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { demoGeneration, demoProvisioned, demoState, platformSession } from '../../api/platform';
import { isDemoTenant } from '../shell/paths';
import { demoCountsText, demoErrorText, demoOutcome } from './Demo';
import { visiblePlatformSections } from './PlatformPage';

const account = '10000000-0000-4000-8000-000000000001';
const tenant = '20000000-0000-4000-8000-000000000001';
const generation = { generationId: '55000000-0000-4000-8000-000000000001', generation: 2, tenantId: tenant,
  tenantName: '[DEMO] Residencial Jardim Fictício (geração 2)', partnerId: '30000000-0000-4000-8000-000000000001',
  partnerName: '[DEMO] Vigilância Fictícia Aurora (geração 2)', status: 'ACTIVE', createdAt: '2026-10-31T12:00:00Z', createdBy: account,
  retiredAt: null, counts: { units: 32, blocks: 2, futureCount: 1 } };

describe('condomínio demo (CPX-046)', () => {
  it('aceita a geração só com o prefixo [DEMO] e estado coerente', () => {
    expect(demoGeneration(generation).counts.units).toBe(32);
    for (const bad of [{ ...generation, tenantName: 'Residencial real' }, { ...generation, partnerName: 'Parceiro real' },
      { ...generation, status: 'RETIRED' }, { ...generation, retiredAt: '2026-10-31T13:00:00Z' }, { ...generation, counts: { units: -1 } },
      { ...generation, generation: 0 }, { ...generation, tenantId: 'x' }]) expect(() => demoGeneration(bad)).toThrow();
    expect(demoState({ current: null, retired: [{ ...generation, status: 'RETIRED', retiredAt: '2026-10-31T13:00:00Z' }] }).retired).toHaveLength(1);
    expect(() => demoState({ current: { ...generation, status: 'RETIRED', retiredAt: '2026-10-31T13:00:00Z' }, retired: [] })).toThrow();
    expect(() => demoState({ current: null, retired: [generation] })).toThrow();
  });
  it('resultado da operação: repetição não aparece como criação e reset avisa da geração suspensa', () => {
    const replay = demoProvisioned({ generation, retiredTenantId: null, applied: false });
    expect(demoOutcome(replay).tone).toBe('info');
    const reset = demoProvisioned({ generation, retiredTenantId: '20000000-0000-4000-8000-000000000009', applied: true });
    expect(demoOutcome(reset).text).toMatch(/suspensa/);
    expect(demoOutcome({ ...reset, retiredTenantId: null }).text).toMatch(/Nenhuma conta real recebeu papel/);
    expect(() => demoProvisioned({ generation, retiredTenantId: 'x', applied: true })).toThrow();
    expect(demoCountsText(generation.counts)).toBe('2 torres · 32 unidades');
  });
  it('erros próprios do demo têm texto claro', () => {
    expect(demoErrorText(new ApiError(422, 'DEMO_RESET_REAL_TENANT', 'x'))).toMatch(/reais nunca são resetados/);
    expect(demoErrorText(new ApiError(409, 'DEMO_CHANGED', 'x'))).toMatch(/Atualize/);
  });
  it('seção da central só com platform.demo.manage; sessão aceita as capacidades de parceiro e do demo', () => {
    expect(visiblePlatformSections(['tenants.read']).some((item) => item.id === 'demo')).toBe(false);
    expect(visiblePlatformSections(['tenants.read', 'platform.demo.manage']).some((item) => item.id === 'demo')).toBe(true);
    expect(platformSession({ accountId: account, role: 'PLATFORM_ADMIN', capabilities: ['partners.read', 'partners.manage', 'platform.demo.manage'],
      mfaRequired: false }).capabilities).toContain('platform.demo.manage');
  });
  it('marca DEMO pelo /v1/me ou pelo prefixo fixo do nome', () => {
    expect(isDemoTenant({ demoTenantIds: [tenant] }, tenant.toUpperCase())).toBe(true);
    expect(isDemoTenant({ demoTenantIds: [] }, tenant, '[DEMO] Algo')).toBe(true);
    expect(isDemoTenant({ demoTenantIds: [] }, tenant, 'Residencial Real')).toBe(false);
    expect(isDemoTenant(null, undefined)).toBe(false);
  });
});
