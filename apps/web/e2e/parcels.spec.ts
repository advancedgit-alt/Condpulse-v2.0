import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const PARCEL = '40000000-0000-4000-8000-0000000000e1';
interface SyntheticParcel {
  parcelId: string; unitId: string; unitLabel: string; blockName: string | null; quantity: number; description: string; storageLocation: string;
  status: 'RECEIVED' | 'DELIVERED'; receivedAt: string; version: number;
  handoff: null | { recipientName: string; deliveredAt: string; evidenceKind: 'ASSISTED' };
}
interface ParcelState { parcels: SyntheticParcel[]; receives: { key: string; body: unknown }[]; deliveries: { key: string; body: unknown }[]; deliveredElsewhere: boolean }

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000e' });
}

/** Identidade com portaria no condomínio A e API sintética das encomendas, registradas por último (precedência). */
async function installParcelsApi(page: Page, api: ApiState, concierge: boolean): Promise<ParcelState> {
  const state: ParcelState = { parcels: [], receives: [], deliveries: [], deliveredElsewhere: false };
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
      const status = url.searchParams.get('status');
      await json(route, 200, { parcels: state.parcels.filter((item) => !status || item.status === status), truncated: false });
      return;
    }
    if (path === '/parcels' && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { unitId: string; quantity: number; description: string | null; storageLocation: string };
      state.receives.push({ key, body });
      const previous = state.receives.filter((item) => item.key === key).length > 1;
      if (!previous) state.parcels.unshift({ parcelId: PARCEL, unitId: body.unitId, unitLabel: '101 — Sintética A', blockName: 'Bloco 1',
        quantity: body.quantity, description: body.description ?? '', storageLocation: body.storageLocation, status: 'RECEIVED',
        receivedAt: '2030-01-01T12:00:00.000Z', version: 0, handoff: null });
      await json(route, 201, state.parcels[0], { 'Idempotency-Replayed': String(previous) });
      return;
    }
    if (path === `/parcels/${PARCEL}/handoff` && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { recipientName: string; identityChecked: boolean; expectedVersion: number };
      state.deliveries.push({ key, body });
      const parcel = state.parcels.find((item) => item.parcelId === PARCEL)!;
      if (state.deliveredElsewhere) {
        Object.assign(parcel, { status: 'DELIVERED', version: 1, handoff: { recipientName: 'Outra Pessoa', deliveredAt: '2030-01-01T14:00:00.000Z', evidenceKind: 'ASSISTED' } });
        await problem(route, 409, 'PARCEL_ALREADY_DELIVERED');
        return;
      }
      Object.assign(parcel, { status: 'DELIVERED', version: 1,
        handoff: { recipientName: body.recipientName, deliveredAt: '2030-01-01T15:30:00.000Z', evidenceKind: 'ASSISTED' } });
      await json(route, 200, parcel, { 'Idempotency-Replayed': 'false' });
      return;
    }
    if (path === `/units/${UNIT_A}/parcels` && request.method() === 'GET') {
      await json(route, 200, { parcels: state.parcels, truncated: false });
      return;
    }
    await route.fallback();
  });
  return state;
}

