import { expect, type Page, type Route } from '@playwright/test';

// Dados exclusivamente sintéticos e interceptados pelo runner. Nunca entram no bundle da aplicação.
export const ACCOUNT = '10000000-0000-4000-8000-000000000001';
export const TENANT_A = '00000000-0000-4000-8000-000000000001';
export const TENANT_B = '00000000-0000-4000-8000-000000000002';
export const UNIT_A = '20000000-0000-4000-8000-000000000001';
export const UNIT_B = '20000000-0000-4000-8000-000000000002';
export const AMENITY_A = '30000000-0000-4000-8000-000000000001';
export const AMENITY_B = '30000000-0000-4000-8000-000000000002';

export function futureWindow() {
  const date = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10);
  return {
    startLocal: `${date}T12:00`, endLocal: `${date}T13:00`,
    startsAt: `${date}T15:00:00Z`, endsAt: `${date}T16:00:00Z`,
  };
}

export function reservationFixture() {
  const window = futureWindow();
  return {
    reservationId: '40000000-0000-4000-8000-000000000001', unitId: UNIT_A,
    amenityId: AMENITY_A, status: 'CONFIRMED', startsAt: window.startsAt,
    endsAt: window.endsAt, occupiedFrom: window.startsAt,
    occupiedUntil: new Date(Date.parse(window.endsAt) + 15 * 60_000).toISOString(),
    acceptedPolicyVersion: 1, feeCents: 2500, version: 0,
  };
}

export type FixtureReservation = ReturnType<typeof reservationFixture>;
export interface ApiState {
  authenticated: boolean;
  csrfVersion: number;
  createKeys: string[];
  cancelKeys: string[];
  loginRequests: number;
  logoutRequests: number;
  denyAmenitiesA: boolean;
  amenitiesARequests: number;
  reservations: FixtureReservation[];
}

interface FixtureOptions {
  delayAmenitiesA?: Promise<void>;
  onAmenitiesARequested?: () => void;
  onAmenitiesASettled?: () => void;
  loseFirstCreationResponse?: boolean;
  cancelStatus?: number;
  reservations?: FixtureReservation[];
}

const identity = {
  accountId: ACCOUNT,
  memberships: [
    { membershipId: '50000000-0000-4000-8000-000000000001', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: UNIT_A, role: 'RESIDENT' },
    { membershipId: '50000000-0000-4000-8000-000000000002', tenantId: TENANT_B,
      tenantName: 'Condomínio Sintético B', timeZone: 'America/Sao_Paulo', unitId: UNIT_B, role: 'RESIDENT' },
  ],
};

function amenity(tenant: string) {
  return {
    amenityId: tenant === TENANT_A ? AMENITY_A : AMENITY_B,
    name: tenant === TENANT_A ? 'Área Sintética A' : 'Área Sintética B',
    capacity: 12, policyVersion: 1, feeCents: 2500, bufferMinutes: 15,
    approvalRequired: false, bookingState: 'AVAILABLE_FOR_REQUEST',
    policy: { schemaVersion: 1, weekDays: [1, 2, 3, 4, 5, 6, 7], opensAt: '08:00', closesAt: '22:00',
      minimumLeadMinutes: 0, maximumAdvanceDays: 366, maximumDurationMinutes: 600,
      maximumActivePerUnit: 3, cancellationLeadMinutes: 0 },
  };
}

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}

async function problem(route: Route, status: number, code: string, detail: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail,
    traceId: '60000000-0000-4000-8000-000000000001' });
}

