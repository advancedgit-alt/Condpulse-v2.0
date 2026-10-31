// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { Membership, TenantModuleId } from '../../api/types';
import { activeResidentUnits, conciergeHome, condoAreas, condominiums, entryDecision, legacyTarget, placeModules, placeOf, principalPath, sectionEnabled } from './paths';

const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const U1 = '20000000-0000-4000-8000-000000000001';
const U2 = '20000000-0000-4000-8000-000000000002';
let next = 0;
function membership(tenantId: string, role: Membership['role'], unitId: string | null = null, tenantName = tenantId === A ? 'Bela Vista' : 'Alto Sintético'): Membership {
  next += 1;
  return { membershipId: `50000000-0000-4000-8000-${String(next).padStart(12, '0')}`, tenantId, tenantName, timeZone: 'America/Sao_Paulo', unitId, role };
}

describe('condomínios da conta', () => {
  it('agrupa por condomínio, ordena por nome e não repete papel nem unidade', () => {
    const condos = condominiums([membership(A, 'RESIDENT', U1), membership(A, 'MANAGER'), membership(A, 'OWNER', U1),
      membership(A, 'RESIDENT', U2), membership(B, 'CONCIERGE')]);
    expect(condos.map((condo) => condo.tenantName)).toEqual(['Alto Sintético', 'Bela Vista']);
    expect(condos[1]).toMatchObject({ roles: ['MANAGER', 'RESIDENT', 'OWNER'], unitIds: [U1, U2] });
    expect(condos[0]).toMatchObject({ roles: ['CONCIERGE'], unitIds: [] });
  });
  it('vínculo com unidade de papel não residencial não vira unidade do morador', () => {
    expect(condominiums([membership(A, 'SUPPORT', U1)])[0]!.unitIds).toEqual([]);
  });
});

describe('áreas por papel dentro do condomínio', () => {
  it('só aparecem as áreas dos papéis da conta', () => {
    const [resident] = condominiums([membership(A, 'RESIDENT', U1)]);
    expect(condoAreas(resident, false).map((area) => area.id)).toEqual(['inicio', 'morador']);
    const [staff] = condominiums([membership(A, 'MANAGER'), membership(A, 'CONCIERGE')]);
    // A gestão acompanha pedidos na própria área: zeladoria é só da equipe de zeladoria.
    expect(condoAreas(staff, false).map((area) => [area.id, area.disabled])).toEqual([['inicio', false], ['portaria', false], ['gestao', false]]);
  });
  it('zelador vê a zeladoria e nenhuma área de gestão ou portaria; é a área principal dele', () => {
    const [maintenance] = condominiums([membership(A, 'MAINTENANCE')]);
    expect(maintenance!.roles).toEqual(['MAINTENANCE']);
    expect(condoAreas(maintenance, false).map((area) => [area.id, area.disabled])).toEqual([['inicio', false], ['zeladoria', false]]);
    expect(principalPath(A, maintenance)).toBe(`/c/${A}/zeladoria/pedidos`);
  });
  it('implantação só com concessão técnica, inclusive sem vínculo no condomínio', () => {
    const [resident] = condominiums([membership(A, 'RESIDENT', U1)]);
    expect(condoAreas(resident, true).at(-1)?.id).toBe('implantacao');
    expect(condoAreas(undefined, true).map((area) => area.id)).toEqual(['implantacao']);
    expect(condoAreas(undefined, false)).toEqual([]);
  });
  it('papel principal: gestão, portaria, morador; várias unidades abrem o seletor interno', () => {
    expect(principalPath(A, condominiums([membership(A, 'RESIDENT', U1), membership(A, 'MANAGER')])[0])).toBe(`/c/${A}/gestao/pendencias`);
    expect(principalPath(A, condominiums([membership(A, 'RESIDENT', U1), membership(A, 'CONCIERGE')])[0])).toBe(`/c/${A}/portaria/encomendas`);
    // Morador passa sempre pelo seletor interno, que confere se a unidade continua ativa.
    expect(principalPath(A, condominiums([membership(A, 'RESIDENT', U1)])[0])).toBe(`/c/${A}/morador`);
    expect(principalPath(A, condominiums([membership(A, 'RESIDENT', U1), membership(A, 'OWNER', U2)])[0])).toBe(`/c/${A}/morador`);
    expect(principalPath(A, condominiums([membership(A, 'SUPPORT')])[0])).toBe(`/c/${A}/inicio`);
    expect(principalPath(A, undefined)).toBe(`/c/${A}/implantacao/roteiro`);
  });
});

