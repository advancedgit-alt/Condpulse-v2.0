import { test, expect } from '@playwright/test';
import { chooseContext, futureWindow, installSyntheticApi, reservationFixture, signIn } from './fixtures';

test.use({ timezoneId: 'UTC' });

test('área com aprovação envia pedido pendente que nunca aparece como confirmado e pode ser retirado', async ({ page }) => {
  const state = await installSyntheticApi(page, { approvalRequired: true });
  await signIn(page); await chooseContext(page);
  await expect(page.getByText('Sujeita a aprovação', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Reservar esta área: Área Sintética A', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Solicitar esta área: Área Sintética A', exact: true }).click();
  await expect(page.getByText(/Esta área exige aprovação da gestão/)).toBeVisible();
  const window = futureWindow();
  await page.getByLabel('Início', { exact: true }).fill(window.startLocal);
  await page.getByLabel('Fim', { exact: true }).fill(window.endLocal);
  await page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ }).check();
  await expect(page.getByRole('button', { name: 'Confirmar reserva', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Enviar pedido', exact: true }).click();
  await expect(page.getByText('Pedido enviado para aprovação.', { exact: true })).toBeVisible();
  await expect(page.getByText(/Reserva confirmada/)).toHaveCount(0);
  expect(state.createKeys).toHaveLength(1);
  expect(state.reservations[0]!.status).toBe('PENDING_APPROVAL');
  const card = page.locator('.reservation-card');
  await expect(card.getByText('Aguardando aprovação', { exact: true })).toBeVisible();
  await expect(card.getByText(/ainda não é uma reserva confirmada/)).toBeVisible();
  await expect(card.getByText('Confirmada', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: /^Retirar pedido / }).click();
  await page.getByRole('button', { name: 'Confirmar retirada', exact: true }).click();
  await expect(page.getByText('Pedido retirado.', { exact: true })).toBeVisible();
  expect(state.cancelKeys).toHaveLength(1);
  await expect(card.getByText('Cancelada', { exact: true })).toBeVisible();
  await expect(card.getByText('Motivo: Cancelamento solicitado pelo autor.', { exact: true })).toBeVisible();
});

test('pedido não aprovado mostra o motivo da gestão e não oferece cancelamento', async ({ page }) => {
  await installSyntheticApi(page, { reservations: [{ ...reservationFixture(), status: 'REJECTED', decisionReason: 'Área em manutenção' }] });
  await signIn(page); await chooseContext(page);
  const card = page.locator('.reservation-card');
  await expect(card.getByText('Não aprovada', { exact: true })).toBeVisible();
  await expect(card.getByText('Motivo: Área em manutenção', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Cancelar reserva / })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Retirar pedido / })).toHaveCount(0);
});
