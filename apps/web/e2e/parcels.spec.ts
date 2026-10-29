import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const PARCEL = '40000000-0000-4000-8000-0000000000e1';
const MEMBER = '50000000-0000-4000-8000-0000000000f1';
const DEPENDENT = '50000000-0000-4000-8000-0000000000f2';
interface SyntheticParcel {
  parcelId: string; unitId: string; unitLabel: string; blockName: string | null; quantity: number; description: string; storageLocation: string;
  status: 'RECEIVED' | 'DELIVERED' | 'VOIDED'; receivedAt: string; version: number;
  handoff: null | { recipientName: string; deliveredAt: string; evidenceKind: 'ASSISTED' }; voidReason: string | null; voidedAt: string | null;
}
interface ParcelState {
  parcels: SyntheticParcel[]; receives: { key: string; body: unknown }[]; deliveries: { key: string; body: unknown }[]; voids: { key: string; body: unknown }[];
  lists: string[]; deliveredElsewhere: boolean; suspectDuplicate: boolean; loseNextReceive: boolean; failLists: boolean;
}

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000e' });
}
const waiting = (overrides: Partial<SyntheticParcel> = {}): SyntheticParcel => ({ parcelId: PARCEL, unitId: UNIT_A, unitLabel: '101 — Sintética A',
  blockName: 'Bloco 1', quantity: 1, description: 'Envelope', storageLocation: 'Armário 3', status: 'RECEIVED', receivedAt: '2030-01-01T12:00:00.000Z',
  version: 0, handoff: null, voidReason: null, voidedAt: null, ...overrides });

/** Identidade com portaria no condomínio A e API sintética das encomendas, registradas por último (precedência). */
async function installParcelsApi(page: Page, api: ApiState, concierge: boolean): Promise<ParcelState> {
  const state: ParcelState = { parcels: [], receives: [], deliveries: [], voids: [], lists: [], deliveredElsewhere: false, suspectDuplicate: false, loseNextReceive: false, failLists: false };
  if (concierge) {
    await page.route('**/v1/me', async (route) => {
      if (!api.authenticated) { await route.fallback(); return; }
      await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
        tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }] });
    });
  }
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    if (path === '/concierge/units') {
      await json(route, 200, [{ unitId: UNIT_A, label: '101 — Sintética A', blockName: 'Bloco 1' }]);
      return;
    }
    if (path === '/parcels' && request.method() === 'GET') {
      state.lists.push(url.search);
      if (state.failLists) { await problem(route, 503, 'PARCEL_DEPENDENCY_UNAVAILABLE'); return; }
      const status = url.searchParams.get('status');
      const unit = url.searchParams.get('unitId');
      await json(route, 200, { parcels: state.parcels.filter((item) => (!status || item.status === status) && (!unit || item.unitId === unit)), truncated: false });
      return;
    }
    if (path === '/parcels' && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { unitId: string; quantity: number; description: string | null; storageLocation: string; confirmDuplicate?: boolean };
      state.receives.push({ key, body });
      if (state.suspectDuplicate && !body.confirmDuplicate) { await problem(route, 409, 'PARCEL_DUPLICATE_SUSPECTED'); return; }
      const previous = state.receives.filter((item) => item.key === key).length > 1;
      if (!previous) state.parcels.unshift(waiting({ parcelId: `40000000-0000-4000-8000-${String(state.receives.length).padStart(12, '0')}`,
        unitId: body.unitId, quantity: body.quantity, description: body.description ?? '', storageLocation: body.storageLocation }));
      if (state.loseNextReceive) { state.loseNextReceive = false; await route.abort('connectionreset'); return; }
      const created = previous ? state.parcels.find((item) => item.storageLocation === body.storageLocation)! : state.parcels[0]!;
      await json(route, 201, created, { 'Idempotency-Replayed': String(previous) });
      return;
    }
    if (path === `/parcels/${PARCEL}/recipients`) {
      await json(route, 200, [{ membershipId: DEPENDENT, displayName: 'Dependente Fictício', role: 'DEPENDENT' },
        { membershipId: MEMBER, displayName: 'Pessoa Fictícia', role: 'RESIDENT' }]);
      return;
    }
    if (path === `/parcels/${PARCEL}/handoff` && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { recipientMembershipId: string; identityChecked: boolean; expectedVersion: number };
      state.deliveries.push({ key, body });
      const parcel = state.parcels.find((item) => item.parcelId === PARCEL)!;
      if (state.deliveredElsewhere) {
        Object.assign(parcel, { status: 'DELIVERED', version: 1, handoff: { recipientName: 'Outra Pessoa', deliveredAt: '2030-01-01T14:00:00.000Z', evidenceKind: 'ASSISTED' } });
        await problem(route, 409, 'PARCEL_ALREADY_DELIVERED');
        return;
      }
      const name = body.recipientMembershipId === MEMBER ? 'Pessoa Fictícia' : 'Dependente Fictício';
      Object.assign(parcel, { status: 'DELIVERED', version: 1, handoff: { recipientName: name, deliveredAt: '2030-01-01T15:30:00.000Z', evidenceKind: 'ASSISTED' } });
      await json(route, 200, parcel, { 'Idempotency-Replayed': 'false' });
      return;
    }
    if (path === `/parcels/${PARCEL}/void` && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { reason: string; expectedVersion: number };
      state.voids.push({ key, body });
      const parcel = state.parcels.find((item) => item.parcelId === PARCEL)!;
      Object.assign(parcel, { status: 'VOIDED', version: 1, voidReason: body.reason, voidedAt: '2030-01-01T13:00:00.000Z' });
      await json(route, 200, parcel, { 'Idempotency-Replayed': 'false' });
      return;
    }
    if (path === `/units/${UNIT_A}/parcels` && request.method() === 'GET') {
      const status = url.searchParams.get('status');
      await json(route, 200, { parcels: state.parcels.filter((item) => item.status !== 'VOIDED' && (!status || item.status === status)), truncated: false });
      return;
    }
    await route.fallback();
  });
  return state;
}

