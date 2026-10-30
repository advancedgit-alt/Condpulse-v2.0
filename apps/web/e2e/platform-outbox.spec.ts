import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, TENANT_B, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

test.use({ timezoneId: 'UTC' });

const FAILED = '88000000-0000-4000-8000-000000000001';
const SKIPPED = '88000000-0000-4000-8000-000000000002';
interface Task { taskId: string; tenantId: string; eventType: string; aggregateId: string; state: string; attempts: number; lastError: string | null;
  createdAt: string; availableAt: string; processedAt: string | null }
async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, traceId: '60000000-0000-4000-8000-000000000009' });
}

/** Painel master sintético com a fila de tarefas (CP-034). */
async function installOutbox(page: Page, options: { capability?: boolean; summaryError?: boolean } = {}) {
  const at = '2030-01-01T12:00:00.000Z';
  const state = { replays: [] as { id: string; reason: string; csrf: string | undefined }[], summaryError: options.summaryError ?? false, conflict: false,
    tasks: [
      { taskId: FAILED, tenantId: TENANT_A, eventType: 'PARCEL_RECEIVED', aggregateId: '40000000-0000-4000-8000-0000000000a1', state: 'FAILED', attempts: 6,
        lastError: 'Destinatários indisponíveis', createdAt: at, availableAt: at, processedAt: at },
      { taskId: SKIPPED, tenantId: TENANT_B, eventType: 'REQUEST_UPDATED', aggregateId: '84000000-0000-4000-8000-000000000001', state: 'SKIPPED', attempts: 1,
        lastError: null, createdAt: at, availableAt: at, processedAt: at },
    ] as Task[] };
  await page.route('**/v1/platform/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false,
        capabilities: options.capability === false ? ['platform.audit.read', 'tenants.manage', 'tenants.read']
          : ['outbox.operate', 'platform.audit.read', 'tenants.manage', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') {
      await json(route, 200, { tenants: [TENANT_A, TENANT_B].map((tenantId, index) => ({ tenantId, name: `Condomínio Sintético ${'AB'[index]}`,
        timeZone: 'America/Sao_Paulo', active: true, version: 0, createdAt: at, updatedAt: at, onboarding: null })), truncated: false });
      return;
    }
    if (path === '/v1/platform/outbox') {
      if (state.summaryError) { await problem(route, 503, 'PLATFORM_DEPENDENCY_UNAVAILABLE'); return; }
      const count = (tenant: string, value: string) => state.tasks.filter((task) => task.tenantId === tenant && task.state === value).length;
      await json(route, 200, { staleAfterSeconds: 60, generatedAt: new Date().toISOString(), tenants: [
        { tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', pending: 3 + count(TENANT_A, 'PENDING'), ready: 2, oldestReadySeconds: 420,
          expiredLeases: 0, failed: count(TENANT_A, 'FAILED'), skipped: 0, stale: true },
        { tenantId: TENANT_B, tenantName: 'Condomínio Sintético B', pending: count(TENANT_B, 'PENDING'), ready: 0, oldestReadySeconds: null,
          expiredLeases: 0, failed: 0, skipped: count(TENANT_B, 'SKIPPED'), stale: false },
      ] });
      return;
    }
    if (path === '/v1/platform/outbox/tasks') {
      const wanted = url.searchParams.get('state');
      const tenant = url.searchParams.get('tenantId');
      await json(route, 200, { truncated: false, tasks: state.tasks.filter((task) => task.state === wanted && (!tenant || task.tenantId === tenant)) });
      return;
    }
    const replay = path.match(/^\/v1\/platform\/outbox\/tasks\/([^/]+)\/replay$/);
    if (replay && request.method() === 'POST') {
      const body = request.postDataJSON() as { reason: string };
      state.replays.push({ id: replay[1]!, reason: body.reason, csrf: request.headers()['x-csrf-token'] });
      if (state.conflict) { await problem(route, 409, 'OUTBOX_TASK_NOT_REPLAYABLE'); return; }
      const task = state.tasks.find((item) => item.taskId === replay[1])!;
      Object.assign(task, { state: 'PENDING', lastError: null, processedAt: null, availableAt: new Date().toISOString() });
      await json(route, 200, task);
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}

test('fila de tarefas: alerta de fila antiga, falhas por condomínio e devolução com motivo auditado', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installOutbox(page);
  await signIn(page);
  await page.goto('/master/fila');
  // Navegação lateral acompanha as capacidades da sessão: fila sim, financeiro não.
  const sidebar = page.getByRole('navigation', { name: 'Navegação principal' });
  await expect(sidebar.getByRole('link', { name: 'Fila de tarefas' })).toHaveAttribute('aria-current', 'page');
  await expect(sidebar.getByRole('link', { name: 'Financeiro' })).toHaveCount(0);
  const queue = page.getByRole('list', { name: 'Fila por condomínio' });
  const rowA = queue.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' });
  await expect(rowA.getByText('Fila atrasada')).toBeVisible();
  await expect(rowA.getByText('mais antiga pronta há 7 min', { exact: false })).toBeVisible();
  await expect(queue.getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' }).getByText('Em dia')).toBeVisible();
  await scanAccessibility(page, 'fila de tarefas');
  const tasks = page.getByRole('list', { name: 'Tarefas com falha' });
  const failed = tasks.getByRole('listitem').filter({ hasText: 'PARCEL_RECEIVED' });
  await expect(failed.getByText('Erro: Destinatários indisponíveis')).toBeVisible();
  await failed.getByRole('button', { name: 'Devolver para a fila' }).click();
  await expect(failed.getByRole('button', { name: 'Confirmar devolução' })).toBeDisabled();
  await failed.getByRole('button', { name: 'Voltar' }).click();
  expect(state.replays).toEqual([]);
  await failed.getByRole('button', { name: 'Devolver para a fila' }).click();
  await failed.getByLabel('Motivo (fica na auditoria da plataforma)').fill('  Destinatário corrigido ');
  await failed.getByRole('button', { name: 'Confirmar devolução' }).click();
  await expect(page.getByText('Tarefa devolvida para a fila: o worker processa de novo, sem repetir aviso já entregue.')).toBeVisible();
  expect(state.replays.map(({ id, reason }) => ({ id, reason }))).toEqual([{ id: FAILED, reason: 'Destinatário corrigido' }]);
  expect(state.replays[0]!.csrf).toBeTruthy();
  await expect(tasks.getByRole('listitem')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Nenhuma tarefa com falha' })).toBeVisible();
  // Ignoradas, filtradas por condomínio; outra pessoa já devolveu: conflito explicado e lista atualizada.
  await page.getByLabel('Situação').selectOption('SKIPPED');
  await page.getByLabel('Condomínio', { exact: true }).selectOption(TENANT_B);
  const skipped = page.getByRole('list', { name: 'Tarefas ignoradas' }).getByRole('listitem').filter({ hasText: 'REQUEST_UPDATED' });
  state.conflict = true;
  await skipped.getByRole('button', { name: 'Devolver para a fila' }).click();
  await skipped.getByLabel('Motivo (fica na auditoria da plataforma)').fill('Evento ganhou consumidor');
  await skipped.getByRole('button', { name: 'Confirmar devolução' }).click();
  await expect(page.getByText('Só tarefa com falha ou ignorada volta para a fila. Atualize a lista.')).toBeVisible();
  await page.getByRole('button', { name: 'Atualizar fila' }).click();
});

test('sem a capacidade a aba não aparece; falha da consulta oferece nova tentativa', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await installOutbox(page, { capability: false });
  await signIn(page);
  await page.goto('/master/fila');
  await expect(page.getByRole('heading', { name: 'Condomínios da plataforma' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Fila de tarefas' })).toHaveCount(0);
  // Seção inexistente para o papel cai em Condomínios também na navegação lateral.
  await expect(page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Condomínios' })).toHaveAttribute('aria-current', 'page');
});

test('falha ao consultar a fila mostra erro com nova tentativa', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installOutbox(page, { summaryError: true });
  await signIn(page);
  await page.goto('/master/fila');
  await expect(page.getByRole('button', { name: 'Tentar novamente' })).toBeVisible();
  state.summaryError = false;
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByRole('list', { name: 'Fila por condomínio' })).toBeVisible();
});
