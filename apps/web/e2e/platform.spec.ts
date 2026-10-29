import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, TENANT_B, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi, type ManagementState } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

interface SyntheticTenant { tenantId: string; name: string; timeZone: string; active: boolean; version: number; createdAt: string; updatedAt: string; onboarding: unknown }
interface PlatformState { role: boolean; tenants: SyntheticTenant[]; updates: unknown[]; audit: unknown[]; requests: string[] }

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, traceId: '60000000-0000-4000-8000-000000000009' });
}

/** API sintética do painel master, registrada depois das demais: rotas mais novas têm precedência. */
async function installPlatformApi(page: Page, management: ManagementState, role = true): Promise<PlatformState> {
  const created = '2030-01-01T12:00:00.000Z';
  const state: PlatformState = { role, updates: [], audit: [], requests: [], tenants: [
    { tenantId: TENANT_A, name: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', active: true, version: 0, createdAt: created, updatedAt: created,
      onboarding: { operationId: '70000000-0000-4000-8000-000000000001', accounts: 3, units: 2, memberships: 3, amenities: 1, completedAt: created } },
    { tenantId: TENANT_B, name: 'Condomínio Sintético B', timeZone: 'America/Sao_Paulo', active: true, version: 0, createdAt: created, updatedAt: created, onboarding: null },
  ] };
  await page.route('**/v1/platform/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    state.requests.push(`${request.method()} ${path}${url.search}`);
    if (!state.role) { await problem(route, 403, 'PLATFORM_ACCESS_DENIED'); return; }
    const aal2 = management.assurance === 'AAL2';
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: !aal2,
        capabilities: aal2 ? ['platform.audit.read', 'tenants.manage', 'tenants.read'] : [] });
      return;
    }
    if (!aal2) { await problem(route, 403, 'MFA_REQUIRED'); return; }
    if (request.method() === 'GET' && path === '/v1/platform/tenants') {
      const search = url.searchParams.get('q')?.toLowerCase();
      const tenants = search ? state.tenants.filter((item) => item.name.toLowerCase().includes(search) || item.tenantId === search) : state.tenants;
      await json(route, 200, { tenants, truncated: false });
      return;
    }
    if (request.method() === 'PUT' && path.startsWith('/v1/platform/tenants/')) {
      const body = request.postDataJSON() as { name: string | null; active: boolean; expectedVersion: number; reason: string };
      state.updates.push(body);
      const tenant = state.tenants.find((item) => item.tenantId === path.split('/').pop())!;
      const name = body.name ?? tenant.name;
      if (tenant.name === name && tenant.active === body.active) { await json(route, 200, { tenant, applied: false }); return; }
      if (tenant.version !== body.expectedVersion) { await problem(route, 409, 'TENANT_CHANGED'); return; }
      const before = { ...tenant };
      Object.assign(tenant, { name, active: body.active, version: tenant.version + 1, updatedAt: new Date().toISOString() });
      if (before.active !== tenant.active) state.audit.unshift({ eventId: `80000000-0000-4000-8000-00000000000${state.audit.length + 1}`,
        actorId: ACCOUNT, action: tenant.active ? 'TENANT_REACTIVATED' : 'TENANT_SUSPENDED', tenantId: tenant.tenantId, reason: body.reason,
        details: {}, createdAt: new Date().toISOString() });
      await json(route, 200, { tenant, applied: true });
      return;
    }
    if (request.method() === 'GET' && path === '/v1/platform/audit-events') { await json(route, 200, { events: state.audit, nextCursor: null }); return; }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}

test('conta sem papel de plataforma vê área restrita sem dados da plataforma', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const management = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const platform = await installPlatformApi(page, management, false);
  await signIn(page);
  await page.goto('/master');
  await expect(page.getByRole('heading', { name: 'Área restrita' })).toBeVisible();
  await expect(page.getByText('Condomínio Sintético B')).toHaveCount(0);
  expect(platform.requests).toEqual(['GET /v1/platform/session']);
});

