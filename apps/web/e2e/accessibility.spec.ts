import AxeBuilder from '@axe-core/playwright';
import { test, expect, type Page } from '@playwright/test';
import { ACCOUNT, chooseContext, installSyntheticApi, signIn, TENANT_A, UNIT_A } from './fixtures';
import { installManagementApi } from './management-fixtures';

/**
 * CP-007/CP-053: varredura automática WCAG 2.1 A/AA (axe) nas telas principais, em desktop e celular. Não substitui o
 * ensaio com leitor de tela, fonte ampliada e aparelhos reais (CP-053), que segue pendente.
 */
test.use({ timezoneId: 'UTC' });

async function scan(page: Page, label: string) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const problems = result.violations.map((item) => `${item.id} (${item.impact}): ${item.nodes.slice(0, 3).map((node) => node.target.join(' ')).join(' | ')}`);
  expect(problems, `${label}: violações WCAG`).toEqual([]);
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
  const size = `${viewport.width}px`;
  test(`acessibilidade: entrada e escolha de contexto (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installSyntheticApi(page);
    await page.goto('/entrar');
    await expect(page.getByLabel('E-mail', { exact: true })).toBeVisible();
    await scan(page, 'entrar');
    await signIn(page);
    await scan(page, 'contextos');
  });

  test(`acessibilidade: unidade — início, reservas, encomendas e acessos (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installSyntheticApi(page);
    await signIn(page);
    await chooseContext(page, 'A', 'inicio');
    await scan(page, 'início');
    await page.getByRole('link', { name: 'Abrir reservas', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Reservas da unidade', exact: true })).toBeVisible();
    await scan(page, 'reservas');
    await page.goto(`/morador/${TENANT_A}/${UNIT_A}/encomendas`);
    await page.waitForLoadState('networkidle');
    await scan(page, 'encomendas');
    await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
    await page.waitForLoadState('networkidle');
    await scan(page, 'acessos');
  });

  test(`acessibilidade: gestão (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installSyntheticApi(page, { manager: true });
    await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
    await signIn(page);
    for (const section of ['pendencias', 'pessoas', 'avisos', 'pedidos', 'mudancas']) {
      await page.goto(`/gestao/${TENANT_A}/${section}`);
      await page.waitForLoadState('networkidle');
      await scan(page, `gestão/${section}`);
    }
  });
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
  const size = `${viewport.width}px`;
  test(`acessibilidade: portaria e páginas públicas (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const api = await installSyntheticApi(page);
    await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
    await page.route('**/v1/me', async (route) => {
      if (!api.authenticated) { await route.fallback(); return; }
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({ accountId: ACCOUNT,
        memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A, tenantName: 'Condomínio Sintético A',
          timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }] }) });
    });
    await page.goto('/recuperar-acesso');
    await page.waitForLoadState('networkidle');
    await scan(page, 'recuperar acesso');
    await page.goto('/visita');
    await page.waitForLoadState('networkidle');
    await scan(page, 'link de visita sem código');
    await signIn(page);
    for (const section of ['visitas', 'encomendas']) {
      await page.goto(`/portaria/${TENANT_A}/${section}`);
      await page.waitForLoadState('networkidle');
      await scan(page, `portaria/${section}`);
    }
  });
}
