// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ApiError, CondPulseApi } from '../../api/client';
import { createSecurityCommandApi, dossier, incidentDetail, portfolio, remoteOpenPage, type Portfolio } from '../../api/securityCommand';
import type { PartnerContext } from '../../api/types';
import { PartnerConsole, PartnerScopeProvider } from './PartnerPage';
import { closeBody, commandKey, dossierFileName, incidentBody, reviewBody } from './model';

const own = '10000000-0000-4000-8000-000000000001';
const partnerId = '30000000-0000-4000-8000-000000000001';
const tenant = '20000000-0000-4000-8000-000000000001';
const incidentId = '53000000-0000-4000-8000-000000000001';
const eventId = '90000000-0000-4000-8000-000000000001';
const h = (n: number) => n.toString(16).padStart(64, '0');
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const indicators = { devicesTotal: 2, devicesOffline: 1, devicesUnknown: 0, securityOccurrencesOpen: 0, criticalAlertsUnlinked: 0, incidentsOpen: 0,
  incidentsCriticalOpen: 0, remoteOpensPendingReview: 3, operatorsOnDuty: 1 };
const tenantRow = { tenantId: tenant, tenantName: 'Residencial Sintético', timeZone: 'America/Sao_Paulo', light: 'AMBER', reasons: ['DEVICES_OFFLINE'], indicators };
const body = { partnerId, generatedAt: '2026-10-31T12:00:00Z', criteria: 'cpx012-v1', unavailableIndicators: ['DOORS_HELD_OPEN', 'STAYS_OVERDUE'], truncated: false, tenants: [tenantRow] };
const event = (seq: number, eventType = 'INCIDENT_OPENED') => ({ eventId: `90000000-0000-4000-8000-${String(seq).padStart(12, '0')}`, seq, occurredAt: '2026-10-31T12:00:00Z',
  recordedAt: '2026-10-31T12:00:00Z', clockSkewMs: null, eventType, severity: 'CRITICAL', source: 'REMOTE_CENTRAL', partnerId, actorAccountId: own,
  actorRole: 'SECURITY_LEAD', subjectType: 'INCIDENT', subjectId: incidentId, unitId: null, pointId: null, deviceId: null, reason: null, evidenceRef: null,
  correlationId: incidentId, traceId: null, correctsId: null, details: {}, prevHash: h(seq - 1), hash: h(seq) });
const incident = { id: incidentId, tenantId: tenant, tenantName: 'Residencial Sintético', partnerId, title: 'Porta forçada', severity: 'CRITICAL', state: 'OPEN',
  openedBy: own, openedAt: '2026-10-31T12:00:00Z', assigneeMemberId: null, assignedAt: null, outcome: null, report: null, closedBy: null, closedAt: null, version: 0, linkCount: 1 };
const remote = { eventId, seq: 7, occurredAt: '2026-10-31T12:00:00Z', recordedAt: '2026-10-31T12:00:00Z', operatorAccountId: own, operatorRole: 'REMOTE_OPERATOR',
  reason: 'Autorizado pela unidade', unitId: null, pointId: null, deviceId: null, evidenceRef: null, review: null };

