import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { scanAccessibility } from './a11y';

// Entrada por contexto de parceiro e casca /p/{parceiro}/{painel} (CPX-004/CPX-005). Dados exclusivamente sintéticos e
// interceptados pelo runner; nenhum nome de parceiro real.

const PARTNER = '30000000-0000-4000-8000-0000000000a1';
const MEMBER_ADMIN = '60000000-0000-4000-8000-0000000000a1';
const MEMBER_LEAD = '60000000-0000-4000-8000-0000000000a2';
const OPERATOR = '60000000-0000-4000-8000-0000000000a3';
const tenants = [{ tenantId: TENANT_A, tenantName: 'Residencial Sintético A', timeZone: 'America/Sao_Paulo' }];
const admin = { memberId: MEMBER_ADMIN, partnerId: PARTNER, partnerName: 'Parceiro Sintético', role: 'PARTNER_ADMIN', panel: 'gestao',
  capabilities: ['partner.manage', 'partner.team.manage', 'security.log.read'], tenants };
const lead = { memberId: MEMBER_LEAD, partnerId: PARTNER, partnerName: 'Parceiro Sintético', role: 'SECURITY_LEAD', panel: 'comando',
  capabilities: ['security.blocklist.approve', 'security.command', 'security.log.export', 'security.log.read', 'security.policy.publish', 'security.remote_open.review'], tenants };

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}

interface Options { contexts: unknown[]; partnersOn: boolean; securityLogOn: boolean; deniedOnCursor?: boolean; refuseMutations?: boolean;
  /** Corpos das trocas de contexto recebidas (R4.4) e quantas falham com 503 antes de aceitar. */
  switches?: Record<string, unknown>[]; switchFailures?: number;
  /** Saídas de painel registradas (R4.4) e vínculos de condomínio da conta em /v1/me. */
  exits?: Record<string, unknown>[]; memberships?: unknown[];
  /** Corpos recebidos pelo Comando de Segurança (abertura de incidente, revisão). */
  commandBodies?: Record<string, unknown>[] }

// Comando de Segurança (CPX-012 a CPX-014): respostas sintéticas no formato do contrato.
const INCIDENT = '53000000-0000-4000-8000-0000000000a1';
const REMOTE_OPEN = '91000000-0000-4000-8000-0000000000a1';
const LOG_EVENT = '90000000-0000-4000-8000-000000000001';
const OPERATOR_ACCOUNT = '10000000-0000-4000-8000-0000000000b3';
const hash = (n: number) => n.toString(16).padStart(64, '0');
const commandIndicators = { devicesTotal: 2, devicesOffline: 1, devicesUnknown: 0, securityOccurrencesOpen: 0, criticalAlertsUnlinked: 1, incidentsOpen: 0,
  incidentsCriticalOpen: 0, remoteOpensPendingReview: 1, operatorsOnDuty: 1 };
const portfolioBody = { partnerId: PARTNER, generatedAt: '2026-10-31T12:00:00Z', criteria: 'cpx012-v1', unavailableIndicators: ['DOORS_HELD_OPEN', 'STAYS_OVERDUE'],
  truncated: false, tenants: [{ tenantId: TENANT_A, tenantName: 'Residencial Sintético A', timeZone: 'America/Sao_Paulo', light: 'RED',
    reasons: ['CRITICAL_ALERTS_UNLINKED', 'DEVICES_OFFLINE'], indicators: commandIndicators }] };
