import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/**
 * Varredura WCAG 2.1 A/AA (axe) da tela no estado atual. Antes, confere que nenhuma seção caiu em estado de erro: uma
 * varredura sobre a tela de erro passaria sem avaliar as listas e os controles que importam.
 */
export async function scanAccessibility(page: Page, label: string) {
  await expect(page.locator('.error-state'), `${label}: tela com estado de erro antes da varredura`).toHaveCount(0);
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const problems = result.violations.map((item) => `${item.id} (${item.impact}): ${item.nodes.slice(0, 3).map((node) => node.target.join(' ')).join(' | ')}`);
  expect(problems, `${label}: violações WCAG`).toEqual([]);
}
