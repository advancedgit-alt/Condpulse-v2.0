import { test, expect, type Page, type Route } from '@playwright/test';
import { AMENITY_A, TENANT_A, UNIT_A, chooseContext, futureWindow, installSyntheticApi, signIn, type ApiState } from './fixtures';

test.use({ timezoneId: 'UTC' });

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000f' });
}
const ENTRY = '79000000-0000-4000-8000-0000000000a1';
const HELD = '78000000-0000-4000-8000-0000000000a1';
type Entry = Record<string, unknown> & { waitlistId: string; state: string; version: number };
function entryFixture(overrides: Partial<Entry> = {}): Entry {
  const window = futureWindow();
  return { waitlistId: ENTRY, unitId: UNIT_A, amenityId: AMENITY_A, state: 'WAITING', startsAt: window.startsAt, endsAt: window.endsAt,
    occupiedFrom: window.startsAt, occupiedUntil: window.endsAt, acceptedPolicyVersion: 1, approvalRequired: false, feeCents: 2500,
    offerReservationId: null, offeredAt: null, offerExpiresAt: null, closedAt: null, createdByMe: true, position: 1, version: 0,
    createdAt: new Date().toISOString(), ...overrides };
}
function offered(overrides: Partial<Entry> = {}): Entry {
  return entryFixture({ state: 'OFFERED', offerReservationId: HELD, offeredAt: new Date().toISOString(),
    offerExpiresAt: new Date(Date.now() + 10 * 60_000).toISOString(), position: null, version: 1, ...overrides });
}
interface WaitState { joins: { key: string; body: Record<string, unknown> }[]; leaves: unknown[]; accepts: { key: string; body: unknown }[];
  loseNextJoin: boolean; loseNextAccept: boolean; joinFailure: string | null; acceptFailure: string | null }

