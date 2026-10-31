import { test, expect, type Page } from '@playwright/test';
import { chooseContext, futureWindow, installSyntheticApi, signIn, TENANT_A, TENANT_B, UNIT_B } from './fixtures';

test.use({ timezoneId: 'UTC' });

async function openBooking(page: Page) {
  await signIn(page); await chooseContext(page);
  await page.getByRole('button', { name: 'Reservar esta área: Área Sintética A', exact: true }).click();
}
async function fillWindow(page: Page) {
  const window = futureWindow();
  await page.getByLabel('Início', { exact: true }).fill(window.startLocal);
  await page.getByLabel('Fim', { exact: true }).fill(window.endLocal);
  await page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ }).check();
  return window;
}

test('ocupação da área não depende da agenda da unidade e a limpeza impede sobreposição', async ({ page }) => {
  const window = futureWindow(); const date = window.startsAt.slice(0, 10);
  const state = await installSyntheticApi(page, { blockedIntervals: [
    { occupiedFrom: `${date}T16:10:00Z`, occupiedUntil: `${date}T17:15:00Z` },
  ] });
  await openBooking(page); await fillWindow(page);
  await expect(page.getByText('Nenhuma reserva neste período', { exact: true })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Intervalos bloqueados da área', exact: true })).toBeVisible();
  await expect(page.getByText(/O período informado encontra um intervalo bloqueado/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeDisabled();
  expect(state.createKeys).toHaveLength(0);
  expect(state.availabilityRequests.some(request => request.tenantId === TENANT_A && request.from.startsWith(date))).toBe(true);
  await page.getByLabel('Fim', { exact: true }).fill(`${date}T12:55`);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Confirmar reserva', exact: true }).click();
  await expect(page.getByText('Reserva confirmada.', { exact: true })).toBeVisible();
  expect(state.createKeys).toHaveLength(1);
  expect(state.reservations[0]!.endsAt).toBe(`${date}T15:55:00.000Z`);
});

test('conflito concorrente atualiza a ocupação e não repete o comando', async ({ page }) => {
  const state = await installSyntheticApi(page, { createConflictAttempts: 1 });
  await openBooking(page); await fillWindow(page);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  const before = state.availabilityRequests.length;
  await page.getByRole('button', { name: 'Confirmar reserva', exact: true }).click();
  await expect(page.getByText(/O período foi ocupado/)).toBeVisible();
  await expect(page.getByText(/O período informado encontra um intervalo bloqueado/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeDisabled();
  expect(state.availabilityRequests.length).toBeGreaterThan(before);
  expect(state.createKeys).toHaveLength(1);
  expect(state.reservations).toHaveLength(0);
});

test('falha de consulta conserva o rascunho e marca o resultado anterior sem habilitar confirmação', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await openBooking(page); const window = await fillWindow(page);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  state.availabilityFailure = 503;
  await page.getByRole('button', { name: 'Atualizar disponibilidade', exact: true }).click();
  await expect(page.getByText(/Não foi possível consultar a ocupação da área agora/)).toBeVisible();
  await expect(page.getByText(/Consulta anterior. Atualize a disponibilidade/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeDisabled();
  await expect(page.getByLabel('Início', { exact: true })).toHaveValue(window.startLocal);
  await expect(page.getByLabel('Fim', { exact: true })).toHaveValue(window.endLocal);
  await expect(page.getByText('PRIVATE-AVAILABILITY-DETAIL', { exact: true })).toHaveCount(0);
  expect(state.createKeys).toHaveLength(0);
});

test('consulta com mais de trinta segundos exige atualização antes de confirmar', async ({ page }) => {
  await page.clock.install();
  const state = await installSyntheticApi(page);
  await openBooking(page); await fillWindow(page);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  await page.clock.runFor(31_000);
  await expect(page.getByText(/Consulta anterior. Atualize a disponibilidade/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Atualizar disponibilidade', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  expect(state.createKeys).toHaveLength(0);
});

test('revogação na consulta remove o contexto e os intervalos anteriores', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await openBooking(page); await fillWindow(page);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toBeEnabled();
  state.denyAvailabilityA = true;
  await page.getByRole('button', { name: 'Atualizar disponibilidade', exact: true }).click();
  await expect(page.getByText(/Este contexto não está disponível para a operação/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Disponibilidade da área', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toHaveCount(0);
  expect(state.createKeys).toHaveLength(0);
});

test('consulta atrasada de A não reaparece depois da troca para B', async ({ page }) => {
  let release!: () => void; let requested!: () => void; let settled!: () => void;
  const delayed = new Promise<void>(resolve => { release = resolve; });
  const wasRequested = new Promise<void>(resolve => { requested = resolve; });
  const wasSettled = new Promise<void>(resolve => { settled = resolve; });
  const state = await installSyntheticApi(page, { delayAvailabilityA: delayed,
    onAvailabilityARequested: requested, onAvailabilityASettled: settled });
  try {
    await openBooking(page); await wasRequested;
    await page.getByRole('button', { name: 'Trocar condomínio', exact: true }).click();
    await chooseContext(page, 'B');
    await page.getByRole('button', { name: 'Reservar esta área: Área Sintética B', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/c/${TENANT_B}/morador/${UNIT_B}/reservas`));
    await expect(page.getByText(/Nenhum intervalo bloqueado nesta consulta/)).toBeVisible();
    release(); await wasSettled;
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByRole('heading', { name: 'Área Sintética A', exact: true })).toHaveCount(0);
    await expect(page.locator('#booking-title')).toHaveText('Área Sintética B');
    expect(state.availabilityRequests.some(request => request.tenantId === TENANT_B && request.unitId === UNIT_B)).toBe(true);
  } finally { release(); }
});
