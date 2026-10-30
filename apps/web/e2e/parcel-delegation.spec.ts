import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const PARCEL = '40000000-0000-4000-8000-0000000000d1';
async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000e' });
}
type Parcel = Record<string, unknown> & { parcelId: string; status: string; version: number };
type Delegation = Record<string, unknown> & { delegationId: string; state: string; version: number; attempts: number; attemptsLeft: number };
const parcelFixture = (): Parcel => ({ parcelId: PARCEL, unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: 'Bloco 1', quantity: 1,
  description: 'Caixa', storageLocation: 'Armário 4', status: 'RECEIVED', receivedAt: '2030-01-01T12:00:00.000Z', version: 0, handoff: null,
  voidReason: null, voidedAt: null });
const delegationFixture = (overrides: Partial<Delegation> = {}): Delegation => ({ delegationId: '41000000-0000-4000-8000-000000000001',
  parcelId: PARCEL, unitId: UNIT_A, delegateName: 'Terceiro Fictício', documentLast4: '9Z', validUntil: new Date(Date.now() + 48 * 3_600_000).toISOString(),
  state: 'ACTIVE', attempts: 0, attemptsLeft: 5, createdByMe: true, createdAt: new Date().toISOString(), usedAt: null, closedAt: null, version: 0, ...overrides });
interface State { parcel: Parcel; delegation: Delegation | null; creates: { key: string; body: Record<string, unknown> }[]; revokes: unknown[];
  handoffs: { key: string; body: Record<string, unknown> }[]; loseNextCreate: boolean; createFailure: string | null; code: string }

async function installApi(page: Page, api: ApiState, concierge: boolean): Promise<State> {
  const state: State = { parcel: parcelFixture(), delegation: null, creates: [], revokes: [], handoffs: [], loseNextCreate: false, createFailure: null, code: '48213907' };
  if (concierge) {
    await page.route('**/v1/me', async (route) => {
      if (!api.authenticated) { await route.fallback(); return; }
      await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
        tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }] });
    });
  }
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    const method = request.method();
    if (path === '/concierge/units') { await json(route, 200, [{ unitId: UNIT_A, label: '101 — Sintética A', blockName: 'Bloco 1' }]); return; }
    if (path === '/parcels' && method === 'GET') {
      const status = url.searchParams.get('status');
      await json(route, 200, { parcels: [state.parcel].filter((item) => !status || item.status === status), truncated: false }); return;
    }
    if (path === `/units/${UNIT_A}/parcels`) {
      const status = url.searchParams.get('status');
      await json(route, 200, { parcels: [state.parcel].filter((item) => !status || item.status === status), truncated: false }); return;
    }
    if (path === `/parcels/${PARCEL}/delegation` && method === 'GET') { await json(route, 200, { delegation: state.delegation }); return; }
    if (path === `/parcels/${PARCEL}/delegation` && method === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as Record<string, unknown>;
      state.creates.push({ key, body });
      if (state.createFailure) { const code = state.createFailure; state.createFailure = null; await problem(route, 403, code); return; }
      const replay = state.creates.filter((item) => item.key === key).length > 1;
      if (!replay) state.delegation = delegationFixture({ delegationId: `41000000-0000-4000-8000-${String(state.creates.length).padStart(12, '0')}`,
        delegateName: body.delegateName, documentLast4: body.documentLast4, validUntil: body.validUntil });
      if (state.loseNextCreate) { state.loseNextCreate = false; await route.abort('connectionreset'); return; }
      await json(route, 201, { delegation: state.delegation, code: replay ? null : state.code }, { 'Idempotency-Replayed': String(replay) }); return;
    }
    if (path === `/parcels/${PARCEL}/delegation/revoke`) {
      state.revokes.push(request.postDataJSON());
      Object.assign(state.delegation!, { state: 'REVOKED', closedAt: new Date().toISOString(), version: state.delegation!.version + 1, attemptsLeft: 0 });
      await json(route, 200, state.delegation); return;
    }
    if (path === `/parcels/${PARCEL}/handoff-code`) {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as Record<string, unknown>;
      state.handoffs.push({ key, body });
      const delegation = state.delegation!;
      if (body.code !== state.code) {
        delegation.attempts += 1; delegation.version += 1;
        if (delegation.attempts >= 5) { Object.assign(delegation, { state: 'LOCKED', attemptsLeft: 0, closedAt: new Date().toISOString() }); await problem(route, 409, 'DELEGATION_LOCKED'); return; }
        delegation.attemptsLeft = 5 - delegation.attempts;
        await problem(route, 409, 'DELEGATION_CODE_INVALID'); return;
      }
      Object.assign(delegation, { state: 'USED', usedAt: new Date().toISOString(), closedAt: new Date().toISOString(), version: delegation.version + 1, attemptsLeft: 0 });
      Object.assign(state.parcel, { status: 'DELIVERED', version: 1, handoff: { recipientName: delegation.delegateName, deliveredAt: '2030-01-01T15:00:00.000Z', evidenceKind: 'DELEGATION' } });
      await json(route, 200, state.parcel, { 'Idempotency-Replayed': 'false' }); return;
    }
    await route.fallback();
  });
  return state;
}