export async function installSyntheticApi(page: Page, options: FixtureOptions = {}): Promise<ApiState> {
  const state: ApiState = {
    authenticated: false, csrfVersion: 0, createKeys: [], cancelKeys: [],
    loginRequests: 0, logoutRequests: 0, denyAmenitiesA: false, amenitiesARequests: 0,
    reservations: options.reservations ?? [],
  };
  const csrf = () => `SYNTHETIC-TEST-CSRF-${state.csrfVersion}`;

  await page.route('**/auth/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/auth/csrf' && request.method() === 'GET') {
      await json(route, 200, { headerName: 'X-CSRF-Token', token: csrf() });
      return;
    }
    if (request.method() !== 'POST' || request.headers()['x-csrf-token'] !== csrf()) {
      await problem(route, 403, 'ACTION_FORBIDDEN', 'A confirmação de segurança precisa ser renovada.');
      return;
    }
    if (path === '/auth/login') {
      state.loginRequests += 1;
      state.authenticated = true;
      state.csrfVersion += 1;
      await json(route, 200, { accountId: ACCOUNT, expiresAt: new Date(Date.now() + 28_800_000).toISOString() });
      return;
    }
    if (path === '/auth/logout') {
      state.logoutRequests += 1;
      state.authenticated = false;
      state.csrfVersion += 1;
      await route.fulfill({ status: 204 });
      return;
    }
    if (path === '/auth/refresh' && state.authenticated) {
      state.csrfVersion += 1;
      await json(route, 200, { accountId: ACCOUNT, expiresAt: new Date(Date.now() + 28_800_000).toISOString() });
      return;
    }
    await problem(route, 401, 'SESSION_EXPIRED', 'Entre novamente para continuar.');
  });

  await page.route('**/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (!state.authenticated) {
      await problem(route, 401, 'AUTHENTICATION_REQUIRED', 'Entre para continuar.');
      return;
    }
    if (path === '/v1/me') {
      await json(route, 200, identity);
      return;
    }
    if (!path.startsWith(`/v1/tenants/${TENANT_A}/`) && !path.startsWith(`/v1/tenants/${TENANT_B}/`)) {
      await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND', 'Condomínio sintético não configurado no teste.');
      return;
    }
    const tenant = path.includes(TENANT_B) ? TENANT_B : TENANT_A;
    if (request.method() === 'GET' && path.endsWith('/units')) {
      await json(route, 200, [{ id: tenant === TENANT_A ? UNIT_A : UNIT_B,
        label: tenant === TENANT_A ? '101 — Sintética A' : '202 — Sintética B',
        blockId: null, blockName: null }]);
      return;
    }
    if (request.method() === 'GET' && path.endsWith('/amenities')) {
      if (tenant === TENANT_A) {
        state.amenitiesARequests += 1;
        if (state.denyAmenitiesA) {
          await problem(route, 403, 'ACTION_FORBIDDEN', 'Acesso residencial não permitido para esta operação.');
          return;
        }
      }
      if (tenant === TENANT_A && options.delayAmenitiesA) {
        options.onAmenitiesARequested?.();
        await options.delayAmenitiesA;
        // O cancelamento do contexto pode abortar a conexão enquanto a resposta está retida.
        try { await json(route, 200, [amenity(tenant)]); }
        catch { /* Requisição já cancelada pelo cliente. */ }
        finally { options.onAmenitiesASettled?.(); }
      } else {
        await json(route, 200, [amenity(tenant)]);
      }
      return;
    }
    if (request.method() === 'GET' && path.endsWith('/reservations')) {
      await json(route, 200, { reservations: state.reservations.filter((item) => item.unitId === url.searchParams.get('unitId')), truncated: false });
      return;
    }
    if (request.method() === 'POST' && request.headers()['x-csrf-token'] !== csrf()) {
      await problem(route, 403, 'ACTION_FORBIDDEN', 'A confirmação de segurança precisa ser renovada.');
      return;
    }
    if (request.method() === 'POST' && path.endsWith('/reservations')) {
      const key = request.headers()['idempotency-key'] ?? '';
      state.createKeys.push(key);
      const body = request.postDataJSON() as { unitId: string; amenityId: string; startsAt: string; endsAt: string; acceptedPolicyVersion: number };
      const replayed = state.createKeys.length > 1;
      if (!replayed) {
        state.reservations.push({ ...reservationFixture(), ...body, occupiedFrom: body.startsAt,
          occupiedUntil: new Date(Date.parse(body.endsAt) + 15 * 60_000).toISOString() });
      }
      if (options.loseFirstCreationResponse && !replayed) {
        await route.abort('failed');
        return;
      }
      await json(route, 201, state.reservations[0], { 'Idempotency-Replayed': String(replayed) });
      return;
    }
    if (request.method() === 'POST' && path.endsWith('/cancel')) {
      state.cancelKeys.push(request.headers()['idempotency-key'] ?? '');
      if (options.cancelStatus) {
        await problem(route, options.cancelStatus, 'RESERVATION_RESOURCE_UNAVAILABLE', 'Recurso não disponível para esta operação.');
        return;
      }
      const current = state.reservations[0];
      if (!current) throw new Error('Fixture de cancelamento ausente.');
      current.status = 'CANCELLED'; current.version += 1;
      await json(route, 200, current, { 'Idempotency-Replayed': 'false' });
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND', 'Rota sintética não configurada no teste.');
  });
  return state;
}

export async function signIn(page: Page) {
  await page.goto('/entrar');
  await page.getByLabel('E-mail', { exact: true }).fill('morador-sintetico@example.invalid');
  await page.getByLabel('Senha', { exact: true }).fill('SYNTHETIC-TEST-PASSWORD');
  await page.getByLabel('Senha', { exact: true }).press('Enter');
  await expect(page).toHaveURL(/\/contextos/);
}

export async function chooseContext(page: Page, tenant: 'A' | 'B' = 'A') {
  await page.getByLabel('Condomínio', { exact: true }).selectOption(tenant === 'A' ? TENANT_A : TENANT_B);
  const unit = tenant === 'A' ? UNIT_A : UNIT_B;
  await expect(page.getByLabel('Unidade', { exact: true }).getByRole('option', { name: new RegExp(tenant === 'A' ? '101' : '202') })).toBeAttached();
  await page.getByLabel('Unidade', { exact: true }).selectOption(unit);
  await page.getByRole('button', { name: 'Continuar', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Reservas da unidade', exact: true })).toBeVisible();
}
