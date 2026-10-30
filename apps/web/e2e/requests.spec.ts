import { test, expect, type Page, type Route } from '@playwright/test';
import { scanAccessibility } from './a11y';
import { TENANT_A, UNIT_A, chooseContext, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const REQUEST = '96000000-0000-4000-8000-000000000001';
const PORTER = '98000000-0000-4000-8000-000000000001';
const minutes = (value: number) => new Date(Date.now() + value * 60_000).toISOString();
const civil = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);

interface Message { messageId: string; authorRole: 'UNIT' | 'STAFF'; authorName: string; mine: boolean; visibility: 'RESIDENT' | 'INTERNAL'; body: string; createdAt: string }
interface Request {
  requestId: string; unitId: string; category: string; title: string; description: string; state: string; assigneeMembershipId: string | null;
  dueOn: string | null; resolution: string | null; requesterName: string; createdAt: string; updatedAt: string; resolvedAt: string | null;
  closedAt: string | null; version: number; messages: Message[];
}
async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
/** Janela de mensagens do detalhe (no servidor real, 200); páginas anteriores do mesmo tamanho. */
let windowSize = Number.POSITIVE_INFINITY;
const unitMessages = (item: Request) => item.messages.filter((message) => message.visibility === 'RESIDENT')
  .map((message) => ({ ...message, authorName: message.authorRole === 'STAFF' ? null : message.authorName, mine: message.authorRole === 'UNIT' }));
const staffMessages = (item: Request) => item.messages.map((message) => ({ ...message, mine: message.authorRole === 'STAFF' }));
function olderPage<T extends { messageId: string }>(all: T[], before: string) {
  const index = all.findIndex((message) => message.messageId === before);
  if (index < 0) return null;
  const start = Math.max(0, index - windowSize);
  return { messages: all.slice(start, index), truncated: start > 0 };
}
const unitView = (item: Request) => ({
  requestId: item.requestId, unitId: item.unitId, category: item.category, title: item.title, description: item.description, state: item.state,
  assigned: item.assigneeMembershipId !== null, dueOn: item.dueOn, resolution: item.resolution, requesterName: item.requesterName, openedByMe: true,
  createdAt: item.createdAt, updatedAt: item.updatedAt, resolvedAt: item.resolvedAt, closedAt: item.closedAt,
  messagesTruncated: unitMessages(item).length > windowSize, messages: unitMessages(item).slice(-Math.min(windowSize, 200)),
});
const managedView = (item: Request) => ({
  requestId: item.requestId, unitId: item.unitId, unitLabel: 'FICTÍCIA 101', category: item.category, title: item.title, description: item.description,
  state: item.state, assigneeMembershipId: item.assigneeMembershipId, assigneeName: item.assigneeMembershipId ? 'PORTARIA FICTÍCIA' : null,
  dueOn: item.dueOn, resolution: item.resolution, requesterName: item.requesterName, createdAt: item.createdAt, updatedAt: item.updatedAt,
  resolvedAt: item.resolvedAt, closedAt: item.closedAt, version: item.version,
  messagesTruncated: staffMessages(item).length > windowSize, messages: staffMessages(item).slice(-Math.min(windowSize, 200)),
  team: [{ membershipId: PORTER, name: 'PORTARIA FICTÍCIA', role: 'CONCIERGE' }],
});
const summary = (view: ReturnType<typeof managedView>) => {
  const { description: _description, resolution: _resolution, requesterName: _requester, resolvedAt: _resolved, closedAt: _closed,
    messages: _messages, messagesTruncated: _truncated, team: _team, ...rest } = view;
  return rest;
};

