import { test, expect, type Page, type Route } from '@playwright/test';
import { scanAccessibility } from './a11y';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi, type ManagementState } from './management-fixtures';

// Zeladoria (MAINTENANCE, V046): pedidos atribuídos ao próprio vínculo, conversa com páginas anteriores, mensagem e
// andamento pela versão lida. Dados exclusivamente sintéticos e interceptados pelo runner.

test.use({ timezoneId: 'UTC' });

const ASSIGNED = '96000000-0000-4000-8000-000000000011';
const OTHER = '96000000-0000-4000-8000-000000000012';
const minutes = (value: number) => new Date(Date.now() + value * 60_000).toISOString();
const ALL = ['reservations', 'parcels', 'visits', 'announcements', 'requests', 'finance', 'access_credentials', 'facial_access'];

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-000000000004' });
}

interface Message { messageId: string; authorRole: 'UNIT' | 'STAFF'; authorName: string; mine: boolean; visibility: 'RESIDENT' | 'INTERNAL'; body: string; createdAt: string }

/** 5 mensagens, janela de 2: o detalhe traz as 2 mais recentes e há duas páginas anteriores. */
function conversation(): Message[] {
  return Array.from({ length: 5 }, (_, index) => ({ messageId: `97000000-0000-4000-8000-0000000001${String(index).padStart(2, '0')}`,
    authorRole: index % 2 === 0 ? 'UNIT' as const : 'STAFF' as const, authorName: index % 2 === 0 ? 'PESSOA FICTÍCIA' : 'GESTÃO FICTÍCIA', mine: false,
    visibility: index === 3 ? 'INTERNAL' as const : 'RESIDENT' as const, body: `Mensagem fictícia ${index + 1}`, createdAt: minutes(-60 + index) }));
}

async function installMaintenance(page: Page, enabled: string[]) {
  const state = { state: 'TRIAGED', version: 3, resolution: null as string | null, resolvedAt: null as string | null, messages: conversation(),
    calls: [] as string[], updates: [] as unknown[], posted: [] as { key: string; body: unknown }[], managementCalls: 0 };
  const WINDOW = 2;
  await page.route('**/v1/me', async (route) => {
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-000000000021', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'MAINTENANCE' }],
    tenantModules: [{ tenantId: TENANT_A, enabled }] });
  });
  const detail = () => ({ requestId: ASSIGNED, unitId: UNIT_A, unitLabel: 'FICTÍCIA 101', category: 'MAINTENANCE', title: 'Vazamento fictício',
    description: 'Água no teto do banheiro.', state: state.state, dueOn: null, resolution: state.resolution, createdAt: minutes(-120),
    updatedAt: minutes(-1), resolvedAt: state.resolvedAt, version: state.version, messagesTruncated: state.messages.length > WINDOW,
    messages: state.messages.slice(-WINDOW) });
  await page.route(`**/v1/tenants/${TENANT_A}/management/**`, async (route) => { state.managementCalls += 1; await problem(route, 403, 'STAFF_ACCESS_DENIED'); });
  await page.route(`**/v1/tenants/${TENANT_A}/maintenance/requests**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}/maintenance/requests`, '');
    state.calls.push(`${request.method()} ${path}${url.search}`);
    if (!enabled.includes('requests')) { await problem(route, 403, 'MODULE_DISABLED'); return; }
    if (request.method() === 'GET' && path === '') {
      const { description: _d, resolution: _r, createdAt: _c, resolvedAt: _rv, messages: _m, messagesTruncated: _t, ...summary } = detail();
      await json(route, 200, { requests: [summary], truncated: false });
      return;
    }
    if (path.startsWith(`/${OTHER}`)) { await problem(route, 404, 'SERVICE_REQUEST_NOT_FOUND'); return; }
    if (request.method() === 'GET' && path === `/${ASSIGNED}`) { await json(route, 200, detail()); return; }
    if (request.method() === 'GET' && path === `/${ASSIGNED}/messages`) {
      const before = url.searchParams.get('before');
      const index = state.messages.findIndex((message) => message.messageId === before);
      if (index < 0) { await problem(route, 404, 'SERVICE_REQUEST_NOT_FOUND'); return; }
      const start = Math.max(0, index - WINDOW);
      await json(route, 200, { messages: state.messages.slice(start, index), truncated: start > 0 });
      return;
    }
    if (request.method() === 'POST' && path === `/${ASSIGNED}/messages`) {
      const body = request.postDataJSON() as { body: string; visibility: 'RESIDENT' | 'INTERNAL' };
      state.posted.push({ key: request.headers()['idempotency-key']!, body });
      const message: Message = { messageId: `97000000-0000-4000-8000-0000000002${String(state.posted.length).padStart(2, '0')}`, authorRole: 'STAFF',
        authorName: 'ZELADOR FICTÍCIO', mine: true, visibility: body.visibility, body: body.body, createdAt: minutes(0) };
      state.messages.push(message);
      await json(route, 201, message, { 'Idempotency-Replayed': 'false' });
      return;
    }
    if (request.method() === 'POST' && path === `/${ASSIGNED}/state`) {
      const body = request.postDataJSON() as { state: string; resolution: string | null; expectedVersion: number };
      state.updates.push(body);
      if (body.expectedVersion !== state.version) { await problem(route, 409, 'SERVICE_REQUEST_CHANGED'); return; }
      state.state = body.state; state.version += 1;
      if (body.state === 'RESOLVED') {
        state.resolution = body.resolution; state.resolvedAt = minutes(0);
        state.messages.push({ messageId: '97000000-0000-4000-8000-000000000299', authorRole: 'STAFF', authorName: 'ZELADOR FICTÍCIO', mine: true,
          visibility: 'RESIDENT', body: `Resolução: ${body.resolution}`, createdAt: minutes(0) });
      }
      await json(route, 200, detail());
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}

