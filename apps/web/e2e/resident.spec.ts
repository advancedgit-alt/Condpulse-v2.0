import { test, expect } from '@playwright/test';
import { chooseContext, futureWindow, installSyntheticApi, reservationFixture, signIn, TENANT_A, TENANT_B, UNIT_B } from './fixtures';

// O navegador usa outro fuso para que a conversão explícita do condomínio seja exercitada.
test.use({ timezoneId: 'UTC' });

test('login por teclado e contexto residencial em viewport móvel', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await installSyntheticApi(page);
  await signIn(page);
  await chooseContext(page);
  await expect(page.getByRole('button', { name: 'Reservar Área Sintética A', exact: true })).toBeVisible();
  expect(state.loginRequests).toBe(1);
  expect(await page.evaluate(() => window.localStorage.length)).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('resposta atrasada de A não reaparece após trocar para B', async ({ page }) => {
  let releaseA!: () => void;
  let requestedA!: () => void;
  let settledA!: () => void;
  const delayedA = new Promise<void>((resolve) => { releaseA = resolve; });
  const aWasRequested = new Promise<void>((resolve) => { requestedA = resolve; });
  const aWasSettled = new Promise<void>((resolve) => { settledA = resolve; });
  await installSyntheticApi(page, { delayAmenitiesA: delayedA, onAmenitiesARequested: requestedA,
    onAmenitiesASettled: settledA });
  try {
    await signIn(page);
    await chooseContext(page);
    await aWasRequested;
    await page.getByRole('button', { name: 'Trocar unidade', exact: true }).click();
    await chooseContext(page, 'B');
    await expect(page).toHaveURL(new RegExp(`/morador/${TENANT_B}/${UNIT_B}/reservas`));
    await expect(page.getByRole('button', { name: 'Reservar Área Sintética B', exact: true })).toBeVisible();
    releaseA();
    await aWasSettled;
    // Conferir depois de entregar/abortar A e de o navegador poder renderizar a resposta tardia.
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByText('Área Sintética A', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reservar Área Sintética B', exact: true })).toBeVisible();
  } finally { releaseA(); }
});

test('resposta perdida não confirma visualmente e retry preserva a intenção idempotente', async ({ page }) => {
  const state = await installSyntheticApi(page, { loseFirstCreationResponse: true });
  await signIn(page);
  await chooseContext(page);
  await page.getByRole('button', { name: 'Reservar Área Sintética A', exact: true }).click();
  const window = futureWindow();
  await page.getByLabel('Início', { exact: true }).fill(window.startLocal);
  await page.getByLabel('Fim', { exact: true }).fill(window.endLocal);
  await page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ }).check();
  await page.getByRole('button', { name: 'Confirmar reserva', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Tentar confirmar novamente', exact: true })).toBeVisible();
  await expect(page.getByText(/Reserva confirmada/)).toHaveCount(0);
  expect(state.createKeys).toHaveLength(1);
  expect(state.reservations).toHaveLength(1);
  await page.getByRole('button', { name: 'Tentar confirmar novamente', exact: true }).click();
  await expect(page.getByText(/Reserva confirmada/)).toBeVisible();
  expect(state.createKeys).toHaveLength(2);
  expect(state.createKeys[0]).toBeTruthy();
  expect(state.createKeys[0]).toBe(state.createKeys[1]);
  expect(state.reservations).toHaveLength(1);
  expect(new Date(state.reservations[0]!.startsAt).toISOString()).toBe(new Date(window.startsAt).toISOString());
  expect(new Date(state.reservations[0]!.endsAt).toISOString()).toBe(new Date(window.endsAt).toISOString());
});

test('cancelamento negado preserva a reserva e informa falha sem falso sucesso', async ({ page }) => {
  const state = await installSyntheticApi(page, { reservations: [reservationFixture()], cancelStatus: 404 });
  await signIn(page);
  await chooseContext(page);
  await page.getByRole('button', { name: /^Cancelar reserva/ }).click();
  await page.getByRole('button', { name: 'Confirmar cancelamento', exact: true }).click();
  const denied = page.getByRole('alert').filter({ hasText: 'A reserva ou a área não está disponível para esta operação.' });
  await expect(denied).toBeVisible();
  await expect(page.getByText(/Reserva cancelada/)).toHaveCount(0);
  expect(state.cancelKeys).toHaveLength(1);
  expect(state.reservations[0]?.status).toBe('CONFIRMED');
});

test('refetch de áreas negado remove nome, regras e formulário antigos antes de outro comando', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await signIn(page);
  await chooseContext(page);
  await page.getByRole('button', { name: 'Reservar Área Sintética A', exact: true }).click();
  await expect(page.getByLabel('Início', { exact: true })).toBeVisible();
  await expect(page.getByText('Condições da versão 1', { exact: true })).toBeVisible();
  state.denyAmenitiesA = true;
  const deniedResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === `/v1/tenants/${TENANT_A}/amenities` && response.status() === 403);
  await page.getByRole('button', { name: 'Atualizar áreas', exact: true }).click();
  await deniedResponse;
  await expect(page.getByRole('alert').filter({ hasText: 'Os dados anteriores foram removidos.' })).toBeVisible();
  await expect(page.getByText('Área Sintética A', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Condições da versão 1', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Início', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toHaveCount(0);
  expect(state.amenitiesARequests).toBeGreaterThanOrEqual(2);
  expect(state.createKeys).toHaveLength(0);
  expect(state.reservations).toHaveLength(0);
});

test('refetch válido com a mesma versão preserva horários e aceite do rascunho', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await signIn(page);
  await chooseContext(page);
  await page.getByRole('button', { name: 'Reservar Área Sintética A', exact: true }).click();
  const window = futureWindow();
  const start = page.getByLabel('Início', { exact: true });
  const end = page.getByLabel('Fim', { exact: true });
  const accepted = page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ });
  await start.fill(window.startLocal);
  await end.fill(window.endLocal);
  await accepted.check();
  const refreshedResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === `/v1/tenants/${TENANT_A}/amenities` && response.status() === 200);
  await page.getByRole('button', { name: 'Atualizar áreas', exact: true }).click();
  await (await refreshedResponse).finished();
  await expect(page.getByRole('button', { name: 'Atualizar áreas', exact: true })).toBeEnabled();
  await expect(start).toHaveValue(window.startLocal);
  await expect(end).toHaveValue(window.endLocal);
  await expect(accepted).toBeChecked();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  expect(state.amenitiesARequests).toBeGreaterThanOrEqual(2);
  expect(state.createKeys).toHaveLength(0);
  expect(state.reservations).toHaveLength(0);
});

test('logout remove contexto e navegação direta exige nova autenticação', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await signIn(page);
  await chooseContext(page);
  await expect(page.getByRole('button', { name: 'Reservar Área Sintética A', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Sair', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeVisible();
  expect(state.logoutRequests).toBe(1);
  expect(state.authenticated).toBe(false);
  await expect(page.getByText('Área Sintética A', { exact: true })).toHaveCount(0);
  await page.goto(`/morador/${TENANT_B}/${UNIT_B}/reservas`);
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeVisible();
  await expect(page.getByText('Área Sintética A', { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => window.localStorage.length)).toBe(0);
});
