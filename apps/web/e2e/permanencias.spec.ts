import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

// Permanências (V055, CPX-015 a CPX-019): painel "quem está agora" na portaria e no console do parceiro, confirmação de
// saída pelo morador e fechamento do dia. Dados exclusivamente sintéticos, interceptados pelo runner.
test.use({ timezoneId: 'America/Sao_Paulo' });

const minutes = (value: number) => new Date(Date.now() + value * 60_000).toISOString();
async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
const OVERDUE = 'a0000000-0000-4000-8000-0000000000a1';
const ONTIME = 'a0000000-0000-4000-8000-0000000000a2';
const stayFixture = (overrides: Record<string, unknown> = {}) => ({
  stayId: ONTIME, unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: 'Bloco 1', visitorName: 'Visita Sintética', documentLast4: null,
  kind: 'VISITOR', origin: 'MANUAL', basis: 'PERMIT', enteredAt: minutes(-60), expectedExitAt: minutes(60), expectedSource: 'PERMIT', state: 'OPEN',
  closure: null, closedAt: null, level: 'NONE', recordedLevel: 'NONE', overdue: false, justified: false, justification: null, justifiedAt: null,
  entryEventId: '90000000-0000-4000-8000-0000000000a1', exitEventId: null, ...overrides });
const overdueFixture = () => stayFixture({ stayId: OVERDUE, visitorName: 'Prestador Sintético', kind: 'SERVICE_PROVIDER', basis: 'ARRIVAL',
  expectedSource: 'TENANT_RULE', enteredAt: minutes(-300), expectedExitAt: minutes(-90), level: 'L1', recordedLevel: 'L1', overdue: true,
  entryEventId: '90000000-0000-4000-8000-0000000000a2' });

async function installStays(page: Page, prefix: string) {
  const state = { stays: [overdueFixture(), stayFixture()], reads: [] as string[], justifications: [] as unknown[], confirmations: 0 };
  await page.route(`**/v1/tenants/${TENANT_A}/${prefix}**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    state.reads.push(`${request.method()} ${path}${url.search}`);
    if (path === '/stays' && request.method() === 'GET') {
      const overdueOnly = url.searchParams.get('view') === 'overdue';
      await json(route, 200, { stays: state.stays.filter((item) => item.state === 'OPEN' && (!overdueOnly || item.overdue)), truncated: false, generatedAt: new Date().toISOString() });
      return;
    }
    if (path === '/stays/day-close') {
      const date = url.searchParams.get('date')!;
      const from = new Date(`${date}T03:00:00Z`).toISOString(); const until = new Date(Date.parse(from) + 86_400_000).toISOString();
      const entries = state.stays.filter((item) => item.state === 'OPEN' && Date.parse(item.enteredAt as string) >= Date.parse(from) && Date.parse(item.enteredAt as string) < Date.parse(until));
      await json(route, 200, { date, timeZone: 'America/Sao_Paulo', from, until, generatedAt: new Date().toISOString(), entries, truncated: false,
        stillOpen: entries.length, listHash: 'c'.repeat(64) });
      return;
    }
    const justify = path.match(/^\/stays\/([^/]+)\/justification$/);
    if (justify) {
      const body = request.postDataJSON() as { reason: string };
      state.justifications.push(body);
      const item = state.stays.find((candidate) => candidate.stayId === justify[1])!;
      Object.assign(item, { justified: true, justification: body.reason, justifiedAt: new Date().toISOString() });
      await json(route, 200, item, { 'Idempotency-Replayed': 'false' });
      return;
    }
    if (path === `/units/${UNIT_A}/stays`) {
      await json(route, 200, { stays: state.stays.filter((item) => item.state === 'OPEN').map((item) => ({ ...item, justification: null })), truncated: false,
        generatedAt: new Date().toISOString() });
      return;
    }
    const confirm = path.match(new RegExp(`^/units/${UNIT_A}/stays/([^/]+)/exit-confirmation$`));
    if (confirm) {
      state.confirmations += 1;
      const item = state.stays.find((candidate) => candidate.stayId === confirm[1])!;
      Object.assign(item, { state: 'CLOSED', closure: 'RESIDENT_CONFIRMED', closedAt: new Date().toISOString(), level: 'NONE', overdue: false });
      await json(route, 200, { ...item, justification: null }, { 'Idempotency-Replayed': 'false' });
      return;
    }
    await route.fallback();
  });
  return state;
}

async function asConcierge(page: Page, api: ApiState) {
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }], tenantModules: [{ tenantId: TENANT_A, enabled: ['visits'] }] });
  });
  // Demais painéis de visitas respondem vazios (o foco aqui são as permanências).
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const path = new URL(route.request().url()).pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    if (path === '/concierge/units') { await json(route, 200, [{ unitId: UNIT_A, label: '101 — Sintética A', blockName: 'Bloco 1' }]); return; }
    if (path === '/arrivals') { await json(route, 200, { arrivals: [], truncated: false }); return; }
    if (path === '/concierge/permits') { await json(route, 200, { permits: [], truncated: false }); return; }
    if (path === '/access-events') { await json(route, 200, { records: [], truncated: false }); return; }
    await route.fallback();
  });
}

test('portaria vê quem está agora com vencidas em destaque e origem, justifica com motivo e gera o fechamento do dia', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await asConcierge(page, api);
  const state = await installStays(page, 'stays');
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/portaria/visitas`);
  await expect(page.getByRole('heading', { name: 'Quem está no condomínio agora · 2 permanências abertas · 1 vencida' })).toBeVisible();
  const list = page.getByRole('list', { name: 'Permanências abertas' });
  const overdue = list.getByRole('listitem').filter({ hasText: 'Prestador Sintético' });
  const ontime = list.getByRole('listitem').filter({ hasText: 'Visita Sintética' });
  await expect(list.getByRole('listitem').first()).toContainText('Prestador Sintético');
  await expect(overdue.getByText('Alerta N1', { exact: true })).toHaveClass(/status-overdue/);
  await expect(overdue.getByText('Origem: registro manual da portaria, por chegada autorizada pela unidade.', { exact: false })).toBeVisible();
  await expect(overdue.getByText('(regra do condomínio)', { exact: false })).toBeVisible();
  await expect(ontime.getByText('No prazo', { exact: true })).toBeVisible();
  await expect(ontime.getByRole('button', { name: 'Justificar' })).toHaveCount(0);
  // Sem consulta automática: cada leitura grava SENSITIVE_VIEWED no servidor.
  expect(state.reads.filter((read) => read.startsWith('GET /stays?') || read === 'GET /stays')).toHaveLength(1);
  await overdue.getByRole('button', { name: 'Justificar' }).click();
  await overdue.getByLabel('Motivo da justificativa').fill('ok');
  await overdue.getByRole('button', { name: 'Registrar justificativa' }).click();
  await expect(overdue.getByText('Informe o motivo, com 3 a 500 caracteres.')).toBeVisible();
  expect(state.justifications).toEqual([]);
  await overdue.getByLabel('Motivo da justificativa').fill('  Prestador   autorizado até 20h pela unidade ');
  await overdue.getByRole('button', { name: 'Registrar justificativa' }).click();
  await expect(page.getByText('Permanência de Prestador Sintético justificada. Os alertas param; a saída continua pendente.')).toBeVisible();
  expect(state.justifications).toEqual([{ reason: 'Prestador autorizado até 20h pela unidade' }]);
  await expect(overdue.getByText('Alerta N1 · justificada')).toBeVisible();
  await expect(overdue.getByText('Prestador autorizado até 20h pela unidade', { exact: false })).toBeVisible();
  await page.getByLabel('Só as vencidas').check();
  await expect(list.getByRole('listitem')).toHaveCount(1);
  // Fechamento do dia: só sob pedido.
  expect(state.reads.some((read) => read.includes('/day-close'))).toBe(false);
  await page.getByRole('button', { name: 'Gerar relatório' }).click();
  // As entradas sintéticas podem cair no dia anterior conforme a hora da execução: confere o aviso do dia, qualquer que seja a contagem.
  await expect(page.getByText(/^Dia \d{4}-\d{2}-\d{2}: (\d+ entradas? sem saída registrada|todas as entradas têm saída)/)).toBeVisible();
  await expect(page.getByText('Hash da lista:', { exact: false })).toBeVisible();
  await scanAccessibility(page, 'portaria/permanências');
});