async function signInMaintenance(page: Page, enabled: string[], management: Partial<ManagementState> = { assurance: 'AAL2', verifiedFactor: true }) {
  await installSyntheticApi(page);
  const mfa = await installManagementApi(page, management);
  const state = await installMaintenance(page, enabled);
  await signIn(page);
  return { state, mfa };
}

test('zelador entra na zeladoria e vê só os pedidos atribuídos a ele, sem gestão nem portaria', async ({ page }) => {
  const { state } = await signInMaintenance(page, ALL);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/zeladoria/pedidos$`));
  await expect(page.getByRole('heading', { name: 'Pedidos atribuídos a você' })).toBeVisible();
  const list = page.getByRole('list', { name: 'Pedidos atribuídos' });
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await expect(list).toContainText('Vazamento fictício');
  await expect(list).toContainText('Unidade FICTÍCIA 101');
  await expect(list).toContainText('Em análise');
  await expect(page.getByText('PESSOA FICTÍCIA')).toHaveCount(0);
  const areas = page.getByRole('navigation', { name: 'Áreas do condomínio' });
  await expect(areas.getByRole('link')).toHaveText(['Início', 'Zeladoria']);
  expect(state.calls).toEqual(['GET ']);
  expect(state.managementCalls).toBe(0);
  await scanAccessibility(page, 'zeladoria/lista');

  // Pedido não atribuído, mesmo com o ID conhecido: não encontrado, sem dados.
  await page.goto(`/c/${TENANT_A}/zeladoria/pedidos/${OTHER}`);
  await expect(page.getByRole('heading', { name: 'Pedido não encontrado' })).toBeVisible();
  await page.getByRole('link', { name: 'Voltar aos pedidos' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/zeladoria/pedidos$`));
});

