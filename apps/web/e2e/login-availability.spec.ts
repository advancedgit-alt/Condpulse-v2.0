import { expect, test } from '@playwright/test';
import { installSyntheticApi, signIn } from './fixtures';

for (const status of [403, 404, 503]) {
  test(`401 de identidade e CSRF ${status} mantêm login indisponível até nova verificação válida`, async ({ page }) => {
    const state = await installSyntheticApi(page);
    let available = false;
    await page.route('**/auth/csrf', async route => {
      if (available) { await route.fallback(); return; }
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ detail: 'PRIVATE_SERVICE_DETAIL' }) });
    });
    await page.goto('/entrar');
    await expect(page.getByRole('status').filter({ hasText: 'Serviço de acesso indisponível.' })).toBeVisible();
    await expect(page.getByLabel('E-mail', { exact: true })).toBeDisabled();
    await expect(page.getByLabel('Senha', { exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeDisabled();
    expect(state.loginRequests).toBe(0);
    await expect(page.getByText('PRIVATE_SERVICE_DETAIL')).toHaveCount(0);

    available = true;
    await page.getByRole('button', { name: 'Verificar conexão', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeEnabled();
    await page.getByLabel('E-mail', { exact: true }).fill('synthetic@example.invalid');
    await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC_ONLY');
    await page.getByRole('button', { name: 'Entrar', exact: true }).click();
    await expect(page).toHaveURL(/\/contextos/);
    expect(state.loginRequests).toBe(1);
  });
}

test('401 de identidade com CSRF 200 inválido não habilita o formulário', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await page.route('**/auth/csrf', route => route.fulfill({ status: 200,
    contentType: 'application/json', body: JSON.stringify({ headerName: 'Authorization', token: 'SYNTHETIC_ONLY' }) }));
  await page.goto('/entrar');
  await expect(page.getByText('Serviço de acesso indisponível. Não foi possível verificar a conexão com o sistema.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeDisabled();
  expect(state.loginRequests).toBe(0);
});

for (const status of [500, 503]) {
  test(`identidade HTTP ${status} fica indisponível sem tentar liberar login via CSRF`, async ({ page }) => {
    const state = await installSyntheticApi(page);
    let csrfRequests = 0;
    page.on('request', request => { if (new URL(request.url()).pathname === '/auth/csrf') csrfRequests += 1; });
    await page.route('**/v1/me', route => route.fulfill({ status,
      contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE' }) }));
    await page.goto('/entrar');
    await expect(page.getByText('Serviço de acesso indisponível. Não foi possível verificar a conexão com o sistema.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeDisabled();
    expect(csrfRequests).toBe(0); expect(state.loginRequests).toBe(0);
  });
}

test('falha 503 do provedor no refresh não oferece login sem nova identidade 401', async ({ page }) => {
  const state = await installSyntheticApi(page);
  await signIn(page);
  state.refreshRequired = true;
  await page.route('**/auth/refresh', route => route.fulfill({ status: 503,
    contentType: 'application/json', body: JSON.stringify({ code: 'AUTH_PROVIDER_UNAVAILABLE' }) }));
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Não foi possível verificar seu acesso.', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Sair', exact: true })).toBeEnabled();
});

test('nova verificação aguarda identidade atual e não usa um 401 anterior para liberar login', async ({ page }) => {
  await installSyntheticApi(page);
  let checkingAgain = false; let csrfRequests = 0;
  let release!: () => void; let started!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const requested = new Promise<void>(resolve => { started = resolve; });
  await page.route('**/v1/me', async route => {
    if (!checkingAgain) { await route.fallback(); return; }
    started(); await held;
    await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE' }) });
  });
  await page.route('**/auth/csrf', async route => {
    csrfRequests += 1;
    if (checkingAgain) { await route.fallback(); return; }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });
  await page.goto('/entrar');
  await expect(page.getByRole('button', { name: 'Verificar conexão', exact: true })).toBeVisible();
  const initialProbes = csrfRequests;
  checkingAgain = true;
  try {
    await page.getByRole('button', { name: 'Verificar conexão', exact: true }).click();
    await requested;
    await expect(page.getByRole('status').filter({ hasText: 'Verificando sua sessão.' })).toBeVisible();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    expect(csrfRequests).toBe(initialProbes);
    await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toHaveCount(0);
  } finally { release(); }
  await expect(page.getByText('Serviço de acesso indisponível. Não foi possível verificar a conexão com o sistema.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Entrar', exact: true })).toBeDisabled();
  expect(csrfRequests).toBe(initialProbes);
});

test('login aceito aguarda identidade sem um probe concorrente nem falso aviso de indisponibilidade', async ({ page }) => {
  const state = await installSyntheticApi(page);
  let csrfRequests = 0; let release!: () => void; let started!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const requested = new Promise<void>(resolve => { started = resolve; });
  page.on('request', request => { if (new URL(request.url()).pathname === '/auth/csrf') csrfRequests += 1; });
  await page.route('**/v1/me', async route => {
    if (state.authenticated) { started(); await held; }
    await route.fallback();
  });
  await page.goto('/entrar');
  await page.getByLabel('E-mail', { exact: true }).fill('synthetic@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC_ONLY');
  try {
    await page.getByRole('button', { name: 'Entrar', exact: true }).click();
    await requested;
    await expect(page.getByRole('status').filter({ hasText: 'Verificando sua sessão.' })).toBeVisible();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    expect(csrfRequests).toBe(2);
    await expect(page.getByText(/Serviço de acesso indisponível/)).toHaveCount(0);
  } finally { release(); }
  await expect(page).toHaveURL(/\/contextos/);
  expect(state.loginRequests).toBe(1);
});