/** API sintética dos pedidos (unidade e gestão), registrada depois da API base. */
async function installRequestsApi(page: Page) {
  const state = { items: [] as Request[], opens: [] as { key: string; body: unknown }[], messages: [] as { key: string; body: unknown }[],
    updates: [] as unknown[], loseNextOpen: false, failNextOlder: false, olderCalls: [] as string[], keys: new Map<string, Request>(), messageKeys: new Map<string, Message>(),
    holdMessage: null as Promise<void> | null };
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/requests`, async (route) => {
    const request = route.request();
    if (request.method() === 'GET') {
      await json(route, 200, { requests: state.items.map((item) => { const view = unitView(item);
        return { requestId: view.requestId, unitId: view.unitId, category: view.category, title: view.title, state: view.state, assigned: view.assigned,
          dueOn: view.dueOn, createdAt: view.createdAt, updatedAt: view.updatedAt }; }), truncated: false });
      return;
    }
    const key = request.headers()['idempotency-key']!;
    const body = request.postDataJSON() as { category: string; title: string; description: string };
    state.opens.push({ key, body });
    const previous = state.keys.get(key);
    if (previous) { await json(route, 201, unitView(previous), { 'Idempotency-Replayed': 'true' }); return; }
    const item: Request = { requestId: REQUEST, unitId: UNIT_A, ...body, state: 'OPEN', assigneeMembershipId: null, dueOn: null, resolution: null,
      requesterName: 'PESSOA FICTÍCIA', createdAt: minutes(0), updatedAt: minutes(0), resolvedAt: null, closedAt: null, version: 0,
      messages: [{ messageId: '97000000-0000-4000-8000-000000000001', authorRole: 'STAFF', authorName: 'GESTÃO FICTÍCIA', mine: false,
        visibility: 'INTERNAL', body: 'Comentário interno fictício', createdAt: minutes(0) }] };
    state.keys.set(key, item); state.items.unshift(item);
    if (state.loseNextOpen) { state.loseNextOpen = false; await route.abort('connectionreset'); return; }
    await json(route, 201, unitView(item), { 'Idempotency-Replayed': 'false' });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/requests/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/requests/`, '');
    // Anexos (CP-017) desligados neste conjunto: a rota não existe e a seção some sem erro.
    if (path.endsWith('/attachments')) { await route.fulfill({ status: 404 }); return; }
    const item = state.items.find((candidate) => candidate.requestId === path.split('/')[0]);
    if (!item) { await json(route, 404, { code: 'SERVICE_REQUEST_NOT_FOUND', status: 404 }); return; }
    const before = new URL(request.url()).searchParams.get('before');
    if (request.method() === 'GET' && path.endsWith('/messages')) {
      state.olderCalls.push(`unit:${before}`);
      if (state.failNextOlder) { state.failNextOlder = false; await json(route, 503, { code: 'SERVICE_REQUEST_DEPENDENCY_UNAVAILABLE', status: 503 }); return; }
      const older = olderPage(unitMessages(item), before ?? '');
      if (!older) { await json(route, 404, { code: 'SERVICE_REQUEST_NOT_FOUND', status: 404 }); return; }
      await json(route, 200, older);
      return;
    }
    if (request.method() === 'GET') { await json(route, 200, unitView(item)); return; }
    const body = request.postDataJSON() as { body: string };
    const messageKey = request.headers()['idempotency-key']!;
    state.messages.push({ key: messageKey, body });
    const replay = state.messageKeys.get(messageKey);
    if (replay) { await json(route, 201, replay, { 'Idempotency-Replayed': 'true' }); return; }
    const message: Message = { messageId: `97000000-0000-4000-8000-00000000000${item.messages.length + 2}`, authorRole: 'UNIT', authorName: 'PESSOA FICTÍCIA',
      mine: true, visibility: 'RESIDENT', body: body.body, createdAt: minutes(0) };
    item.messages.push(message);
    state.messageKeys.set(messageKey, message);
    if (state.holdMessage) await state.holdMessage;
    await json(route, 201, message, { 'Idempotency-Replayed': 'false' });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/management/requests**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}/management/requests`, '');
    if (request.method() === 'GET' && path === '') { await json(route, 200, { requests: state.items.map((item) => summary(managedView(item))), truncated: false }); return; }
    const item = state.items.find((candidate) => candidate.requestId === path.split('/')[1]);
    if (!item) { await json(route, 404, { code: 'SERVICE_REQUEST_NOT_FOUND', status: 404 }); return; }
    if (request.method() === 'GET' && path.endsWith('/messages')) {
      state.olderCalls.push(`staff:${url.searchParams.get('before')}`);
      const older = olderPage(staffMessages(item), url.searchParams.get('before') ?? '');
      if (!older) { await json(route, 404, { code: 'SERVICE_REQUEST_NOT_FOUND', status: 404 }); return; }
      await json(route, 200, older);
      return;
    }
    if (request.method() === 'GET') { await json(route, 200, managedView(item)); return; }
    if (path.endsWith('/messages')) {
      const body = request.postDataJSON() as { body: string; visibility: 'RESIDENT' | 'INTERNAL' };
      const message: Message = { messageId: `97000000-0000-4000-8000-00000000001${item.messages.length}`, authorRole: 'STAFF', authorName: 'GESTÃO FICTÍCIA',
        mine: true, visibility: body.visibility, body: body.body, createdAt: minutes(0) };
      item.messages.push(message);
      await json(route, 201, message, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const body = request.postDataJSON() as { state: string; assigneeMembershipId: string | null; dueOn: string | null; resolution: string | null; expectedVersion: number };
    state.updates.push(body);
    item.state = body.state; item.assigneeMembershipId = body.assigneeMembershipId; item.dueOn = body.dueOn; item.version += 1;
    if (body.state === 'RESOLVED') {
      item.resolution = body.resolution; item.resolvedAt = minutes(0);
      item.messages.push({ messageId: '97000000-0000-4000-8000-000000000099', authorRole: 'STAFF', authorName: 'GESTÃO FICTÍCIA', mine: true,
        visibility: 'RESIDENT', body: `Resolução: ${body.resolution}`, createdAt: minutes(0) });
    }
    await json(route, 200, managedView(item));
  });
  return state;
}

test('unidade abre pedido com chave pendente, conversa no próprio pedido e não vê comentário interno', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installRequestsApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Abrir pedido' }).click();
  const form = page.getByRole('form', { name: 'Abrir pedido' });
  await form.getByLabel('Categoria').selectOption('MAINTENANCE');
  await form.getByLabel('Título').fill('  Vazamento   no banheiro ');
  await form.getByLabel('Descrição').fill('Água no teto <b>forte</b>.\n\n\nDesde ontem.\n');
  state.loseNextOpen = true;
  await form.getByRole('button', { name: 'Enviar pedido' }).click();
  await expect(form.getByText('O resultado não foi confirmado e o pedido ficou travado', { exact: false })).toBeVisible();
  await form.getByRole('button', { name: 'Voltar' }).click();
  await page.getByRole('button', { name: 'Abrir pedido' }).click();
  await expect(page.getByRole('form', { name: 'Abrir pedido' }).getByLabel('Título')).toHaveValue('Vazamento no banheiro');
  await page.getByRole('form', { name: 'Abrir pedido' }).getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }).click();
  await expect(page.getByText('Este pedido já estava aberto: Vazamento no banheiro', { exact: false })).toBeVisible();
  expect(state.opens.map((item) => item.body)).toEqual([
    { category: 'MAINTENANCE', title: 'Vazamento no banheiro', description: 'Água no teto <b>forte</b>.\n\nDesde ontem.' },
    { category: 'MAINTENANCE', title: 'Vazamento no banheiro', description: 'Água no teto <b>forte</b>.\n\nDesde ontem.' }]);
  expect(state.opens[1]!.key).toBe(state.opens[0]!.key);
  const row = page.getByRole('list', { name: 'Pedidos da unidade' }).getByRole('listitem').filter({ hasText: 'Vazamento no banheiro' });
  await expect(row.getByText('Enviado', { exact: true })).toBeVisible();
  await expect(row.locator('.request-detail .announcement-body').first()).toHaveText('Água no teto <b>forte</b>.\n\nDesde ontem.', { useInnerText: false });
  await expect(row.locator('.request-detail b')).toHaveCount(0);
  await expect(page.getByText('Comentário interno fictício')).toHaveCount(0);
  await row.getByLabel('Mensagem para a gestão').fill('Pode vir à tarde.');
  await row.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect(row.getByRole('list', { name: 'Conversa do pedido' }).getByText('Pode vir à tarde.')).toBeVisible();
  await expect(row.getByRole('list', { name: 'Conversa do pedido' }).getByText('Você')).toBeVisible();
  expect(state.messages.map((item) => item.body)).toEqual([{ body: 'Pode vir à tarde.' }]);
});

test('pedido com resultado incerto não fica pendente para a próxima conta depois de sair', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installRequestsApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await page.getByRole('button', { name: 'Abrir pedido' }).click();
  const form = page.getByRole('form', { name: 'Abrir pedido' });
  await form.getByLabel('Título').fill('Portão travado');
  await form.getByLabel('Descrição').fill('Não abre pelo controle.');
  state.loseNextOpen = true;
  await form.getByRole('button', { name: 'Enviar pedido' }).click();
  await expect(form.getByText('O resultado não foi confirmado e o pedido ficou travado', { exact: false })).toBeVisible();
  // Nova entrada na mesma aba, sem recarregar a página (a memória da aba continua).
  await page.getByRole('button', { name: 'Sair', exact: true }).click();
  await page.getByLabel('E-mail', { exact: true }).fill('morador-sintetico@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByLabel('Senha', { exact: true }).press('Enter');
  await expect(page).toHaveURL(/\/contextos/);
  await chooseContext(page, 'A', 'inicio');
  await page.getByRole('button', { name: 'Abrir pedido' }).click();
  const reopened = page.getByRole('form', { name: 'Abrir pedido' });
  await expect(reopened.getByLabel('Título')).toHaveValue('');
  await expect(reopened.getByText('O resultado não foi confirmado', { exact: false })).toHaveCount(0);
  await expect(reopened.getByRole('button', { name: 'Enviar pedido' })).toBeVisible();
  await reopened.getByLabel('Título').fill('Outro pedido');
  await reopened.getByLabel('Descrição').fill('Texto do outro pedido.');
  await reopened.getByRole('button', { name: 'Enviar pedido' }).click();
  await expect.poll(() => state.opens.length).toBe(2);
  expect(state.opens[1]!.key).not.toBe(state.opens[0]!.key);
});

test('conversa reaberta durante o envio não grava a mesma mensagem de novo depois que o envio termina', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installRequestsApi(page);
  state.items.push({ requestId: REQUEST, unitId: UNIT_A, category: 'NOISE', title: 'Barulho fictício', description: 'Barulho depois das 22h.',
    state: 'OPEN', assigneeMembershipId: null, dueOn: null, resolution: null, requesterName: 'PESSOA FICTÍCIA', createdAt: minutes(-30),
    updatedAt: minutes(-30), resolvedAt: null, closedAt: null, version: 0, messages: [] });
  let release!: () => void;
  state.holdMessage = new Promise<void>((resolve) => { release = resolve; });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const row = page.getByRole('list', { name: 'Pedidos da unidade' }).getByRole('listitem').filter({ hasText: 'Barulho fictício' });
  await row.getByRole('button', { name: 'Ver conversa' }).click();
  await row.getByLabel('Mensagem para a gestão').fill('Continua o barulho.');
  await row.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect.poll(() => state.messages.length).toBe(1);
  // Antes da resposta, a pessoa fecha e reabre a conversa: o formulário reaberto mostra o envio em andamento.
  await row.getByRole('button', { name: 'Fechar conversa' }).click();
  await row.getByRole('button', { name: 'Ver conversa' }).click();
  await expect(row.getByLabel('Mensagem para a gestão')).toHaveValue('Continua o barulho.');
  await expect(row.getByRole('button', { name: 'Enviando…' })).toBeDisabled();
  release();
  state.holdMessage = null;
  await expect(row.getByText('Este envio já foi concluído pela conversa aberta antes', { exact: false })).toBeVisible();
  await row.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect.poll(() => state.messages.length).toBe(2);
  expect(state.messages[1]!.key).toBe(state.messages[0]!.key);
  expect(state.items[0]!.messages.filter((message) => message.body === 'Continua o barulho.')).toHaveLength(1);
});

test('gestão registra comentário interno, define responsável e prazo e conclui com resolução visível', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installRequestsApi(page);
  state.items.push({ requestId: REQUEST, unitId: UNIT_A, category: 'NOISE', title: 'Barulho fictício', description: 'Barulho depois das 22h.',
    state: 'OPEN', assigneeMembershipId: null, dueOn: null, resolution: null, requesterName: 'PESSOA FICTÍCIA', createdAt: minutes(-30),
    updatedAt: minutes(-30), resolvedAt: null, closedAt: null, version: 0, messages: [] });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pedidos`);
  await expect(page.getByRole('heading', { name: 'Pedidos das unidades' })).toBeVisible();
  const row = page.getByRole('list', { name: 'Pedidos das unidades' }).getByRole('listitem').filter({ hasText: 'Barulho fictício' });
  await expect(row).toBeVisible();
  await scanAccessibility(page, 'gestão/pedidos');
  await row.getByRole('button', { name: 'Abrir pedido' }).click();
  await row.getByLabel('Mensagem', { exact: true }).fill('Conferir câmera do corredor.');
  await row.getByLabel('Comentário interno (não aparece para a unidade)').check();
  await row.getByRole('button', { name: 'Registrar comentário interno' }).click();
  await expect(row.getByText('comentário interno, não aparece para a unidade', { exact: false })).toBeVisible();
  const update = row.getByRole('form', { name: 'Atualizar pedido Barulho fictício' });
  await update.getByLabel('Andamento').selectOption('TRIAGED');
  await update.getByLabel('Responsável da equipe').selectOption(PORTER);
  await update.getByLabel('Prazo comunicado à unidade (opcional)').fill(civil(3));
  await update.getByRole('button', { name: 'Salvar andamento' }).click();
  await expect(row.getByText('Pedido atualizado.', { exact: true })).toBeVisible();
  expect(state.updates).toEqual([{ state: 'TRIAGED', assigneeMembershipId: PORTER, dueOn: civil(3), resolution: null, expectedVersion: 0 }]);
  await update.getByLabel('Andamento').selectOption('RESOLVED');
  await expect(update.getByRole('button', { name: 'Salvar andamento' })).toBeDisabled();
  await update.getByLabel('Resolução (aparece para a unidade)').fill('Morador orientado.');
  await update.getByRole('button', { name: 'Salvar andamento' }).click();
  await expect(row.getByText('Pedido concluído; a resolução também foi enviada na conversa.')).toBeVisible();
  expect(state.updates.at(-1)).toEqual({ state: 'RESOLVED', assigneeMembershipId: PORTER, dueOn: civil(3), resolution: 'Morador orientado.', expectedVersion: 1 });
  await expect(row.getByRole('list', { name: 'Conversa do pedido' }).getByText('Resolução: Morador orientado.')).toBeVisible();
});

