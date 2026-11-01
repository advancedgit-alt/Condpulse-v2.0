import { test, expect, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { scanAccessibility } from './a11y';

// Condomínio demo (CPX-046, V053). Dados exclusivamente sintéticos e interceptados pelo runner.

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
const created = '2030-01-01T12:00:00.000Z';
const DEMO_1 = '00000000-0000-4000-8000-0000000000d1';
const DEMO_2 = '00000000-0000-4000-8000-0000000000d2';
function generation(number: number, tenantId: string, status: 'ACTIVE' | 'RETIRED' = 'ACTIVE') {
  return { generationId: `55000000-0000-4000-8000-00000000000${number}`, generation: number, tenantId,
    tenantName: `[DEMO] Residencial Jardim Fictício (geração ${number})`, partnerId: `30000000-0000-4000-8000-00000000000${number}`,
    partnerName: `[DEMO] Vigilância Fictícia Aurora (geração ${number})`, status, createdAt: created, createdBy: ACCOUNT,
    retiredAt: status === 'RETIRED' ? '2030-01-02T12:00:00.000Z' : null, counts: { blocks: 2, units: 32, parcels: 3 } };
}

test('central cria e reseta o demo com confirmação, motivo e chave de operação; o reset aponta o demo lido', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = { current: null as ReturnType<typeof generation> | null, retired: [] as ReturnType<typeof generation>[] };
  const posts: { key: string | undefined; body: { expectedTenantId: string | null; reason: string; confirmation: string } }[] = [];
  const members: unknown[] = [];
  let loseNextPost = false;
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [] }); return; }
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false,
        capabilities: ['partners.manage', 'partners.read', 'platform.audit.read', 'platform.demo.manage', 'tenants.manage', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') { await json(route, 200, { tenants: [], truncated: false }); return; }
    if (path === '/v1/platform/demo-tenant' && request.method() === 'GET') { await json(route, 200, state); return; }
    if (path === '/v1/platform/demo-tenant' && request.method() === 'POST') {
      const body = request.postDataJSON() as (typeof posts)[number]['body'];
      posts.push({ key: request.headers()['idempotency-key'], body });
      const previous = state.current;
      // Mesma chave e mesmo pedido já aplicados: repetição (applied=false); mesma chave com outro pedido: recusa.
      const earlier = posts.slice(0, -1).find((item) => item.key === posts.at(-1)!.key);
      if (earlier) {
        if (JSON.stringify(earlier.body) !== JSON.stringify(body)) {
          await json(route, 409, { type: 'urn:condpulse:problem:DEMO_KEY_REUSED', status: 409, code: 'DEMO_KEY_REUSED', detail: 'x', traceId: '60000000-0000-4000-8000-000000000001' });
          return;
        }
        await json(route, 200, { generation: state.current, retiredTenantId: null, applied: false });
        return;
      }
      if (body.expectedTenantId !== (previous?.tenantId ?? null)) {
        await json(route, 409, { type: 'urn:condpulse:problem:DEMO_CHANGED', status: 409, code: 'DEMO_CHANGED', detail: 'x', traceId: '60000000-0000-4000-8000-000000000001' });
        return;
      }
      if (previous) state.retired = [{ ...previous, status: 'RETIRED', retiredAt: '2030-01-02T12:00:00.000Z' }];
      const next = previous ? previous.generation + 1 : 1;
      state.current = generation(next, next === 1 ? DEMO_1 : next === 2 ? DEMO_2 : `00000000-0000-4000-8000-0000000000d${next}`);
      // Resposta perdida depois de o banco aplicar: a tela fica incerta e só pode reenviar o mesmo pedido com a mesma chave.
      if (loseNextPost) { loseNextPost = false; await route.abort('connectionreset'); return; }
      await json(route, 201, { generation: state.current, retiredTenantId: previous?.tenantId ?? null, applied: true });
      return;
    }
    if (path.startsWith('/v1/platform/partners/') && path.endsWith('/members') && request.method() === 'POST') {
      const body = request.postDataJSON() as { accountId: string; role: string; displayName: string; reason: string };
      members.push(body);
      await json(route, 201, { id: '61000000-0000-4000-8000-000000000001', partnerId: state.current!.partnerId, accountId: body.accountId, role: body.role,
        displayName: body.displayName, status: 'ACTIVE', version: 0, createdAt: created, revokedAt: null, revokeReason: null });
      return;
    }
    await route.fallback();
  });
  await signIn(page);
  await expect(page).toHaveURL(/\/central\/condominios$/);
  await page.getByRole('link', { name: 'Condomínio demo', exact: true }).first().click();
  await expect(page).toHaveURL(/\/central\/demo$/);
  await expect(page.getByText('Nenhum condomínio demo ativo')).toBeVisible();
  await page.getByRole('button', { name: 'Criar condomínio demo' }).click();
  const form = page.getByRole('form', { name: 'Criar condomínio demo' });
  await form.getByLabel(/Motivo/).fill('Início dos testes de homologação');
  await form.getByLabel('Para confirmar, digite DEMO').fill('demo');
  await expect(form.getByRole('button', { name: 'Criar demo' })).toBeDisabled();
  await form.getByLabel('Para confirmar, digite DEMO').fill('DEMO');
  await form.getByRole('button', { name: 'Criar demo' }).click();
  await expect(page.getByText(/Condomínio demo criado \(geração 1\)/)).toBeVisible();
  await expect(page.getByText('[DEMO] Residencial Jardim Fictício (geração 1)')).toBeVisible();
  await expect(page.getByText('DEMO', { exact: true }).first()).toBeVisible();
  await scanAccessibility(page, 'central/demo');

  const link = page.getByRole('form', { name: 'Vincular conta ao parceiro demo' });
  await expect(link.getByLabel(/Identificador da conta/)).toHaveValue(ACCOUNT);
  await link.getByLabel('Papel').selectOption('PARTNER_ADMIN');
  await link.getByLabel('Nome de exibição').fill('Pessoa responsável pelos testes');
  await link.getByLabel(/Motivo/).fill('Testar o painel do parceiro');
  await link.getByRole('button', { name: 'Conceder papel' }).click();
  await expect(link.getByText(/concedido a Pessoa responsável pelos testes/)).toBeVisible();
  expect(members).toEqual([{ accountId: ACCOUNT, role: 'PARTNER_ADMIN', displayName: 'Pessoa responsável pelos testes', reason: 'Testar o painel do parceiro' }]);

  await page.getByRole('button', { name: 'Resetar condomínio demo' }).click();
  const reset = page.getByRole('form', { name: 'Resetar condomínio demo' });
  await expect(reset.getByText(/fica suspensa/)).toBeVisible();
  await reset.getByLabel(/Motivo/).fill('Recomeçar do zero');
  await reset.getByLabel('Para confirmar, digite DEMO').fill('DEMO');
  await reset.getByRole('button', { name: 'Resetar demo' }).click();
  await expect(page.getByText(/Demo resetado: geração 2 criada/)).toBeVisible();
  await expect(page.getByText('Gerações anteriores (1)')).toBeVisible();
  expect(posts.map((item) => item.body)).toEqual([
    { expectedTenantId: null, reason: 'Início dos testes de homologação', confirmation: 'DEMO' },
    { expectedTenantId: DEMO_1, reason: 'Recomeçar do zero', confirmation: 'DEMO' }]);
  expect(posts.every((item) => /^web:[0-9a-f-]{36}$/.test(item.key ?? ''))).toBe(true);
  expect(posts[0]!.key).not.toBe(posts[1]!.key);

  // Resultado incerto: o banco aplicou a geração 3, a resposta se perdeu e "Atualizar" relê o demo (agora a geração 3).
  // Reenviar precisa repetir exatamente o pedido original (demo lido = geração 2, mesma chave), não o demo relido.
  loseNextPost = true;
  await page.getByRole('button', { name: 'Resetar condomínio demo' }).click();
  const again = page.getByRole('form', { name: 'Resetar condomínio demo' });
  await again.getByLabel(/Motivo/).fill('Reset com resposta perdida');
  await again.getByLabel('Para confirmar, digite DEMO').fill('DEMO');
  await again.getByRole('button', { name: 'Resetar demo' }).click();
  await expect(again.getByText(/A operação pode ter sido concluída/)).toBeVisible();
  await expect(again.getByLabel(/Motivo/)).toBeDisabled();
  await page.getByRole('button', { name: 'Atualizar' }).click();
  await expect(page.getByText('[DEMO] Residencial Jardim Fictício (geração 3)')).toBeVisible();
  await again.getByRole('button', { name: 'Reenviar a mesma operação' }).click();
  await expect(page.getByText(/Esta operação já tinha sido feita: o demo atual é a geração 3/)).toBeVisible();
  expect(posts.slice(2).map((item) => item.body)).toEqual([
    { expectedTenantId: DEMO_2, reason: 'Reset com resposta perdida', confirmation: 'DEMO' },
    { expectedTenantId: DEMO_2, reason: 'Reset com resposta perdida', confirmation: 'DEMO' }]);
  expect(posts[2]!.key).toBe(posts[3]!.key);
});

