import { readFile } from 'node:fs/promises';
import { test, expect, type Page, type Route } from '@playwright/test';
import { TENANT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

// API sintética da importação de unidades (CP-016). Registrada depois da gestão: rotas mais novas têm precedência.
interface Batch {
  batchId: string; source: string; fileHash: string; mappingVersion: 1; state: 'PREVIEW' | 'APPROVED' | 'DISCARDED';
  totalRows: number; validRows: number; invalidRows: number; duplicateRows: number; partial: boolean; createdUnits: number | null;
  existingUnits: number | null; createdBlocks: number | null; createdAt: string; decidedAt: string | null; version: number;
}
interface Row { line: number; block: string | null; unit: string | null; externalId: string | null; outcome: string; errorCode: string | null;
  alreadyExists: boolean; applied: string | null; unitId: string | null }
const EXISTING = new Set(['bloco a|101']);
let serial = 0;

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-000000000002' });
}
function hash(content: string): string {
  let value = 0;
  for (const char of content) value = (value * 31 + char.charCodeAt(0)) >>> 0;
  return value.toString(16).padStart(8, '0').repeat(8);
}
/** Mesmo mapa de colunas do servidor, simplificado para os arquivos deste teste. */
function parse(content: string): Row[] | null {
  const lines = content.replace(/^﻿/, '').split(/\r?\n/);
  const header = lines[0]!.split(';').map((item) => item.trim().toLowerCase());
  if (!header.includes('unidade')) return null;
  const seen = new Set<string>();
  const rows: Row[] = [];
  lines.slice(1).forEach((line, index) => {
    if (!line.trim()) return;
    const values = line.split(';');
    const block = values[header.indexOf('bloco')]?.trim() || null;
    const unit = values[header.indexOf('unidade')]?.trim() || null;
    const key = `${(block ?? '').toLowerCase()}|${(unit ?? '').toLowerCase()}`;
    const outcome = !unit ? 'INVALID' : seen.has(key) ? 'DUPLICATE' : 'VALID';
    seen.add(key);
    rows.push({ line: index + 2, block, unit, externalId: null, outcome, errorCode: unit ? null : 'UNIT_LABEL_REQUIRED',
      alreadyExists: outcome === 'VALID' && EXISTING.has(key), applied: null, unitId: null });
  });
  return rows;
}