const logEvent = (seq: number, eventType: string, severity = 'CRITICAL') => ({ eventId: seq === 1 ? LOG_EVENT : `90000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
  seq, occurredAt: '2026-10-31T12:00:00Z', recordedAt: '2026-10-31T12:00:00Z', clockSkewMs: null, eventType, severity, source: 'REMOTE_CENTRAL', partnerId: PARTNER,
  actorAccountId: null, actorRole: 'SECURITY_LEAD', subjectType: null, subjectId: null, unitId: null, pointId: null, deviceId: null, reason: null, evidenceRef: null,
  correlationId: null, traceId: null, correctsId: null, details: {}, prevHash: hash(seq - 1), hash: hash(seq) });
const incidentRow = (title: string) => ({ id: INCIDENT, tenantId: TENANT_A, tenantName: 'Residencial Sintético A', partnerId: PARTNER, title, severity: 'CRITICAL',
  state: 'OPEN', openedBy: ACCOUNT, openedAt: '2026-10-31T12:05:00Z', assigneeMemberId: null, assignedAt: null, outcome: null, report: null, closedBy: null,
  closedAt: null, version: 0, linkCount: 1 });
const remoteOpenRow = (review: unknown) => ({ eventId: REMOTE_OPEN, seq: 7, occurredAt: '2026-10-31T11:00:00Z', recordedAt: '2026-10-31T11:00:00Z',
  operatorAccountId: OPERATOR_ACCOUNT, operatorRole: 'REMOTE_OPERATOR', reason: 'Autorizado pela unidade por ligação', unitId: null, pointId: null, deviceId: null,
  evidenceRef: null, review });
async function installPartner(page: Page, api: ApiState, options: Options) {
  const requests: string[] = [];
  await page.route('**/auth/mfa', async (route) => { await json(route, 200, { assuranceLevel: 'AAL2', factors: [] }); });
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: options.memberships ?? [], tenantModules: [], partnerContexts: options.contexts }); return; }
    if (path === '/v1/platform/session') { await json(route, 403, { code: 'PLATFORM_ACCESS_DENIED', status: 403 }); return; }
    if (path.startsWith('/v1/partners/')) {
      requests.push(path);
      // Flag desligada: a rota não existe no servidor.
      if (!options.partnersOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
      // R4.4: registro da entrada no painel; a mesma chave repetida não grava de novo no servidor.
      if (path.endsWith('/context-switches')) {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        options.switches?.push(body);
        if ((options.switchFailures ?? 0) > 0) {
          options.switchFailures! -= 1;
          await json(route, 503, { status: 503, code: 'CONTEXT_SWITCH_UNAVAILABLE', detail: 'Não foi possível registrar a troca de contexto. Tente novamente.' });
          return;
        }
        await json(route, 200, { status: 'RECORDED', recorded: 1 });
        return;
      }
      if (path.endsWith('/context-exits')) {
        options.exits?.push(route.request().postDataJSON() as Record<string, unknown>);
        await json(route, 200, { status: 'RECORDED', recorded: 1 });
        return;
      }
      // Papel revogado entre a leitura e a ação: o servidor recusa a mutação.
      if (options.refuseMutations && route.request().method() === 'POST') {
        await json(route, 403, { status: 403, code: 'PARTNER_ACCESS_DENIED', detail: 'Esta ação não está disponível para o seu papel no parceiro.' }); return;
      }
      if (path.includes('/command/')) {
        // O Comando exige também o Log de Segurança ligado.
        if (!options.securityLogOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
        const method = route.request().method();
        if (path.endsWith('/command/portfolio')) { await json(route, 200, portfolioBody); return; }
        if (path.endsWith('/command/team')) {
          await json(route, 200, { partnerId: PARTNER, members: [{ memberId: OPERATOR, accountId: OPERATOR_ACCOUNT, role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético' }] });
          return;
        }
        if (path.endsWith('/command/incidents') && method === 'GET') {
          const opened = (options.commandBodies ?? []).find((item) => 'title' in item);
          await json(route, 200, { partnerId: PARTNER, state: url.searchParams.get('state') ?? 'OPEN', truncated: false,
            incidents: opened && url.searchParams.get('state') !== 'CLOSED' ? [incidentRow(String(opened.title))] : [] });
          return;
        }
        if (path.endsWith('/command/incidents') && method === 'POST') {
          const body = route.request().postDataJSON() as Record<string, unknown>;
          options.commandBodies?.push(body);
          await json(route, 201, { incident: incidentRow(String(body.title)), links: [{ eventId: LOG_EVENT, linkedBy: ACCOUNT, linkedAt: '2026-10-31T12:05:00Z' }], timeline: [] });
          return;
        }
        if (path.endsWith(`/incidents/${INCIDENT}`)) {
          const opened = (options.commandBodies ?? []).find((item) => 'title' in item);
          await json(route, 200, { incident: incidentRow(String(opened?.title ?? 'Incidente sintético')),
            links: [{ eventId: LOG_EVENT, linkedBy: ACCOUNT, linkedAt: '2026-10-31T12:05:00Z' }],
            timeline: [logEvent(1, 'FORCED_OPEN'), { ...logEvent(2, 'INCIDENT_OPENED'), correlationId: INCIDENT }] });
          return;
        }
        if (path.endsWith('/remote-opens')) { await json(route, 200, { tenantId: TENANT_A, items: [remoteOpenRow(null)], nextBeforeSeq: null }); return; }
        if (path.endsWith('/reviews')) {
          const body = route.request().postDataJSON() as Record<string, unknown>;
          options.commandBodies?.push(body);
          await json(route, 201, remoteOpenRow({ reviewEventId: '91000000-0000-4000-8000-0000000000b1', verdict: body.verdict, note: body.note ?? null,
            reviewedBy: ACCOUNT, reviewedAt: '2026-10-31T12:10:00Z' }));
          return;
        }
      }
      if (path.endsWith('/team')) {
        await json(route, 200, { partnerId: PARTNER, members: [{ id: OPERATOR, partnerId: PARTNER, accountId: '10000000-0000-4000-8000-0000000000b3',
          role: 'REMOTE_OPERATOR', displayName: 'Operador Sintético', status: 'ACTIVE', version: 0, createdAt: '2026-10-30T12:00:00Z', revokedAt: null, revokeReason: null }] });
        return;
      }
    }
    if (path.startsWith(`/v1/tenants/${TENANT_A}/security-log`)) {
      requests.push(path);
      if (!options.securityLogOn) { await json(route, 404, { status: 404, error: 'Not Found' }); return; }
      // Contrato encerrado entre uma página e outra: o servidor passa a recusar o condomínio.
      if (options.refuseMutations && route.request().method() === 'POST') {
        await json(route, 404, { status: 404, code: 'TENANT_ACCESS_DENIED', detail: 'Condomínio não disponível para este acesso.' }); return;
      }
      if (options.deniedOnCursor && url.searchParams.has('beforeSeq')) {
        await json(route, 404, { status: 404, code: 'TENANT_ACCESS_DENIED', detail: 'Condomínio não disponível para este acesso.' }); return;
      }
      if (url.searchParams.get('severity') === 'CRITICAL') { await json(route, 200, { events: [logEvent(1, 'FORCED_OPEN')], nextBeforeSeq: null }); return; }
      await json(route, 200, { events: [{ eventId: '90000000-0000-4000-8000-000000000001', seq: 1, occurredAt: '2026-10-31T12:00:00Z', recordedAt: '2026-10-31T12:00:00Z',
        clockSkewMs: null, eventType: 'ACCESS_ENTRY', severity: 'INFO', source: 'CONCIERGE', partnerId: null, actorAccountId: null, actorRole: 'CONCIERGE',
        subjectType: null, subjectId: null, unitId: null, pointId: null, deviceId: null, reason: null, evidenceRef: null, correlationId: null, traceId: null,
        correctsId: null, details: {}, prevHash: '0'.repeat(64), hash: '1'.padStart(64, '0') }], nextBeforeSeq: options.deniedOnCursor ? 1 : null });
      return;
    }
    await route.fallback();
  });
  return requests;
}

test('conta só com o papel de gestor do parceiro vai direto à equipe; abas pelas capacidades', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin], partnersOn: true, securityLogOn: true });
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/gestao/equipe$`));
  await expect(page.getByRole('heading', { name: 'Painel do Parceiro' })).toBeVisible();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  for (const label of ['Equipe', 'Escala', 'Carteira', 'Log de Segurança']) await expect(nav.getByRole('link', { name: label })).toBeVisible();
  await scanAccessibility(page, 'parceiro/equipe');
});