describe('módulos desligados pela administradora', () => {
  const modules = (...enabled: TenantModuleId[]) => [{ tenantId: A, enabled }];
  it('sem informação do servidor nada é escondido', () => {
    const [condo] = condominiums([membership(A, 'CONCIERGE')]);
    expect(condo!.modules).toBeNull();
    expect(sectionEnabled(condo, 'portaria', 'encomendas')).toBe(true);
    expect(condoAreas(condo, true).every((area) => !area.disabled)).toBe(true);
  });
  it('módulos de outro condomínio não valem aqui; id comparado sem caixa', () => {
    const [condo] = condominiums([membership(A, 'RESIDENT', U1)], [{ tenantId: B, enabled: [] }]);
    expect(condo!.modules).toBeNull();
    const [same] = condominiums([membership(A, 'RESIDENT', U1)], [{ tenantId: A.toUpperCase(), enabled: ['parcels'] }]);
    expect(same!.modules).toEqual(['parcels']);
  });
  it('seções seguem a regra das rotas do servidor', () => {
    const [condo] = condominiums([membership(A, 'MANAGER'), membership(A, 'RESIDENT', U1)], modules('parcels', 'announcements'));
    expect(sectionEnabled(condo, 'gestao', 'avisos')).toBe(true);
    for (const section of ['reservas', 'areas', 'pedidos', 'mudancas', 'acesso', 'mapa-acesso', 'ocorrencias', 'financeiro'])
      expect(sectionEnabled(condo, 'gestao', section)).toBe(false);
    for (const section of ['pendencias', 'unidades', 'importacao', 'pessoas', 'auditoria']) expect(sectionEnabled(condo, 'gestao', section)).toBe(true);
    expect(sectionEnabled(condo, 'morador', 'encomendas')).toBe(true);
    expect(sectionEnabled(condo, 'morador', 'reservas')).toBe(false);
    expect(sectionEnabled(condo, 'morador', 'inicio')).toBe(true);
    expect(sectionEnabled(condo, 'morador', 'inquilinos')).toBe(true);
    expect(placeModules('zeladoria', 'qualquer')).toEqual(['requests']);
    expect(placeModules('implantacao', 'roteiro')).toEqual(['facial_access']);
    expect(placeModules('inicio', '')).toBeNull();
    expect(placeModules('', '')).toBeNull();
  });
  it('área com todos os módulos desligados fica desabilitada e deixa de ser a principal', () => {
    const [condo] = condominiums([membership(A, 'CONCIERGE'), membership(A, 'MAINTENANCE'), membership(A, 'RESIDENT', U1)], modules('visits'));
    expect(condoAreas(condo, true).map((area) => [area.id, area.disabled])).toEqual([['inicio', false], ['morador', false],
      ['portaria', false], ['zeladoria', true], ['implantacao', true]]);
    // Portaria abre na primeira seção liberada.
    expect(conciergeHome(condo)).toBe('visitas');
    expect(principalPath(A, condo)).toBe(`/c/${A}/portaria/visitas`);
    const [none] = condominiums([membership(A, 'CONCIERGE'), membership(A, 'MAINTENANCE'), membership(A, 'RESIDENT', U1)], modules());
    expect(condoAreas(none, false).find((area) => area.id === 'portaria')?.disabled).toBe(true);
    expect(principalPath(A, none)).toBe(`/c/${A}/morador`);
    const [maintenance] = condominiums([membership(A, 'MAINTENANCE')], modules());
    expect(principalPath(A, maintenance)).toBe(`/c/${A}/inicio`);
    const [zelador] = condominiums([membership(A, 'MAINTENANCE')], modules('requests'));
    expect(principalPath(A, zelador)).toBe(`/c/${A}/zeladoria/pedidos`);
  });
});

describe('unidades do morador', () => {
  it('unidade do vínculo que /units não devolve (inativa) não abre', () => {
    const units = [{ id: U2, label: '102', blockName: 'Bloco 1' }];
    expect(activeResidentUnits([U1], units)).toEqual([]);
    expect(activeResidentUnits([U1, U2], units)).toEqual([{ unitId: U2, label: 'Bloco 1 · 102' }]);
    expect(activeResidentUnits([U2], [{ id: U2, label: '102', blockName: null }])).toEqual([{ unitId: U2, label: '102' }]);
  });
});