async function installImports(page: Page) {
  const state = {
    batches: [] as Batch[], rows: new Map<string, Row[]>(), opens: [] as { fileName: string; content: string }[],
    approvals: [] as unknown[], discards: [] as unknown[], failNextApprove: null as null | 503 | 'MFA', gets: 0,
  };
  await page.route(`**/v1/tenants/${TENANT_A}/management/imports**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/management/imports`, '');
    const detail = (batch: Batch) => ({ batch, rows: state.rows.get(batch.batchId) });
    if (request.method() === 'GET' && path === '') { state.gets += 1; await json(route, 200, { batches: state.batches }); return; }
    if (request.method() === 'POST' && path === '') {
      const body = request.postDataJSON() as { fileName: string; content: string };
      state.opens.push(body);
      const rows = parse(body.content);
      if (!rows) { await problem(route, 400, 'IMPORT_HEADER_INVALID'); return; }
      const fileHash = hash(body.content);
      const live = state.batches.find((item) => item.fileHash === fileHash && item.state !== 'DISCARDED');
      if (live) { await json(route, 200, detail(live), { 'Import-Batch-Created': 'false' }); return; }
      serial += 1;
      const batch: Batch = { batchId: `a1000000-0000-4000-8000-${String(serial).padStart(12, '0')}`, source: body.fileName, fileHash,
        mappingVersion: 1, state: 'PREVIEW', totalRows: rows.length, validRows: rows.filter((row) => row.outcome === 'VALID').length,
        invalidRows: rows.filter((row) => row.outcome === 'INVALID').length, duplicateRows: rows.filter((row) => row.outcome === 'DUPLICATE').length,
        partial: false, createdUnits: null, existingUnits: null, createdBlocks: null, createdAt: new Date().toISOString(), decidedAt: null, version: 0 };
      state.batches.unshift(batch); state.rows.set(batch.batchId, rows);
      await json(route, 201, detail(batch), { 'Import-Batch-Created': 'true' });
      return;
    }
    const match = /^\/([0-9a-f-]{36})(\/approve|\/discard)?$/.exec(path);
    const batch = match ? state.batches.find((item) => item.batchId === match[1]) : undefined;
    if (!batch) { await problem(route, 404, 'IMPORT_BATCH_NOT_FOUND'); return; }
    if (request.method() === 'GET' && !match![2]) { await json(route, 200, detail(batch)); return; }
    if (match![2] === '/approve') {
      const body = request.postDataJSON() as { expectedVersion: number; acceptPartial: boolean };
      state.approvals.push(body);
      if (state.failNextApprove === 'MFA') { state.failNextApprove = null; await problem(route, 403, 'MFA_REQUIRED'); return; }
      if (batch.state === 'APPROVED') { await json(route, 200, detail(batch), { 'Import-Decision-Applied': 'false' }); return; }
      if (batch.invalidRows + batch.duplicateRows > 0 && !body.acceptPartial) { await problem(route, 409, 'IMPORT_PARTIAL_NOT_ACCEPTED'); return; }
      const rows = state.rows.get(batch.batchId)!;
      for (const row of rows) if (row.outcome === 'VALID') {
        row.applied = row.alreadyExists ? 'EXISTING' : 'CREATED'; row.unitId = '20000000-0000-4000-8000-0000000000f1'; row.alreadyExists = false;
      }
      Object.assign(batch, { state: 'APPROVED', partial: batch.invalidRows + batch.duplicateRows > 0, version: batch.version + 1,
        createdUnits: rows.filter((row) => row.applied === 'CREATED').length, existingUnits: rows.filter((row) => row.applied === 'EXISTING').length,
        createdBlocks: 1, decidedAt: new Date().toISOString() });
      // Resposta perdida depois de gravar: o servidor aplicou, a tela recebe 503.
      if (state.failNextApprove === 503) { state.failNextApprove = null; await problem(route, 503, 'IMPORT_DEPENDENCY_UNAVAILABLE'); return; }
      await json(route, 200, detail(batch), { 'Import-Decision-Applied': 'true' });
      return;
    }
    if (match![2] === '/discard') {
      state.discards.push(request.postDataJSON());
      if (batch.state === 'APPROVED') { await problem(route, 409, 'IMPORT_BATCH_APPROVED'); return; }
      const applied = batch.state !== 'DISCARDED';
      Object.assign(batch, { state: 'DISCARDED', version: batch.version + (applied ? 1 : 0), decidedAt: new Date().toISOString() });
      await json(route, 200, detail(batch), { 'Import-Decision-Applied': String(applied) });
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}

async function openImports(page: Page) {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installImports(page);
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/gestao/importacao`);
  await expect(page.getByRole('heading', { name: 'Importação de unidades' })).toBeVisible();
  return state;
}
const csv = (name: string, content: string) => ({ name, mimeType: 'text/csv', buffer: Buffer.from(content, 'utf8') });
const PARTIAL = 'bloco;unidade;codigo_externo\nBloco A;101;\nBloco A;102;\nBloco B;201;\nBloco A;;\nbloco a;102;\n';

test('modelo, prévia com erro por linha, aprovação parcial explícita e mesmo arquivo reabrindo o mesmo lote', async ({ page }) => {
  const state = await openImports(page);
  await expect(page.getByText('Nenhuma importação')).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Baixar modelo' }).click();
  const template = await download;
  expect(template.suggestedFilename()).toBe('modelo-unidades.csv');
  expect((await readFile(await template.path())).toString('utf8')).toMatch(/^bloco;unidade;codigo_externo\n/);

  const preview = page.getByRole('button', { name: 'Gerar prévia' });
  await expect(preview).toBeDisabled();
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('unidades.csv', PARTIAL));
  await preview.click();
  await expect(page.getByText('Prévia criada. Nada foi cadastrado ainda', { exact: false })).toBeVisible();
  const rows = page.getByRole('list', { name: 'Linhas do lote' });
  await expect(rows.getByRole('listitem').filter({ hasText: 'Linha 2' })).toContainText('Já existe (não será criada)');
  await expect(rows.getByRole('listitem').filter({ hasText: 'Linha 3' })).toContainText('Nova unidade');
  await expect(rows.getByRole('listitem').filter({ hasText: 'Linha 5' })).toContainText('Erro: Unidade em branco');
  await expect(rows.getByRole('listitem').filter({ hasText: 'Linha 6' })).toContainText('Repetida no arquivo');
  await expect(page.locator('.import-summary')).toContainText('Serão criadas2');
  expect(state.opens).toEqual([{ fileName: 'unidades.csv', content: PARTIAL }]);

  const approve = page.getByRole('button', { name: 'Aprovar lote' });
  await expect(approve).toBeDisabled();
  await page.getByLabel(/Aprovar só as 3 linha\(s\) válida\(s\)/).check();
  await approve.click();
  await expect(page.getByText('Lote aprovado: 2 unidade(s) criada(s), 1 já existia(m), 1 bloco(s) criado(s).')).toBeVisible();
  expect(state.approvals).toEqual([{ expectedVersion: 0, acceptPartial: true }]);
  await expect(rows.getByRole('listitem').filter({ hasText: 'Linha 2' })).toContainText('Já existia');
  await expect(rows.getByRole('listitem').filter({ hasText: 'Linha 3' })).toContainText('Criada');
  await expect(page.getByRole('button', { name: 'Aprovar lote' })).toHaveCount(0);
  await expect(page.getByRole('list', { name: 'Lotes de importação' }).getByText('Aprovado')).toBeVisible();

  // Mesmo conteúdo com outro nome: o servidor devolve o lote vivo e nada novo é gravado.
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('copia.csv', PARTIAL));
  await page.getByRole('button', { name: 'Gerar prévia' }).click();
  await expect(page.getByText('Este arquivo já tinha um lote. Mostrando o lote existente', { exact: false })).toBeVisible();
  await expect(page.getByText('Este arquivo já tinha sido enviado. O lote abaixo é o mesmo', { exact: false })).toBeVisible();
  expect(state.batches).toHaveLength(1);
  // Em tela estreita a prévia e as abas da gestão cabem sem rolagem horizontal.
  await page.setViewportSize({ width: 360, height: 780 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('descarte pede confirmação, volta sem gravar e fecha o lote', async ({ page }) => {
  const state = await openImports(page);
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('descartar.csv', 'unidade\n301\n302\n'));
  await page.getByRole('button', { name: 'Gerar prévia' }).click();
  await expect(page.getByRole('heading', { name: 'descartar.csv' })).toBeVisible();
  await page.getByRole('button', { name: 'Descartar lote' }).click();
  const confirm = page.getByRole('group', { name: 'Confirmar descarte' });
  await confirm.getByRole('button', { name: 'Voltar' }).click();
  expect(state.discards).toEqual([]);
  await page.getByRole('button', { name: 'Descartar lote' }).click();
  await page.getByRole('button', { name: 'Confirmar descarte' }).click();
  await expect(page.getByText('Lote descartado. Nada foi cadastrado.')).toBeVisible();
  expect(state.discards).toEqual([{ expectedVersion: 0 }]);
  await expect(page.getByRole('button', { name: 'Aprovar lote' })).toHaveCount(0);
  await expect(page.getByRole('list', { name: 'Lotes de importação' }).getByText('Descartado')).toBeVisible();
  await page.getByRole('button', { name: 'Fechar' }).click();
  await expect(page.getByRole('list', { name: 'Linhas do lote' })).toHaveCount(0);
  // Reabrir pelo histórico consulta o lote pelo ID.
  await page.getByRole('button', { name: 'Abrir lote descartar.csv' }).click();
  await expect(page.getByRole('list', { name: 'Linhas do lote' })).toBeVisible();
  await expect(page.locator('.import-batch')).toContainText('LOTE · DESCARTADO');
});

test('arquivo recusado pelo servidor ou grande demais não vira lote', async ({ page }) => {
  const state = await openImports(page);
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('errado.csv', 'casa;numero\n1;2\n'));
  await page.getByRole('button', { name: 'Gerar prévia' }).click();
  await expect(page.getByText('Cabeçalho inválido. Use as colunas bloco, unidade e codigo_externo', { exact: false })).toBeVisible();
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('grande.csv', `unidade\n${'1\n'.repeat(140_000)}`));
  await expect(page.getByText('O arquivo passa de 256 KB. Divida em lotes menores.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Gerar prévia' })).toBeDisabled();
  expect(state.opens).toHaveLength(1);
  expect(state.batches).toHaveLength(0);
});

test('resposta perdida na aprovação: repetir não cria de novo e avisa que o lote já estava aprovado', async ({ page }) => {
  const state = await openImports(page);
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('incerto.csv', 'unidade\n401\n402\n'));
  await page.getByRole('button', { name: 'Gerar prévia' }).click();
  state.failNextApprove = 503;
  await page.getByRole('button', { name: 'Aprovar lote' }).click();
  await expect(page.getByText('Atualize a lista e confira o estado antes de repetir.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Aprovar lote' }).click();
  await expect(page.getByText('Este lote já estava aprovado; nada novo foi criado.')).toBeVisible();
  expect(state.approvals).toEqual([{ expectedVersion: 0, acceptPartial: false }, { expectedVersion: 0, acceptPartial: false }]);
  expect(state.batches[0]!.createdUnits).toBe(2);
});

test('segundo fator vencido na aprovação leva à verificação sem aprovar', async ({ page }) => {
  const state = await openImports(page);
  await page.getByLabel('Arquivo CSV').setInputFiles(csv('mfa.csv', 'unidade\n501\n'));
  await page.getByRole('button', { name: 'Gerar prévia' }).click();
  state.failNextApprove = 'MFA';
  await page.getByRole('button', { name: 'Aprovar lote' }).click();
  await expect(page.getByRole('heading', { name: 'Confirme sua identidade para usar a gestão.' })).toBeVisible();
  expect(state.batches[0]!.state).toBe('PREVIEW');
});