test('reset recusado para condomínio que não é o demo mostra a mensagem própria e nada muda', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/v1/me') { await json(route, 200, { accountId: ACCOUNT, memberships: [], tenantModules: [] }); return; }
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false, capabilities: ['platform.demo.manage', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') { await json(route, 200, { tenants: [], truncated: false }); return; }
    if (path === '/v1/platform/demo-tenant' && request.method() === 'GET') { await json(route, 200, { current: generation(1, DEMO_1), retired: [] }); return; }
    if (path === '/v1/platform/demo-tenant' && request.method() === 'POST') {
      await json(route, 422, { type: 'urn:condpulse:problem:DEMO_RESET_REAL_TENANT', status: 422, code: 'DEMO_RESET_REAL_TENANT',
        detail: 'Somente o condomínio demo pode ser resetado.', traceId: '60000000-0000-4000-8000-000000000001' });
      return;
    }
    await route.fallback();
  });
  await signIn(page);
  await page.goto('/central/demo');
  await expect(page.getByText('[DEMO] Residencial Jardim Fictício (geração 1)')).toBeVisible();
  await expect(page.getByText(/hierarquia de parceiro está desligada/)).toBeVisible();
  await page.getByRole('button', { name: 'Resetar condomínio demo' }).click();
  const reset = page.getByRole('form', { name: 'Resetar condomínio demo' });
  await reset.getByLabel(/Motivo/).fill('Teste de recusa');
  await reset.getByLabel('Para confirmar, digite DEMO').fill('DEMO');
  await reset.getByRole('button', { name: 'Resetar demo' }).click();
  await expect(reset.getByText(/Condomínios reais nunca são resetados/)).toBeVisible();
});

