import { test, expect, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { scanAccessibility } from './a11y';

// Módulos por condomínio na central (V045). Dados exclusivamente sintéticos e interceptados pelo runner.

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
const created = '2030-01-01T12:00:00.000Z';
const tenant = { tenantId: TENANT_A, name: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', active: true, version: 0, createdAt: created, updatedAt: created, onboarding: null };
const ids = ['reservations', 'parcels', 'visits', 'announcements', 'requests', 'finance', 'access_credentials', 'facial_access'];

test('central suspende um módulo pela versão lida, com motivo, e a repetição não vira sucesso', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const modules = ids.map((module) => ({ module, globallyEnabled: module !== 'facial_access', tenantEnabled: true, enabled: module !== 'facial_access',
    defaultState: true, version: 0, reason: null as string | null, updatedBy: null as string | null, updatedAt: null as string | null }));
  const changes: unknown[] = [];
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [] }); return; }
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false, capabilities: ['platform.audit.read', 'tenant_modules.manage', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') { await json(route, 200, { tenants: [tenant], truncated: false }); return; }
    if (path === `/v1/platform/tenants/${TENANT_A}`) {
      await json(route, 200, { tenant, managers: [], managerInvitations: [], recentEvents: [] });
      return;
    }
    if (path === `/v1/platform/tenants/${TENANT_A}/modules` && request.method() === 'GET') { await json(route, 200, { tenantId: TENANT_A, modules }); return; }
    if (path === `/v1/platform/tenants/${TENANT_A}/modules` && request.method() === 'PUT') {
      const body = request.postDataJSON() as { module: string; enabled: boolean; expectedVersion: number; reason: string };
      changes.push(body);
      const current = modules.find((item) => item.module === body.module)!;
      if (current.tenantEnabled === body.enabled) { await json(route, 200, { applied: false, module: current }); return; }
      Object.assign(current, { tenantEnabled: body.enabled, enabled: body.enabled && current.globallyEnabled, defaultState: false, version: current.version + 1,
        reason: body.reason, updatedBy: ACCOUNT, updatedAt: '2030-01-02T12:00:00.000Z' });
      await json(route, 200, { applied: true, module: current });
      return;
    }
    await route.fallback();
  });
  await signIn(page);
  await expect(page).toHaveURL(/\/central\/condominios$/);
  await page.getByRole('link', { name: 'Abrir ficha de Condomínio Sintético A' }).click();
  await page.getByRole('link', { name: 'Módulos', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/central/modulos\\?condominio=${TENANT_A}$`));
  const list = page.getByRole('list', { name: 'Módulos do condomínio' });
  const parcels = list.getByRole('listitem').filter({ hasText: 'Encomendas' });
  await expect(parcels.getByText('Ativo', { exact: true })).toBeVisible();
  await expect(list.getByRole('listitem').filter({ hasText: 'Acesso facial' }).getByText('Desligado no servidor')).toBeVisible();
  await scanAccessibility(page, 'central/módulos');
  await parcels.getByRole('button', { name: 'Suspender Encomendas' }).click();
  const form = page.getByRole('form', { name: 'Suspender Encomendas' });
  await expect(form.getByRole('button', { name: 'Confirmar suspensão' })).toBeDisabled();
  await form.getByLabel(/Motivo/).fill('Contrato sem encomendas');
  await form.getByRole('button', { name: 'Confirmar suspensão' }).click();
  await expect(page.getByText(/Encomendas suspenso/)).toBeVisible();
  await expect(parcels.getByText('Suspenso pela central')).toBeVisible();
  await expect(parcels.getByText('Motivo: Contrato sem encomendas')).toBeVisible();
  expect(changes).toEqual([{ module: 'parcels', enabled: false, expectedVersion: 0, reason: 'Contrato sem encomendas' }]);
});
