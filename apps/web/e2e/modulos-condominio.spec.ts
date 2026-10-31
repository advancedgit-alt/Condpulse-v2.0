import { test, expect, type Page, type Route } from '@playwright/test';
import { scanAccessibility } from './a11y';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

// Módulos por condomínio (V045) no menu: seção de módulo desligado sai do menu, o endereço direto mostra o estado
// próprio e o 403 MODULE_DISABLED do servidor não vira perda de vínculo. Dados exclusivamente sintéticos.

const ALL = ['reservations', 'parcels', 'visits', 'announcements', 'requests', 'finance', 'access_credentials', 'facial_access'];
async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function moduleDisabled(route: Route) {
  await json(route, 403, { type: 'urn:condpulse:problem:MODULE_DISABLED', status: 403, code: 'MODULE_DISABLED', detail: 'PRIVATE-SERVER-DETAIL',
    traceId: '60000000-0000-4000-8000-000000000003' });
}

/** /v1/me com gestor e morador no condomínio A e os módulos liberados informados pelo teste (mutável). */
async function installIdentity(page: Page, modules: { enabled: string[] }) {
  let reads = 0;
  await page.route('**/v1/me', async (route) => {
    reads += 1;
    await json(route, 200, { accountId: ACCOUNT, memberships: [
      { membershipId: '50000000-0000-4000-8000-000000000001', tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: UNIT_A, role: 'RESIDENT' },
      { membershipId: '50000000-0000-4000-8000-000000000003', tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'MANAGER' },
    ], tenantModules: [{ tenantId: TENANT_A, enabled: modules.enabled }] });
  });
  return () => reads;
}

test('módulos desligados saem do menu e o endereço direto mostra "módulo desligado pela administradora"', async ({ page }) => {
  const api = await installSyntheticApi(page, { manager: true });
  const management = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await installIdentity(page, { enabled: ALL.filter((module) => !['reservations', 'announcements'].includes(module)) });
  const amenityCalls: string[] = [];
  page.on('request', (request) => { if (/\/amenities|\/reservations/.test(new URL(request.url()).pathname)) amenityCalls.push(request.url()); });
  await signIn(page);
  expect(api.authenticated).toBe(true);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/gestao/pendencias$`));
  const tabs = page.getByRole('navigation', { name: 'Seções da gestão' });
  await expect(tabs.getByRole('link', { name: 'Pessoas' })).toBeVisible();
  for (const label of ['Reservas', 'Áreas', 'Avisos']) await expect(tabs.getByRole('link', { name: label, exact: true })).toHaveCount(0);
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  await expect(nav.getByRole('link', { name: 'Pedidos' })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Avisos' })).toHaveCount(0);

  await page.goto(`/c/${TENANT_A}/gestao/avisos`);
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toBeVisible();
  await expect(page.getByText('Avisos não está liberado para este condomínio.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tentar novamente' })).toHaveCount(0);
  expect(management.managementRequests.filter((call) => call.includes('/announcements'))).toEqual([]);
  await scanAccessibility(page, 'gestão/módulo desligado');

  // Morador: reservas fora do menu e do resumo; o endereço direto não consulta a API de reservas.
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Sua unidade, em dia.', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: /Abrir reservas/ })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Próximas reservas da unidade' })).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Navegação da unidade' }).getByRole('link', { name: 'Reservas' })).toHaveCount(0);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/reservas`);
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toBeVisible();
  await expect(page.getByText('Reservas e áreas comuns não está liberado para este condomínio.')).toBeVisible();
  await page.getByRole('link', { name: 'Voltar ao início do condomínio' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/inicio$`));
  expect(amenityCalls).toEqual([]);
});

test('403 MODULE_DISABLED no meio do uso: estado próprio, sem perder o vínculo, e o menu se atualiza', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const modules = { enabled: [...ALL] };
  const reads = await installIdentity(page, modules);
  // A central desliga o módulo depois que /v1/me foi lido: o servidor responde 403 MODULE_DISABLED e /v1/me passa a omiti-lo.
  const off = (module: string) => async (route: Route) => { modules.enabled = modules.enabled.filter((item) => item !== module); await moduleDisabled(route); };
  await page.route(`**/v1/tenants/${TENANT_A}/management/announcements**`, off('announcements'));
  await page.route(`**/v1/tenants/${TENANT_A}/amenities`, off('reservations'));
  await page.route(`**/v1/tenants/${TENANT_A}/reservations**`, off('reservations'));
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/gestao/pendencias$`));
  const tabs = page.getByRole('navigation', { name: 'Seções da gestão' });
  await expect(tabs.getByRole('link', { name: 'Avisos', exact: true })).toBeVisible();
  const before = reads();
  await tabs.getByRole('link', { name: 'Avisos', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toBeVisible();
  await expect(page.getByText('não está mais disponível para o seu vínculo')).toHaveCount(0);
  // A casca releu /v1/me e tirou a seção do menu.
  await expect.poll(reads).toBeGreaterThan(before);
  await expect(tabs.getByRole('link', { name: 'Avisos', exact: true })).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Pedidos' })).toBeVisible();

  // Morador com /v1/me ainda desatualizado: o 403 das reservas não derruba o resumo nem apaga o contexto.
  modules.enabled = [...ALL];
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Sua unidade, em dia.', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Próximas reservas da unidade' })).toHaveCount(0);
  await expect(page.getByText('Este contexto deixou de estar disponível')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Sua unidade, em dia.', exact: true })).toBeVisible();
});

/** Conta só de portaria no condomínio A com os módulos informados; registra as chamadas da portaria. */
async function installConcierge(page: Page, enabled: string[]) {
  const calls: string[] = [];
  await page.route('**/v1/me', async (route) => {
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-000000000031', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }], tenantModules: [{ tenantId: TENANT_A, enabled }] });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/concierge/**`, async (route) => {
    const path = new URL(route.request().url()).pathname.replace(`/v1/tenants/${TENANT_A}/concierge`, '');
    calls.push(path);
    if (path === '/moves') { await json(route, 200, { moves: [], truncated: false }); return; }
    await moduleDisabled(route);
  });
  await page.route(`**/v1/tenants/${TENANT_A}/access-health`, async (route) => { calls.push('/access-health'); await moduleDisabled(route); });
  return calls;
}

test('portaria com visitas desligadas e pedidos ligados mantém as mudanças em Visitas, sem consultar chegadas', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const calls = await installConcierge(page, ['requests']);
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/portaria/visitas$`));
  const tabs = page.getByRole('navigation', { name: 'Seções da portaria' });
  await expect(tabs.getByRole('link')).toHaveText(['Visitas']);
  await expect(page.getByRole('heading', { name: 'Mudanças aprovadas' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toHaveCount(0);
  expect(calls.every((path) => path.startsWith('/moves'))).toBe(true);
  await page.goto(`/c/${TENANT_A}/portaria/ocorrencias`);
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toBeVisible();
  await expect(page.getByText('Visitas e acessos não está liberado para este condomínio.')).toBeVisible();
});

test('portaria com todos os módulos dela desligados fica desabilitada no condomínio', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const calls = await installConcierge(page, ['reservations', 'announcements', 'finance']);
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/inicio$`));
  const cards = page.getByRole('list', { name: 'Áreas do condomínio' });
  await expect(cards).toContainText('Desligado pela administradora');
  await expect(cards.getByRole('link', { name: /Portaria/ })).toHaveCount(0);
  await page.goto(`/c/${TENANT_A}/portaria/visitas`);
  await expect(page.getByRole('heading', { name: 'Módulo desligado pela administradora' })).toBeVisible();
  expect(calls).toEqual([]);
});
