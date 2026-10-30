import { test, expect, type Page } from '@playwright/test';
import { chooseContext, installSyntheticApi, signIn, TENANT_A } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

/**
 * CP-007/CP-053: varredura automática WCAG 2.1 A/AA (axe) nas telas sem dados de módulo, em desktop e celular; as telas
 * de encomendas, visitas, avisos, pedidos e credenciais são varridas nos próprios specs, com dados sintéticos carregados. Não substitui o
 * ensaio com leitor de tela, fonte ampliada e aparelhos reais (CP-053), que segue pendente.
 */
test.use({ timezoneId: 'UTC' });

const scan = scanAccessibility;

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

  test(`acessibilidade: unidade — início e reservas (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installSyntheticApi(page);
    await signIn(page);
    await chooseContext(page, 'A', 'inicio');
    await scan(page, 'início');
    await page.getByRole('link', { name: 'Abrir reservas', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Reservas da unidade', exact: true })).toBeVisible();
    await scan(page, 'reservas');
  });

  test(`acessibilidade: gestão (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installSyntheticApi(page, { manager: true });
    await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
    await signIn(page);
    for (const section of ['pendencias', 'pessoas']) {
      await page.goto(`/gestao/${TENANT_A}/${section}`);
      await page.waitForLoadState('networkidle');
      await scan(page, `gestão/${section}`);
    }
  });
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
  const size = `${viewport.width}px`;
  test(`acessibilidade: páginas públicas (${size})`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installSyntheticApi(page);
    await page.goto('/recuperar-acesso');
    await page.waitForLoadState('networkidle');
    await scan(page, 'recuperar acesso');
    await page.goto('/visita');
    await page.waitForLoadState('networkidle');
    await scan(page, 'link de visita sem código');
  });
}
