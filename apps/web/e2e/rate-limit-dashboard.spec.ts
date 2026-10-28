import { expect, test } from '@playwright/test';
import { chooseContext, installSyntheticApi, reservationFixture, signIn, TENANT_A, TENANT_B, UNIT_B } from './fixtures';

test('login limitado espera o prazo, não repete e permite nova tentativa explícita', async ({ page }) => {
  await page.clock.install();
  const state = await installSyntheticApi(page, { loginRateAttempts: 1, retryAfter: '10' });
  await page.goto('/entrar');
  await page.getByLabel('E-mail', { exact: true }).fill('synthetic@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC_PASSWORD');
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Aguarde para entrar', exact: true })).toBeDisabled();
  await expect(page.getByText(/Nova tentativa em \d+ s\./)).toBeVisible();
  await expect(page.getByLabel('Senha', { exact: true })).toHaveValue('');
  expect(state.loginRequests).toBe(1);
  await page.clock.fastForward(10_100);
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeEnabled();
  expect(state.loginRequests).toBe(1);
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC_PASSWORD');
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page).toHaveURL(/\/contextos/);
  expect(state.loginRequests).toBe(2);
});

test('refresh limitado não finge logout e saída continua disponível', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await signIn(page); await chooseContext(page);
  state.refreshRequired = true; state.rateRefresh = true;
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Não foi possível verificar seu acesso.', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tentar novamente', exact: true })).toBeDisabled();
  expect(state.refreshRequests).toBe(1); expect(state.authenticated).toBe(true);
  await page.getByRole('button', { name: 'Sair', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeVisible();
  expect(state.logoutRequests).toBe(1); expect(state.authenticated).toBe(false);
});

test('painel móvel mostra próximas reservas próprias e navegação preserva o contexto', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const own = reservationFixture();
  const other = { ...own, reservationId: '40000000-0000-4000-8000-000000000002', unitId: UNIT_B };
  await installSyntheticApi(page, { reservations: [own, other] });
  await signIn(page); await chooseContext(page, 'A', 'inicio');
  await expect(page.getByRole('heading', { name: 'Próximas reservas da unidade', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Área Sintética A', exact: true })).toBeVisible();
  await expect(page.getByText(/Consulta de 30 dias locais/)).toBeVisible();
  await expect(page.locator('.reservation-card')).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('navigation', { name: 'Navegação da unidade' }).getByRole('link', { name: 'Reservas', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Reservas da unidade', exact: true })).toBeVisible();
  await page.getByRole('navigation', { name: 'Navegação da unidade' }).getByRole('link', { name: 'Início', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/morador/${TENANT_A}/.*/inicio`));
  await expect(page.getByRole('heading', { name: 'Sua unidade, em dia.', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Trocar unidade', exact: true }).click();
  await chooseContext(page, 'B', 'inicio');
  await expect(page).toHaveURL(new RegExp(`/morador/${TENANT_B}/${UNIT_B}/inicio`));
  await expect(page.getByRole('heading', { name: 'Área Sintética A', exact: true })).toHaveCount(0);
});

test('painel remove cartões e catálogo quando o vínculo é negado', async ({ page }) => {
  const state = await installSyntheticApi(page, { reservations: [reservationFixture()] });
  await signIn(page); await chooseContext(page, 'A', 'inicio');
  await expect(page.getByRole('heading', { name: 'Área Sintética A', exact: true })).toBeVisible();
  state.denyAmenitiesA = true;
  await page.getByRole('button', { name: 'Atualizar resumo', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Os dados anteriores foram removidos.' })).toBeVisible();
  await expect(page.locator('.reservation-card')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Área Sintética A', exact: true })).toHaveCount(0);
});

test('painel recusa uma resposta que mistura reservas de outra unidade', async ({ page }) => {
  await installSyntheticApi(page);
  const foreign = { ...reservationFixture(), unitId: UNIT_B };
  await page.route(url => url.pathname === `/v1/tenants/${TENANT_A}/reservations`, route => route.fulfill({ status: 200,
    contentType: 'application/json', body: JSON.stringify({ reservations: [foreign], truncated: false }) }));
  await signIn(page); await chooseContext(page, 'A', 'inicio');
  await expect(page.getByRole('alert').filter({ hasText: 'A agenda retornada não pôde ser validada.' })).toBeVisible();
  await expect(page.locator('.reservation-card')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Tentar novamente', exact: true })).toBeVisible();
});