async function installWaitlist(page: Page, api: ApiState): Promise<WaitState> {
  const state: WaitState = { joins: [], leaves: [], accepts: [], loseNextJoin: false, loseNextAccept: false, joinFailure: null, acceptFailure: null };
  const entries = api.waitlist as Entry[];
  await page.route(`**/v1/tenants/${TENANT_A}/waitlist`, async (route) => {
    const key = route.request().headers()['idempotency-key']!;
    const body = route.request().postDataJSON() as Record<string, unknown>;
    state.joins.push({ key, body });
    if (state.joinFailure) { const code = state.joinFailure; state.joinFailure = null; await problem(route, 409, code); return; }
    const existing = entries.find((item) => item.key === key);
    if (!existing) entries.unshift(entryFixture({ key, waitlistId: `79000000-0000-4000-8000-${String(state.joins.length).padStart(12, '0')}`,
      startsAt: body.startsAt, endsAt: body.endsAt, occupiedFrom: body.startsAt, occupiedUntil: body.endsAt }));
    if (state.loseNextJoin) { state.loseNextJoin = false; await route.abort('connectionreset'); return; }
    const { key: _key, ...entry } = entries.find((item) => item.key === key)!;
    await json(route, 201, entry, { 'Idempotency-Replayed': existing ? 'true' : 'false' });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/waitlist/*/leave`, async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!;
    state.leaves.push(route.request().postDataJSON());
    const entry = entries.find((item) => item.waitlistId === id)!;
    Object.assign(entry, { state: 'LEFT', closedAt: new Date().toISOString(), version: entry.version + 1, position: null });
    const { key: _key, ...result } = entry;
    await json(route, 200, result);
  });
  await page.route(`**/v1/tenants/${TENANT_A}/waitlist/*/accept`, async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!;
    const key = route.request().headers()['idempotency-key']!;
    state.accepts.push({ key, body: route.request().postDataJSON() });
    if (state.acceptFailure) { const code = state.acceptFailure; state.acceptFailure = null; await problem(route, 409, code); return; }
    const entry = entries.find((item) => item.waitlistId === id)!;
    // Resposta perdida antes de gravar: a oferta continua e a repetição com a mesma chave confirma uma única vez.
    if (state.loseNextAccept) { state.loseNextAccept = false; await route.abort('connectionreset'); return; }
    if (entry.state === 'OFFERED') Object.assign(entry, { state: 'ACCEPTED', closedAt: new Date().toISOString(), version: entry.version + 1 });
    const { key: _key, ...result } = entry;
    await json(route, 200, { entry: result, reservation: { reservationId: HELD, unitId: UNIT_A, amenityId: AMENITY_A, status: 'CONFIRMED',
      startsAt: entry.startsAt, endsAt: entry.endsAt, occupiedFrom: entry.occupiedFrom, occupiedUntil: entry.occupiedUntil,
      acceptedPolicyVersion: 1, feeCents: 2500, version: 1, decisionReason: null } });
  });
  return state;
}
async function openBooking(page: Page) {
  await signIn(page); await chooseContext(page);
  await page.getByRole('button', { name: 'Reservar Área Sintética A', exact: true }).click();
}

test('período ocupado: entrar na fila exige aceite das regras, repete na mesma chave e sai da fila com confirmação', async ({ page }) => {
  const window = futureWindow();
  const api = await installSyntheticApi(page, { blockedIntervals: [{ occupiedFrom: window.startsAt, occupiedUntil: window.endsAt }] });
  const state = await installWaitlist(page, api);
  state.loseNextJoin = true;
  await openBooking(page);
  await expect(page.getByRole('heading', { name: 'Fila de espera', exact: true })).toBeVisible();
  await expect(page.getByText('Nenhuma fila de espera', { exact: true })).toBeVisible();
  await page.getByLabel('Início', { exact: true }).fill(window.startLocal);
  await page.getByLabel('Fim', { exact: true }).fill(window.endLocal);
  await expect(page.getByText(/O período informado encontra um intervalo bloqueado/)).toBeVisible();
  await expect(page.getByText('Para entrar na fila de espera, aceite as regras da versão 1.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Entrar na fila de espera' })).toHaveCount(0);
  await page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ }).check();
  await page.getByRole('button', { name: 'Entrar na fila de espera' }).click();
  await expect(page.getByRole('button', { name: 'Tentar confirmar a mesma entrada' })).toBeVisible();
  await page.getByRole('button', { name: 'Tentar confirmar a mesma entrada' }).click();
  await expect(page.getByText('Você já estava na fila deste período. Posição: 1. Acompanhe na seção Fila de espera.')).toBeVisible();
  expect(state.joins).toHaveLength(2);
  expect(state.joins[1]!.key).toBe(state.joins[0]!.key);
  expect(state.joins[0]!.key).toMatch(/^[A-Za-z0-9._:-]{16,200}$/);
  expect(state.joins[0]!.body).toEqual({ unitId: UNIT_A, amenityId: AMENITY_A, startsAt: window.startsAt.replace('Z', '.000Z'), endsAt: window.endsAt.replace('Z', '.000Z'), acceptedPolicyVersion: 1 });

  const list = page.getByRole('list', { name: 'Entradas da fila de espera' });
  const row = list.getByRole('listitem').filter({ hasText: 'Área Sintética A' });
  await expect(row.getByText('Na fila', { exact: true })).toBeVisible();
  await expect(row.getByText('Posição na fila: 1.')).toBeVisible();
  await row.getByRole('button', { name: 'Sair da fila' }).click();
  await row.getByRole('button', { name: 'Voltar' }).click();
  await expect(row.getByRole('button', { name: 'Confirmar saída' })).toHaveCount(0);
  await row.getByRole('button', { name: 'Sair da fila' }).click();
  await row.getByRole('button', { name: 'Confirmar saída' }).click();
  await expect(page.getByText('Você saiu da fila de Área Sintética A.')).toBeVisible();
  expect(state.leaves).toEqual([{ unitId: UNIT_A, expectedVersion: 0 }]);
  await expect(row.getByText('Saiu da fila', { exact: true })).toBeVisible();
});

test('vaga oferecida: aceite com resultado incerto trava e repete na mesma chave; outra oferta é recusada', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installWaitlist(page, api);
  (api.waitlist as Entry[]).push(offered(), offered({ waitlistId: '79000000-0000-4000-8000-0000000000a2', offerReservationId: '78000000-0000-4000-8000-0000000000a2',
    startsAt: new Date(Date.parse(futureWindow().startsAt) + 86_400_000).toISOString(), endsAt: new Date(Date.parse(futureWindow().endsAt) + 86_400_000).toISOString() }));
  state.loseNextAccept = true;
  await signIn(page); await chooseContext(page);
  const rows = page.getByRole('list', { name: 'Entradas da fila de espera' }).getByRole('listitem');
  await expect(rows).toHaveCount(2);
  const first = rows.nth(0);
  await expect(first.getByText('Vaga oferecida', { exact: true })).toBeVisible();
  await expect(first.getByText(/Aceite até .*Ao aceitar, a reserva é confirmada\./)).toBeVisible();
  await first.getByRole('button', { name: 'Aceitar vaga' }).click();
  await expect(first.getByText('Não foi possível confirmar o aceite. A próxima tentativa usa o mesmo envio, sem criar outra reserva.')).toBeVisible();
  await expect(first.getByRole('button', { name: 'Recusar vaga' })).toBeDisabled();
  await first.getByRole('button', { name: 'Tentar confirmar o mesmo aceite' }).click();
  await expect(page.getByText('Reserva de Área Sintética A confirmada pela fila de espera.')).toBeVisible();
  await expect(page.getByText('Oferta aceita', { exact: true })).toBeVisible();
  expect(state.accepts).toHaveLength(2);
  expect(state.accepts[1]!.key).toBe(state.accepts[0]!.key);
  expect(state.accepts[0]!.body).toEqual({ unitId: UNIT_A, expectedVersion: 1 });

  const second = rows.filter({ hasText: 'Vaga oferecida' });
  await second.getByRole('button', { name: 'Recusar vaga' }).click();
  await expect(second.getByText(/Recusar esta vaga\? Ela passa ao próximo da fila/)).toBeVisible();
  await second.getByRole('button', { name: 'Confirmar recusa' }).click();
  await expect(page.getByText('Vaga de Área Sintética A recusada. A vez passa ao próximo da fila.')).toBeVisible();
  expect(state.leaves).toEqual([{ unitId: UNIT_A, expectedVersion: 1 }]);
});

test('aceite confirmado mostra a reserva; oferta vencida e entrada de outra pessoa não oferecem ação', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installWaitlist(page, api);
  (api.waitlist as Entry[]).push(offered(),
    offered({ waitlistId: '79000000-0000-4000-8000-0000000000a3', offerExpiresAt: new Date(Date.now() - 60_000).toISOString(),
      startsAt: new Date(Date.parse(futureWindow().startsAt) + 86_400_000).toISOString(), endsAt: new Date(Date.parse(futureWindow().endsAt) + 86_400_000).toISOString() }),
    entryFixture({ waitlistId: '79000000-0000-4000-8000-0000000000a4', createdByMe: false, position: 2,
      startsAt: new Date(Date.parse(futureWindow().startsAt) + 2 * 86_400_000).toISOString(), endsAt: new Date(Date.parse(futureWindow().endsAt) + 2 * 86_400_000).toISOString() }));
  await signIn(page); await chooseContext(page);
  const rows = page.getByRole('list', { name: 'Entradas da fila de espera' }).getByRole('listitem');
  await expect(rows).toHaveCount(3);
  const expired = rows.nth(1);
  await expect(expired.getByText('O prazo desta oferta terminou. Atualize a fila.')).toBeVisible();
  await expect(expired.getByRole('button', { name: 'Aceitar vaga' })).toBeDisabled();
  const other = rows.nth(2);
  await expect(other.getByText('Entrada de outra pessoa da unidade: somente quem entrou na fila pode alterá-la.')).toBeVisible();
  await expect(other.getByRole('button')).toHaveCount(0);
  state.acceptFailure = 'WAITLIST_OFFER_EXPIRED';
  await rows.nth(0).getByRole('button', { name: 'Aceitar vaga' }).click();
  await expect(rows.nth(0).getByText('O prazo da oferta terminou e a vaga seguiu para o próximo da fila.')).toBeVisible();
  await rows.nth(0).getByRole('button', { name: 'Aceitar vaga' }).click();
  await expect(page.getByText('Reserva de Área Sintética A confirmada pela fila de espera.')).toBeVisible();
  expect(state.accepts).toHaveLength(2);
});

test('recusa do servidor ao entrar na fila aparece; falha ao consultar a fila mostra nova tentativa', async ({ page }) => {
  const window = futureWindow();
  const api = await installSyntheticApi(page, { blockedIntervals: [{ occupiedFrom: window.startsAt, occupiedUntil: window.endsAt }] });
  const state = await installWaitlist(page, api);
  let failures = 1;
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/waitlist`, async (route) => {
    if (failures-- > 0) { await problem(route, 500, 'INTERNAL_ERROR'); return; }
    await route.fallback();
  });
  await openBooking(page);
  const section = page.getByRole('region', { name: 'Fila de espera' });
  await section.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(section.getByText('Nenhuma fila de espera', { exact: true })).toBeVisible();
  await page.getByLabel('Início', { exact: true }).fill(window.startLocal);
  await page.getByLabel('Fim', { exact: true }).fill(window.endLocal);
  await page.getByRole('checkbox', { name: /Li e aceito as regras da versão 1/ }).check();
  state.joinFailure = 'WAITLIST_ALREADY_JOINED';
  await page.getByRole('button', { name: 'Entrar na fila de espera' }).click();
  await expect(page.getByText('A unidade já está na fila deste período.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Entrar na fila de espera' })).toBeEnabled();
});