test('dois papéis no mesmo parceiro mostram o seletor; comando lista o que vem e consulta o log sob pedido', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const requests = await installPartner(page, api, { contexts: [admin, lead], partnersOn: true, securityLogOn: true });
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Escolha o acesso.' })).toBeVisible();
  await page.getByRole('link', { name: /Parceiro Sintético – Comando de Segurança/ }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/comando/carteira-seguranca$`));
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  for (const label of ['Visão da carteira', 'Incidentes', 'Revisão de aberturas', 'Log de Segurança']) await expect(nav.getByRole('link', { name: label })).toBeVisible();
  for (const label of ['Políticas de segurança', 'Lista de bloqueio']) {
    await expect(nav.getByText(label)).toBeVisible();
    await expect(nav.getByRole('link', { name: label })).toHaveCount(0);
  }
  await nav.getByRole('link', { name: 'Log de Segurança' }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/comando/log$`));
  expect(requests.filter((path) => path.includes('security-log'))).toEqual([]);
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Exportar com manifesto' })).toBeVisible();
  // Reenviar com os mesmos filtros refaz a busca (achado P2 do Codex).
  const before = requests.filter((path) => path.endsWith('/security-log')).length;
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect.poll(() => requests.filter((path) => path.endsWith('/security-log')).length).toBe(before + 1);
  await scanAccessibility(page, 'parceiro/comando/log');
});