test('todas as telas do condomínio demo mostram a marca DEMO; condomínio real não', async ({ page }) => {
  const api = await installSyntheticApi(page);
  let demo = true;
  await page.route('**/v1/**', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    const path = new URL(route.request().url()).pathname;
    if (path === '/v1/me') {
      await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000d1', tenantId: TENANT_A,
        tenantName: demo ? '[DEMO] Residencial Jardim Fictício (geração 1)' : 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null,
        role: 'MAINTENANCE' }], tenantModules: [{ tenantId: TENANT_A, enabled: ['requests'] }], demoTenantIds: demo ? [TENANT_A] : [] });
      return;
    }
    if (path === `/v1/tenants/${TENANT_A}/maintenance/requests`) { await json(route, 200, { requests: [], truncated: false }); return; }
    await route.fallback();
  });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/zeladoria/pedidos`);
  await expect(page.getByRole('note').filter({ hasText: 'Ambiente de demonstração com dados fictícios' })).toBeVisible();
  await page.goto(`/c/${TENANT_A}/inicio`);
  await expect(page.getByRole('note').filter({ hasText: 'Ambiente de demonstração com dados fictícios' })).toBeVisible();
  await scanAccessibility(page, 'condomínio demo');
  demo = false;
  await page.goto('/entrada');
  await page.goto(`/c/${TENANT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Condomínio Sintético A' })).toBeVisible();
  await expect(page.getByText('Ambiente de demonstração com dados fictícios')).toHaveCount(0);
});