test('pedidos desligados no ambiente não aparecem para a unidade nem mostram erro técnico', async ({ page }) => {
  await installSyntheticApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Um espaço para a sua rotina.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Pedidos/ })).toHaveCount(0);
  await expect(page.getByText('Esta função ainda não está disponível', { exact: false })).toHaveCount(0);
});

const at = (n: number) => new Date(Date.UTC(2030, 0, 1, 12, n)).toISOString();
const historyMessage = (n: number, role: 'UNIT' | 'STAFF', visibility: 'RESIDENT' | 'INTERNAL' = 'RESIDENT'): Message => ({
  messageId: `97000000-0000-4000-8000-0000000002${String(n).padStart(2, '0')}`, authorRole: role,
  authorName: role === 'UNIT' ? 'PESSOA FICTÍCIA' : 'GESTÃO FICTÍCIA', mine: false, visibility, body: `Mensagem ${n}`, createdAt: at(n) });
const longRequest = (): Request => ({ requestId: REQUEST, unitId: UNIT_A, category: 'MAINTENANCE', title: 'Vazamento antigo', description: 'Água no teto.',
  state: 'IN_PROGRESS', assigneeMembershipId: null, dueOn: null, resolution: null, requesterName: 'PESSOA FICTÍCIA', createdAt: at(0), updatedAt: at(6),
  resolvedAt: null, closedAt: null, version: 1, messages: [historyMessage(1, 'UNIT'), historyMessage(2, 'STAFF'), historyMessage(3, 'STAFF', 'INTERNAL'),
    historyMessage(4, 'UNIT'), historyMessage(5, 'STAFF'), historyMessage(6, 'UNIT')] });

