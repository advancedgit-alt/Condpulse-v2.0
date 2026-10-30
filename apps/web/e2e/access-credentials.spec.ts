import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const ZONE = 'America/Sao_Paulo';
const CONNECTOR = '43000000-0000-4000-8000-000000000001';
const DEVICE_IN = '44000000-0000-4000-8000-000000000001';
const DEVICE_OUT = '44000000-0000-4000-8000-000000000002';
const HOLDER = '51000000-0000-4000-8000-0000000000a1';
const CREDENTIAL = '45000000-0000-4000-8000-000000000001';

interface Synthetic {
  credentialId: string; connectorId: string; membershipId: string; unitId: string; unitLabel: string; holderName: string | null; kind: string;
  label: string; desiredState: string; desiredVersion: number; validUntil: string | null; status: string; revokeReason: string | null; revokedAt: string | null;
  createdAt: string; version: number; devices: { deviceId: string; deviceLabel: string; direction: string; state: string; lastOutcome: string | null;
    confirmedAt: string | null; lastReason: string | null; lastAttemptAt: string | null }[];
}
interface CredentialState {
  connectors: unknown[]; credentials: Synthetic[]; creates: { key: string; body: Record<string, unknown> }[]; keys: Map<string, Synthetic>;
  revokes: unknown[]; managedQueries: string[]; loseNextCreate: boolean; listFailures: number; unavailable: boolean; revokeConflict: boolean;
}
async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000f' });
}
const devices = (state: string, confirmed = false) => [DEVICE_IN, DEVICE_OUT].map((deviceId, index) => ({ deviceId,
  deviceLabel: index === 0 ? 'Entrada social' : 'Garagem', direction: index === 0 ? 'ENTRY' : 'BOTH', state,
  lastOutcome: confirmed ? 'APPLIED' : null, confirmedAt: confirmed ? new Date().toISOString() : null, lastReason: null, lastAttemptAt: null }));
const synthetic = (overrides: Partial<Synthetic> = {}): Synthetic => ({ credentialId: CREDENTIAL, connectorId: CONNECTOR, membershipId: HOLDER, unitId: UNIT_A,
  unitLabel: '101 — Sintética A', holderName: 'Morador Sintético', kind: 'CARD', label: 'Cartão final 1234', desiredState: 'ACTIVE', desiredVersion: 1, validUntil: null,
  status: 'SYNCING', revokeReason: null, revokedAt: null, createdAt: new Date().toISOString(), version: 0, devices: devices('PENDING'), ...overrides });
const connector = { connectorId: CONNECTOR, provider: 'SIMULATED', label: 'Integração sintética', status: 'ACTIVE', devices: [
  { deviceId: DEVICE_IN, label: 'Entrada social', direction: 'ENTRY', status: 'ACTIVE' }, { deviceId: DEVICE_OUT, label: 'Garagem', direction: 'BOTH', status: 'ACTIVE' }] };

