// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ApiError, CondPulseApi } from '../../api/client';
import { createPartnersApi } from '../../api/partners';
import { createSecurityLogApi } from '../../api/securityLog';
import type { PartnerContext } from '../../api/types';
import { FailureState, PartnerConsole, PartnerScopeProvider } from './PartnerPage';
import { applyRefusal, exportBody, failureKind, initialRefusalState, logAccessFailure, logFilter, logRequest, logRequestKey, memberBody, partnerKey, refusalOf,
  reachedTenant, scheduleBody, securityLogKey, stillReached } from './model';

const own = '10000000-0000-4000-8000-000000000001';
const other = '10000000-0000-4000-8000-000000000002';
const tenant = '20000000-0000-4000-8000-000000000001';
const memberId = '60000000-0000-4000-8000-000000000001';
const context = (role: PartnerContext['role'], panel: PartnerContext['panel'], capabilities: PartnerContext['capabilities'],
  tenants: PartnerContext['tenants'] = []): PartnerContext => ({ memberId, partnerId: '30000000-0000-4000-8000-000000000001',
  partnerName: 'Parceiro Sintético', role, panel, capabilities, tenants });

function render(value: PartnerContext, section = '') {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderToStaticMarkup(<QueryClientProvider client={queries}><MemoryRouter>
    <PartnerScopeProvider value={{ accountId: own, context: value, refuse: () => false, deniedTenant: () => null }}>
      <PartnerConsole section={section} /></PartnerScopeProvider></MemoryRouter></QueryClientProvider>);
}

describe('casca do parceiro renderizada', () => {
  it('painel sem backend mostra em breve com as seções previstas, sem dado', () => {
    const html = render(context('TECHNICIAN', 'tecnico', ['devices.commission']));
    expect(html).toContain('Painel Técnico: em breve');
    expect(html).toContain('Previsto para o seu papel: Equipamentos.');
  });
  it('gestor do parceiro abre a equipe e as abas das capacidades', () => {
    const html = render(context('PARTNER_ADMIN', 'gestao', ['partner.manage', 'partner.team.manage', 'security.log.read']));
    expect(html).toContain('Painel do Parceiro');
    for (const label of ['Equipe', 'Escala', 'Carteira', 'Log de Segurança']) expect(html).toContain(`>${label}</a>`);
    expect(html).toContain('Consultando a equipe');
  });
  it('sem a capacidade a aba não existe, mesmo com a seção no endereço', () => {
    const html = render(context('PARTNER_ADMIN', 'gestao', ['partner.team.manage']), 'log');
    expect(html).not.toContain('Log de Segurança');
    expect(html).not.toContain('>Carteira</a>');
  });
  it('líder de segurança sem condomínio alcançado vê o log sem consulta e sem exportação', () => {
    const html = render(context('SECURITY_LEAD', 'comando', ['security.command', 'security.log.export', 'security.log.read']));
    expect(html).toContain('Nenhum condomínio alcançado agora');
    expect(html).not.toContain('Exportar com manifesto');
  });
  it('log de um condomínio alcançado só consulta sob pedido; exportação só com a capacidade', () => {
    const tenants = [{ tenantId: tenant, tenantName: 'Condomínio Sintético', timeZone: 'America/Sao_Paulo' }];
    const lead = render(context('SECURITY_LEAD', 'comando', ['security.log.export', 'security.log.read'], tenants));
    expect(lead).toContain('Consulta sob pedido');
    expect(lead).toContain('Exportar com manifesto');
    const admin = render(context('PARTNER_ADMIN', 'gestao', ['security.log.read'], tenants), 'log');
    expect(admin).not.toContain('Exportar com manifesto');
  });
  it('falhas: flag desligada, indisponível e recusa têm mensagem própria', () => {
    const off = renderToStaticMarkup(<FailureState error={new ApiError(404, 'API_NOT_AVAILABLE', 'x')} retry={() => undefined} off="Flag desligada." subject="Log de Segurança" />);
    expect(off).toContain('Log de Segurança indisponível neste ambiente');
    expect(off).toContain('Flag desligada.');
    const down = renderToStaticMarkup(<FailureState error={new ApiError(503, 'HTTP_ERROR', 'Tente mais tarde.')} retry={() => undefined} off="" subject="Log de Segurança" />);
    expect(down).toContain('temporariamente indisponível');
    expect(down).toContain('Tentar novamente');
    const denied = renderToStaticMarkup(<FailureState error={new ApiError(404, 'TENANT_ACCESS_DENIED', 'Condomínio não disponível.')} retry={() => undefined} off="" subject="Log" />);
    expect(denied).toContain('Acesso não disponível');
  });
});