async function openConcierge(page: Page) {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installParcelsApi(page, api, true);
  await signIn(page);
  return state;
}

test('portaria registra recebimento uma única vez e entrega à pessoa escolhida com documento conferido', async ({ page }) => {
  const state = await openConcierge(page);
  await page.goto('/contextos');
  await page.getByRole('link', { name: 'Abrir portaria · Condomínio Sintético A' }).click();
  await expect(page.getByRole('heading', { name: 'Receber encomenda' })).toBeVisible();
  const form = page.getByRole('form', { name: 'Receber encomenda' });
  await form.getByLabel('Unidade', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Volumes').fill('2');
  await form.getByLabel('Local de guarda').fill('  Prateleira B ');
  await form.getByRole('button', { name: 'Registrar recebimento' }).click();
  await expect(page.getByText('Encomenda registrada para Bloco 1 · 101 — Sintética A: 2 volumes em Prateleira B.', { exact: false })).toBeVisible();
  expect(state.receives).toHaveLength(1);
  expect(state.receives[0]!.body).toEqual({ unitId: UNIT_A, quantity: 2, description: null, storageLocation: 'Prateleira B' });
  state.parcels[0]!.parcelId = PARCEL;
  await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
  const row = page.getByRole('list', { name: 'Encomendas' }).getByRole('listitem').filter({ hasText: 'Prateleira B' });
  await expect(row.getByText('A retirar', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: 'Registrar retirada' }).click();
  const deliver = page.getByRole('form', { name: 'Registrar retirada de Bloco 1 · 101 — Sintética A' });
  await expect(deliver.getByRole('button', { name: 'Confirmar retirada' })).toBeDisabled();
  await deliver.getByRole('radio', { name: 'Pessoa Fictícia · Morador(a)' }).check();
  await expect(deliver.getByRole('radio', { name: 'Dependente Fictício · Dependente autorizado(a)' })).toBeVisible();
  await expect(deliver.getByRole('button', { name: 'Confirmar retirada' })).toBeDisabled();
  await deliver.getByLabel('Conferi o documento da pessoa escolhida').check();
  await deliver.getByRole('button', { name: 'Confirmar retirada' }).click();
  await expect(page.getByText('Retirada registrada: Pessoa Fictícia retirou a encomenda de Bloco 1 · 101 — Sintética A', { exact: false })).toBeVisible();
  expect(state.deliveries.map((item) => item.body)).toEqual([{ recipientMembershipId: MEMBER, identityChecked: true, expectedVersion: 0 }]);
  await expect(page.getByText('Não há encomenda aguardando retirada neste filtro.')).toBeVisible();
  await page.setViewportSize({ width: 360, height: 780 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('segunda retirada recebe "já retirada" sem sucesso falso e a lista mostra o estado real', async ({ page }) => {
  const state = await openConcierge(page);
  state.parcels.push(waiting());
  state.deliveredElsewhere = true;
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  const row = page.getByRole('list', { name: 'Encomendas' }).getByRole('listitem').filter({ hasText: 'Armário 3' });
  await row.getByRole('button', { name: 'Registrar retirada' }).click();
  await page.getByRole('radio', { name: 'Pessoa Fictícia · Morador(a)' }).check();
  await page.getByLabel('Conferi o documento da pessoa escolhida').check();
  await page.getByRole('button', { name: 'Confirmar retirada' }).click();
  await expect(page.getByText('Esta encomenda já foi retirada.')).toBeVisible();
  await expect(page.getByText('Retirada registrada', { exact: false })).toHaveCount(0);
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  await expect(page.getByText('Não há encomenda aguardando retirada neste filtro.')).toBeVisible();
});

test('duplicidade suspeita pede confirmação explícita e resultado incerto trava o formulário na mesma chave', async ({ page }) => {
  const state = await openConcierge(page);
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  const form = page.getByRole('form', { name: 'Receber encomenda' });
  await form.getByLabel('Unidade', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Local de guarda').fill('Armário 7');
  state.suspectDuplicate = true;
  await form.getByRole('button', { name: 'Registrar recebimento' }).click();
  await expect(form.getByText('Há uma encomenda igual registrada para esta unidade nos últimos 10 minutos.', { exact: false })).toBeVisible();
  await expect(form.getByRole('button', { name: 'Registrar recebimento' })).toBeDisabled();
  await form.getByRole('button', { name: 'É outra encomenda: registrar' }).click();
  await expect(page.getByText('Encomenda registrada para Bloco 1 · 101 — Sintética A: 1 volume em Armário 7.', { exact: false })).toBeVisible();
  expect(state.receives.map((item) => item.body)).toEqual([
    { unitId: UNIT_A, quantity: 1, description: null, storageLocation: 'Armário 7' },
    { unitId: UNIT_A, quantity: 1, description: null, storageLocation: 'Armário 7', confirmDuplicate: true },
  ]);
  expect(state.receives[0]!.key).not.toBe(state.receives[1]!.key);
  // Resposta perdida: o formulário trava; repetir usa a mesma chave e o servidor devolve o registro já feito.
  state.suspectDuplicate = false;
  state.loseNextReceive = true;
  await form.getByLabel('Unidade', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Local de guarda').fill('Armário 8');
  await form.getByRole('button', { name: 'Registrar recebimento' }).click();
  await expect(form.getByText('O resultado do último envio não foi confirmado', { exact: false })).toBeVisible();
  await expect(form.getByLabel('Local de guarda')).toBeDisabled();
  await form.getByRole('button', { name: 'Tentar confirmar o mesmo envio' }).click();
  await expect(page.getByText('Este recebimento já estava registrado com os mesmos dados. Nada novo foi criado.')).toBeVisible();
  expect(state.receives.at(-1)!.key).toBe(state.receives.at(-2)!.key);
  expect(state.parcels.filter((item) => item.storageLocation === 'Armário 8')).toHaveLength(1);
});

test('repetir um recebimento incerto confirmado como outra encomenda mantém a confirmação e a chave', async ({ page }) => {
  const state = await openConcierge(page);
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  const form = page.getByRole('form', { name: 'Receber encomenda' });
  await form.getByLabel('Unidade', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Local de guarda').fill('Armário 9');
  state.suspectDuplicate = true;
  await form.getByRole('button', { name: 'Registrar recebimento' }).click();
  await expect(form.getByRole('button', { name: 'É outra encomenda: registrar' })).toBeVisible();
  // A confirmação de duplicidade é gravada, mas a resposta se perde.
  state.loseNextReceive = true;
  await form.getByRole('button', { name: 'É outra encomenda: registrar' }).click();
  await expect(form.getByText('O resultado do último envio não foi confirmado', { exact: false })).toBeVisible();
  await form.getByRole('button', { name: 'Tentar confirmar o mesmo envio' }).click();
  await expect(page.getByText('Este recebimento já estava registrado com os mesmos dados. Nada novo foi criado.')).toBeVisible();
  const confirmations = state.receives.filter((item) => item.body.storageLocation === 'Armário 9' && item.body.confirmDuplicate === true);
  expect(confirmations).toHaveLength(2);
  expect(confirmations[1]!.key).toBe(confirmations[0]!.key);
  expect(state.receives.filter((item) => item.body.storageLocation === 'Armário 9' && !item.body.confirmDuplicate)).toHaveLength(1);
  expect(state.parcels.filter((item) => item.storageLocation === 'Armário 9')).toHaveLength(1);
});

test('atualização que falha mantém a fila na tela, mas avisa que ela pode estar desatualizada', async ({ page }) => {
  const state = await openConcierge(page);
  state.parcels.push(waiting());
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  const queue = page.getByRole('list', { name: 'Encomendas' });
  await expect(queue.getByText('Armário 3', { exact: false })).toBeVisible();
  await expect(page.getByText('O que aparece pode estar desatualizado', { exact: false })).toHaveCount(0);
  state.failLists = true;
  await page.getByRole('region', { name: 'Encomendas a retirar' }).getByRole('button', { name: 'Atualizar' }).click();
  await expect(page.getByText('O que aparece pode estar desatualizado', { exact: false })).toBeVisible();
  await expect(queue.getByText('Armário 3', { exact: false })).toBeVisible();
  state.failLists = false;
  await page.getByRole('button', { name: 'Tentar atualizar de novo' }).click();
  await expect(page.getByText('O que aparece pode estar desatualizado', { exact: false })).toHaveCount(0);
});

test('registro indevido é anulado com motivo e a fila filtra por unidade', async ({ page }) => {
  const state = await openConcierge(page);
  state.parcels.push(waiting());
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  await page.getByRole('search', { name: 'Filtrar encomendas' }).getByLabel('Unidade').selectOption(UNIT_A);
  await expect.poll(() => state.lists.some((search) => search.includes(`unitId=${UNIT_A}`))).toBe(true);
  const row = page.getByRole('list', { name: 'Encomendas' }).getByRole('listitem').filter({ hasText: 'Armário 3' });
  await row.getByRole('button', { name: 'Anular registro' }).click();
  const form = page.getByRole('form', { name: 'Anular registro de Bloco 1 · 101 — Sintética A' });
  await expect(form.getByRole('button', { name: 'Anular registro' })).toBeDisabled();
  await form.getByLabel('Motivo (fica na auditoria)').fill('Registrada na unidade errada');
  await form.getByRole('button', { name: 'Anular registro' }).click();
  await expect(page.getByText('Registro anulado: encomenda de Bloco 1 · 101 — Sintética A', { exact: false })).toBeVisible();
  expect(state.voids.map((item) => item.body)).toEqual([{ reason: 'Registrada na unidade errada', expectedVersion: 0 }]);
  await page.getByLabel('Situação').selectOption('VOIDED');
  await expect(page.getByRole('list', { name: 'Encomendas' }).getByText('Anulada em', { exact: false })).toContainText('Registrada na unidade errada');
});

test('morador consulta encomendas da unidade com local e retirada', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installParcelsApi(page, api, false);
  state.parcels.push(
    waiting({ blockName: null, description: 'Caixa média', storageLocation: 'Prateleira B' }),
    waiting({ parcelId: '40000000-0000-4000-8000-0000000000e2', blockName: null, quantity: 2, description: '', storageLocation: 'Armário 1',
      status: 'DELIVERED', receivedAt: '2029-12-30T12:00:00.000Z', version: 1,
      handoff: { recipientName: 'Pessoa Fictícia', deliveredAt: '2029-12-30T18:00:00.000Z', evidenceKind: 'ASSISTED' } }),
    waiting({ parcelId: '40000000-0000-4000-8000-0000000000e3', storageLocation: 'Armário anulado', status: 'VOIDED', version: 1,
      voidReason: 'Unidade errada', voidedAt: '2030-01-01T13:00:00.000Z' }));
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/encomendas`);
  await expect(page.getByRole('heading', { name: 'Encomendas da unidade' })).toBeVisible();
  const list = page.getByRole('list', { name: 'Encomendas a retirar' });
  await expect(list.getByText('guardada em Prateleira B', { exact: false })).toBeVisible();
  await expect(list.getByText('Caixa média')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Encomendas retiradas' }).getByText('Retirada por Pessoa Fictícia', { exact: false })).toBeVisible();
  await expect(page.getByText('Armário anulado', { exact: false })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Encomendas' }).first()).toHaveAttribute('aria-current', 'page');
  // Em tela estreita as quatro abas da unidade cabem sem rolagem horizontal nem rótulo cortado.
  await page.setViewportSize({ width: 360, height: 780 });
  const tabs = page.getByRole('navigation', { name: 'Navegação da unidade' });
  await expect(tabs.getByRole('link')).toHaveCount(4);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await tabs.evaluate((nav) => [...nav.querySelectorAll('a')].every((link) => link.scrollWidth <= link.clientWidth))).toBe(true);
});
