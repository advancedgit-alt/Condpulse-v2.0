import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, TENANT_B, UNIT_A, UNIT_B, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { scanAccessibility } from './a11y';

// Entrada única, casca do condomínio por papel e central administradora (docs/ESTRUTURA.md, fatias 1 e 6).
// Dados exclusivamente sintéticos e interceptados pelo runner.

type Role = 'RESIDENT' | 'OWNER' | 'CONCIERGE' | 'MANAGER';
interface Link { tenantId: string; tenantName: string; unitId: string | null; role: Role }

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}

/** Sobrepõe /v1/me, a sessão de plataforma e a lista de unidades; registrado depois da API base, tem precedência. */
async function installIdentity(page: Page, api: ApiState, links: Link[], platform: boolean) {
  const requests: string[] = [];
  await page.route('**/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    // Sem sessão, a API base responde 401 como em qualquer rota.
    if (!api.authenticated) { await route.fallback(); return; }
    if (path === '/v1/me') {
      await json(route, 200, { accountId: ACCOUNT, memberships: links.map((link, index) => ({ membershipId: `50000000-0000-4000-8000-0000000001${String(index).padStart(2, '0')}`,
        timeZone: 'America/Sao_Paulo', ...link })) });
      return;
    }
    if (path === '/v1/platform/session') {
      requests.push(path);
      if (!platform) { await json(route, 403, { type: 'urn:condpulse:problem:PLATFORM_ACCESS_DENIED', status: 403, code: 'PLATFORM_ACCESS_DENIED', detail: 'Negado.' }); return; }
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false, capabilities: ['platform.audit.read', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') { requests.push(path); await json(route, 200, { tenants: [], truncated: false }); return; }
    if (path === `/v1/tenants/${TENANT_A}/units`) {
      await json(route, 200, [{ id: UNIT_A, label: '101 — Sintética A', blockId: null, blockName: 'Bloco 1' },
        { id: UNIT_B, label: '102 — Sintética A', blockId: null, blockName: 'Bloco 1' }]);
      return;
    }
    await route.fallback();
  });
  return requests;
}

test('conta só de plataforma entra direto na central, com as seções futuras desabilitadas', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const requests = await installIdentity(page, api, [], true);
  await signIn(page);
  await expect(page).toHaveURL(/\/central\/condominios$/);
  await expect(page.getByRole('heading', { name: 'Central administradora' })).toBeVisible();
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  await expect(nav.getByRole('link', { name: 'Condomínios' })).toHaveAttribute('aria-current', 'page');
  for (const label of ['Módulos por condomínio', 'Contas e acessos', 'Chaves e integrações', 'Acesso facial']) {
    await expect(nav.getByText(label)).toBeVisible();
    await expect(nav.getByRole('link', { name: label })).toHaveCount(0);
  }
  await expect(page.getByRole('button', { name: 'Meus condomínios' })).toHaveCount(0);
  expect(requests).toContain('/v1/platform/session');
  await scanAccessibility(page, 'central/condomínios');
});