test('conversa longa: a unidade carrega mensagens anteriores, sem comentário interno, e falha oferece nova tentativa', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installRequestsApi(page);
  windowSize = 2;
  try {
    state.items.push(longRequest());
    await signIn(page);
    await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
    await page.getByRole('button', { name: 'Ver conversa' }).click();
    const conversation = page.getByRole('list', { name: 'Conversa do pedido' });
    await expect(conversation.getByRole('listitem')).toHaveCount(2);
    await expect(page.getByText('A conversa mostra as mensagens mais recentes.')).toBeVisible();
    state.failNextOlder = true;
    await page.getByRole('button', { name: 'Carregar mensagens anteriores' }).click();
    await expect(page.getByRole('button', { name: 'Tentar novamente' })).toBeVisible();
    await expect(conversation.getByRole('listitem')).toHaveCount(2);
    await page.getByRole('button', { name: 'Tentar novamente' }).click();
    await expect(conversation.getByRole('listitem')).toHaveCount(4);
    await expect(page.getByRole('button', { name: 'Tentar novamente' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Carregar mensagens anteriores' }).click();
    await expect(conversation.getByRole('listitem')).toHaveCount(5);
    await expect(page.getByRole('button', { name: 'Carregar mensagens anteriores' })).toHaveCount(0);
    await expect(page.getByText('A conversa mostra as mensagens mais recentes.')).toHaveCount(0);
    await expect(conversation.getByRole('listitem').locator('p')).toHaveText(['Mensagem 1', 'Mensagem 2', 'Mensagem 4', 'Mensagem 5', 'Mensagem 6']);
    await expect(page.getByText('Mensagem 3')).toHaveCount(0);
    await scanAccessibility(page, 'conversa longa da unidade');
    expect(state.olderCalls).toEqual(['unit:97000000-0000-4000-8000-000000000205', 'unit:97000000-0000-4000-8000-000000000205',
      'unit:97000000-0000-4000-8000-000000000202']);
  } finally { windowSize = Number.POSITIVE_INFINITY; }
});

test('conversa longa na gestão: histórico completo, inclusive o comentário interno', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installRequestsApi(page);
  windowSize = 2;
  try {
    state.items.push(longRequest());
    await signIn(page);
    await page.goto(`/gestao/${TENANT_A}/pedidos`);
    const row = page.getByRole('list', { name: 'Pedidos das unidades' }).getByRole('listitem').filter({ hasText: 'Vazamento antigo' });
    await row.getByRole('button', { name: 'Abrir pedido' }).click();
    const conversation = row.getByRole('list', { name: 'Conversa do pedido' });
    await expect(conversation.getByRole('listitem')).toHaveCount(2);
    for (const count of [4, 6]) {
      await row.getByRole('button', { name: 'Carregar mensagens anteriores' }).click();
      await expect(conversation.getByRole('listitem')).toHaveCount(count);
    }
    await expect(row.getByRole('button', { name: 'Carregar mensagens anteriores' })).toHaveCount(0);
    await expect(conversation.getByText('comentário interno, não aparece para a unidade', { exact: false })).toHaveCount(1);
    expect(state.olderCalls).toEqual(['staff:97000000-0000-4000-8000-000000000205', 'staff:97000000-0000-4000-8000-000000000203']);
    await scanAccessibility(page, 'conversa longa da gestão');
  } finally { windowSize = Number.POSITIVE_INFINITY; }
});
