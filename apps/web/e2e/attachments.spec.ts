import { test, expect, type Page, type Route } from '@playwright/test';
import { TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const REQUEST = '96000000-0000-4000-8000-000000000011';
const STORAGE = 'https://abcdefghijklmnopqrst.supabase.co';
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
const PDF = Buffer.from('%PDF-1.7 conteudo ficticio');
const now = () => new Date().toISOString();

interface Item {
  attachmentId: string; entityId: string; fileName: string; mimeType: string; sizeBytes: number; state: 'PENDING' | 'AVAILABLE' | 'REJECTED';
  rejection: string | null; mine: boolean; createdAt: string; completedAt: string | null; content?: Buffer;
}
async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
const view = ({ content: _content, ...item }: Item) => item;

/** Pedido único (unidade e gestão) e API sintética de anexos + Storage fictício. Conferência igual à do servidor. */
async function installAttachmentsApi(page: Page, options: { requestState?: string } = {}) {
  const state = { items: [] as Item[], intents: [] as unknown[], puts: [] as { headers: Record<string, string>; size: number }[],
    completes: 0, downloads: 0, failNextPut: false, requestState: options.requestState ?? 'OPEN' };
  const request = () => ({ requestId: REQUEST, unitId: UNIT_A, category: 'MAINTENANCE', title: 'Vazamento fictício',
    state: state.requestState, createdAt: now(), updatedAt: now(), dueOn: null });
  const details = { description: 'Água no teto.', resolution: null, requesterName: 'PESSOA FICTÍCIA', resolvedAt: null, messages: [], messagesTruncated: false };
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/requests`, (route) =>
    json(route, 200, { requests: [{ ...request(), assigned: false }], truncated: false }));
  await page.route(`**/v1/tenants/${TENANT_A}/management/requests**`, async (route) => {
    const managed = { ...request(), unitLabel: 'FICTÍCIA 101', assigneeMembershipId: null, assigneeName: null, version: 0 };
    if (new URL(route.request().url()).pathname.endsWith('/management/requests')) { await json(route, 200, { requests: [managed], truncated: false }); return; }
    await json(route, 200, { ...managed, ...details, closedAt: state.requestState === 'CLOSED' ? now() : null, team: [] });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/requests/**`, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/attachments')) { await json(route, 200, { attachments: state.items.map(view) }); return; }
    await json(route, 200, { ...request(), ...details, assigned: false, openedByMe: true, closedAt: state.requestState === 'CLOSED' ? now() : null });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/uploads/intents`, async (route) => {
    const body = route.request().postDataJSON() as { purpose: string; entityId: string; fileName: string; mimeType: string; sizeBytes: number };
    state.intents.push(body);
    if (state.items.filter((item) => item.state !== 'REJECTED').length >= 10) { await json(route, 409, { code: 'ATTACHMENT_LIMIT_REACHED', status: 409 }); return; }
    const item: Item = { attachmentId: `a7000000-0000-4000-8000-${String(state.items.length + 1).padStart(12, '0')}`, entityId: body.entityId,
      fileName: body.fileName, mimeType: body.mimeType, sizeBytes: body.sizeBytes, state: 'PENDING', rejection: null, mine: true, createdAt: now(), completedAt: null };
    state.items.push(item);
    await route.fulfill({ status: 201, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({
      attachment: view(item), uploadUrl: `${STORAGE}/storage/v1/object/upload/sign/cp-upload-quarantine/${TENANT_A}/${item.attachmentId}?token=synthetic.upload_token-${item.attachmentId}`,
      method: 'PUT', contentType: body.mimeType, expiresAt: new Date(Date.now() + 15 * 60_000).toISOString() }) });
  });
  await page.route(`${STORAGE}/storage/v1/object/upload/sign/**`, async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').pop()!;
    const content = route.request().postDataBuffer() ?? Buffer.alloc(0);
    state.puts.push({ headers: route.request().headers(), size: content.length });
    if (state.failNextPut) { state.failNextPut = false; await route.fulfill({ status: 500, body: '' }); return; }
    state.items.find((item) => item.attachmentId === id)!.content = content;
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*' }, body: '{"Key":"ok"}' });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/attachments/**`, async (route) => {
    const parts = new URL(route.request().url()).pathname.split('/');
    const item = state.items.find((candidate) => candidate.attachmentId === parts.at(-2));
    if (!item) { await json(route, 404, { code: 'ATTACHMENT_NOT_FOUND', status: 404 }); return; }
    if (parts.at(-1) === 'complete') {
      state.completes += 1;
      if (item.state === 'PENDING') {
        if (!item.content) { await json(route, 409, { code: 'ATTACHMENT_NOT_UPLOADED', status: 409 }); return; }
        const signature = item.mimeType === 'application/pdf' ? PDF.subarray(0, 5) : PNG.subarray(0, 8);
        item.completedAt = now();
        if (item.content.length !== item.sizeBytes) { item.state = 'REJECTED'; item.rejection = 'SIZE_MISMATCH'; }
        else if (!item.content.subarray(0, signature.length).equals(signature)) { item.state = 'REJECTED'; item.rejection = 'TYPE_MISMATCH'; }
        else item.state = 'AVAILABLE';
      }
      await json(route, 200, view(item));
      return;
    }
    state.downloads += 1;
    if (item.state !== 'AVAILABLE') { await json(route, 409, { code: 'ATTACHMENT_NOT_AVAILABLE', status: 409 }); return; }
    await json(route, 200, { url: `${STORAGE}/storage/v1/object/sign/cp-upload-quarantine/${TENANT_A}/${item.attachmentId}?token=synthetic.download_token-1&download=${encodeURIComponent(item.fileName)}`,
      expiresAt: new Date(Date.now() + 60_000).toISOString() });
  });
  await page.route(`${STORAGE}/storage/v1/object/sign/**`, async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').pop()!;
    const item = state.items.find((candidate) => candidate.attachmentId === id)!;
    await route.fulfill({ status: 200, contentType: item.mimeType, headers: { 'Content-Disposition': `attachment; filename="${item.fileName}"` }, body: item.content! });
  });
  return state;
}

async function openResidentRequest(page: Page) {
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const row = page.getByRole('list', { name: 'Pedidos da unidade' }).getByRole('listitem').filter({ hasText: 'Vazamento fictício' });
  await row.getByRole('button', { name: 'Ver conversa' }).click();
  return row;
}

test('unidade anexa foto conferida, baixa por link curto e vê arquivo disfarçado recusado', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installAttachmentsApi(page);
  const row = await openResidentRequest(page);
  const group = row.getByRole('group', { name: 'Anexos do pedido' });
  await expect(group.getByText('Nenhum anexo neste pedido.')).toBeVisible();
  const send = group.getByRole('button', { name: 'Enviar anexo' });
  await expect(send).toBeDisabled();
  // Tipo fora da lista: recusado antes de qualquer chamada.
  await group.getByLabel('Anexar foto ou PDF').setInputFiles({ name: 'animacao.gif', mimeType: 'image/gif', buffer: Buffer.from('GIF89a') });
  await expect(group.getByText('Envie foto JPEG ou PNG ou documento PDF.')).toBeVisible();
  await expect(send).toBeDisabled();
  expect(state.intents).toHaveLength(0);
  // Foto válida: reserva, envio direto ao Storage sem credenciais do CondPulse e conferência.
  await group.getByLabel('Anexar foto ou PDF').setInputFiles({ name: 'vazamento.png', mimeType: 'image/png', buffer: PNG });
  await send.click();
  await expect(group.getByText('Arquivo anexado: vazamento.png.')).toBeVisible();
  expect(state.intents).toEqual([{ purpose: 'REQUEST_ATTACHMENT', entityId: REQUEST, fileName: 'vazamento.png', mimeType: 'image/png', sizeBytes: PNG.length }]);
  expect(state.puts[0]!.size).toBe(PNG.length);
  expect(state.puts[0]!.headers['content-type']).toBe('image/png');
  expect(state.puts[0]!.headers['cookie']).toBeUndefined();
  expect(state.puts[0]!.headers['x-csrf-token']).toBeUndefined();
  const list = group.getByRole('list', { name: 'Arquivos anexados' });
  await expect(list.getByText('vazamento.png', { exact: false })).toBeVisible();
  // Download: a API emite link de 60 s a cada clique.
  const downloaded = page.waitForEvent('download');
  await list.getByRole('button', { name: 'Baixar vazamento.png' }).click();
  expect((await downloaded).suggestedFilename()).toBe('vazamento.png');
  expect(state.downloads).toBe(1);
  // PDF disfarçado de PNG: a conferência recusa e não há botão de download.
  await group.getByLabel('Anexar foto ou PDF').setInputFiles({ name: 'falso.png', mimeType: 'image/png', buffer: PDF });
  await group.getByRole('button', { name: 'Enviar anexo' }).click();
  await expect(group.getByText('Arquivo recusado: o conteúdo não é foto JPEG/PNG ou PDF como informado. Envie outro arquivo.')).toBeVisible();
  const rejected = list.getByRole('listitem').filter({ hasText: 'falso.png' });
  await expect(rejected.getByText('Recusado: o conteúdo não é foto JPEG/PNG ou PDF', { exact: false })).toBeVisible();
  await expect(rejected.getByRole('button')).toHaveCount(0);
});

test('envio que falha no Storage fica pendente e a conferência explica o que fazer', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installAttachmentsApi(page);
  state.failNextPut = true;
  const row = await openResidentRequest(page);
  const group = row.getByRole('group', { name: 'Anexos do pedido' });
  await group.getByLabel('Anexar foto ou PDF').setInputFiles({ name: 'laudo.pdf', mimeType: 'application/pdf', buffer: PDF });
  await group.getByRole('button', { name: 'Enviar anexo' }).click();
  await expect(group.getByText('O armazenamento recusou o arquivo. Tente de novo.')).toBeVisible();
  expect(state.completes).toBe(0);
  const pending = group.getByRole('list', { name: 'Arquivos anexados' }).getByRole('listitem').filter({ hasText: 'laudo.pdf' });
  await expect(pending.getByText('Envio não confirmado')).toBeVisible();
  await pending.getByRole('button', { name: 'Conferir envio de laudo.pdf' }).click();
  await expect(group.getByText('O arquivo ainda não chegou ao armazenamento. Envie de novo.')).toBeVisible();
  // Novo envio do mesmo arquivo funciona.
  await group.getByRole('button', { name: 'Enviar anexo' }).click();
  await expect(group.getByText('Arquivo anexado: laudo.pdf.')).toBeVisible();
  expect(state.intents).toHaveLength(2);
});

test('arquivo grande demais é barrado antes do envio e o limite de 10 anexos some com o formulário', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installAttachmentsApi(page);
  const row = await openResidentRequest(page);
  const group = row.getByRole('group', { name: 'Anexos do pedido' });
  await group.getByLabel('Anexar foto ou PDF').setInputFiles({ name: 'enorme.png', mimeType: 'image/png', buffer: Buffer.alloc(10 * 1024 * 1024 + 1) });
  await expect(group.getByText('Fotos até 10 MB e PDFs até 20 MB.')).toBeVisible();
  await expect(group.getByRole('button', { name: 'Enviar anexo' })).toBeDisabled();
  expect(state.intents).toHaveLength(0);
  for (let index = 0; index < 10; index += 1) state.items.push({ attachmentId: `a7000000-0000-4000-8000-0000000001${String(index).padStart(2, '0')}`,
    entityId: REQUEST, fileName: `foto-${index}.png`, mimeType: 'image/png', sizeBytes: PNG.length, state: 'AVAILABLE', rejection: null, mine: true,
    createdAt: now(), completedAt: now(), content: PNG });
  await row.getByRole('button', { name: 'Fechar conversa' }).click();
  await row.getByRole('button', { name: 'Ver conversa' }).click();
  await expect(group.getByText('Este pedido já tem o máximo de 10 anexos.')).toBeVisible();
  await expect(group.getByLabel('Anexar foto ou PDF')).toHaveCount(0);
});

test('gestão com segundo fator vê e baixa o anexo; pedido encerrado não recebe arquivo', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installAttachmentsApi(page, { requestState: 'CLOSED' });
  state.items.push({ attachmentId: 'a7000000-0000-4000-8000-000000000201', entityId: REQUEST, fileName: 'foto da unidade.png', mimeType: 'image/png',
    sizeBytes: PNG.length, state: 'AVAILABLE', rejection: null, mine: false, createdAt: now(), completedAt: now(), content: PNG });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/pedidos`);
  const row = page.getByRole('list', { name: 'Pedidos das unidades' }).getByRole('listitem').filter({ hasText: 'Vazamento fictício' });
  await row.getByRole('button', { name: 'Abrir pedido' }).click();
  const group = row.getByRole('group', { name: 'Anexos do pedido' });
  await expect(group.getByText('foto da unidade.png', { exact: false })).toBeVisible();
  await expect(group.getByText('por você')).toHaveCount(0);
  await expect(group.getByLabel('Anexar foto ou PDF')).toHaveCount(0);
  const downloaded = page.waitForEvent('download');
  await group.getByRole('button', { name: 'Baixar foto da unidade.png' }).click();
  expect((await downloaded).suggestedFilename()).toBe('foto da unidade.png');
  await expect(group.getByText('Download de foto da unidade.png iniciado.', { exact: false })).toBeVisible();
});

test('anexos desligados no ambiente não aparecem nem mostram erro técnico', async ({ page }) => {
  await installSyntheticApi(page);
  await installAttachmentsApi(page);
  await page.route(`**/v1/tenants/${TENANT_A}/requests/*/attachments`, (route) => route.fulfill({ status: 404 }));
  const row = await openResidentRequest(page);
  await expect(row.locator('.request-detail')).toBeVisible();
  await expect(row.getByRole('group', { name: 'Anexos do pedido' })).toHaveCount(0);
  await expect(row.getByText('não está disponível', { exact: false })).toHaveCount(0);
});
