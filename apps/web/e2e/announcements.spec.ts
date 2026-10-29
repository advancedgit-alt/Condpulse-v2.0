import { test, expect, type Page, type Route } from '@playwright/test';
import { TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const DRAFT = '95000000-0000-4000-8000-000000000001';
const PUBLISHED = '95000000-0000-4000-8000-000000000002';
const minutes = (value: number) => new Date(Date.now() + value * 60_000).toISOString();

interface Managed {
  announcementId: string; title: string; body: string; audience: 'TENANT' | 'BLOCKS' | 'UNITS'; audienceIds: string[];
  state: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'; authoredByMe: boolean; createdAt: string; updatedAt: string; publishedAt: string | null;
  archivedAt: string | null; version: number; opens: number;
}
async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}

/** API sintética dos avisos (gestão e unidade), registrada depois da API base. */
async function installAnnouncementsApi(page: Page) {
  const state = {
    items: [] as Managed[], drafts: [] as { key: string; body: unknown }[], publishes: [] as unknown[], archives: [] as unknown[],
    opens: [] as string[], loseNextDraft: false, keys: new Map<string, Managed>(),
  };
  await page.route(`**/v1/tenants/${TENANT_A}/management/announcements**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/management/announcements`, '');
    if (request.method() === 'GET' && path === '') { await json(route, 200, { announcements: state.items, truncated: false }); return; }
    if (request.method() === 'POST' && path === '') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { title: string; body: string; audience: Managed['audience']; audienceIds: string[] };
      state.drafts.push({ key, body });
      const previous = state.keys.get(key);
      if (previous) { await json(route, 201, previous, { 'Idempotency-Replayed': 'true' }); return; }
      const item: Managed = { announcementId: DRAFT, ...body, state: 'DRAFT', authoredByMe: true, createdAt: minutes(0), updatedAt: minutes(0),
        publishedAt: null, archivedAt: null, version: 0, opens: 0 };
      state.keys.set(key, item);
      state.items.unshift(item);
      if (state.loseNextDraft) { state.loseNextDraft = false; await route.abort('connectionreset'); return; }
      await json(route, 201, item, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const action = path.match(/^\/([^/]+)\/(publish|archive)$/);
    if (request.method() === 'POST' && action) {
      const body = request.postDataJSON() as { expectedVersion: number; reason?: string | null };
      const item = state.items.find((candidate) => candidate.announcementId === action[1])!;
      if (action[2] === 'publish') { state.publishes.push(body); item.state = 'PUBLISHED'; item.publishedAt = minutes(0); }
      else { state.archives.push(body); item.state = 'ARCHIVED'; item.archivedAt = minutes(0); }
      item.version += 1;
      await json(route, 200, item);
      return;
    }
    await route.fallback();
  });
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/announcements`, async (route) => {
    await json(route, 200, { announcements: state.items.filter((item) => item.state === 'PUBLISHED').map((item) => ({
      announcementId: item.announcementId, title: item.title, publishedAt: item.publishedAt,
      openedAt: state.opens.includes(item.announcementId) ? minutes(-1) : null })), truncated: false });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/announcements/*/open`, async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!;
    const item = state.items.find((candidate) => candidate.announcementId === id && candidate.state === 'PUBLISHED');
    if (!item) { await json(route, 404, { type: 'urn:condpulse:problem:ANNOUNCEMENT_NOT_FOUND', status: 404, code: 'ANNOUNCEMENT_NOT_FOUND', detail: 'x' }); return; }
    if (!state.opens.includes(id)) state.opens.push(id);
    item.opens = state.opens.filter((value) => value === id).length;
    await json(route, 200, { announcementId: id, title: item.title, body: item.body, publishedAt: item.publishedAt, openedAt: minutes(-1) });
  });
  return state;
}

