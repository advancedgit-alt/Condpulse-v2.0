import { test, expect } from '@playwright/test';
import { TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

test.use({ timezoneId: 'UTC' });

async function openManagement(page: import('@playwright/test').Page, section: string) {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/${section}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Condomínio Sintético A' })).toBeVisible();
}

test('menu lateral em tela baixa rola até o último item sem cobrir o rodapé', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 560 });
  await openManagement(page, 'pendencias');
  const nav = page.getByRole('navigation', { name: 'Navegação principal' });
  await expect(page.getByRole('button', { name: 'Abrir menu' })).toBeHidden();
  const last = nav.getByRole('link', { name: 'Auditoria' });
  await last.scrollIntoViewIfNeeded();
  await expect(last).toBeInViewport();
  // A lista rola dentro da barra; a página não ganha rolagem por causa dela e o rodapé fica abaixo da lista.
  const layout = await page.evaluate(() => {
    const list = document.querySelector('.sidebar-nav')!.getBoundingClientRect();
    const bottom = document.querySelector('.sidebar-bottom')!.getBoundingClientRect();
    const sidebar = document.querySelector('.sidebar')!.getBoundingClientRect();
    return { overlap: list.bottom > bottom.top + 0.5, fits: sidebar.height <= window.innerHeight + 0.5 };
  });
  expect(layout).toEqual({ overlap: false, fits: true });
  await last.click();
  await expect(last).toHaveAttribute('aria-current', 'page');
});

for (const viewport of [{ width: 390, height: 844 }, { width: 1000, height: 700 }]) {
  test(`menu recolhível acessível em ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openManagement(page, 'pendencias');
    const toggle = page.getByRole('button', { name: 'Abrir menu' });
    const nav = page.getByRole('navigation', { name: 'Navegação principal' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(nav).toBeHidden();
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('button', { name: 'Fechar menu' })).toBeFocused();
    await scanAccessibility(page, `menu aberto ${viewport.width}px`);
    await page.keyboard.press('Escape');
    await expect(nav).toBeHidden();
    await expect(toggle).toBeFocused();
    await toggle.click();
    await nav.getByRole('link', { name: 'Ocorrências' }).click();
    await expect(page).toHaveURL(new RegExp(`/gestao/${TENANT_A}/ocorrencias$`));
    await expect(nav).toBeHidden();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });
}

test('abas da gestão em uma linha, roláveis e com a aba atual visível no celular', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openManagement(page, 'auditoria');
  const tabs = page.getByRole('navigation', { name: 'Seções da gestão' });
  await expect(tabs.getByRole('link')).toHaveCount(14);
  const metrics = await tabs.evaluate((nav) => {
    const links = [...nav.querySelectorAll('a')];
    const tops = new Set(links.map((link) => Math.round(link.getBoundingClientRect().top)));
    const box = nav.getBoundingClientRect();
    const active = nav.querySelector('[aria-current="page"]')!.getBoundingClientRect();
    return { rows: tops.size, scrolls: nav.scrollWidth > nav.clientWidth, activeVisible: active.left >= box.left - 1 && active.right <= box.right + 1,
      // Alvo de toque mínimo de 48 px (PRD) mantido nas abas compactas.
      touchTargets: links.every((link) => link.getBoundingClientRect().height >= 48) };
  });
  expect(metrics).toEqual({ rows: 1, scrolls: true, activeVisible: true, touchTargets: true });
  const toggle = await page.getByRole('button', { name: 'Abrir menu' }).boundingBox();
  expect(toggle && toggle.height >= 48 && toggle.width >= 48).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
