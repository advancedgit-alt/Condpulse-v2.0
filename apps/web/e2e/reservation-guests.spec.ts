import { test, expect, type Route } from '@playwright/test';
import { TENANT_A, UNIT_A, installSyntheticApi, reservationFixture, signIn } from './fixtures';

test.use({ timezoneId: 'UTC' });

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}

test('convidados da reserva: adicionar com chave, repetir incerto sem duplicar, remover e cancelar avisa', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const reservation = reservationFixture();
  api.reservations.push(reservation);
  const guests: Record<string, unknown>[] = [];
  const posts: { key: string; body: unknown; csrf: string | undefined }[] = [];
  const revokes: unknown[] = [];
  let loseNext = true;
  const route = `**/v1/tenants/${TENANT_A}/reservations/${reservation.reservationId}/guests`;
  await page.route(route, async (request) => {
    if (request.request().method() === 'GET') { await json(request, 200, { permits: guests, truncated: false }); return; }
    const key = request.request().headers()['idempotency-key']!;
    const body = request.request().postDataJSON() as { visitorName: string; documentLast4: string | null };
    posts.push({ key, body, csrf: request.request().headers()['x-csrf-token'] });
    const existing = guests.find((item) => item.key === key);
    if (!existing) guests.push({ key, permitId: `75000000-0000-4000-8000-${String(guests.length + 1).padStart(12, '0')}`, unitId: UNIT_A,
      unitLabel: '101', blockName: null, visitorName: body.visitorName, documentLast4: body.documentLast4, purpose: 'Convidado: Área',
      validFrom: new Date(Date.parse(reservation.startsAt) - 3_600_000).toISOString(), validUntil: reservation.endsAt, state: 'SCHEDULED',
      createdByMe: true, revokedAt: null, revokeReason: null, version: 0, createdAt: new Date().toISOString(), shareLinkActive: false,
      reservationId: reservation.reservationId });
    if (loseNext) { loseNext = false; await request.abort('connectionreset'); return; }
    const { key: _key, ...permit } = guests.find((item) => item.key === key)!;
    await json(request, 201, permit, { 'Idempotency-Replayed': existing ? 'true' : 'false' });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/permits/*/revoke`, async (request) => {
    const id = new URL(request.request().url()).pathname.split('/').at(-2)!;
    revokes.push(request.request().postDataJSON());
    const guest = guests.find((item) => item.permitId === id)!;
    Object.assign(guest, { state: 'REVOKED', revokedAt: new Date().toISOString(), revokeReason: 'Convidado removido pela unidade', version: 1 });
    const { key: _key, ...permit } = guest;
    await json(request, 200, permit);
  });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/reservas`);
  const card = page.locator('article.reservation-card').first();
  await card.getByRole('button', { name: 'Convidados' }).click();
  await expect(card.getByText('Nenhum convidado.')).toBeVisible();
  await card.getByLabel('Nome do convidado').fill('Convidada Fictícia');
  await card.getByLabel('Final do documento (opcional)').fill('12ab');
  await card.getByRole('button', { name: 'Adicionar convidado' }).click();
  // Resposta perdida: a mesma intenção fica presa à mesma chave.
  await expect(card.getByRole('button', { name: 'Tentar confirmar o mesmo convidado' })).toBeVisible();
  await expect(card.getByLabel('Nome do convidado')).toBeDisabled();
  await card.getByRole('button', { name: 'Tentar confirmar o mesmo convidado' }).click();
  await expect(card.getByText('Convidada Fictícia foi convidado(a).', { exact: false })).toBeVisible();
  expect(posts).toHaveLength(2);
  expect(posts[1]!.key).toBe(posts[0]!.key);
  expect(posts[0]!.body).toEqual({ visitorName: 'Convidada Fictícia', documentLast4: '12AB' });
  expect(posts[0]!.csrf).toBeTruthy();
  expect(guests).toHaveLength(1);
  const list = card.getByRole('list', { name: 'Convidados' });
  await expect(list.getByText('Convidada Fictícia')).toBeVisible();
  await list.getByRole('button', { name: 'Remover Convidada Fictícia' }).click();
  await list.getByRole('button', { name: 'Manter' }).click();
  expect(revokes).toHaveLength(0);
  await list.getByRole('button', { name: 'Remover Convidada Fictícia' }).click();
  await list.getByRole('button', { name: 'Confirmar remoção' }).click();
  await expect(list.getByText('Revogado')).toBeVisible();
  expect(revokes).toEqual([{ reason: 'Convidado removido pela unidade', expectedVersion: 0 }]);
  await card.getByRole('button', { name: /^Cancelar reserva/ }).click();
  await expect(card.getByText('Os convidados da reserva perdem o convite.', { exact: false })).toBeVisible();
});

test('recusa do servidor aparece e libera nova tentativa', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const reservation = reservationFixture();
  api.reservations.push(reservation);
  await page.route(`**/v1/tenants/${TENANT_A}/reservations/${reservation.reservationId}/guests`, async (request) => {
    if (request.request().method() === 'GET') { await json(request, 200, { permits: [], truncated: false }); return; }
    await request.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ code: 'RESERVATION_NOT_CONFIRMED' }) });
  });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/reservas`);
  const card = page.locator('article.reservation-card').first();
  await card.getByRole('button', { name: 'Convidados' }).click();
  await card.getByLabel('Nome do convidado').fill('Convidado Atrasado');
  await card.getByRole('button', { name: 'Adicionar convidado' }).click();
  await expect(card.getByText('Só reservas confirmadas e ainda não encerradas recebem convidados.')).toBeVisible();
  await expect(card.getByRole('button', { name: 'Adicionar convidado' })).toBeEnabled();
});

test('capacidade da área atingida esconde o formulário', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const reservation = reservationFixture();
  api.reservations.push(reservation);
  const guest = (n: number) => ({ permitId: `75000000-0000-4000-8000-${String(n).padStart(12, '0')}`, unitId: UNIT_A, unitLabel: '101', blockName: null,
    visitorName: `Convidado ${n}`, documentLast4: null, purpose: 'Convidado: Área', validFrom: reservation.startsAt, validUntil: reservation.endsAt,
    state: 'SCHEDULED', createdByMe: true, revokedAt: null, revokeReason: null, version: 0, createdAt: new Date().toISOString(), shareLinkActive: false,
    reservationId: reservation.reservationId });
  await page.route(`**/v1/tenants/${TENANT_A}/reservations/${reservation.reservationId}/guests`, (request) =>
    json(request, 200, { permits: Array.from({ length: 12 }, (_, index) => guest(index + 1)), truncated: false }));
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/reservas`);
  const card = page.locator('article.reservation-card').first();
  await card.getByRole('button', { name: 'Convidados' }).click();
  await expect(card.getByText('A área permite até 12 convidados nesta reserva.')).toBeVisible();
  await expect(card.getByRole('form', { name: 'Adicionar convidado' })).toHaveCount(0);
});