test('portaria registra recebimento uma única vez e entrega com documento conferido', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installParcelsApi(page, api, true);
  await signIn(page);
  await page.goto('/contextos');
  await page.getByRole('link', { name: 'Abrir portaria · Condomínio Sintético A' }).click();
  await expect(page.getByRole('heading', { name: 'Receber encomenda' })).toBeVisible();
  const form = page.getByRole('form', { name: 'Receber encomenda' });
  await form.getByLabel('Unidade', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Volumes').fill('2');
  await form.getByLabel('Local de guarda').fill('  Prateleira B ');
  await form.getByRole('button', { name: 'Registrar recebimento' }).click();
  await expect(page.getByText('Encomenda registrada para Bloco 1 · 101 — Sintética A: 2 volumes em Prateleira B.', { exact: false })).toBeVisible();
  expect(state.receives).toHaveLength(1);
  expect(state.receives[0]!.body).toEqual({ unitId: UNIT_A, quantity: 2, description: null, storageLocation: 'Prateleira B' });
  const row = page.getByRole('list', { name: 'Encomendas' }).getByRole('listitem').filter({ hasText: 'Prateleira B' });
  await expect(row.getByText('A retirar', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: 'Registrar retirada' }).click();
  const deliver = page.getByRole('form', { name: 'Registrar retirada de Bloco 1 · 101 — Sintética A' });
  await deliver.getByLabel('Nome de quem retira').fill('Pessoa  Fictícia');
  await expect(deliver.getByRole('button', { name: 'Confirmar retirada' })).toBeDisabled();
  await deliver.getByLabel('Conferi o documento da pessoa que retira').check();
  await deliver.getByRole('button', { name: 'Confirmar retirada' }).click();
  await expect(page.getByText('Retirada registrada: Pessoa Fictícia retirou a encomenda de Bloco 1 · 101 — Sintética A', { exact: false })).toBeVisible();
  expect(state.deliveries.map((item) => item.body)).toEqual([{ recipientName: 'Pessoa Fictícia', identityChecked: true, expectedVersion: 0 }]);
  await expect(page.getByText('Não há encomenda aguardando retirada neste filtro.')).toBeVisible();
  await page.setViewportSize({ width: 360, height: 780 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('segunda retirada recebe "já retirada" sem sucesso falso e a lista mostra o estado real', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installParcelsApi(page, api, true);
  state.parcels.push({ parcelId: PARCEL, unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: 'Bloco 1', quantity: 1, description: 'Envelope',
    storageLocation: 'Armário 3', status: 'RECEIVED', receivedAt: '2030-01-01T12:00:00.000Z', version: 0, handoff: null });
  state.deliveredElsewhere = true;
  await signIn(page);
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  const row = page.getByRole('list', { name: 'Encomendas' }).getByRole('listitem').filter({ hasText: 'Armário 3' });
  await row.getByRole('button', { name: 'Registrar retirada' }).click();
  await page.getByLabel('Nome de quem retira').fill('Pessoa Fictícia');
  await page.getByLabel('Conferi o documento da pessoa que retira').check();
  await page.getByRole('button', { name: 'Confirmar retirada' }).click();
  await expect(page.getByText('Esta encomenda já foi retirada.')).toBeVisible();
  await expect(page.getByText('Retirada registrada', { exact: false })).toHaveCount(0);
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  await expect(page.getByText('Não há encomenda aguardando retirada neste filtro.')).toBeVisible();
});

test('morador consulta encomendas da unidade com local e retirada', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installParcelsApi(page, api, false);
  state.parcels.push(
    { parcelId: PARCEL, unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: null, quantity: 1, description: 'Caixa média',
      storageLocation: 'Prateleira B', status: 'RECEIVED', receivedAt: '2030-01-01T12:00:00.000Z', version: 0, handoff: null },
    { parcelId: '40000000-0000-4000-8000-0000000000e2', unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: null, quantity: 2, description: '',
      storageLocation: 'Armário 1', status: 'DELIVERED', receivedAt: '2029-12-30T12:00:00.000Z', version: 1,
      handoff: { recipientName: 'Pessoa Fictícia', deliveredAt: '2029-12-30T18:00:00.000Z', evidenceKind: 'ASSISTED' } });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/encomendas`);
  await expect(page.getByRole('heading', { name: 'Encomendas da unidade' })).toBeVisible();
  const waiting = page.getByRole('list', { name: 'Encomendas a retirar' });
  await expect(waiting.getByText('guardada em Prateleira B', { exact: false })).toBeVisible();
  await expect(waiting.getByText('Caixa média')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Encomendas retiradas' }).getByText('Retirada por Pessoa Fictícia', { exact: false })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Encomendas' }).first()).toHaveAttribute('aria-current', 'page');
  // Em tela estreita as três abas da unidade cabem sem rolagem horizontal nem rótulo cortado.
  await page.setViewportSize({ width: 360, height: 780 });
  const tabs = page.getByRole('navigation', { name: 'Navegação da unidade' });
  await expect(tabs.getByRole('link')).toHaveCount(3);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await tabs.evaluate((nav) => [...nav.querySelectorAll('a')].every((link) => link.scrollWidth <= link.clientWidth))).toBe(true);
});