test('administrador confirma o segundo fator, suspende com motivo e a repetição não vira sucesso', async ({ page }) => {
  await installSyntheticApi(page);
  const management = await installManagementApi(page, { assurance: 'AAL1', verifiedFactor: false });
  const platform = await installPlatformApi(page, management);
  await signIn(page);
  await page.goto('/master');
  await page.getByRole('button', { name: 'Cadastrar autenticador' }).click();
  await page.getByLabel('Código de 6 dígitos').fill('123456');
  await page.getByRole('button', { name: 'Verificar código' }).click();
  await expect(page.getByRole('heading', { name: 'Condomínios da plataforma' })).toBeVisible();
  expect(platform.requests.filter((request) => request !== 'GET /v1/platform/session')).toEqual(['GET /v1/platform/tenants']);
  await expect(page.getByText('Onboarding em', { exact: false }).first()).toBeVisible();
  const rowB = page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' });
  await rowB.getByRole('button', { name: 'Alterar nome ou estado' }).click();
  await rowB.getByLabel('Suspenso').check();
  await expect(rowB.getByText('Suspender tira o condomínio do acesso', { exact: false })).toBeVisible();
  await rowB.getByLabel('Motivo (fica na auditoria da plataforma)').fill('Contrato encerrado');
  await rowB.getByRole('button', { name: 'Salvar alteração' }).click();
  await expect(page.getByText('Condomínio suspenso: moradores e gestão local perdem o acesso', { exact: false })).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' }).getByText('Suspenso', { exact: true })).toBeVisible();
  // Nome intocado vai como null: suspender não renomeia.
  expect(platform.updates).toEqual([{ name: null, active: false, expectedVersion: 0, reason: 'Contrato encerrado' }]);
  // Outra pessoa já deixou o condomínio no estado pedido: resposta sem gravação aparece como informação.
  await page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' }).getByRole('button', { name: 'Alterar nome ou estado' }).click();
  const again = page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' });
  await again.getByLabel('Motivo (fica na auditoria da plataforma)').fill('Repetição');
  await again.getByRole('button', { name: 'Salvar alteração' }).click();
  await expect(page.getByText('O condomínio já estava neste estado. Nada foi alterado e o seu motivo não foi registrado.')).toBeVisible();
  await page.getByRole('link', { name: 'Auditoria' }).first().click();
  await expect(page.getByText('Condomínio suspenso', { exact: true })).toBeVisible();
  await expect(page.getByText('Motivo: Contrato encerrado')).toBeVisible();
  // Nome atual do condomínio, mesmo depois de trocar de aba (a lista é consultada de novo).
  await expect(page.getByRole('listitem').filter({ hasText: 'Condomínio suspenso' }).getByText('Condomínio Sintético B')).toBeVisible();
});

test('busca encontra condomínio por trecho do nome e limpa de volta para o catálogo', async ({ page }) => {
  await installSyntheticApi(page);
  const management = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const platform = await installPlatformApi(page, management);
  await signIn(page);
  await page.goto('/master/condominios');
  await expect(page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' })).toBeVisible();
  const search = page.getByRole('search', { name: 'Buscar condomínio' });
  await search.getByLabel('Buscar por trecho do nome ou id').fill('  sintético\u00a0b ');
  await search.getByRole('button', { name: 'Buscar' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' })).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' })).toHaveCount(0);
  expect(platform.requests).toContain(`GET /v1/platform/tenants?q=${encodeURIComponent('sintético b').replace(/%20/g, '+')}`);
  await search.getByLabel('Buscar por trecho do nome ou id').fill('\u200b');
  await search.getByRole('button', { name: 'Buscar' }).click();
  await expect(page.getByText('Use até 160 caracteres visíveis na busca.')).toBeVisible();
  await search.getByRole('button', { name: 'Limpar busca' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' })).toBeVisible();
});

test('versão alterada por outra pessoa recusa a gravação e pede recarga antes de salvar', async ({ page }) => {
  await installSyntheticApi(page);
  const management = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const platform = await installPlatformApi(page, management);
  await signIn(page);
  await page.goto('/master/condominios');
  const row = page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' });
  await row.getByRole('button', { name: 'Alterar nome ou estado' }).click();
  // Mudança concorrente no servidor depois de o formulário abrir.
  Object.assign(platform.tenants[0]!, { name: 'Condomínio Sintético A Renomeado', version: 1 });
  const editor = page.getByRole('form', { name: 'Alterar Condomínio Sintético A' });
  await editor.getByLabel('Nome do condomínio').fill('Condomínio A Novo');
  await editor.getByLabel('Motivo (fica na auditoria da plataforma)').fill('Ajuste de cadastro');
  await editor.getByRole('button', { name: 'Salvar alteração' }).click();
  await expect(page.getByText('O condomínio foi alterado por outra pessoa. Recarregue os dados antes de salvar.')).toBeVisible();
  await expect(page.getByText('foi alterado depois que você abriu o formulário', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Salvar alteração' })).toBeDisabled();
  await page.getByRole('button', { name: 'Recarregar os dados atuais' }).click();
  await expect(page.getByLabel('Nome do condomínio')).toHaveValue('Condomínio Sintético A Renomeado');
  await page.getByLabel('Nome do condomínio').fill('Condomínio A Novo');
  await page.getByRole('button', { name: 'Salvar alteração' }).click();
  await expect(page.getByText('Nome atualizado para "Condomínio A Novo".')).toBeVisible();
  expect(platform.updates).toEqual([
    { name: 'Condomínio A Novo', active: true, expectedVersion: 0, reason: 'Ajuste de cadastro' },
    { name: 'Condomínio A Novo', active: true, expectedVersion: 1, reason: 'Ajuste de cadastro' },
  ]);
});