test('gestão cria rascunho com chave pendente, publica com confirmação e vê aberturas sem tratá-las como ciência', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installAnnouncementsApi(page);
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/avisos`);
  await expect(page.getByRole('heading', { name: 'Avisos do condomínio' })).toBeVisible();
  await page.getByRole('button', { name: 'Novo aviso' }).click();
  const form = page.getByRole('form', { name: 'Novo aviso' });
  await form.getByLabel('Título').fill('  Assembleia   fictícia ');
  await form.getByLabel('Texto').fill('Pauta:\n\n\n- obras\n');
  await expect(form.getByText('Não publique dados de inadimplência', { exact: false })).toBeVisible();
  state.loseNextDraft = true;
  await form.getByRole('button', { name: 'Criar rascunho' }).click();
  await expect(form.getByText('O resultado não foi confirmado e o rascunho ficou travado', { exact: false })).toBeVisible();
  // Fechar e reabrir mantém a mesma chave e os mesmos dados.
  await form.getByRole('button', { name: 'Voltar' }).click();
  await page.getByRole('button', { name: 'Novo aviso' }).click();
  await expect(page.getByRole('form', { name: 'Novo aviso' }).getByLabel('Título')).toHaveValue('Assembleia fictícia');
  await page.getByRole('form', { name: 'Novo aviso' }).getByRole('button', { name: 'Tentar confirmar o mesmo rascunho' }).click();
  await expect(page.getByText('Este rascunho já estava criado: Assembleia fictícia', { exact: false })).toBeVisible();
  expect(state.drafts.map((item) => item.body)).toEqual([
    { title: 'Assembleia fictícia', body: 'Pauta:\n\n- obras', audience: 'TENANT', audienceIds: [] },
    { title: 'Assembleia fictícia', body: 'Pauta:\n\n- obras', audience: 'TENANT', audienceIds: [] }]);
  expect(state.drafts[1]!.key).toBe(state.drafts[0]!.key);
  const row = page.getByRole('list', { name: 'Avisos do condomínio' }).getByRole('listitem').filter({ hasText: 'Assembleia fictícia' });
  await expect(row.getByText('Rascunho', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: 'Publicar' }).click();
  await expect(row.getByText('Depois de publicado, o texto e o público não mudam', { exact: false })).toBeVisible();
  await row.getByRole('button', { name: 'Confirmar publicação' }).click();
  await expect(page.getByText('Aviso publicado: Assembleia fictícia', { exact: false })).toBeVisible();
  expect(state.publishes).toEqual([{ expectedVersion: 0 }]);
  await expect(row.getByText('Publicado', { exact: true })).toBeVisible();
  await expect(row.getByText('Abertura não é ciência jurídica', { exact: false })).toBeVisible();
  await expect(row.getByRole('button', { name: 'Editar rascunho' })).toHaveCount(0);
  await row.getByRole('button', { name: 'Arquivar' }).click();
  await row.getByRole('button', { name: 'Arquivar aviso' }).click();
  await expect(page.getByText('Aviso arquivado: Assembleia fictícia', { exact: false })).toBeVisible();
  expect(state.archives).toEqual([{ expectedVersion: 1, reason: null }]);
});

test('unidade vê só avisos publicados, sem o texto na lista; abrir mostra o texto como texto e registra a abertura', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installAnnouncementsApi(page);
  state.items.push({ announcementId: DRAFT, title: 'Rascunho interno', body: 'Nao deve aparecer', audience: 'TENANT', audienceIds: [], state: 'DRAFT',
    authoredByMe: false, createdAt: minutes(-20), updatedAt: minutes(-20), publishedAt: null, archivedAt: null, version: 0, opens: 0 },
  { announcementId: PUBLISHED, title: 'Manutenção da caixa d’água', body: 'Linha <b>um</b>\n\nLinha dois', audience: 'TENANT', audienceIds: [],
    state: 'PUBLISHED', authoredByMe: false, createdAt: minutes(-60), updatedAt: minutes(-30), publishedAt: minutes(-30), archivedAt: null, version: 1, opens: 0 });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const list = page.getByRole('list', { name: 'Avisos do condomínio' });
  await expect(page.getByRole('heading', { name: 'Avisos · 1 não aberto' })).toBeVisible();
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await expect(page.getByText('Rascunho interno')).toHaveCount(0);
  const item = list.getByRole('listitem').filter({ hasText: 'Manutenção da caixa d’água' });
  await expect(item.getByText('Não aberto')).toBeVisible();
  await expect(item.getByText('Linha dois')).toHaveCount(0);
  expect(state.opens).toEqual([]);
  await item.getByRole('button', { name: 'Abrir aviso' }).click();
  await expect(item.locator('.announcement-body')).toHaveText('Linha <b>um</b>\n\nLinha dois', { useInnerText: false });
  await expect(item.locator('.announcement-body b')).toHaveCount(0);
  await expect(item.getByText('aberto por você em', { exact: false })).toBeVisible();
  expect(state.opens).toEqual([PUBLISHED]);
  await expect(page.getByRole('heading', { name: 'Avisos', exact: true })).toBeVisible();
});

test('avisos desligados no ambiente não aparecem para a unidade nem mostram erro técnico', async ({ page }) => {
  await installSyntheticApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  await expect(page.getByRole('heading', { name: 'Próximas reservas da unidade' })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Avisos/ })).toHaveCount(0);
  await expect(page.getByText('SYNTHETIC_TEST_ROUTE_NOT_FOUND')).toHaveCount(0);
});