describe('contratos do Comando de Segurança', () => {
  it('semáforo: cor e motivos coerentes, do parceiro pedido', () => {
    const parsed: Portfolio = portfolio(body, partnerId);
    expect(parsed.tenants[0]!.light).toBe('AMBER');
    expect(() => portfolio(body, '30000000-0000-4000-8000-000000000009')).toThrow(ApiError);
    expect(() => portfolio({ ...body, tenants: [{ ...tenantRow, light: 'GREEN' }] }, partnerId)).toThrow(ApiError);
    expect(() => portfolio({ ...body, tenants: [{ ...tenantRow, light: 'AMBER', reasons: ['CRITICAL_ALERTS_UNLINKED'] }] }, partnerId)).toThrow(ApiError);
    expect(() => portfolio({ ...body, tenants: [{ ...tenantRow, indicators: { ...indicators, devicesOffline: -1 } }] }, partnerId)).toThrow(ApiError);
    expect(portfolio({ ...body, tenants: [{ ...tenantRow, light: 'GREEN', reasons: [] }] }, partnerId).tenants[0]!.light).toBe('GREEN');
  });
  it('incidente: encerrado tem desfecho e relatório; linha do tempo em ordem de seq', () => {
    expect(incidentDetail({ incident, links: [], timeline: [event(1, 'FORCED_OPEN'), event(2)] }, tenant).timeline).toHaveLength(2);
    expect(() => incidentDetail({ incident, links: [], timeline: [event(2), event(1)] }, tenant)).toThrow(ApiError);
    expect(() => incidentDetail({ incident: { ...incident, state: 'CLOSED' }, links: [], timeline: [] }, tenant)).toThrow(ApiError);
    expect(() => incidentDetail({ incident, links: [], timeline: [] }, '20000000-0000-4000-8000-000000000009')).toThrow(ApiError);
  });
  it('dossiê: manifesto do incidente pedido, contagem e intervalo iguais aos eventos', () => {
    const manifest = { tenantId: tenant, incidentId, count: 2, firstSeq: 1, lastSeq: 2, eventsDigest: h(9), canonicalization: 'cpdx1', exportedBy: own,
      exportedAt: '2026-10-31T12:00:00Z', reason: 'Pedido formal', exportEventId: '91000000-0000-4000-8000-000000000001' };
    const value = { manifest, incident, links: [], events: [event(1), event(2)] };
    expect(dossier(value, tenant, incidentId).raw).toBe(value);
    expect(() => dossier({ ...value, manifest: { ...manifest, count: 3 } }, tenant, incidentId)).toThrow(ApiError);
    expect(() => dossier(value, tenant, '53000000-0000-4000-8000-000000000009')).toThrow(ApiError);
    expect(() => dossier({ ...value, manifest: { ...manifest, eventsDigest: 'x' } }, tenant, incidentId)).toThrow(ApiError);
  });
  it('aberturas remotas: página decrescente com cursor na menor seq; revisão fechada', () => {
    const page = remoteOpenPage({ tenantId: tenant, items: [remote, { ...remote, eventId: incidentId, seq: 3 }], nextBeforeSeq: 3 }, tenant, 50);
    expect(page.nextBeforeSeq).toBe(3);
    expect(() => remoteOpenPage({ tenantId: tenant, items: [remote], nextBeforeSeq: 2 }, tenant, 50)).toThrow(ApiError);
    expect(() => remoteOpenPage({ tenantId: tenant, items: [{ ...remote, review: { reviewEventId: eventId, verdict: 'OK', note: null, reviewedBy: own,
      reviewedAt: '2026-10-31T12:00:00Z' } }], nextBeforeSeq: null }, tenant, 50)).toThrow(ApiError);
  });
  it('rotas e corpos: identificadores normalizados e revisão conferida na resposta', async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const fetch = vi.fn<typeof globalThis.fetch>(async (input, init) => {
      const url = String(input); calls.push({ url, ...(init ? { init } : {}) });
      if (url === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (url.endsWith('/reviews')) return json({ ...remote, review: { reviewEventId: incidentId, verdict: 'CONFERRED', note: null, reviewedBy: own, reviewedAt: '2026-10-31T12:00:00Z' } }, 201);
      return json({ tenantId: tenant, items: [remote], nextBeforeSeq: null });
    });
    const api = createSecurityCommandApi(new CondPulseApi({ fetch }));
    await api.remoteOpens(partnerId, tenant, { status: 'PENDING' });
    expect(calls.at(-1)!.url).toBe(`/v1/partners/${partnerId}/command/tenants/${tenant}/remote-opens?status=PENDING&limit=50`);
    const reviewed = await api.review(partnerId, tenant, eventId, { verdict: 'CONFERRED', note: null, correctsReviewId: null });
    expect(reviewed.review?.verdict).toBe('CONFERRED');
    await expect(api.review(partnerId, tenant, eventId, { verdict: 'IRREGULAR', note: 'Sem autorização', correctsReviewId: null })).rejects.toThrow(ApiError);
  });
});