test('zelador carrega mensagens anteriores, conversa e muda o andamento pela versão lida', async ({ page }) => {
  const { state } = await signInMaintenance(page, ALL);
  await page.getByRole('link', { name: 'Abrir pedido Vazamento fictício' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/zeladoria/pedidos/${ASSIGNED}$`));
  await expect(page.getByRole('heading', { name: 'Vazamento fictício' })).toBeVisible();
  await expect(page.getByText('Água no teto do banheiro.')).toBeVisible();
  const messages = page.getByRole('list', { name: 'Conversa do pedido' });
  await expect(messages.getByRole('listitem')).toHaveCount(2);
  await expect(messages).toContainText('comentário interno, não aparece para a unidade');
  // Duas páginas anteriores, pela mensagem mais antiga exibida, sem repetir mensagem.
  await page.getByRole('button', { name: 'Carregar mensagens anteriores' }).click();
  await expect(messages.getByRole('listitem')).toHaveCount(4);
  await expect(page.getByText('Há mensagens ainda mais antigas.')).toBeVisible();
  await page.getByRole('button', { name: 'Carregar mensagens anteriores' }).click();
  await expect(messages.getByRole('listitem')).toHaveCount(5);
  await expect(messages.getByRole('listitem').first()).toContainText('Mensagem fictícia 1');
  await expect(page.getByRole('button', { name: 'Carregar mensagens anteriores' })).toHaveCount(0);
  expect(state.calls.filter((call) => call.includes('before='))).toEqual([
    `GET /${ASSIGNED}/messages?before=97000000-0000-4000-8000-000000000103`,
    `GET /${ASSIGNED}/messages?before=97000000-0000-4000-8000-000000000101`]);
  await scanAccessibility(page, 'zeladoria/detalhe');

  await page.getByLabel('Mensagem', { exact: true }).fill('Passo aí às 14h.');
  await page.getByRole('button', { name: 'Enviar para a unidade' }).click();
  await expect(page.getByText('Mensagem enviada para a unidade.')).toBeVisible();
  expect(state.posted).toHaveLength(1);
  expect(state.posted[0]!.body).toEqual({ body: 'Passo aí às 14h.', visibility: 'RESIDENT' });
  expect(state.posted[0]!.key.length).toBeGreaterThanOrEqual(16);
  await expect(messages).toContainText('Passo aí às 14h.');

  // Andamento: em execução, depois concluído com resolução; sempre pela versão lida.
  const select = page.getByLabel('Novo andamento');
  await expect(select.locator('option')).toHaveText(['Em execução', 'Aguardando a unidade', 'Concluído']);
  await select.selectOption('IN_PROGRESS');
  await page.getByRole('button', { name: 'Salvar andamento' }).click();
  await expect(page.getByText('Andamento salvo: em execução.')).toBeVisible();
  await expect(page.getByLabel('Novo andamento').locator('option')).toHaveText(['Aguardando a unidade', 'Concluído']);
  await page.getByLabel('Novo andamento').selectOption('RESOLVED');
  await expect(page.getByRole('button', { name: 'Salvar andamento' })).toBeDisabled();
  await page.getByLabel('Resolução (aparece para a unidade)').fill('Vedação trocada.');
  await page.getByRole('button', { name: 'Salvar andamento' }).click();
  await expect(page.getByText('Pedido concluído; a resolução também foi enviada na conversa.')).toBeVisible();
  await expect(page.getByText('Resolução vigente: Vedação trocada.')).toBeVisible();
  await expect(page.getByLabel('Novo andamento')).toHaveCount(0);
  expect(state.updates).toEqual([{ state: 'IN_PROGRESS', resolution: null, expectedVersion: 3 }, { state: 'RESOLVED', resolution: 'Vedação trocada.', expectedVersion: 4 }]);
});

test('zeladoria exige segundo fator antes de consultar os pedidos', async ({ page }) => {
  const { state } = await signInMaintenance(page, ALL, { assurance: 'AAL1', verifiedFactor: true });
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/zeladoria/pedidos$`));
  await expect(page.getByRole('heading', { name: /Confirme sua identidade/ })).toBeVisible();
  expect(state.calls).toEqual([]);
});

test('módulo de pedidos desligado: zeladoria desabilitada no menu e endereço direto sem consulta à API', async ({ page }) => {
  const { state } = await signInMaintenance(page, ALL.filter((module) => module !== 'requests'));
  // Sem área liberada, a entrada abre o início do condomínio com a zeladoria desligada.
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/inicio$`));
  const cards = page.getByRole('list', { name: 'Áreas do condomínio' });
  await expect(cards).toContainText('Desligado pela administradora');
  await expect(cards.getByRole('link', { name: /Zeladoria/ })).toHaveCount(0);
  await page.goto(`/c/${TENANT_A}/zeladoria/pedidos/${ASSIGNED}`);
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toBeVisible();
  await expect(page.getByText('Pedidos e anexos não está liberado para este condomínio.')).toBeVisible();
  expect(state.calls).toEqual([]);
  await scanAccessibility(page, 'zeladoria/módulo desligado');
});