test('morador autoriza terceiro: código aparece uma vez, repetição incerta não mostra outro, nova autorização e cancelamento', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installApi(page, api, false);
  state.loseNextCreate = true;
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/encomendas`);
  const row = page.getByRole('list', { name: 'Encomendas a retirar' }).getByRole('listitem');
  await row.getByRole('button', { name: 'Autorizar retirada por terceiro' }).click();
  const form = row.getByRole('form', { name: 'Autorizar retirada por terceiro' });
  const submit = form.getByRole('button', { name: 'Gerar código' });
  await expect(submit).toBeDisabled();
  await form.getByLabel('Nome de quem vai retirar').fill('  Terceiro   Fictício ');
  await form.getByLabel('Final do documento (opcional)').fill('9z');
  await form.getByLabel('Válida até').fill('2020-01-01T10:00');
  await expect(form.getByText('A autorização vale por pelo menos 15 minutos.')).toBeVisible();
  await expect(submit).toBeDisabled();
  const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
  await form.getByLabel('Válida até').fill(`${tomorrow}T18:00`);
  await submit.click();
  await expect(form.getByText('Não foi possível confirmar a autorização.', { exact: false })).toBeVisible();
  await form.getByRole('button', { name: 'Tentar confirmar a mesma autorização' }).click();
  await expect(row.getByText('Esta autorização já estava criada, mas o código só aparece na primeira resposta.', { exact: false })).toBeVisible();
  expect(state.creates).toHaveLength(2);
  expect(state.creates[1]!.key).toBe(state.creates[0]!.key);
  expect(state.creates[0]!.body).toMatchObject({ delegateName: 'Terceiro Fictício', documentLast4: '9Z' });
  await expect(row.getByText('Autorização em vigor: Terceiro Fictício', { exact: false })).toBeVisible();
  await expect(row.getByText('5 tentativas restantes', { exact: false })).toBeVisible();

  await row.getByRole('button', { name: 'Gerar nova autorização' }).click();
  const renew = row.getByRole('form', { name: 'Autorizar retirada por terceiro' });
  await expect(renew.getByText('A nova autorização substitui a atual: o código anterior deixa de valer.')).toBeVisible();
  await renew.getByLabel('Nome de quem vai retirar').fill('Outra Pessoa');
  await renew.getByRole('button', { name: 'Gerar código' }).click();
  await expect(row.getByText(`Código para Outra Pessoa: ${state.code}`)).toBeVisible();
  expect(state.creates[2]!.key).not.toBe(state.creates[0]!.key);

  await row.getByRole('button', { name: 'Cancelar autorização' }).click();
  await row.getByRole('button', { name: 'Manter' }).click();
  await row.getByRole('button', { name: 'Cancelar autorização' }).click();
  await row.getByRole('button', { name: 'Confirmar cancelamento' }).click();
  await expect(row.getByText('Autorização de Outra Pessoa cancelada. O código deixou de valer.')).toBeVisible();
  expect(state.revokes).toEqual([{ delegationId: state.delegation!.delegationId, expectedVersion: 0 }]);
  await expect(row.getByText(`Código para Outra Pessoa`)).toHaveCount(0);
  await expect(row.getByText('Autorização cancelada: Outra Pessoa', { exact: false })).toBeVisible();
});

test('vínculo sem permissão de retirada vê a recusa do servidor ao autorizar', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installApi(page, api, false);
  state.createFailure = 'DELEGATION_NOT_ALLOWED';
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/encomendas`);
  const row = page.getByRole('list', { name: 'Encomendas a retirar' }).getByRole('listitem');
  await row.getByRole('button', { name: 'Autorizar retirada por terceiro' }).click();
  await row.getByLabel('Nome de quem vai retirar').fill('Terceiro');
  await row.getByRole('button', { name: 'Gerar código' }).click();
  await expect(row.getByText('Seu vínculo não permite autorizar ou registrar retirada desta encomenda.')).toBeVisible();
  await row.getByRole('button', { name: 'Voltar' }).click();
  await expect(row.getByRole('button', { name: 'Autorizar retirada por terceiro' })).toBeVisible();
});

