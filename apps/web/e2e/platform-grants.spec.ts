import { test, expect, type Page, type Route } from '@playwright/test';
import { scanAccessibility } from './a11y';
import { ACCOUNT, TENANT_A, TENANT_B, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

// Concessões técnicas pelo painel master (AC-003, V037). Contas e condomínios sintéticos.
test.use({ timezoneId: 'UTC' });

const OTHER = '10000000-0000-4000-8000-0000000000b2';
const GRANT = '78000000-0000-4000-8000-000000000001';
const created = '2030-01-01T12:00:00.000Z';

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, traceId: '60000000-0000-4000-8000-0000000000b1' });
}
interface State {
  capability: boolean; facial: boolean; grants: Record<string, unknown>[]; issued: Record<string, unknown>[]; revoked: Record<string, unknown>[];
  loseNextIssue: boolean;
}
const grantRow = (extra: Record<string, unknown> = {}) => ({ grantId: GRANT, tenantId: TENANT_A, accountId: OTHER, roleCode: 'MASTER_IMPLANTACAO',
  scopes: ['installation.read', 'installation.manage'], validFrom: '2030-01-01T12:00:00Z', validUntil: '2030-01-08T12:00:00Z', grantedVia: 'PLATFORM_API',
  status: 'ACTIVE', current: true, revokedAt: null, version: 0, ...extra });

async function openGrants(page: Page, initial: Partial<State> = {}, path = '/master/concessoes') {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state: State = { capability: true, facial: true, grants: [], issued: [], revoked: [], loseNextIssue: false, ...initial };
  await page.route('**/v1/platform/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false,
        capabilities: ['platform.audit.read', 'tenants.manage', 'tenants.read', ...(state.capability ? ['technical_grants.manage'] : [])] });
      return;
    }
    if (url.pathname === '/v1/platform/tenants') {
      await json(route, 200, { truncated: false, tenants: [TENANT_A, TENANT_B].map((tenantId, index) => ({ tenantId, name: `Condomínio Sintético ${index ? 'B' : 'A'}`,
        timeZone: 'America/Sao_Paulo', active: true, version: 0, createdAt: created, updatedAt: created, onboarding: null })) });
      return;
    }
    const base = `/v1/platform/tenants/${TENANT_A}/technical-grants`;
    if (!url.pathname.startsWith(base)) { await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND'); return; }
    if (!state.facial) { await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND'); return; }
    if (request.method() === 'GET') { await json(route, 200, { grants: state.grants }); return; }
    if (url.pathname === base) {
      const body = request.postDataJSON() as Record<string, unknown>;
      state.issued.push(body);
      const row = grantRow({ grantId: body.grantId, accountId: body.accountId, scopes: body.scopes });
      const repeated = state.grants.some((item) => item.grantId === body.grantId);
      if (!repeated) state.grants.push(row);
      if (state.loseNextIssue) { state.loseNextIssue = false; await route.abort('connectionreset'); return; }
      await json(route, repeated ? 200 : 201, { grant: row, applied: !repeated });
      return;
    }
    const body = request.postDataJSON() as Record<string, unknown>;
    state.revoked.push(body);
    const row = state.grants.find((item) => item.grantId === GRANT)!;
    if (row.version !== body.expectedVersion) { await problem(route, 409, 'TECHNICAL_GRANT_STALE'); return; }
    Object.assign(row, { status: 'REVOKED', current: false, revokedAt: '2030-01-02T12:00:00Z', version: (row.version as number) + 1 });
    await json(route, 200, row);
  });
  await signIn(page);
  await page.goto(path);
  return state;
}

test('emite concessão para outra conta; repetição incerta reenvia o mesmo pedido; a própria conta é recusada', async ({ page }) => {
  const state = await openGrants(page);
  await expect(page.getByRole('navigation', { name: 'Seções do painel master' }).getByRole('link', { name: 'Concessões técnicas' })).toBeVisible();
  await page.getByLabel('Condomínio', { exact: true }).selectOption(TENANT_A);
  await expect(page.getByText('Este condomínio ainda não tem MASTER de implantação.')).toBeVisible();
  await page.getByRole('button', { name: 'Nova concessão' }).click();
  const form = page.getByRole('form', { name: 'Nova concessão técnica' });
  await form.getByLabel('Conta que recebe (identificador)').fill(ACCOUNT);
  await form.getByLabel(/Motivo e referência/).fill('Chamado OPS-123 aprovado pelo responsável');
  await form.getByRole('button', { name: 'Emitir concessão' }).click();
  await expect(form.getByText(/Ninguém concede papel técnico a si próprio/)).toBeVisible();
  expect(state.issued).toEqual([]);
  await form.getByLabel('Conta que recebe (identificador)').fill(OTHER.toUpperCase());
  await form.getByLabel('Validade (dias)').fill('14');
  await expect(form.getByLabel('exportar auditoria (só por pedido específico)')).not.toBeChecked();
  await scanAccessibility(page, 'master/concessões');
  state.loseNextIssue = true;
  await form.getByRole('button', { name: 'Emitir concessão' }).click();
  await expect(form.getByLabel('Conta que recebe (identificador)')).toBeDisabled();
  await form.getByRole('button', { name: 'Tentar confirmar a mesma concessão' }).click();
  await expect(page.getByText('Esta concessão já estava gravada. Nada foi emitido de novo.')).toBeVisible();
  expect(state.issued).toHaveLength(2);
  expect(state.issued[1]).toEqual(state.issued[0]);
  expect(state.issued[0]).toMatchObject({ accountId: OTHER, roleCode: 'MASTER_IMPLANTACAO', validDays: 14, reason: 'Chamado OPS-123 aprovado pelo responsável' });
  await expect(page.getByRole('list', { name: 'Concessões técnicas' }).getByText('Vigente', { exact: true })).toBeVisible();
});

test('revogar exige motivo e a versão exibida; versão nova no servidor pede recarga', async ({ page }) => {
  const state = await openGrants(page, { grants: [grantRow()] }, `/master/concessoes?condominio=${TENANT_A}`);
  const row = page.getByRole('list', { name: 'Concessões técnicas' }).getByRole('listitem').first();
  await expect(row.getByText('Vigente', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: 'Revogar' }).click();
  const form = row.getByRole('form', { name: /Revogar concessão/ });
  await expect(form.getByRole('button', { name: 'Revogar concessão' })).toBeDisabled();
  state.grants[0]!.version = 2;
  await page.getByRole('button', { name: 'Atualizar' }).click();
  await expect(form.getByText(/alterado por outra pessoa/)).toBeVisible();
  await form.getByRole('button', { name: 'Recarregar os dados atuais' }).click();
  await form.getByLabel(/Motivo da revogação/).fill('Fim da implantação');
  await form.getByRole('button', { name: 'Revogar concessão' }).click();
  await expect(page.getByText(/Concessão revogada/)).toBeVisible();
  expect(state.revoked).toEqual([{ expectedVersion: 2, reason: 'Fim da implantação' }]);
  await expect(row.getByText('Revogada', { exact: true })).toBeVisible();
});

test('sem a capacidade a seção não aparece; com o acesso facial desligado a tela avisa', async ({ page }) => {
  const state = await openGrants(page, { facial: false }, `/master/concessoes?condominio=${TENANT_A}`);
  await expect(page.getByText('Concessões indisponíveis')).toBeVisible();
  state.capability = false;
  await page.goto('/master/concessoes');
  await expect(page.getByRole('heading', { name: 'Condomínios da plataforma' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Concessões técnicas' })).toHaveCount(0);
});