describe('roteamento automático depois do login', () => {
  const one = condominiums([membership(A, 'RESIDENT', U1)]);
  const two = condominiums([membership(A, 'RESIDENT', U1), membership(B, 'CONCIERGE')]);
  it('espera a consulta da plataforma antes de decidir', () => {
    expect(entryDecision(one, 'pending', false)).toEqual({ kind: 'wait' });
  });
  it('só plataforma vai à central; plataforma com condomínio mostra o seletor', () => {
    expect(entryDecision([], 'yes', false)).toEqual({ kind: 'redirect', to: '/central/condominios' });
    expect(entryDecision(one, 'yes', false)).toEqual({ kind: 'select' });
  });
  it('um condomínio vai direto à área do papel principal; vários mostram o seletor; nenhum, o vazio', () => {
    expect(entryDecision(one, 'no', false)).toEqual({ kind: 'redirect', to: `/c/${A}/morador` });
    expect(entryDecision(two, 'no', false)).toEqual({ kind: 'select' });
    expect(entryDecision([], 'no', false)).toEqual({ kind: 'empty' });
  });
  it('convite aberto ou falha da consulta mantém a pessoa na entrada', () => {
    expect(entryDecision(one, 'no', true)).toEqual({ kind: 'select' });
    expect(entryDecision([], 'no', true)).toEqual({ kind: 'empty' });
    expect(entryDecision(one, 'unknown', false)).toEqual({ kind: 'select' });
    expect(entryDecision([], 'unknown', false)).toEqual({ kind: 'select' });
  });
});

describe('rotas antigas', () => {
  it('redirecionam preservando busca e fragmento', () => {
    expect(legacyTarget('/contextos', '?x=1', '#y')).toBe('/entrada?x=1#y');
    expect(legacyTarget(`/morador/${A}/${U1}/reservas`, '?dia=2030-01-01')).toBe(`/c/${A}/morador/${U1}/reservas?dia=2030-01-01`);
    expect(legacyTarget(`/morador/${A}/${U1}`)).toBe(`/c/${A}/morador/${U1}/inicio`);
    expect(legacyTarget(`/morador/${A}`)).toBe(`/c/${A}/morador`);
    expect(legacyTarget(`/portaria/${A}`)).toBe(`/c/${A}/portaria/encomendas`);
    expect(legacyTarget(`/portaria/${A}/visitas`)).toBe(`/c/${A}/portaria/visitas`);
    expect(legacyTarget(`/gestao/${A}`)).toBe(`/c/${A}/gestao`);
    expect(legacyTarget(`/gestao/${A}/avisos`)).toBe(`/c/${A}/gestao/avisos`);
    expect(legacyTarget(`/implantacao/${A}/canais`)).toBe(`/c/${A}/implantacao/canais`);
    expect(legacyTarget('/master')).toBe('/central');
    expect(legacyTarget('/master/fila')).toBe('/central/operacao');
    expect(legacyTarget('/master/concessoes', `?condominio=${A}`)).toBe(`/central/concessoes?condominio=${A}`);
    expect(legacyTarget(`/master/condominios/${A}`)).toBe(`/central/condominios/${A}`);
  });
  it('caminho desconhecido não é tratado como rota antiga', () => {
    expect(legacyTarget('/qualquer')).toBeNull();
    expect(legacyTarget(`/gestao/${A}/avisos/extra`)).toBeNull();
    expect(legacyTarget('/contextos/extra')).toBeNull();
  });
});

describe('lugar lido do endereço', () => {
  it('entrada, central e casca do condomínio', () => {
    expect(placeOf('/entrada')).toEqual({ world: 'entry' });
    expect(placeOf('/central')).toEqual({ world: 'central', section: 'condominios' });
    expect(placeOf('/central/operacao')).toEqual({ world: 'central', section: 'operacao' });
    expect(placeOf(`/c/${A}/morador/${U1}/reservas`)).toEqual({ world: 'condo', tenantId: A, area: 'morador', unitId: U1, section: 'reservas' });
    expect(placeOf(`/c/${A}/morador`)).toEqual({ world: 'condo', tenantId: A, area: 'morador', section: 'inicio' });
    expect(placeOf(`/c/${A}/gestao`)).toEqual({ world: 'condo', tenantId: A, area: 'gestao', section: 'pendencias' });
    expect(placeOf(`/c/${A}/portaria/visitas`)).toEqual({ world: 'condo', tenantId: A, area: 'portaria', section: 'visitas' });
    expect(placeOf(`/c/${A}/zeladoria`)).toEqual({ world: 'condo', tenantId: A, area: 'zeladoria', section: 'pedidos' });
    expect(placeOf(`/c/${A}/zeladoria/pedidos/${U1}`)).toEqual({ world: 'condo', tenantId: A, area: 'zeladoria', section: 'pedidos' });
    expect(placeOf(`/c/${A}`)).toEqual({ world: 'condo', tenantId: A, area: '', section: '' });
  });
});
