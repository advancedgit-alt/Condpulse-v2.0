// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { platformTenant } from '../../api/platform';
import { billingText, originText, parseUnitLines, usageText, validTimeZone } from './tenants';
import { creationOutcome, emptyTenantDraft, tenantInput } from './TenantViews';

const base = {
  tenantId: '20000000-0000-4000-8000-000000000001', name: 'Condomínio Fictício', timeZone: 'America/Sao_Paulo', active: true, version: 0,
  createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-01T12:00:00Z', onboarding: null, origin: 'PLATFORM',
  usage: { activeUnits: 2, activeMemberships: 1, activeManagers: 1, lastActivityAt: '2030-01-02T15:30:00Z' },
  billing: { activeSubscription: true, openCharges: 2, overdueCharges: 1 },
};

describe('cadastro de condomínio pelo painel', () => {
  it('unidades: uma por linha, sem vazias, sem repetição e até 40 caracteres', () => {
    expect(parseUnitLines(' 101 \n\n102\r\nBloco  A   201')).toEqual({ units: ['101', '102', 'Bloco A 201'] });
    expect(parseUnitLines('A1\na1')).toEqual({ error: 'A unidade "a1" aparece mais de uma vez.' });
    expect(parseUnitLines('x'.repeat(41))).toEqual({ error: 'Linha 1: use de 1 a 40 caracteres visíveis por unidade.' });
    expect(parseUnitLines('  \n ')).toEqual({ error: 'Informe ao menos uma unidade, uma por linha.' });
    expect(parseUnitLines(Array.from({ length: 251 }, (_, i) => `U${i}`).join('\n'))).toEqual({ error: 'Informe até 250 unidades por cadastro.' });
  });
  it('fuso precisa ser IANA', () => {
    expect(validTimeZone('America/Sao_Paulo')).toBe(true);
    expect(validTimeZone('Brasil/Inexistente')).toBe(false);
    expect(validTimeZone('-03:00')).toBe(false);
  });
  it('pedido completo com área inicial reaproveita as regras da gestão local', () => {
    const draft = { ...emptyTenantDraft, name: '  Residencial  Sol ', units: '101\n102', managerName: 'Gestora', managerEmail: ' Gestora@Example.Invalid ',
      reason: 'Contrato assinado', withAmenity: true, amenity: { ...emptyTenantDraft.amenity, name: 'Salão', capacity: '40', fee: '150,00' } };
    const input = tenantInput(draft);
    expect(input).toMatchObject({ name: 'Residencial Sol', timeZone: 'America/Sao_Paulo', units: ['101', '102'],
      manager: { displayName: 'Gestora', email: 'gestora@example.invalid', validDays: 7 }, reason: 'Contrato assinado',
      amenity: { name: 'Salão', capacity: 40, feeCents: 15000, policy: { schemaVersion: 1 } } });
    expect(Object.keys((input as { amenity: object }).amenity).sort()).toEqual(['approvalRequired', 'bufferMinutes', 'capacity', 'feeCents', 'name', 'policy']);
    expect(tenantInput({ ...draft, withAmenity: true, amenity: { ...draft.amenity, capacity: '0' } })).toMatch(/^Área inicial:/);
    expect(tenantInput({ ...draft, managerEmail: 'sem-arroba' })).toBe('Informe um e-mail válido para o gestor.');
    expect(tenantInput({ ...draft, validDays: '15' })).toBe('A validade do convite deve ter entre 1 e 14 dias.');
    expect(tenantInput({ ...draft, reason: ' ' })).toMatch(/motivo/);
    expect(tenantInput({ ...draft, withAmenity: false })).toMatchObject({ amenity: null });
  });
  it('resumos de uso, assinatura e origem; API anterior sem uso não derruba a lista', () => {
    const tenant = platformTenant(base);
    expect(usageText(tenant, 'America/Sao_Paulo')).toBe('2 unidades ativas · 1 vínculo vigente · 1 gestor · última atividade em ' +
      usageText(tenant, 'America/Sao_Paulo').split('última atividade em ')[1]);
    expect(billingText(tenant)).toBe('Assinatura ativa · 2 cobranças em aberto (1 vencida)');
    expect(originText(tenant.origin)).toBe('Criado no painel master');
    const legacy = platformTenant({ ...base, origin: undefined, usage: undefined, billing: undefined });
    expect(legacy.usage).toBeNull(); expect(legacy.billing).toBeNull(); expect(legacy.origin).toBeNull();
    expect(usageText(legacy, 'America/Sao_Paulo')).toBe('Uso não informado por esta versão da API.');
    expect(billingText({ ...tenant, billing: { activeSubscription: false, openCharges: 0, overdueCharges: 0 } })).toBe('Sem assinatura ativa · nenhuma cobrança em aberto');
    expect(() => platformTenant({ ...base, usage: { ...base.usage, activeManagers: 5 } })).toThrow();
    expect(() => platformTenant({ ...base, billing: { activeSubscription: true, openCharges: 0, overdueCharges: 1 } })).toThrow();
  });
  it('resultado da criação: repetição da mesma operação não aparece como criação nem traz link', () => {
    const tenant = platformTenant(base);
    expect(creationOutcome({ tenant, applied: true, invitationId: '30000000-0000-4000-8000-000000000001', invitationToken: 'T'.repeat(43),
      invitationExpiresAt: '2030-01-08T12:00:00Z' }).tone).toBe('success');
    const replay = creationOutcome({ tenant, applied: false, invitationId: null, invitationToken: null, invitationExpiresAt: null });
    expect(replay.tone).toBe('info'); expect(replay.text).toMatch(/Nada foi gravado de novo/);
  });
});
