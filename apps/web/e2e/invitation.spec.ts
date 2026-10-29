import { test, expect, type Page } from '@playwright/test';
import { ACCOUNT, TENANT_A, installSyntheticApi, type ApiState } from './fixtures';
import { INVITATION_TOKEN } from './management-fixtures';

test.use({ timezoneId: 'UTC' });
const MEMBERSHIP = '52000000-0000-4000-8000-000000000001';

/** Rota sintética do primeiro acesso, com o mesmo CSRF do restante da API sintética. */
async function firstAccessApi(page: Page, state: ApiState, outcome: 'session' | 'noSession' | 'spent') {
  const bodies: unknown[] = [];
  await page.route('**/auth/invitation/accept', async (route) => {
    const request = route.request();
    if (request.method() !== 'POST' || request.headers()['x-csrf-token'] !== `SYNTHETIC-TEST-CSRF-${state.csrfVersion}`) {
      await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ status: 403, code: 'ACTION_FORBIDDEN' }) });
      return;
    }
    bodies.push(request.postDataJSON());
    if (outcome === 'spent') {
      await route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ status: 404, code: 'INVITATION_NOT_AVAILABLE', detail: 'PRIVATE-SERVER-DETAIL' }) });
      return;
    }
    const session = outcome === 'session';
    if (session) { state.authenticated = true; state.csrfVersion += 1; }
    await route.fulfill({ status: 201, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({
      tenantId: TENANT_A, membershipId: MEMBERSHIP, status: 'PENDING', sessionStarted: session,
      accountId: session ? ACCOUNT : null, expiresAt: session ? new Date(Date.now() + 28_800_000).toISOString() : null }) });
  });
  return bodies;
}

test('convidado sem conta local aceita o convite e entra na mesma operação', async ({ page }) => {
  const state = await installSyntheticApi(page);
  const bodies = await firstAccessApi(page, state, 'session');
  await page.goto(`/convite#token=${INVITATION_TOKEN}`);
  await expect(page.getByRole('heading', { name: 'Aceite o convite e entre.' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
  await page.getByLabel('E-mail convidado').fill('convidada-sintetica@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByRole('button', { name: 'Aceitar convite e entrar' }).click();
  await expect(page.getByRole('heading', { name: 'Pedido de vínculo enviado.' })).toBeVisible();
  expect(bodies).toEqual([{ email: 'convidada-sintetica@example.invalid', password: 'SYNTHETIC-TEST-PASSWORD', token: INVITATION_TOKEN }]);
  expect(state.loginRequests).toBe(0);
  await page.getByRole('link', { name: 'Ir para meus acessos' }).click();
  await expect(page.getByRole('heading', { name: 'Escolha seu espaço.' })).toBeVisible();
  await expect(page.getByText('Há um convite aberto nesta janela.')).toHaveCount(0);
});

test('aceite sem sessão orienta o login e convite já usado é descartado sem detalhe do servidor', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await firstAccessApi(page, state, 'noSession');
  await page.goto(`/convite#token=${INVITATION_TOKEN}`);
  await page.getByLabel('E-mail convidado').fill('convidada-sintetica@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByRole('button', { name: 'Aceitar convite e entrar' }).click();
  await expect(page.getByText('O pedido foi registrado, mas a sessão não pôde ser iniciada agora.', { exact: false })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Entrar' })).toBeVisible();
  expect(state.authenticated).toBe(false);

  await page.unroute('**/auth/invitation/accept');
  await firstAccessApi(page, state, 'spent');
  // Nova carga da página (mudar só o fragmento da mesma URL não recarrega o documento).
  await page.goto('/entrar');
  await page.goto(`/convite#token=${INVITATION_TOKEN}`);
  await page.getByLabel('E-mail convidado').fill('convidada-sintetica@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByRole('button', { name: 'Aceitar convite e entrar' }).click();
  await expect(page.getByRole('heading', { name: 'Convite indisponível.' })).toBeVisible();
  await expect(page.getByText('Convite inválido, expirado, já utilizado ou emitido para outro e-mail.')).toBeVisible();
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
});