test('portaria confere o código: errado conta tentativa, certo registra a retirada pela pessoa autorizada', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installApi(page, api, true);
  state.delegation = delegationFixture();
  await signIn(page);
  await page.goto('/contextos');
  await page.getByRole('link', { name: 'Abrir portaria · Condomínio Sintético A' }).click();
  const queue = page.getByRole('list', { name: 'Encomendas' });
  await queue.getByRole('button', { name: 'Retirada por código' }).click();
  const form = page.getByRole('form', { name: 'Retirada por código de Bloco 1 · 101 — Sintética A' });
  await expect(form.getByText('Autorizado(a) pela unidade:', { exact: false })).toBeVisible();
  await expect(form.getByText('Terceiro Fictício')).toBeVisible();
  await expect(form.getByText('Restam 5 tentativas de código.', { exact: false })).toBeVisible();
  const submit = form.getByRole('button', { name: 'Conferir código e registrar retirada' });
  await form.getByLabel('Código informado pela pessoa').fill('11111111');
  await expect(submit).toBeDisabled();
  await form.getByLabel('Conferi o documento com o nome autorizado').check();
  await submit.click();
  await expect(form.getByText('Código incorreto. Confira com a pessoa', { exact: false })).toBeVisible();
  await expect(form.getByText('Restam 4 tentativas de código.', { exact: false })).toBeVisible();
  await expect(form.getByLabel('Código informado pela pessoa')).toHaveValue('');
  await form.getByLabel('Código informado pela pessoa').fill(state.code);
  await submit.click();
  await expect(page.getByText('Retirada registrada: Terceiro Fictício, autorizada pela unidade, retirou a encomenda de Bloco 1 · 101 — Sintética A.')).toBeVisible();
  expect(state.handoffs).toHaveLength(2);
  expect(state.handoffs[1]!.body).toEqual({ code: state.code, identityChecked: true, expectedVersion: 0 });
  expect(state.handoffs[1]!.key).not.toBe(state.handoffs[0]!.key);
});

test('portaria: autorização bloqueada na quinta tentativa e encomenda sem autorização não aceitam código', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installApi(page, api, true);
  state.delegation = delegationFixture({ attempts: 4, attemptsLeft: 1, version: 4 });
  await signIn(page);
  await page.goto('/contextos');
  await page.getByRole('link', { name: 'Abrir portaria · Condomínio Sintético A' }).click();
  await page.getByRole('list', { name: 'Encomendas' }).getByRole('button', { name: 'Retirada por código' }).click();
  const form = page.getByRole('form', { name: 'Retirada por código de Bloco 1 · 101 — Sintética A' });
  await form.getByLabel('Código informado pela pessoa').fill('00000000');
  await form.getByLabel('Conferi o documento com o nome autorizado').check();
  await form.getByRole('button', { name: 'Conferir código e registrar retirada' }).click();
  await expect(form.getByText('Código incorreto pela quinta vez: a autorização foi bloqueada.', { exact: false })).toBeVisible();
  await expect(form.getByText('Autorização bloqueada por tentativas erradas. Peça à unidade uma nova autorização.')).toBeVisible();
  await expect(form.getByLabel('Código informado pela pessoa')).toBeDisabled();
  await form.getByRole('button', { name: 'Voltar' }).click();
  state.delegation = null;
  await page.getByRole('list', { name: 'Encomendas' }).getByRole('button', { name: 'Retirada por código' }).click();
  await expect(page.getByText('A unidade não autorizou ninguém para esta encomenda. Peça à unidade uma nova autorização.')).toBeVisible();
});
