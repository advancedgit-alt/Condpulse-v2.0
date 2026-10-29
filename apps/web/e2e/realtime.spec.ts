import { test, expect, type Page, type Route } from '@playwright/test';
import { TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';

test.use({ timezoneId: 'UTC' });

const EVENTS = `**/v1/tenants/${TENANT_A}/units/${UNIT_A}/events`;
const NEW_PARCEL = '93000000-0000-4000-8000-0000000000e1';

function stream(...events: [string, unknown][]) {
  return events.map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`).join('');
}
async function sse(route: Route, body: string, retryMillis = 600_000) {
  await route.fulfill({ status: 200, headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' },
    body: `retry: ${retryMillis}\n\n${body}` });
}

/** Caixa sintética que começa vazia; o canal de eventos é registrado por teste. */
async function installInbox(page: Page) {
  const state = { lists: 0, items: [] as unknown[] };
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/notifications`, async (route) => {
    state.lists += 1;
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify({ items: state.items, unread: state.items.length }) });
  });
  return state;
}
const parcelNotice = () => ({ notificationId: NEW_PARCEL, unitId: UNIT_A, kind: 'PARCEL_RECEIVED', resourceId: '40000000-0000-4000-8000-0000000000e1',
  resourceState: 'RECEIVED', state: 'AVAILABLE', createdAt: new Date().toISOString(), readAt: null });

test('aviso novo aparece sozinho: o canal só diz o assunto e a tela consulta a caixa de novo', async ({ page }) => {
  await installSyntheticApi(page);
  const inbox = await installInbox(page);
  const opened: string[] = [];
  await page.route(EVENTS, async (route) => {
    opened.push(route.request().headers()['accept'] ?? '');
    await expect.poll(() => inbox.lists).toBeGreaterThan(0);
    inbox.items = [parcelNotice()];
    await sse(route, stream(['ready', { topics: ['notifications'] }], ['changed', { topics: ['notifications'] }]));
  });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect(page.getByText('Encomenda recebida na portaria')).toBeVisible();
  expect(inbox.lists).toBeGreaterThan(1);
  expect(opened[0]).toContain('text/event-stream');
});

test('reconectar consulta o estado de novo, sem esperar um evento de mudança', async ({ page }) => {
  await installSyntheticApi(page);
  const inbox = await installInbox(page);
  let connections = 0;
  await page.route(EVENTS, async (route) => {
    connections += 1;
    if (connections === 2) inbox.items = [parcelNotice()];
    // Fim normal do canal (prazo de vida): o navegador reconecta depois do intervalo informado.
    await sse(route, stream(['ready', { topics: [] }], ['end', { reason: 'timeout' }]), connections === 1 ? 300 : 600_000);
  });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect.poll(() => connections).toBe(2);
  await expect(page.getByText('Encomenda recebida na portaria')).toBeVisible();
});

test('acesso revogado encerra o canal e confere a identidade de novo, sem reconectar', async ({ page }) => {
  await installSyntheticApi(page);
  await installInbox(page);
  let identity = 0;
  let connections = 0;
  let atEnd = Number.POSITIVE_INFINITY;
  await page.route('**/v1/me', async (route) => { identity += 1; await route.fallback(); });
  await page.route(EVENTS, async (route) => {
    connections += 1;
    atEnd = identity;
    await sse(route, stream(['ready', { topics: [] }], ['end', { reason: 'revoked' }]), 300);
  });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect.poll(() => connections).toBe(1);
  await expect.poll(() => identity).toBeGreaterThan(atEnd);
  await page.waitForTimeout(1500);
  expect(connections).toBe(1);
});

test('função desligada (404) não mostra erro nem insiste: uma tentativa e espera longa', async ({ page }) => {
  await installSyntheticApi(page);
  const inbox = await installInbox(page);
  let connections = 0;
  await page.route(EVENTS, async (route) => {
    connections += 1;
    await route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify({ code: 'SYNTHETIC_TEST_ROUTE_NOT_FOUND' }) });
  });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: /Caixa da unidade/ })).toBeVisible();
  await page.waitForTimeout(2000);
  expect(connections).toBe(1);
  expect(inbox.lists).toBe(1);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('fora da unidade (gestão, troca de contexto) o canal não é aberto', async ({ page }) => {
  await installSyntheticApi(page);
  let connections = 0;
  await page.route('**/events', async (route) => { connections += 1; await route.fallback(); });
  await signIn(page);
  await page.goto('/contextos');
  await expect(page.getByRole('heading', { name: 'Escolha seu espaço.' })).toBeVisible();
  await page.waitForTimeout(1000);
  expect(connections).toBe(0);
});
