// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { tenantModuleChange, tenantModulePage } from './tenantModules';

const T = '00000000-0000-4000-8000-00000000000a';
const state = (overrides: Record<string, unknown> = {}) => ({ module: 'parcels', globallyEnabled: true, tenantEnabled: true, enabled: true, defaultState: true,
  version: 0, reason: null, updatedBy: null, updatedAt: null, ...overrides });

describe('módulos por condomínio', () => {
  it('lê a página na ordem do catálogo e confere o efeito real (flag global E condomínio)', () => {
    const page = tenantModulePage({ tenantId: T, modules: [state(), state({ module: 'reservations', tenantEnabled: false, enabled: false, defaultState: false, version: 2,
      reason: 'Inadimplência', updatedBy: '10000000-0000-4000-8000-000000000001', updatedAt: '2030-01-01T12:00:00Z' })] }, T);
    expect(page.modules.map((item) => item.module)).toEqual(['reservations', 'parcels']);
    expect(() => tenantModulePage({ tenantId: T, modules: [state({ enabled: false })] }, T)).toThrow();
    expect(() => tenantModulePage({ tenantId: T, modules: [state({ globallyEnabled: false })] }, T)).toThrow();
  });
  it('recusa padrão com versão, módulo desconhecido, repetição e condomínio trocado', () => {
    expect(() => tenantModulePage({ tenantId: T, modules: [state({ version: 1 })] }, T)).toThrow();
    expect(() => tenantModulePage({ tenantId: T, modules: [state({ module: 'cofre' })] }, T)).toThrow();
    expect(() => tenantModulePage({ tenantId: T, modules: [state(), state()] }, T)).toThrow();
    expect(() => tenantModulePage({ tenantId: '00000000-0000-4000-8000-00000000000b', modules: [] }, T)).toThrow();
  });
  it('resposta da alteração precisa ser do mesmo módulo', () => {
    expect(tenantModuleChange({ applied: false, module: state() }, 'parcels').applied).toBe(false);
    expect(() => tenantModuleChange({ applied: true, module: state() }, 'visits')).toThrow();
  });
});
