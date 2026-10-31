import { test, expect } from '@playwright/test';
import { AMENITY_A, TENANT_A, UNIT_A, UNIT_B, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

test.use({ timezoneId: 'UTC' });

const at = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();
const entry = (overrides: Record<string, unknown>) => ({ waitlistId: '79000000-0000-4000-8000-000000000001', unitId: UNIT_A, unitLabel: '101 — Sintética A',
  amenityId: AMENITY_A, amenityName: 'Área Sintética A', state: 'WAITING', startsAt: at(2 * 24 * 60), endsAt: at(2 * 24 * 60 + 120), position: 1,
  offerExpiresAt: null, closedAt: null, createdAt: at(-60), ...overrides });

test('gestão vê a fila de espera em aberto, com posição e prazo da oferta, e as encerradas no filtro', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true, waitlist: [
    entry({ state: 'OFFERED', position: null, offerExpiresAt: at(12) }),
    entry({ waitlistId: '79000000-0000-4000-8000-000000000002', unitId: UNIT_B, unitLabel: '202 — Sintética B', position: 2 }),
    entry({ waitlistId: '79000000-0000-4000-8000-000000000003', state: 'LEFT', position: null, closedAt: at(-30) }),
  ] });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/gestao/reservas`);
  const section = page.getByRole('region', { name: 'Fila de espera' });
  const list = section.getByRole('list', { name: 'Fila de espera' });
  await expect(list.locator(':scope > li')).toHaveCount(2);
  await expect(list.getByText('Oferta em aberto')).toBeVisible();
  await expect(list.getByText(/Oferta vale até/)).toBeVisible();
  await expect(list.getByText(/Unidade 202 — Sintética B · 2º da fila/)).toBeVisible();
  await expect(section.getByText(/Somente leitura/)).toBeVisible();
  await expect(section.getByRole('button', { name: /Oferecer|Reordenar|Remover/ })).toHaveCount(0);
  await scanAccessibility(page, 'gestão/reservas com fila');
  await section.getByLabel('Filtrar fila de espera').selectOption('all');
  await expect(list.locator(':scope > li')).toHaveCount(3);
  await expect(list.getByText('Saiu da fila')).toBeVisible();
  expect(state.waitlistQueries).toEqual(['open', 'all']);
});

test('fim do período aparece com horário no mesmo dia e com data quando passa da meia-noite', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true, waitlist: [
    entry({ startsAt: '2030-01-10T22:00:00Z', endsAt: '2030-01-11T00:00:00Z' }),
    entry({ waitlistId: '79000000-0000-4000-8000-000000000002', unitId: UNIT_B, unitLabel: '202 — Sintética B', position: 2,
      startsAt: '2030-01-10T22:00:00Z', endsAt: '2030-01-11T04:00:00Z' }),
  ] });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/gestao/reservas`);
  const list = page.getByRole('region', { name: 'Fila de espera' }).getByRole('list', { name: 'Fila de espera' });
  await expect(list.getByText(/Área Sintética A · 10 de jan\.? de 2030,? 19:00 até 21:00$/)).toBeVisible();
  await expect(list.getByText(/Área Sintética A · 10 de jan\.? de 2030,? 19:00 até 11 de jan\.? de 2030,? 01:00$/)).toBeVisible();
});

test('fila vazia, falha ao atualizar e primeira consulta com erro', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  const state = await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true, waitlist: [], waitlistFailures: 1 });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/gestao/reservas`);
  const section = page.getByRole('region', { name: 'Fila de espera' });
  await section.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(section.getByText('Nenhuma unidade aguardando período.')).toBeVisible();
  state.waitlist = [entry({})];
  state.waitlistFailures = 1;
  await section.getByRole('button', { name: 'Atualizar fila' }).click();
  await expect(section.getByText('Não foi possível atualizar a fila. A lista é da última consulta.')).toBeVisible();
  await section.getByRole('button', { name: 'Atualizar fila' }).click();
  await expect(section.getByRole('list', { name: 'Fila de espera' }).locator(':scope > li')).toHaveCount(1);
  await expect(section.getByText(/Não foi possível atualizar a fila/)).toHaveCount(0);
});