test('morador adulto confirma a saída de quem entrou para a unidade', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installStays(page, 'units');
  // Os demais painéis da unidade respondem vazios.
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/*`, async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/arrivals')) { await json(route, 200, { arrivals: [], truncated: false }); return; }
    if (path.endsWith('/permits')) { await json(route, 200, { permits: [], truncated: false }); return; }
    if (path.endsWith('/visit-rules')) { await json(route, 200, { rules: [], truncated: false }); return; }
    if (path.endsWith('/access-events')) { await json(route, 200, { records: [], truncated: false }); return; }
    await route.fallback();
  });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/acessos`);
  const list = page.getByRole('list', { name: 'Permanências abertas da unidade' });
  const row = list.getByRole('listitem').filter({ hasText: 'Visita Sintética' });
  await expect(row.getByText('Sem registro de saída não prova que a pessoa continua no condomínio.', { exact: false })).toBeVisible();
  await scanAccessibility(page, 'unidade/permanências');
  await row.getByRole('button', { name: 'Confirmar que saiu' }).click();
  await expect(page.getByText('Saída de Visita Sintética confirmada. A portaria vê a confirmação.')).toBeVisible();
  expect(state.confirmations).toBe(1);
  await expect(list.getByRole('listitem').filter({ hasText: 'Visita Sintética' })).toHaveCount(0);
});

test('console do operador remoto mostra as permanências do condomínio escalado, só leitura', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const PARTNER = '30000000-0000-4000-8000-0000000000a1';
  const operator = { memberId: '60000000-0000-4000-8000-0000000000a3', partnerId: PARTNER, partnerName: 'Parceiro Sintético', role: 'REMOTE_OPERATOR',
    panel: 'console', capabilities: ['ops.remote.open', 'ops.remote.operate'],
    tenants: [{ tenantId: TENANT_A, tenantName: 'Residencial Sintético A', timeZone: 'America/Sao_Paulo' }] };
  await page.route('**/auth/mfa', async (route) => { await json(route, 200, { assuranceLevel: 'AAL2', factors: [] }); });
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [], partnerContexts: [operator] });
  });
  await page.route('**/v1/platform/session', async (route) => { await json(route, 403, { code: 'PLATFORM_ACCESS_DENIED', status: 403 }); });
  await page.route(`**/v1/partners/${PARTNER}/context-switches`, async (route) => { await json(route, 200, { status: 'RECORDED', recorded: 1 }); });
  const state = await installStays(page, 'stays');
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/p/${PARTNER}/console/permanencias$`));
  const list = page.getByRole('list', { name: 'Permanências abertas de Residencial Sintético A' });
  await expect(list.getByRole('listitem').filter({ hasText: 'Prestador Sintético' }).getByText('Alerta N1', { exact: true })).toBeVisible();
  await expect(list.getByRole('button', { name: 'Justificar' })).toHaveCount(0);
  await expect(page.getByText('2 permanências abertas · 1 vencida', { exact: false })).toBeVisible();
  await scanAccessibility(page, 'parceiro/console/permanências');
  expect(state.reads).toEqual(['GET /stays']);
});
