import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

test.use({ timezoneId: 'America/Sao_Paulo' });

interface Occurrence {
  occurrenceId: string; category: string; title: string; description: string; unitId: string | null; unitLabel: string | null; state: 'OPEN' | 'RESOLVED';
  createdByName: string; createdByMe: boolean; createdAt: string; resolution: string | null; resolvedByName: string | null; resolvedAt: string | null; version: number;
}
async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, traceId: '60000000-0000-4000-8000-000000000009' });
}
async function asConcierge(page: Page, api: ApiState) {
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }] });
  });
}
const existing = (): Occurrence => ({ occurrenceId: '89000000-0000-4000-8000-000000000001', category: 'EQUIPMENT', title: 'Portão da garagem',
  description: 'Não fecha sozinho.', unitId: null, unitLabel: null, state: 'OPEN', createdByName: 'Outra Pessoa da Portaria', createdByMe: false,
  createdAt: new Date(Date.now() - 3_600_000).toISOString(), resolution: null, resolvedByName: null, resolvedAt: null, version: 0 });

async function installOccurrences(page: Page, initial: Occurrence[] = [existing()]) {
  const state = { items: initial, creates: [] as Record<string, unknown>[], resolves: [] as Record<string, unknown>[], loseNextCreate: false, listError: false };
  await page.route(`**/v1/tenants/${TENANT_A}/concierge/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    if (path === '/concierge/units') { await json(route, 200, [{ unitId: UNIT_A, label: '101 — Sintética A', blockName: 'Bloco 1' }]); return; }
    if (path === '/concierge/occurrences' && request.method() === 'GET') {
      if (state.listError) { await problem(route, 503, 'OCCURRENCE_DEPENDENCY_UNAVAILABLE'); return; }
      const resolved = url.searchParams.get('resolved') === 'true';
      const items = state.items.filter((item) => item.state === 'OPEN' || resolved);
      await json(route, 200, { occurrences: items, open: state.items.filter((item) => item.state === 'OPEN').length, truncated: false });
      return;
    }
    if (path === '/concierge/occurrences' && request.method() === 'POST') {
      const body = request.postDataJSON() as { occurrenceId: string; category: string; title: string; description: string; unitId: string | null };
      state.creates.push(body);
      let found = state.items.find((item) => item.occurrenceId === body.occurrenceId);
      const replayed = Boolean(found);
      if (!found) {
        found = { occurrenceId: body.occurrenceId, category: body.category, title: body.title, description: body.description, unitId: body.unitId,
          unitLabel: body.unitId ? '101 — Sintética A' : null, state: 'OPEN', createdByName: 'Pessoa da Portaria', createdByMe: true,
          createdAt: new Date().toISOString(), resolution: null, resolvedByName: null, resolvedAt: null, version: 0 };
        state.items = [...state.items, found];
      }
      if (state.loseNextCreate) { state.loseNextCreate = false; await route.abort('connectionreset'); return; }
      await json(route, replayed ? 200 : 201, found, { 'Idempotency-Replayed': String(replayed) });
      return;
    }
    const resolve = path.match(/^\/concierge\/occurrences\/([^/]+)\/resolve$/);
    if (resolve) {
      const body = request.postDataJSON() as { resolution: string; expectedVersion: number };
      state.resolves.push(body);
      const item = state.items.find((candidate) => candidate.occurrenceId === resolve[1])!;
      if (item.version !== body.expectedVersion) { await problem(route, 409, 'OCCURRENCE_CHANGED'); return; }
      Object.assign(item, { state: 'RESOLVED', resolution: body.resolution, resolvedByName: 'Pessoa da Portaria', resolvedAt: new Date().toISOString(),
        version: item.version + 1 });
      await json(route, 200, item);
      return;
    }
    await route.fallback();
  });
  return state;
}

test('portaria registra ocorrência, repete o envio incerto sem duplicar e encerra com resolução', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await asConcierge(page, api);
  const state = await installOccurrences(page);
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/portaria/ocorrencias`);
  await expect(page.getByRole('heading', { name: 'Ocorrências · 1 em aberto' })).toBeVisible();
  const list = page.getByRole('list', { name: 'Ocorrências' });
  await expect(list.getByText('Portão da garagem')).toBeVisible();
  await expect(list.getByText('registrada por Outra Pessoa da Portaria', { exact: false })).toBeVisible();
  await scanAccessibility(page, 'ocorrências da portaria');
  await page.getByRole('button', { name: 'Registrar ocorrência' }).click();
  await page.getByRole('button', { name: 'Voltar' }).click();
  expect(state.creates).toEqual([]);
  await page.getByRole('button', { name: 'Registrar ocorrência' }).click();
  const form = page.getByRole('form', { name: 'Registrar ocorrência' });
  await expect(form.getByRole('button', { name: 'Salvar ocorrência' })).toBeDisabled();
  await form.getByLabel('Categoria').selectOption('SECURITY');
  await form.getByLabel('Título').fill('  Porta   do hall aberta ');
  await form.getByLabel('Descrição').fill('Encontrada aberta na ronda.');
  await form.getByLabel('Unidade (opcional)').selectOption(UNIT_A);
  state.loseNextCreate = true;
  await form.getByRole('button', { name: 'Salvar ocorrência' }).click();
  await expect(form.getByRole('button', { name: 'Tentar confirmar a mesma ocorrência' })).toBeVisible();
  await expect(form.getByLabel('Título')).toBeDisabled();
  await form.getByRole('button', { name: 'Tentar confirmar a mesma ocorrência' }).click();
  await expect(page.getByText('Esta ocorrência já estava registrada. Nada novo foi criado.')).toBeVisible();
  expect(state.creates).toHaveLength(2);
  expect(state.creates[1]!.occurrenceId).toBe(state.creates[0]!.occurrenceId);
  expect(state.creates[0]).toMatchObject({ category: 'SECURITY', title: 'Porta do hall aberta', description: 'Encontrada aberta na ronda.', unitId: UNIT_A });
  await expect(page.getByRole('heading', { name: 'Ocorrências · 2 em aberto' })).toBeVisible();
  const mine = list.getByRole('listitem').filter({ hasText: 'Porta do hall aberta' });
  await expect(mine.getByText('registrada por você', { exact: false })).toBeVisible();
  await mine.getByRole('button', { name: 'Encerrar ocorrência' }).click();
  await mine.getByRole('button', { name: 'Voltar' }).click();
  await mine.getByRole('button', { name: 'Encerrar ocorrência' }).click();
  await expect(mine.getByRole('button', { name: 'Confirmar encerramento' })).toBeDisabled();
  await mine.getByLabel('Resolução').fill('Porta fechada e síndico avisado.');
  await mine.getByRole('button', { name: 'Confirmar encerramento' }).click();
  await expect(page.getByText('Ocorrência encerrada com a resolução registrada.')).toBeVisible();
  expect(state.resolves).toEqual([{ resolution: 'Porta fechada e síndico avisado.', expectedVersion: 0 }]);
  await expect(page.getByRole('heading', { name: 'Ocorrências · 1 em aberto' })).toBeVisible();
  await page.getByLabel('Mostrar encerradas dos últimos 30 dias').check();
  await expect(list.getByRole('listitem').filter({ hasText: 'Porta do hall aberta' }).getByText('Encerrada', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Atualizar ocorrências' }).click();
});

test('versão alterada é explicada; falha da lista oferece nova tentativa', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await asConcierge(page, api);
  const state = await installOccurrences(page);
  state.listError = true;
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/portaria/ocorrencias`);
  await expect(page.getByRole('button', { name: 'Tentar novamente' })).toBeVisible({ timeout: 15000 });
  state.listError = false;
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  const row = page.getByRole('list', { name: 'Ocorrências' }).getByRole('listitem').filter({ hasText: 'Portão da garagem' });
  await row.getByRole('button', { name: 'Encerrar ocorrência' }).click();
  state.items[0]!.version = 3;
  await row.getByLabel('Resolução').fill('Ajustado');
  await row.getByRole('button', { name: 'Confirmar encerramento' }).click();
  await expect(page.getByText('A ocorrência foi alterada. Atualize antes de encerrar.')).toBeVisible();
});

test('gestão vê e encerra ocorrências pela aba própria, sem escolher unidade', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installOccurrences(page);
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/gestao/ocorrencias`);
  await expect(page.getByRole('heading', { name: 'Ocorrências · 1 em aberto' })).toBeVisible();
  await page.getByRole('button', { name: 'Registrar ocorrência' }).click();
  await expect(page.getByLabel('Unidade (opcional)')).toHaveCount(0);
  await page.getByRole('button', { name: 'Voltar' }).click();
  const row = page.getByRole('list', { name: 'Ocorrências' }).getByRole('listitem').filter({ hasText: 'Portão da garagem' });
  await row.getByRole('button', { name: 'Encerrar ocorrência' }).click();
  await row.getByLabel('Resolução').fill('Fornecedor chamado.');
  await row.getByRole('button', { name: 'Confirmar encerramento' }).click();
  await expect(page.getByText('Ocorrência encerrada com a resolução registrada.')).toBeVisible();
  expect(state.resolves).toHaveLength(1);
});
