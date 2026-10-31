import { test, expect, type Page, type Route } from '@playwright/test';
import { TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';

test.use({ timezoneId: 'UTC' });

const minutes = (value: number) => new Date(Date.now() + value * 60_000).toISOString();
const WAITING = '93000000-0000-4000-8000-000000000001';
const DELIVERED = '93000000-0000-4000-8000-000000000002';
const VOIDED = '93000000-0000-4000-8000-000000000003';
const OFFER = '93000000-0000-4000-8000-000000000004';
const REQUEST = '93000000-0000-4000-8000-000000000005';
const MOVE = '93000000-0000-4000-8000-000000000006';

interface Notice {
  notificationId: string; unitId: string; kind: 'ARRIVAL_WAITING' | 'PARCEL_RECEIVED' | 'WAITLIST_OFFERED' | 'REQUEST_UPDATED' | 'MOVE_DECIDED'; resourceId: string; resourceState: string | null;
  state: 'AVAILABLE' | 'READ_IN_APP'; createdAt: string; readAt: string | null;
}
async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}

/** Caixa sintética da unidade A, registrada depois da API base (precedência). */
async function installInbox(page: Page) {
  const state = {
    opens: [] as { id: string; csrf: string | undefined }[], lists: 0, hold: null as Promise<void> | null,
    items: [
      { notificationId: WAITING, unitId: UNIT_A, kind: 'ARRIVAL_WAITING', resourceId: '80000000-0000-4000-8000-0000000000b1', resourceState: 'WAITING',
        state: 'AVAILABLE', createdAt: minutes(-1), readAt: null },
      { notificationId: DELIVERED, unitId: UNIT_A, kind: 'PARCEL_RECEIVED', resourceId: '40000000-0000-4000-8000-0000000000d1', resourceState: 'DELIVERED',
        state: 'READ_IN_APP', createdAt: minutes(-120), readAt: minutes(-90) },
      { notificationId: VOIDED, unitId: UNIT_A, kind: 'PARCEL_RECEIVED', resourceId: '40000000-0000-4000-8000-0000000000d2', resourceState: null,
        state: 'AVAILABLE', createdAt: minutes(-60), readAt: null },
    ] as Notice[],
  };
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/notifications`, async (route) => {
    state.lists += 1;
    await json(route, 200, { items: state.items, unread: state.items.filter((item) => item.state === 'AVAILABLE').length });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/notifications/*/open`, async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!;
    state.opens.push({ id, csrf: route.request().headers()['x-csrf-token'] });
    if (state.hold) await state.hold;
    const item = state.items.find((candidate) => candidate.notificationId === id)!;
    if (item.state === 'AVAILABLE') { item.state = 'READ_IN_APP'; item.readAt = new Date().toISOString(); }
    await json(route, 200, item);
  });
  return state;
}

test('caixa da unidade lista avisos sem marcar leitura; só abrir registra a leitura e leva ao detalhe', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installInbox(page);
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  const inbox = page.getByRole('list', { name: 'Avisos da unidade' });
  await expect(page.getByRole('heading', { name: 'Caixa da unidade · 2 novos' })).toBeVisible();
  await expect(page.getByText('Aparecer aqui não quer dizer que alguém leu', { exact: false })).toBeVisible();
  const waiting = inbox.getByRole('listitem').filter({ hasText: 'Chegada aguardando resposta da unidade' });
  await expect(waiting.getByText('Não aberto')).toBeVisible();
  await expect(waiting.getByText('Situação atual: aguardando resposta.')).toBeVisible();
  const delivered = inbox.getByRole('listitem').filter({ hasText: 'retirada registrada' });
  await expect(delivered.getByText('aberto por você em', { exact: false })).toBeVisible();
  await expect(inbox.getByRole('listitem').filter({ hasText: 'Este registro não está mais disponível para o seu vínculo.' })).toHaveCount(1);
  // Listar e atualizar não abre nada.
  await page.getByRole('button', { name: 'Atualizar', exact: true }).first().click();
  await expect.poll(() => state.lists).toBeGreaterThan(1);
  expect(state.opens).toEqual([]);
  await waiting.getByRole('button', { name: 'Abrir e ver acessos' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/acessos$`));
  expect(state.opens.map((item) => item.id)).toEqual([WAITING]);
  expect(state.opens[0]!.csrf).toBeTruthy();
});

test('oferta da fila de espera aparece só para quem entrou e leva às reservas para aceitar', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installInbox(page);
  state.items = [{ notificationId: OFFER, unitId: UNIT_A, kind: 'WAITLIST_OFFERED', resourceId: '82000000-0000-4000-8000-000000000001',
    resourceState: 'OFFERED', state: 'AVAILABLE', createdAt: minutes(-1), readAt: null }];
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  const offer = page.getByRole('list', { name: 'Avisos da unidade' }).getByRole('listitem').filter({ hasText: 'Vaga da fila de espera' });
  await expect(offer.getByText('Situação atual: oferta aguardando o seu aceite, com prazo.')).toBeVisible();
  await offer.getByRole('button', { name: 'Abrir e ver reservas' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/reservas$`));
  expect(state.opens.map((item) => item.id)).toEqual([OFFER]);
});

