import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

test.use({ timezoneId: 'America/Sao_Paulo' });

const TENANT_MEMBERSHIP = '51000000-0000-4000-8000-0000000008a1';
const TOKEN = 'SYNTHETICrentalTOKEN0123456789abcdefghijklm';

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function asOwner(page: Page, api: ApiState) {
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000f1', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: UNIT_A, role: 'OWNER' }] });
  });
}
async function installRental(page: Page, rented: boolean) {
  const state = { rented, removals: [] as unknown[], invitations: [] as unknown[], other: [] as string[],
    occupant: { membershipId: TENANT_MEMBERSHIP, displayName: 'Inquilina Sintética', role: 'RESPONSIBLE', status: 'ACTIVE',
      validFrom: '2030-01-01T12:00:00Z', version: 3 } };
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/units/${UNIT_A}/rental`, '');
    if (path.startsWith('/v1/')) { state.other.push(path); await route.fallback(); return; }
    if (path === '' && request.method() === 'GET') {
      await json(route, 200, state.rented ? { unitId: UNIT_A, unitLabel: '101 — Sintética A', rented: true, rentedSince: '2030-01-01T10:00:00Z',
        occupants: state.occupant.status === 'REVOKED' ? [] : [state.occupant], invitations: [] }
        : { unitId: UNIT_A, unitLabel: '101 — Sintética A', rented: false, rentedSince: null, occupants: [], invitations: [] });
      return;
    }
    if (path === '/invitations' && request.method() === 'POST') {
      state.invitations.push(request.postDataJSON());
      await json(route, 201, { invitationId: '71000000-0000-4000-8000-0000000008a1', token: TOKEN, expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString() });
      return;
    }
    if (path === `/occupants/${TENANT_MEMBERSHIP}/remove` && request.method() === 'POST') {
      state.removals.push(request.postDataJSON());
      state.occupant = { ...state.occupant, status: 'REVOKED', version: 4 };
      await json(route, 200, state.occupant);
      return;
    }
    await json(route, 404, { code: 'SYNTHETIC_TEST_ROUTE_NOT_FOUND', status: 404 });
  });
  return state;
}

test('proprietário de unidade alugada só vê inquilinos: remove e convida', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await asOwner(page, api);
  const state = await installRental(page, true);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/reservas`);
  await expect(page).toHaveURL(new RegExp(`/morador/${TENANT_A}/${UNIT_A}/inquilinos$`));
  await expect(page.getByRole('heading', { name: /Inquilinos/ })).toBeVisible();
  await expect(page.getByText(/você não vê reservas, encomendas, visitas/)).toBeVisible();
  const navigation = page.getByRole('navigation', { name: 'Navegação principal' });
  await expect(navigation.getByRole('link')).toHaveText(['Inquilinos']);
  await scanAccessibility(page, 'inquilinos do proprietário');

  const occupants = page.getByRole('list', { name: 'Ocupantes da unidade' });
  await occupants.getByRole('button', { name: 'Remover Inquilina Sintética' }).click();
  await expect(occupants.getByRole('button', { name: 'Confirmar remoção' })).toBeDisabled();
  await occupants.getByLabel('Motivo da remoção').fill('Fim do contrato');
  await occupants.getByRole('button', { name: 'Confirmar remoção' }).click();
  await expect(page.getByText('Inquilina Sintética: vínculo encerrado. O acesso e as credenciais deixam de valer.')).toBeVisible();
  expect(state.removals).toEqual([{ expectedVersion: 3, reason: 'Fim do contrato' }]);
  await expect(page.getByText('Nenhum ocupante')).toBeVisible();

  const form = page.getByRole('form', { name: 'Convidar inquilino' });
  await form.getByLabel('Nome').fill('Novo Inquilino Sintético');
  await form.getByLabel('E-mail da conta').fill('novo-inquilino@example.invalid');
  await form.getByRole('button', { name: 'Criar convite' }).click();
  await expect(page.getByText(`/convite#token=${TOKEN}`)).toBeVisible();
  expect(state.invitations).toEqual([{ role: 'RESPONSIBLE', displayName: 'Novo Inquilino Sintético', email: 'novo-inquilino@example.invalid', validDays: 7 }]);
  // Nenhum outro dado da unidade é consultado pelo proprietário de unidade alugada.
  expect(state.other.filter((path) => path.includes(`/units/${UNIT_A}/`))).toEqual([]);
});

test('proprietário sem locação vê a indicação e mantém as demais áreas', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await asOwner(page, api);
  await installRental(page, false);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inquilinos`);
  await expect(page.getByText('Unidade não alugada')).toBeVisible();
  const navigation = page.getByRole('navigation', { name: 'Navegação principal' });
  await expect(navigation.getByRole('link')).toHaveText(['Início', 'Reservas', 'Financeiro', 'Inquilinos']);
  await expect(page.getByRole('form', { name: 'Convidar inquilino' })).toHaveCount(0);
});

test('gestão marca e encerra a locação com motivo', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const management = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const calls: { path: string; body: unknown }[] = [];
  await page.route(`**/v1/tenants/${TENANT_A}/management/units/${UNIT_A}/rental**`, async (route) => {
    const request = route.request();
    calls.push({ path: new URL(request.url()).pathname, body: request.postDataJSON() });
    if (request.url().endsWith('/rental/end')) {
      management.rentals = [];
      await route.fulfill({ status: 204, headers: { 'Cache-Control': 'no-store' } });
      return;
    }
    const started = { unitId: UNIT_A, unitLabel: '101 — Sintética A', startedAt: new Date().toISOString(), owners: ['Proprietária Sintética'] };
    management.rentals = [started];
    await json(route, 201, started);
  });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pessoas`);
  await expect(page.getByText('Nenhuma unidade alugada')).toBeVisible();
  const form = page.getByRole('form', { name: 'Marcar unidade como alugada' });
  await form.getByLabel('Unidade alugada').selectOption({ label: '101 — Sintética A' });
  await expect(form.getByRole('button', { name: 'Marcar como alugada' })).toBeDisabled();
  await form.getByLabel('Motivo da locação').fill('Contrato apresentado pela proprietária');
  await form.getByRole('button', { name: 'Marcar como alugada' }).click();
  await expect(page.getByText(/Unidade 101 — Sintética A marcada como alugada/)).toBeVisible();
  const list = page.getByRole('list', { name: 'Unidades alugadas' });
  await expect(list.getByText('Proprietário: Proprietária Sintética')).toBeVisible();
  await list.getByRole('button', { name: 'Encerrar locação da unidade 101 — Sintética A' }).click();
  await list.getByLabel('Motivo do encerramento').fill('Contrato encerrado');
  await list.getByRole('button', { name: 'Confirmar encerramento' }).click();
  await expect(page.getByText('Nenhuma unidade alugada')).toBeVisible();
  expect(calls).toEqual([
    { path: `/v1/tenants/${TENANT_A}/management/units/${UNIT_A}/rental`, body: { reason: 'Contrato apresentado pela proprietária' } },
    { path: `/v1/tenants/${TENANT_A}/management/units/${UNIT_A}/rental/end`, body: { reason: 'Contrato encerrado' } },
  ]);
});