describe('regras locais do Comando de Segurança', () => {
  it('incidente: condomínio alcançado, título, severidade e até 50 eventos distintos', () => {
    const draft = { tenantId: tenant, title: '  Porta   forçada ', severity: 'CRITICAL' as const, eventIds: [eventId, eventId.toUpperCase()] };
    expect(incidentBody(draft, [tenant])).toEqual({ tenantId: tenant, title: 'Porta forçada', severity: 'CRITICAL', eventIds: [eventId] });
    expect(incidentBody(draft, [])).toBe('Escolha o condomínio.');
    expect(incidentBody({ ...draft, title: 'ab' }, [tenant])).toMatch(/título/);
    expect(incidentBody({ ...draft, severity: '' }, [tenant])).toMatch(/severidade/);
    expect(incidentBody({ ...draft, eventIds: Array.from({ length: 51 }, (_, i) => `90000000-0000-4000-8000-${String(i).padStart(12, '0')}`) }, [tenant])).toMatch(/50/);
  });
  it('encerramento exige desfecho e relatório; revisão irregular ou correção exige motivo', () => {
    expect(closeBody({ outcome: '', report: 'Relatório completo' }, 1)).toMatch(/desfecho/);
    expect(closeBody({ outcome: 'RESOLVED', report: 'curto' }, 1)).toMatch(/relatório/);
    expect(closeBody({ outcome: 'RESOLVED', report: ' Relatório completo ' }, 1)).toEqual({ outcome: 'RESOLVED', report: 'Relatório completo', expectedVersion: 1 });
    expect(reviewBody({ verdict: '', note: '' }, null)).toMatch(/conferida ou irregular/);
    expect(reviewBody({ verdict: 'IRREGULAR', note: '' }, null)).toMatch(/motivo/);
    expect(reviewBody({ verdict: 'CONFERRED', note: '' }, incidentId)).toMatch(/motivo/);
    expect(reviewBody({ verdict: 'CONFERRED', note: '' }, null)).toEqual({ verdict: 'CONFERRED', note: null, correctsReviewId: null });
    expect(dossierFileName(tenant, incidentId)).toBe('dossie-incidente-20000000-53000000.json');
    expect(commandKey(own, partnerId, 'portfolio').slice(0, 5)).toEqual(['private', own, 'partner', partnerId, 'command']);
  });
});

describe('painel do Comando renderizado', () => {
  const tenants = [{ tenantId: tenant, tenantName: 'Residencial Sintético', timeZone: 'America/Sao_Paulo' }];
  const lead: PartnerContext = { memberId: '60000000-0000-4000-8000-000000000001', partnerId, partnerName: 'Parceiro Sintético', role: 'SECURITY_LEAD',
    panel: 'comando', tenants, capabilities: ['security.blocklist.approve', 'security.command', 'security.log.export', 'security.log.read',
      'security.policy.publish', 'security.remote_open.review'] };
  function render(section: string, prefill?: (queries: QueryClient) => void) {
    const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    prefill?.(queries);
    return renderToStaticMarkup(<QueryClientProvider client={queries}><MemoryRouter>
      <PartnerScopeProvider value={{ accountId: own, context: lead, refuse: () => false, deniedTenant: () => null }}>
        <PartnerConsole section={section} /></PartnerScopeProvider></MemoryRouter></QueryClientProvider>);
  }
  it('o líder entra na visão da carteira; abas prontas pelas capacidades', () => {
    const html = render('');
    for (const label of ['Visão da carteira', 'Incidentes', 'Revisão de aberturas', 'Log de Segurança']) expect(html).toContain(`>${label}</a>`);
    expect(html).not.toContain('>Políticas de segurança</a>');
    expect(html).toContain('Consultando a carteira');
  });
  it('semáforo com motivo, contagens e indicadores sem fonte (nunca zero)', () => {
    const html = render('carteira-seguranca', (queries) => queries.setQueryData(commandKey(own, partnerId, 'portfolio'), portfolio(body, partnerId)));
    expect(html).toContain('Semáforo: Amarelo');
    expect(html).toContain('equipamento fora do ar');
    expect(html).toContain('portas abertas, permanências vencidas');
  });
  it('aberturas remotas só consultam sob pedido; incidentes mostram o formulário de abertura', () => {
    expect(render('aberturas')).toContain('Consulta sob pedido');
    const incidents = render('incidentes', (queries) => queries.setQueryData(commandKey(own, partnerId, 'incidents', 'OPEN'), { truncated: false, incidents: [] }));
    expect(incidents).toContain('Nenhum incidente aberto');
    expect(incidents).toContain('Abrir incidente');
  });
});
