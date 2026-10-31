// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ApiError } from '../../api/client';
import type { PartnerContext } from '../../api/types';
import { FailureState, PartnerConsole, PartnerScopeProvider } from './PartnerPage';
import { exportBody, failureKind, logFilter, logRequest, logRequestKey, memberBody, scheduleBody } from './model';

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
    <PartnerScopeProvider value={{ accountId: own, context: value, requireMfa: () => undefined, denied: () => undefined }}>
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