test('plataforma com condomínio vê o seletor com a central e os cartões só de condomínio', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installIdentity(page, api, [{ tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', unitId: UNIT_A, role: 'RESIDENT' }], true);
  await signIn(page);
  await expect(page).toHaveURL(/\/entrada$/);
  await expect(page.getByRole('link', { name: /Central administradora/ })).toBeVisible();
  const cards = page.getByRole('list', { name: 'Condomínios' });
  await expect(cards.getByRole('link')).toHaveCount(1);
  await expect(cards.getByRole('link')).toContainText('Morador');
  // Sem seleção de unidade nem atalho de gestão na entrada.
  await expect(page.getByLabel('Unidade', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: /Abrir gestão/ })).toHaveCount(0);
  await page.getByRole('link', { name: /Central administradora/ }).click();
  await expect(page).toHaveURL(/\/central\/condominios$/);
  await expect(page.getByRole('button', { name: 'Meus condomínios' })).toBeVisible();
});

test('vários papéis no mesmo condomínio: troca de área sem sair do condomínio; seletor interno de unidade', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const api = await installSyntheticApi(page);
  await installIdentity(page, api, [
    { tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', unitId: UNIT_A, role: 'RESIDENT' },
    { tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', unitId: UNIT_B, role: 'OWNER' },
    { tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', unitId: null, role: 'CONCIERGE' },
  ], false);
  await signIn(page);
  // Um condomínio: direto na área do papel principal (portaria).
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/portaria/encomendas$`));
  await page.getByRole('button', { name: 'Abrir menu' }).click();
  const areas = page.getByRole('navigation', { name: 'Áreas do condomínio' });
  await expect(areas.getByRole('link')).toHaveText(['Início', 'Morador', 'Portaria']);
  await expect(areas.getByText('Zeladoria')).toHaveCount(0);
  await areas.getByRole('link', { name: 'Morador' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador$`));
  await expect(page.getByRole('heading', { name: 'Escolha a unidade.' })).toBeVisible();
  const units = page.getByRole('list', { name: 'Unidades' });
  await expect(units.getByRole('link')).toHaveCount(2);
  await scanAccessibility(page, 'morador/seletor de unidade');
  await units.getByRole('link', { name: /101 — Sintética A/ }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/inicio$`));
  await expect(page.getByRole('heading', { name: 'Sua unidade, em dia.', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Trocar unidade', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador$`));
  await page.getByRole('button', { name: 'Abrir menu' }).click();
  // No seletor de unidade a área ainda não tem seções: as áreas ocupam a navegação principal.
  await page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Início' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/inicio$`));
  const home = page.getByRole('list', { name: 'Áreas do condomínio' });
  await expect(home.getByRole('link')).toHaveCount(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await scanAccessibility(page, 'condomínio/início 390px');
});

test('rotas antigas redirecionam para os endereços novos preservando parâmetros', async ({ page }) => {
  const api = await installSyntheticApi(page, { manager: true });
  await installIdentity(page, api, [
    { tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', unitId: UNIT_A, role: 'RESIDENT' },
    { tenantId: TENANT_B, tenantName: 'Condomínio Sintético B', unitId: UNIT_B, role: 'RESIDENT' },
  ], false);
  await signIn(page);
  await page.goto('/contextos?origem=link#x');
  await expect(page).toHaveURL(/\/entrada\?origem=link#x$/);
  await expect(page.getByRole('heading', { name: 'Escolha o condomínio.' })).toBeVisible();
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/reservas?dia=2030-01-02`);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/morador/${UNIT_A}/reservas\\?dia=2030-01-02$`));
  await page.goto(`/gestao/${TENANT_A}/avisos`);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/gestao/avisos$`));
  await page.goto(`/portaria/${TENANT_A}`);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/portaria/encomendas$`));
  await page.goto(`/implantacao/${TENANT_A}/canais`);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_A}/implantacao/canais$`));
  await page.goto(`/master/concessoes?condominio=${TENANT_A}`);
  await expect(page).toHaveURL(new RegExp(`/central/concessoes\\?condominio=${TENANT_A}$`));
  await page.goto('/master/fila');
  await expect(page).toHaveURL(/\/central\/operacao$/);
});

test('vínculo com unidade desativada não abre o painel nem volta em ciclo: mostra que não há unidade ativa', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installIdentity(page, api, [{ tenantId: TENANT_B, tenantName: 'Condomínio Sintético B', unitId: UNIT_B, role: 'RESIDENT' }], false);
  // /v1/me ainda traz o vínculo, mas /units lista só unidades ativas e não devolve a unidade desativada.
  await page.route(`**/v1/tenants/${TENANT_B}/units`, async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, []);
  });
  await signIn(page);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_B}/morador$`));
  await expect(page.getByText('Nenhuma unidade ativa')).toBeVisible();
  await page.waitForTimeout(500);
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_B}/morador$`));
  // Endereço direto do painel da unidade desativada: "Selecionar unidade" leva ao mesmo estado vazio, sem ciclo.
  await page.goto(`/c/${TENANT_B}/morador/${UNIT_B}/inicio`);
  await page.getByRole('link', { name: 'Selecionar unidade' }).click();
  await expect(page).toHaveURL(new RegExp(`/c/${TENANT_B}/morador$`));
  await expect(page.getByText('Nenhuma unidade ativa')).toBeVisible();
  await scanAccessibility(page, 'morador sem unidade ativa');
});