test('flags desligadas no servidor: mensagem clara, sem quebrar a tela', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin], partnersOn: false, securityLogOn: false });
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Gestão do parceiro indisponível neste ambiente' })).toBeVisible();
  await page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Log de Segurança' }).click();
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByRole('heading', { name: 'Log de Segurança indisponível neste ambiente' })).toBeVisible();
});

test('endereço de painel sem papel ativo mostra aviso e caminho de volta', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin], partnersOn: true, securityLogOn: true });
  await signIn(page);
  await page.goto(`/p/${PARTNER}/console`);
  await expect(page.getByRole('heading', { name: 'Painel não disponível' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Voltar para meus acessos' })).toBeVisible();
});

test('condomínio recusado na página seguinte tira os eventos já carregados da tela', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [lead], partnersOn: true, securityLogOn: true, deniedOnCursor: true });
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/comando/carteira-seguranca$`));
  await page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Log de Segurança' }).click();
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toBeVisible();
  await page.getByRole('button', { name: 'Carregar eventos anteriores' }).click();
  await expect(page.getByRole('heading', { name: 'Acesso não disponível' })).toBeVisible();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toHaveCount(0);
});

test('recusa numa exportação tira o log da tela; recusa numa ação de equipe derruba o painel', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [admin, lead], partnersOn: true, securityLogOn: true, refuseMutations: true });
  await signIn(page);
  await page.goto(`/p/${PARTNER}/comando/log`);
  await page.getByRole('button', { name: 'Consultar log' }).click();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toBeVisible();
  await page.getByLabel('Motivo da exportação').fill('Pedido formal do condomínio');
  await page.getByRole('button', { name: 'Exportar', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Acesso não disponível' })).toBeVisible();
  await expect(page.getByText('#1 · ACCESS_ENTRY')).toHaveCount(0);

  await page.goto(`/p/${PARTNER}/gestao/equipe`);
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  await page.getByRole('button', { name: 'Revogar papel de Operador Sintético' }).click();
  await page.getByLabel('Motivo da revogação').fill('Saiu da equipe');
  await page.getByRole('button', { name: 'Confirmar revogação' }).click();
  await expect(page.getByText('Os dados exibidos foram removidos.')).toBeVisible();
  await expect(page.getByText('Operador Sintético')).toHaveCount(0);
});

test('R4.4: cada troca de painel é registrada com a origem; voltar ao mesmo painel não registra de novo', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const switches: Record<string, unknown>[] = [];
  const requests = await installPartner(page, api, { contexts: [admin, lead], partnersOn: true, securityLogOn: true, switches });
  await signIn(page);
  await page.getByRole('link', { name: /Parceiro Sintético – Comando de Segurança/ }).click();
  await expect(page.getByRole('heading', { name: 'Comando de Segurança' })).toBeVisible();
  expect(switches).toHaveLength(1);
  expect(switches[0]).toMatchObject({ role: 'SECURITY_LEAD', from: { kind: 'ENTRY' } });
  await page.getByRole('link', { name: 'CondPulse, meus acessos' }).click();
  await page.getByRole('link', { name: /Parceiro Sintético – Painel do Parceiro/ }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches).toHaveLength(2);
  expect(switches[1]).toMatchObject({ role: 'PARTNER_ADMIN', from: { kind: 'PARTNER', partnerId: PARTNER, role: 'SECURITY_LEAD' } });
  expect(switches[1].switchId).not.toBe(switches[0].switchId);
  // O registro vem antes da primeira consulta do painel.
  expect(requests.indexOf(`/v1/partners/${PARTNER}/context-switches`)).toBeLessThan(requests.indexOf(`/v1/partners/${PARTNER}/team`));
  await page.getByRole('link', { name: 'CondPulse, meus acessos' }).click();
  await page.getByRole('link', { name: /Parceiro Sintético – Painel do Parceiro/ }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches, 'mesmo painel sem outro contexto no meio não é troca').toHaveLength(2);
  // Sair e entrar de novo na mesma janela (sem recarregar): a sessão nova começa com origem ENTRY e chave nova.
  await page.getByRole('button', { name: 'Sair' }).click();
  await page.getByLabel('E-mail', { exact: true }).fill('morador-sintetico@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByLabel('Senha', { exact: true }).press('Enter');
  await page.getByRole('link', { name: /Parceiro Sintético – Painel do Parceiro/ }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches, 'novo login registra a entrada no mesmo painel').toHaveLength(3);
  expect(switches[2]).toMatchObject({ role: 'PARTNER_ADMIN', from: { kind: 'ENTRY' } });
  expect(switches[2].switchId).not.toBe(switches[1].switchId);
});

test('R4.4: falha ao registrar a troca não abre o painel; nova tentativa usa a mesma chave', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const switches: Record<string, unknown>[] = [];
  const requests = await installPartner(page, api, { contexts: [admin], partnersOn: true, securityLogOn: true, switches, switchFailures: 1 });
  await signIn(page);
  await expect(page.getByText('O registro da entrada no painel está temporariamente indisponível.')).toBeVisible();
  expect(requests.filter((path) => path.endsWith('/team'))).toEqual([]);
  await expect(page.getByText('Operador Sintético')).toHaveCount(0);
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByText('Operador Sintético')).toBeVisible();
  expect(switches).toHaveLength(2);
  expect(switches[1].switchId).toBe(switches[0].switchId);
});

test('Comando: semáforo com dados do servidor, incidente com linha do tempo e revisão de abertura remota', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const commandBodies: Record<string, unknown>[] = [];
  const requests = await installPartner(page, api, { contexts: [lead], partnersOn: true, securityLogOn: true, commandBodies });
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/comando/carteira-seguranca$`));
  const semaphore = page.getByRole('list', { name: 'Semáforo da carteira' });
  await expect(semaphore.getByText('Residencial Sintético A')).toBeVisible();
  await expect(semaphore.getByText('Semáforo: Vermelho')).toBeVisible();
  await expect(semaphore.getByText('alerta crítico sem incidente (24 h) · equipamento fora do ar')).toBeVisible();
  await expect(page.getByText('portas abertas, permanências vencidas')).toBeVisible();
  await scanAccessibility(page, 'parceiro/comando/carteira');
  // Nenhuma leitura de eventos do log sem pedido.
  expect(requests.filter((path) => path.includes('security-log') || path.includes('remote-opens') || path.includes(`/incidents/`))).toEqual([]);

  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  await nav.getByRole('link', { name: 'Incidentes' }).click();
  await expect(page.getByText('Nenhum incidente aberto')).toBeVisible();
  await page.getByLabel('Título').fill('Porta forçada na garagem');
  await page.getByLabel('Severidade', { exact: true }).selectOption('CRITICAL');
  await page.getByRole('button', { name: 'Buscar eventos' }).click();
  await page.getByLabel(/#1 · FORCED_OPEN/).check();
  await page.getByRole('button', { name: 'Abrir incidente', exact: true }).click();
  await expect(page.getByText('Incidente aberto: Porta forçada na garagem.')).toBeVisible();
  expect(commandBodies[0]).toMatchObject({ tenantId: TENANT_A, title: 'Porta forçada na garagem', severity: 'CRITICAL', eventIds: [LOG_EVENT] });
  const timeline = page.getByRole('list', { name: 'Linha do tempo do incidente' });
  await expect(timeline.getByText('#1 · FORCED_OPEN')).toBeVisible();
  await expect(timeline.getByText('#2 · INCIDENT_OPENED')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Exportar dossiê' })).toBeVisible();
  await scanAccessibility(page, 'parceiro/comando/incidentes');

  await nav.getByRole('link', { name: 'Revisão de aberturas' }).click();
  await page.getByRole('button', { name: 'Consultar aberturas' }).click();
  const opens = page.getByRole('list', { name: 'Aberturas remotas' });
  await expect(opens.getByText('Operador: Operador Sintético (REMOTE_OPERATOR) · motivo: Autorizado pela unidade por ligação')).toBeVisible();
  await opens.getByRole('button', { name: 'Revisar da abertura #7' }).click();
  await page.getByLabel('Irregular').check();
  await page.getByRole('button', { name: 'Registrar revisão' }).click();
  await expect(page.getByText('Informe o motivo, com 3 a 500 caracteres.')).toBeVisible();
  await page.getByLabel('Observação (obrigatória se irregular)').fill('Sem registro da ligação');
  await page.getByRole('button', { name: 'Registrar revisão' }).click();
  await expect(page.getByText('Abertura #7 marcada como irregular.')).toBeVisible();
  expect(commandBodies.at(-1)).toEqual({ verdict: 'IRREGULAR', note: 'Sem registro da ligação', correctsReviewId: null });
});