describe('regras locais dos painéis', () => {
  it('leitura de falhas', () => {
    expect(failureKind(new ApiError(404, 'API_NOT_AVAILABLE', ''))).toBe('off');
    expect(failureKind(new ApiError(403, 'MFA_REQUIRED', ''))).toBe('mfa');
    expect(failureKind(new ApiError(403, 'PARTNER_ACCESS_DENIED', ''))).toBe('denied');
    expect(failureKind(new ApiError(503, 'PARTNER_UNAVAILABLE', ''))).toBe('unavailable');
    expect(failureKind(new ApiError(0, 'NETWORK_UNAVAILABLE', ''))).toBe('unavailable');
    expect(failureKind(new Error('x'))).toBe('other');
  });
  it('novo papel: conta válida e de outra pessoa, papel, nome e motivo', () => {
    const draft = { accountId: other.toUpperCase(), role: 'REMOTE_OPERATOR' as const, displayName: ' Operador  Sintético ', reason: 'Novo turno' };
    expect(memberBody({ ...draft, accountId: 'x' }, own)).toMatch(/UUID/);
    expect(memberBody({ ...draft, accountId: own }, own)).toMatch(/próprio papel/);
    expect(memberBody({ ...draft, role: '' }, own)).toMatch(/papel/);
    expect(memberBody({ ...draft, reason: 'ab' }, own)).toMatch(/motivo/);
    expect(memberBody(draft, own)).toEqual({ accountId: other, role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', reason: 'Novo turno' });
  });
  it('escala: horário de parede no fuso do condomínio, fim depois do início e no máximo 31 dias', () => {
    const draft = { memberId, tenantId: tenant, startsAt: '2026-11-01T09:00', endsAt: '2026-11-01T21:00', reason: 'Turno diurno' };
    expect(scheduleBody(draft, 'America/Sao_Paulo')).toEqual({ memberId, tenantId: tenant, startsAt: '2026-11-01T12:00:00.000Z', endsAt: '2026-11-02T00:00:00.000Z', reason: 'Turno diurno' });
    expect(scheduleBody({ ...draft, startsAt: '' }, 'America/Sao_Paulo')).not.toHaveProperty('startsAt');
    expect(scheduleBody(draft, undefined)).toMatch(/condomínio/);
    expect(scheduleBody({ ...draft, endsAt: '2026-11-01T08:00' }, 'America/Sao_Paulo')).toMatch(/depois do início/);
    expect(scheduleBody({ ...draft, endsAt: '2026-12-05T09:00' }, 'America/Sao_Paulo')).toMatch(/31 dias/);
  });
  it('reenviar a consulta com os mesmos filtros gera chave nova (refaz a busca desde a primeira página)', () => {
    const first = logRequest(null, { severity: 'CRITICAL' });
    const again = logRequest(first, { severity: 'CRITICAL' });
    expect(again.run).toBe(first.run + 1);
    expect(logRequestKey(own, tenant, again)).not.toEqual(logRequestKey(own, tenant, first));
    expect(logRequestKey(own, tenant, first)).toEqual(logRequestKey(own, tenant, { ...first }));
    expect(logRequestKey(own, tenant.toUpperCase(), first)).toEqual(logRequestKey(own, tenant, first));
  });
  it('só consulta pedida derruba a tela do log; recusa do condomínio e MFA vencem falha transitória', () => {
    const mfa = new ApiError(403, 'MFA_REQUIRED', '');
    const denied = new ApiError(404, 'TENANT_ACCESS_DENIED', '');
    const down = new ApiError(503, 'SECURITY_LOG_DEPENDENCY_UNAVAILABLE', '');
    // Integridade com MFA_REQUIRED antigo em cache, não pedida nesta montagem: não reabre o segundo fator.
    expect(logAccessFailure({ pages: false, integrity: false }, null, mfa)).toBeNull();
    expect(logAccessFailure({ pages: false, integrity: true }, null, mfa)).toBe(mfa);
    // Página seguinte recusada depois de páginas carregadas: a recusa é devolvida para limpar a tela.
    expect(logAccessFailure({ pages: true, integrity: false }, denied, null)).toBe(denied);
    expect(logAccessFailure({ pages: true, integrity: true }, down, null)).toBeNull();
  });
  it('condomínio que sai do alcance deixa de ser o selecionado (log e escala)', () => {
    const second = '20000000-0000-4000-8000-000000000002';
    const both = [{ tenantId: tenant, tenantName: 'A', timeZone: 'America/Sao_Paulo' }, { tenantId: second, tenantName: 'B', timeZone: 'America/Sao_Paulo' }];
    expect(reachedTenant(both, second)?.tenantId).toBe(second);
    // /v1/me relido sem o condomínio escolhido: cai no que restou, nunca no que saiu.
    expect(reachedTenant(both.slice(0, 1), second)?.tenantId).toBe(tenant);
    expect(reachedTenant([], second)).toBeNull();
    expect(stillReached([tenant], second)).toBe('');
    expect(stillReached([tenant], tenant.toUpperCase())).toBe(tenant.toUpperCase());
  });
  it('log renderizado com a lista atual: selecionado fora do alcance não aparece', () => {
    const tenants = [{ tenantId: tenant, tenantName: 'Condomínio Restante', timeZone: 'America/Sao_Paulo' }];
    const html = render(context('SECURITY_LEAD', 'comando', ['security.log.read'], tenants));
    expect(html).toContain('Filtros do log de Condomínio Restante');
  });
  it('filtro do log e exportação', () => {
    expect(logFilter({ type: ' access_entry ', severity: 'CRITICAL' })).toEqual({ type: 'ACCESS_ENTRY', severity: 'CRITICAL' });
    expect(logFilter({ type: '1X', severity: '' })).toMatch(/Tipo/);
    expect(logFilter({ type: '', severity: '' })).toEqual({});
    expect(exportBody({ reason: 'curto', fromSeq: '', toSeq: '' })).toMatch(/10 a 500/);
    expect(exportBody({ reason: 'Pedido formal do condomínio', fromSeq: '5', toSeq: '2' })).toMatch(/menor/);
    expect(exportBody({ reason: 'Pedido formal do condomínio', fromSeq: '1', toSeq: '6000' })).toMatch(/5.000/);
    expect(exportBody({ reason: 'Pedido formal do condomínio', fromSeq: 'a', toSeq: '' })).toMatch(/inteiros/);
    expect(exportBody({ reason: 'Pedido formal do condomínio', fromSeq: '', toSeq: '' })).toEqual({ reason: 'Pedido formal do condomínio' });
  });
});

describe('tratador único de recusa: todos os pedidos das telas de parceiro', () => {
  const partnerId = '30000000-0000-4000-8000-000000000001';
  const scheduleId = '80000000-0000-4000-8000-000000000001';
  const scope = { accountId: own, partnerId, tenantIds: [tenant, '20000000-0000-4000-8000-000000000002'] };
  const respond = (status: number, code: string) => vi.fn<typeof globalThis.fetch>(async (input) => String(input) === '/auth/csrf'
    ? new Response(JSON.stringify({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    : new Response(JSON.stringify({ code, status }), { status, headers: { 'Content-Type': 'application/json' } }));
  // Cada pedido com o condomínio que a tela entrega ao tratador (as telas de equipe/escala/carteira não têm condomínio)
  // e as chaves de cache com os dados que a tela mostra.
  type Client = { partners: ReturnType<typeof createPartnersApi>; log: ReturnType<typeof createSecurityLogApi> };
  const requests: { name: string; tenantId?: string; keys: (readonly unknown[])[]; run(client: Client): Promise<unknown> }[] = [
    { name: 'equipe', keys: [partnerKey(own, partnerId, 'team')], run: (c) => c.partners.team(partnerId) },
    { name: 'adicionar papel', keys: [partnerKey(own, partnerId, 'team')],
      run: (c) => c.partners.addMember(partnerId, { accountId: other, role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', reason: 'Novo turno' }) },
    { name: 'revogar papel', keys: [partnerKey(own, partnerId, 'team')], run: (c) => c.partners.revokeMember(partnerId, memberId, 'Saiu da equipe') },
    { name: 'escala', keys: [partnerKey(own, partnerId, 'schedules')], run: (c) => c.partners.schedules(partnerId) },
    { name: 'criar escala', keys: [partnerKey(own, partnerId, 'schedules')],
      run: (c) => c.partners.createSchedule(partnerId, { memberId, tenantId: tenant, endsAt: '2026-11-01T21:00:00Z', reason: 'Turno' }) },
    { name: 'cancelar escala', keys: [partnerKey(own, partnerId, 'schedules')], run: (c) => c.partners.cancelSchedule(partnerId, scheduleId, 'Troca') },
    { name: 'carteira', keys: [partnerKey(own, partnerId, 'portfolio')], run: (c) => c.partners.portfolio(partnerId) },
    { name: 'log', tenantId: tenant, keys: [logRequestKey(own, tenant, logRequest(null, {})), securityLogKey(own, tenant, 'integrity')], run: (c) => c.log.page(tenant) },
    { name: 'integridade', tenantId: tenant, keys: [logRequestKey(own, tenant, logRequest(null, {})), securityLogKey(own, tenant, 'integrity')], run: (c) => c.log.integrity(tenant) },
    { name: 'exportação', tenantId: tenant, keys: [logRequestKey(own, tenant, logRequest(null, {})), securityLogKey(own, tenant, 'integrity')],
      run: (c) => c.log.export(tenant, { reason: 'Pedido formal do condomínio' }) },
  ];
  const refusals: [number, string][] = [[403, 'MFA_REQUIRED'], [403, 'PARTNER_ACCESS_DENIED'], [404, 'PARTNER_NOT_FOUND'], [403, 'PARTNER_FORBIDDEN'],
    [404, 'TENANT_ACCESS_DENIED'], [403, 'STAFF_ACCESS_DENIED']];
  const covered = (key: readonly unknown[], prefixes: (readonly unknown[])[]) => prefixes.some((prefix) => prefix.every((part, index) => part === key[index]));

  it('toda recusa vira estado de acesso negado (ou segundo fator) e apaga do cache os dados da tela', async () => {
    for (const request of requests) for (const [status, code] of refusals) {
      const client = new CondPulseApi({ fetch: respond(status, code) });
      const error = await request.run({ partners: createPartnersApi(client), log: createSecurityLogApi(client) }).then(() => null, (failure: unknown) => failure);
      const refusal = refusalOf(error, request.tenantId);
      expect(refusal, `${request.name} / ${code}`).not.toBeNull();
      const { state, forget } = applyRefusal(initialRefusalState(), refusal!, scope, error);
      for (const key of request.keys) expect(covered(key, forget), `${request.name} / ${code}: cache ${String(key)}`).toBe(true);
      if (code === 'MFA_REQUIRED') expect(state.mfa).toBe(true);
      else if (request.tenantId && !code.startsWith('PARTNER_')) {
        expect(state.deniedTenants.get(tenant)).toBe(error);
        expect(forget).toEqual([securityLogKey(own, tenant)]);
      } else expect(state.partnerDenied, `${request.name} / ${code}`).toBe(error);
    }
  });
  it('MFA e recusa do parceiro apagam o log de todos os condomínios; falha transitória ou de regra não é recusa', async () => {
    const all = applyRefusal(initialRefusalState(), { kind: 'partner' }, scope, null).forget;
    for (const tenantId of scope.tenantIds) expect(covered(securityLogKey(own, tenantId, 'integrity'), all)).toBe(true);
    for (const [status, code] of [[503, 'PARTNER_UNAVAILABLE'], [503, 'SECURITY_LOG_DEPENDENCY_UNAVAILABLE'], [403, 'PARTNER_SELF_CHANGE_DENIED'],
      [404, 'NOT_FOUND']] as [number, string][]) {
      const error = await createPartnersApi(new CondPulseApi({ fetch: respond(status, code) })).team(partnerId).then(() => null, (failure: unknown) => failure);
      expect(refusalOf(error, tenant), code).toBeNull();
    }
  });
});