test('resposta do pedido e decisão da mudança abrem na própria página e registram a leitura', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installInbox(page);
  state.items = [
    { notificationId: REQUEST, unitId: UNIT_A, kind: 'REQUEST_UPDATED', resourceId: '84000000-0000-4000-8000-000000000001', resourceState: 'WAITING_RESIDENT',
      state: 'AVAILABLE', createdAt: minutes(-2), readAt: null },
    { notificationId: MOVE, unitId: UNIT_A, kind: 'MOVE_DECIDED', resourceId: '86000000-0000-4000-8000-000000000001', resourceState: 'APPROVED',
      state: 'AVAILABLE', createdAt: minutes(-1), readAt: null },
  ];
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  const inbox = page.getByRole('list', { name: 'Avisos da unidade' });
  const request = inbox.getByRole('listitem').filter({ hasText: 'Atualização no pedido da unidade' });
  await expect(request.getByText('Situação atual: aguardando a resposta da unidade.')).toBeVisible();
  const move = inbox.getByRole('listitem').filter({ hasText: 'Decisão sobre a mudança' });
  await expect(move.getByText('Situação atual: mudança aprovada.')).toBeVisible();
  await request.getByRole('button', { name: 'Abrir e ver pedidos' }).click();
  await expect(page.getByText('Aviso aberto. O pedido está na seção “Pedidos”, nesta página.')).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/inicio$`));
  await move.getByRole('button', { name: 'Abrir e ver mudanças' }).click();
  await expect(page.getByText('Aviso aberto. A mudança está na seção “Mudança e entrega de móveis”, nesta página.')).toBeVisible();
  expect(state.opens.map((item) => item.id)).toEqual([REQUEST, MOVE]);
});

test('abertura que termina depois de a pessoa sair da página não a leva de volta para outra tela', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installInbox(page);
  let release!: () => void;
  state.hold = new Promise<void>((resolve) => { release = resolve; });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  const waiting = page.getByRole('list', { name: 'Avisos da unidade' }).getByRole('listitem').filter({ hasText: 'Chegada aguardando resposta da unidade' });
  await waiting.getByRole('button', { name: 'Abrir e ver acessos' }).click();
  await expect.poll(() => state.opens.length).toBe(1);
  // Antes da resposta, a pessoa vai para as reservas pela navegação da unidade.
  await page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Reservas' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/reservas$`));
  release();
  await page.waitForTimeout(500);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/reservas$`));
});

test('caixa desligada no ambiente não aparece nem mostra erro técnico', async ({ page }) => {
  await installSyntheticApi(page);
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Próximas reservas da unidade' })).toBeVisible();
  await expect(page.getByRole('heading', { name: /Caixa da unidade/ })).toHaveCount(0);
  await expect(page.getByText('SYNTHETIC_TEST_ROUTE_NOT_FOUND')).toHaveCount(0);
});