test('Comando com o Log de Segurança desligado no servidor: mensagem própria, sem quebrar o painel', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installPartner(page, api, { contexts: [lead], partnersOn: true, securityLogOn: false });
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Comando de Segurança indisponível neste ambiente' })).toBeVisible();
});

test('R4.4: sair do Comando direto para um condomínio por vínculo registra o lado da origem', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const exits: Record<string, unknown>[] = [];
  const switches: Record<string, unknown>[] = [];
  await installPartner(page, api, { contexts: [lead], partnersOn: true, securityLogOn: true, exits, switches,
    memberships: [{ membershipId: '50000000-0000-4000-8000-000000000001', tenantId: TENANT_A, tenantName: 'Condomínio Sintético A',
      timeZone: 'America/Sao_Paulo', unitId: '20000000-0000-4000-8000-000000000001', role: 'RESIDENT' }] });
  await signIn(page);
  await page.getByRole('link', { name: /Parceiro Sintético – Comando de Segurança/ }).click();
  await expect(page.getByRole('heading', { name: 'Comando de Segurança' })).toBeVisible();
  expect(switches).toHaveLength(1);
  await page.getByRole('link', { name: 'CondPulse, meus acessos' }).click();
  expect(exits, 'a entrada não é contexto: nada registrado ao passar por ela').toHaveLength(0);
  await page.getByRole('link', { name: /Condomínio Sintético A/ }).first().click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/`));
  await expect.poll(() => exits.length).toBe(1);
  expect(exits[0]).toMatchObject({ role: 'SECURITY_LEAD', to: { kind: 'TENANT', tenantId: TENANT_A } });
  expect(exits[0]!.switchId).not.toBe(switches[0]!.switchId);
});
