import AxeBuilder from '@axe-core/playwright';
import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, chooseContext, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

/**
 * Identidade visual (ADR-0010): marca canônica, tema claro/escuro e evidência por perfil. As capturas ficam em
 * test-results/identidade (artefato do CI). Dados sintéticos; nenhuma API real.
 */
test.use({ timezoneId: 'UTC' });

const VIEWPORTS = [{ width: 1366, height: 768 }, { width: 1280, height: 720 }, { width: 390, height: 844 }] as const;
const THEMES = ['light', 'dark'] as const;

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}

async function contrastScan(page: Page, label: string) {
  const result = await new AxeBuilder({ page }).withRules(['color-contrast', 'link-in-text-block']).analyze();
  const problems = result.violations.flatMap((item) => item.nodes.slice(0, 5).map((node) => `${item.id}: ${node.target.join(' ')} ${node.failureSummary ?? ''}`));
  expect(problems, `${label}: contraste`).toEqual([]);
}

/** Percorre temas e tamanhos na mesma página já carregada; o tema segue o sistema emulado (preferência padrão). */
async function capture(page: Page, profile: string, ready: () => Promise<void>) {
  for (const theme of THEMES) {
    await page.emulateMedia({ colorScheme: theme });
    await expect(page.locator('html')).toHaveAttribute('data-cp-theme', theme);
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport);
      await ready();
      await page.screenshot({ path: test.info().outputPath(`../identidade/${profile}-${theme}-${viewport.width}x${viewport.height}.png`), animations: 'disabled' });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `${profile} ${theme} ${viewport.width}px sem rolagem horizontal`).toBe(true);
      if (viewport.width === 1366) await contrastScan(page, `${profile} ${theme}`);
    }
  }
}

test('login com a marca canônica nos dois temas', async ({ page }) => {
  await installSyntheticApi(page);
  await page.goto('/entrar');
  const logos = page.getByRole('img', { name: 'CondPulse' });
  await expect(logos.first()).toBeVisible();
  // A marca é o SVG do kit ("C envolvendo três edifícios"), não as antigas barras em CSS.
  await expect(page.locator('.brand-mark')).toHaveCount(0);
  await capture(page, 'login', async () => { await expect(page.getByRole('heading', { name: 'Entre para continuar.' })).toBeVisible(); });
});

test('morador: início da unidade', async ({ page }) => {
  await installSyntheticApi(page);
  await signIn(page);
  await chooseContext(page, 'A', 'inicio');
  await capture(page, 'morador', async () => { await expect(page.getByRole('heading', { name: 'Sua unidade, em dia.', exact: true })).toBeVisible(); });
});

test('portaria: encomendas', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }] });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/concierge/units`, (route) => json(route, 200, [{ unitId: UNIT_A, label: '101 — Sintética A', blockName: 'Bloco 1' }]));
  await page.route((url) => url.pathname === `/v1/tenants/${TENANT_A}/parcels`, (route) => json(route, 200, { parcels: [], truncated: false }));
  await signIn(page);
  await page.goto(`/portaria/${TENANT_A}/encomendas`);
  await capture(page, 'portaria', async () => { await expect(page.getByRole('heading', { name: 'Receber encomenda' })).toBeVisible(); });
});

test('gestão: pendências', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pendencias`);
  await capture(page, 'gestao', async () => { await expect(page.getByRole('heading', { level: 1, name: 'Condomínio Sintético A' })).toBeVisible(); });
});

test('master: condomínios da plataforma', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const created = '2030-01-01T12:00:00.000Z';
  await page.route('**/v1/platform/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/v1/platform/session') { await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false, capabilities: ['platform.audit.read', 'tenants.manage', 'tenants.read'] }); return; }
    if (path === '/v1/platform/tenants') {
      await json(route, 200, { truncated: false, tenants: [{ tenantId: TENANT_A, name: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', active: true, version: 0,
        createdAt: created, updatedAt: created, onboarding: null }] });
      return;
    }
    await json(route, 404, { type: 'urn:condpulse:problem:SYNTHETIC_TEST_ROUTE_NOT_FOUND', status: 404, code: 'SYNTHETIC_TEST_ROUTE_NOT_FOUND', traceId: '60000000-0000-4000-8000-000000000009' });
  });
  await signIn(page);
  await page.goto('/master');
  await capture(page, 'master', async () => { await expect(page.getByRole('heading', { name: 'Condomínios da plataforma' })).toBeVisible(); });
});

test('preferência manual de tema vence o sistema e fica salva no navegador', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await page.emulateMedia({ colorScheme: 'light' });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pendencias`);
  const html = page.locator('html');
  await expect(html).toHaveAttribute('data-cp-theme', 'light');
  await page.getByRole('button', { name: 'Tema: Sistema. Mudar para Claro' }).click();
  await page.getByRole('button', { name: 'Tema: Claro. Mudar para Escuro' }).click();
  await expect(html).toHaveAttribute('data-cp-theme', 'dark');
  const toggle = page.getByRole('button', { name: 'Tema: Escuro. Mudar para Sistema' });
  const box = await toggle.boundingBox();
  expect(box && box.width >= 48 && box.height >= 48).toBe(true);
  await page.reload();
  await expect(html).toHaveAttribute('data-cp-theme', 'dark');
  await page.getByRole('button', { name: 'Tema: Escuro. Mudar para Sistema' }).click();
  await expect(html).toHaveAttribute('data-cp-theme', 'light');
  expect(await page.evaluate(() => window.localStorage.getItem('condpulse.theme'))).toBeNull();
});