async function installCredentialsApi(page: Page, initial: Partial<CredentialState> = {}): Promise<CredentialState> {
  const state: CredentialState = { connectors: [connector], credentials: [], creates: [], keys: new Map(), revokes: [], managedQueries: [],
    loseNextCreate: false, listFailures: 0, unavailable: false, revokeConflict: false, ...initial };
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    const credentialRoute = path === '/access-connectors' || path.startsWith('/management/access-credentials') || path === '/concierge/pending-revocations';
    if (credentialRoute && state.unavailable) { await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND'); return; }
    if (path === '/access-connectors') { await json(route, 200, { connectors: state.connectors }); return; }
    if (path === '/management/memberships') {
      await json(route, 200, { memberships: [
        { membershipId: HOLDER, unitId: UNIT_A, unitLabel: '101 — Sintética A', displayName: 'Morador Sintético', role: 'RESIDENT', status: 'ACTIVE',
          validFrom: new Date(Date.now() - 86_400_000).toISOString(), validUntil: null, version: 1 },
        { membershipId: '51000000-0000-4000-8000-0000000000a2', unitId: null, unitLabel: null, displayName: 'Gestão Sintética', role: 'MANAGER', status: 'ACTIVE',
          validFrom: new Date(Date.now() - 86_400_000).toISOString(), validUntil: null, version: 1 }], truncated: false });
      return;
    }
    if (path === '/management/access-credentials') {
      if (request.method() === 'GET') {
        state.managedQueries.push(url.searchParams.get('pending') ?? 'all');
        if (state.listFailures > 0) { state.listFailures--; await problem(route, 503, 'CREDENTIAL_DEPENDENCY_UNAVAILABLE'); return; }
        const pending = url.searchParams.get('pending') === 'true';
        await json(route, 200, { credentials: state.credentials.filter((item) => !pending || item.devices.some((entry) => entry.state !== 'CONFIRMED')), truncated: false });
        return;
      }
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as Record<string, unknown>;
      state.creates.push({ key, body });
      const previous = state.keys.get(key);
      if (previous) { await json(route, 201, previous, { 'Idempotency-Replayed': 'true' }); return; }
      const created = synthetic({ kind: body.kind as string, label: body.label as string, membershipId: body.membershipId as string });
      state.keys.set(key, created); state.credentials.unshift(created);
      if (state.loseNextCreate) { state.loseNextCreate = false; await route.abort('connectionreset'); return; }
      await json(route, 201, created, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const revoke = /^\/management\/access-credentials\/([^/]+)\/revoke$/.exec(path);
    if (revoke) {
      const body = request.postDataJSON() as { reason: string; expectedVersion: number };
      state.revokes.push(body);
      if (state.revokeConflict) { state.revokeConflict = false; await problem(route, 409, 'CREDENTIAL_CHANGED'); return; }
      const item = state.credentials.find((candidate) => candidate.credentialId === revoke[1])!;
      Object.assign(item, { desiredState: 'REVOKED', desiredVersion: 2, status: 'REVOKING', revokeReason: body.reason, revokedAt: new Date().toISOString(),
        version: item.version + 1, devices: devices('PENDING') });
      await json(route, 200, item);
      return;
    }
    if (path === '/concierge/pending-revocations') {
      await json(route, 200, { credentials: state.credentials.filter((item) => item.status === 'REVOKING'), truncated: false });
      return;
    }
    await route.fallback();
  });
  return state;
}
async function openManagement(page: Page, initial: Partial<CredentialState> = {}) {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installCredentialsApi(page, initial);
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/acesso`);
  return state;
}
async function openConcierge(page: Page, api: ApiState) {
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: ZONE, unitId: null, role: 'CONCIERGE' }] });
  });
}

test('sem integração ativa a gestão vê o aviso e não cadastra', async ({ page }) => {
  await openManagement(page, { connectors: [] });
  const section = page.getByRole('region', { name: 'Credenciais de acesso' });
  await expect(section.getByText(/Nenhuma integração de acesso ativa/)).toBeVisible();
  await expect(section.getByRole('button', { name: 'Cadastrar credencial' })).toHaveCount(0);
  await expect(section.getByText('Nenhuma credencial cadastrada.')).toBeVisible();
});

test('função desligada no ambiente mostra credenciais indisponíveis', async ({ page }) => {
  await openManagement(page, { unavailable: true });
  await expect(page.getByText('As credenciais de acesso ainda não estão ativas neste ambiente.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cadastrar credencial' })).toHaveCount(0);
});

test('cadastro exige morador e descrição; resultado incerto trava e repete a mesma chave', async ({ page }) => {
  const state = await openManagement(page);
  const section = page.getByRole('region', { name: 'Credenciais de acesso' });
  await expect(section.getByText(/Integração: Integração sintética \(2 equipamentos\)/)).toBeVisible();
  await section.getByRole('button', { name: 'Cadastrar credencial' }).click();
  const form = page.getByRole('form', { name: 'Cadastrar credencial' });
  await form.getByRole('button', { name: 'Voltar' }).click();
  await expect(form).toHaveCount(0);
  await section.getByRole('button', { name: 'Cadastrar credencial' }).click();
  await expect(form.getByRole('button', { name: 'Cadastrar' })).toBeDisabled();
  await expect(form.getByLabel('Morador').locator('option')).toHaveCount(2);
  await form.getByLabel('Morador').selectOption(HOLDER);
  await form.getByLabel('Tipo').selectOption('QR');
  await form.getByLabel('Tipo').selectOption('CARD');
  await expect(form.getByRole('button', { name: 'Cadastrar' })).toBeDisabled();
  await form.getByLabel('Descrição').fill('  Cartão   final 1234 ');
  state.loseNextCreate = true;
  await form.getByRole('button', { name: 'Cadastrar' }).click();
  const again = form.getByRole('button', { name: 'Tentar confirmar o mesmo cadastro' });
  await expect(again).toBeVisible();
  await expect(form.getByLabel('Descrição')).toBeDisabled();
  await again.click();
  await expect(section.getByText('Esta credencial já estava cadastrada.')).toBeVisible();
  expect(state.creates).toHaveLength(2);
  expect(state.creates[1]!.key).toBe(state.creates[0]!.key);
  expect(state.creates[0]!.body).toEqual({ connectorId: CONNECTOR, membershipId: HOLDER, kind: 'CARD', label: 'Cartão final 1234' });
  const list = section.getByRole('list', { name: 'Credenciais de acesso' });
  await expect(list.getByText('Enviando aos equipamentos')).toBeVisible();
  await expect(list.getByText('Entrada social: aguardando')).toBeVisible();
  await expect(list.getByText('Garagem: aguardando')).toBeVisible();
});

test('revogar exige motivo e confirmação; vale para a versão exibida; equipamentos ficam pendentes sem afirmar bloqueio', async ({ page }) => {
  const state = await openManagement(page, { credentials: [synthetic({ status: 'ACTIVE', devices: devices('CONFIRMED', true) })] });
  const section = page.getByRole('region', { name: 'Credenciais de acesso' });
  const row = section.getByRole('listitem').filter({ hasText: 'Cartão final 1234' }).first();
  await expect(row.getByText('Ativa em todos os equipamentos')).toBeVisible();
  await expect(row.getByText(/Entrada social: ativa em/)).toBeVisible();
  await row.getByRole('button', { name: 'Revogar' }).click();
  await row.getByRole('button', { name: 'Voltar' }).click();
  expect(state.revokes).toEqual([]);
  await row.getByRole('button', { name: 'Revogar' }).click();
  await expect(row.getByRole('button', { name: 'Confirmar revogação' })).toBeDisabled();
  await row.getByLabel('Motivo da revogação').fill('Cartão perdido');
  state.revokeConflict = true;
  await row.getByRole('button', { name: 'Confirmar revogação' }).click();
  await expect(row.getByText('A credencial foi alterada. Atualize antes de continuar.')).toBeVisible();
  await row.getByRole('button', { name: 'Confirmar revogação' }).click();
  await expect(section.getByText(/revogada no CondPulse\. Os equipamentos aparecem como pendentes/)).toBeVisible();
  expect(state.revokes).toEqual([{ reason: 'Cartão perdido', expectedVersion: 0 }, { reason: 'Cartão perdido', expectedVersion: 0 }]);
  await expect(row.getByText('Revogação pendente nos equipamentos')).toBeVisible();
  await expect(row.getByText('Garagem: revogação pendente')).toBeVisible();
  await expect(row.getByRole('button', { name: 'Revogar' })).toHaveCount(0);
  await expect(row.getByText(/bloquead/i)).toHaveCount(0);
  await section.getByLabel('Filtrar credenciais').selectOption('pending');
  await expect.poll(() => state.managedQueries.at(-1)).toBe('true');
  await expect(section.getByRole('listitem').filter({ hasText: 'Cartão final 1234' })).toHaveCount(1);
});

test('falha ao consultar credenciais mostra nova tentativa', async ({ page }) => {
  const state = await openManagement(page, { listFailures: 1, credentials: [synthetic()] });
  const section = page.getByRole('region', { name: 'Credenciais de acesso' });
  await expect(section.getByRole('button', { name: 'Tentar novamente' })).toBeVisible({ timeout: 15_000 });
  await section.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(section.getByText('Cartão final 1234', { exact: false })).toBeVisible();
  expect(state.managedQueries.length).toBeGreaterThanOrEqual(2);
  await section.getByRole('button', { name: 'Atualizar' }).click();
  await expect.poll(() => state.managedQueries.length).toBeGreaterThanOrEqual(3);
});

test('portaria vê revogações pendentes com os equipamentos e o vazio quando confirmadas', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await openConcierge(page, api);
  const state = await installCredentialsApi(page, { credentials: [synthetic({ desiredState: 'REVOKED', desiredVersion: 2, status: 'REVOKING',
    revokeReason: 'Vínculo encerrado: mudou-se', revokedAt: new Date().toISOString(), version: 1, holderName: null,
    devices: [{ ...devices('CONFIRMED', true)[0]!, lastOutcome: 'APPLIED' }, devices('PENDING')[1]!] })] });
  await signIn(page);
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const section = page.getByRole('region', { name: 'Revogações pendentes' });
  const list = section.getByRole('list', { name: 'Revogações pendentes' });
  await expect(list.locator(':scope > li')).toHaveCount(1);
  await expect(list.getByText(/vínculo encerrado/)).toBeVisible();
  await expect(list.getByText(/Entrada social: revogação confirmada/)).toBeVisible();
  await expect(list.getByText('Garagem: revogação pendente')).toBeVisible();
  await expect(section.getByText(/Não trate como bloqueadas/)).toBeVisible();
  state.credentials = [];
  await section.getByRole('button', { name: 'Atualizar' }).click();
  await expect(section.getByText('Todas as revogações foram confirmadas pelos equipamentos.')).toBeVisible();
});
