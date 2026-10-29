import { test, expect, type Page, type Route } from '@playwright/test';
import { installSyntheticApi, type ApiState } from './fixtures';

const LINK = 'SYNTHETIC_link_hash_0123456789';
const PASSWORD = 'Frase-sintetica-de-teste-2030';

interface RecoveryState {
  requests: unknown[];
  verifies: unknown[];
  completes: unknown[];
  verifyOutcome: 'ok' | 'mfa' | 'invalid';
  completeOutcomes: string[];
}

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL',
    traceId: '60000000-0000-4000-8000-000000000009' });
}

/** API sintética da recuperação, registrada depois da API base (rotas mais novas têm precedência). */
async function installRecoveryApi(page: Page, api: ApiState, initial: Partial<RecoveryState> = {}): Promise<RecoveryState> {
  const state: RecoveryState = { requests: [], verifies: [], completes: [], verifyOutcome: 'mfa', completeOutcomes: ['ok'], ...initial };
  await page.route('**/auth/recovery**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'POST' || request.headers()['x-csrf-token'] !== `SYNTHETIC-TEST-CSRF-${api.csrfVersion}`) {
      await problem(route, 403, 'ACTION_FORBIDDEN'); return;
    }
    const body = request.postDataJSON() as Record<string, unknown>;
    if (path === '/auth/recovery') { state.requests.push(body); await json(route, 202, { status: 'RECOVERY_REQUESTED' }); return; }
    if (path === '/auth/recovery/verify') {
      state.verifies.push(body);
      if (state.verifyOutcome === 'invalid') { await problem(route, 400, 'RECOVERY_LINK_INVALID'); return; }
      await json(route, 200, { mfaRequired: state.verifyOutcome === 'mfa', expiresAt: new Date(Date.now() + 600_000).toISOString() });
      return;
    }
    if (path === '/auth/recovery/complete') {
      state.completes.push(body);
      const outcome = state.completeOutcomes.shift() ?? 'ok';
      if (outcome !== 'ok') { await problem(route, outcome === 'RECOVERY_OUTCOME_UNKNOWN' ? 503 : 422, outcome); return; }
      // O servidor apaga sessão e CSRF deste navegador junto com a troca confirmada.
      api.authenticated = false; api.csrfVersion += 1;
      await json(route, 200, { status: 'PASSWORD_CHANGED', otherSessionsEnded: true });
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}

test('esqueci minha senha envia pedido neutro sem revelar se a conta existe', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const recovery = await installRecoveryApi(page, api);
  await page.goto('/entrar');
  await page.getByRole('link', { name: 'Esqueci minha senha' }).click();
  await expect(page.getByRole('heading', { name: 'Esqueceu sua senha?' })).toBeVisible();
  await page.getByLabel('E-mail').fill(' pessoa@example.invalid ');
  await page.getByRole('button', { name: 'Enviar link de recuperação' }).click();
  await expect(page.getByRole('heading', { name: 'Confira seu e-mail.' })).toBeVisible();
  await expect(page.getByText(/Se o e-mail estiver cadastrado/)).toBeVisible();
  expect(recovery.requests).toEqual([{ email: 'pessoa@example.invalid' }]);
});

test('link sai da barra, exige clique e código do autenticador e só confirma depois do servidor', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const recovery = await installRecoveryApi(page, api, { completeOutcomes: ['PASSWORD_REJECTED', 'ok'] });
  await page.goto(`/recuperar-acesso#token_hash=${LINK}`);
  await expect(page.getByRole('heading', { name: 'Definir uma nova senha.' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
  expect(recovery.verifies).toHaveLength(0);
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page.getByRole('heading', { name: 'Crie sua nova senha.' })).toBeVisible();
  expect(recovery.verifies).toEqual([{ tokenHash: LINK }]);
  expect(await page.evaluate(() => window.sessionStorage.getItem('condpulse:recovery-link'))).toBeNull();
  const save = page.getByRole('button', { name: 'Salvar nova senha' });
  await page.getByLabel('Nova senha', { exact: true }).fill('curta');
  await expect(page.getByText(/Use de 12 a 72 caracteres \(letras/)).toBeVisible();
  await expect(save).toBeDisabled();
  await page.getByLabel('Nova senha', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirme a nova senha').fill(`${PASSWORD}x`);
  await expect(page.getByText('As senhas não são iguais.')).toBeVisible();
  await page.getByLabel('Confirme a nova senha').fill(PASSWORD);
  await expect(save).toBeDisabled();
  await page.getByLabel('Código de 6 dígitos do autenticador').fill('123456');
  await save.click();
  await expect(page.getByText('A senha não foi aceita. Use uma senha diferente da anterior e mais difícil de adivinhar.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Crie sua nova senha.' })).toBeVisible();
  await expect(page.getByLabel('Código de 6 dígitos do autenticador')).toHaveValue('');
  await page.getByLabel('Código de 6 dígitos do autenticador').fill('654321');
  await save.click();
  await expect(page.getByRole('heading', { name: 'Senha alterada.' })).toBeVisible();
  await expect(page.getByText('As sessões do CondPulse abertas com a senha anterior foram encerradas.', { exact: false })).toBeVisible();
  expect(recovery.completes).toEqual([{ password: PASSWORD, code: '123456' }, { password: PASSWORD, code: '654321' }]);
  await page.getByRole('button', { name: 'Entrar com a nova senha' }).click();
  await expect(page.getByRole('heading', { name: 'Entre para continuar.' })).toBeVisible();
});

test('link inválido encerra a tentativa e oferece novo pedido sem guardar o link', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installRecoveryApi(page, api, { verifyOutcome: 'invalid' });
  await page.goto(`/recuperar-acesso#token_hash=${LINK}`);
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page.getByRole('heading', { name: 'Recuperação não concluída.' })).toBeVisible();
  await expect(page.getByText('Link de recuperação inválido, expirado ou já utilizado. Solicite um novo link.')).toBeVisible();
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  await page.getByRole('button', { name: 'Solicitar novo link' }).click();
  await expect(page.getByRole('heading', { name: 'Esqueceu sua senha?' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Esqueceu sua senha?' })).toBeVisible();
});

test('recarregar depois de validar o link retoma a senha e resultado incerto não vira sucesso', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const recovery = await installRecoveryApi(page, api, { verifyOutcome: 'ok', completeOutcomes: ['RECOVERY_OUTCOME_UNKNOWN'] });
  await page.goto(`/recuperar-acesso#token_hash=${LINK}`);
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page.getByRole('heading', { name: 'Crie sua nova senha.' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Crie sua nova senha.' })).toBeVisible();
  await expect(page.getByLabel('Código de 6 dígitos do autenticador')).toHaveCount(0);
  expect(recovery.verifies).toHaveLength(1);
  await page.getByLabel('Nova senha', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirme a nova senha').fill(PASSWORD);
  await page.getByRole('button', { name: 'Salvar nova senha' }).click();
  await expect(page.getByRole('heading', { name: 'Recuperação não concluída.' })).toBeVisible();
  await expect(page.getByText(/Não foi possível confirmar a troca da senha/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Senha alterada.' })).toHaveCount(0);
  expect(recovery.completes).toEqual([{ password: PASSWORD, code: null }]);
});
