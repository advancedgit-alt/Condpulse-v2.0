import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const PERMIT = '70000000-0000-4000-8000-0000000000a1';
const SCHEDULED = '70000000-0000-4000-8000-0000000000a2';
const ARRIVAL = '80000000-0000-4000-8000-0000000000b1';
const OTHER_ARRIVAL = '80000000-0000-4000-8000-0000000000b2';
const minutes = (value: number) => new Date(Date.now() + value * 60_000).toISOString();

interface SyntheticPermit {
  permitId: string; unitId: string; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  purpose: string | null; validFrom: string; validUntil: string; state: 'SCHEDULED' | 'ACTIVE' | 'REVOKED' | 'EXPIRED'; createdByMe: boolean;
  revokedAt: string | null; revokeReason: string | null; version: number; createdAt: string;
}
interface SyntheticArrival {
  arrivalId: string; unitId: string; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  permitId: string | null; state: 'WAITING' | 'APPROVED' | 'DENIED' | 'EXPIRED' | 'CANCELLED'; expiresAt: string; decidedAt: string | null;
  decisionSource: 'APP' | 'INTERCOM' | 'IN_PERSON' | 'CONCIERGE' | null; decisionNote: string | null; decidedByMe: boolean; version: number; createdAt: string;
}
interface VisitState {
  permits: SyntheticPermit[]; arrivals: SyntheticArrival[];
  permitPosts: { key: string; body: unknown }[]; arrivalPosts: { key: string; body: unknown }[];
  decisions: unknown[]; assisted: unknown[]; cancels: unknown[]; revokes: unknown[]; expectedSearches: string[]; queueSearches: string[];
  loseNextRegister: boolean; decidedElsewhere: boolean;
}

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000f' });
}
const permitFixture = (overrides: Partial<SyntheticPermit> = {}): SyntheticPermit => ({ permitId: PERMIT, unitId: UNIT_A, unitLabel: '101 — Sintética A',
  blockName: 'Bloco 1', visitorName: 'Visita Prevista', documentLast4: '12AB', purpose: 'Visita familiar', validFrom: minutes(-30), validUntil: minutes(90),
  state: 'ACTIVE', createdByMe: false, revokedAt: null, revokeReason: null, version: 0, createdAt: minutes(-60), ...overrides });
const arrivalFixture = (overrides: Partial<SyntheticArrival> = {}): SyntheticArrival => ({ arrivalId: ARRIVAL, unitId: UNIT_A, unitLabel: '101 — Sintética A',
  blockName: 'Bloco 1', visitorName: 'Pessoa Fictícia', documentLast4: null, permitId: null, state: 'WAITING', expiresAt: minutes(5), decidedAt: null,
  decisionSource: null, decisionNote: null, decidedByMe: false, version: 0, createdAt: minutes(0), ...overrides });

/** API sintética de visitas, registrada por último (precedência); identidade com portaria quando pedido. */
async function installVisitsApi(page: Page, api: ApiState, concierge: boolean): Promise<VisitState> {
  const state: VisitState = { permits: [], arrivals: [], permitPosts: [], arrivalPosts: [], decisions: [], assisted: [], cancels: [], revokes: [],
    expectedSearches: [], queueSearches: [], loseNextRegister: false, decidedElsewhere: false };
  if (concierge) {
    await page.route('**/v1/me', async (route) => {
      if (!api.authenticated) { await route.fallback(); return; }
      await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
        tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: null, role: 'CONCIERGE' }] });
    });
  }
  const decide = (arrival: SyntheticArrival, values: Partial<SyntheticArrival>) =>
    Object.assign(arrival, { decidedAt: new Date().toISOString(), version: arrival.version + 1, ...values });
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    const method = request.method();
    if (path === '/concierge/units') { await json(route, 200, [{ unitId: UNIT_A, label: '101 — Sintética A', blockName: 'Bloco 1' }]); return; }
    if (path === `/units/${UNIT_A}/permits` && method === 'GET') { await json(route, 200, { permits: state.permits, truncated: false }); return; }
    if (path === `/units/${UNIT_A}/permits` && method === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { visitorName: string; documentLast4: string | null; purpose: string | null; validFrom: string; validUntil: string };
      state.permitPosts.push({ key, body });
      const permit = permitFixture({ permitId: `70000000-0000-4000-8000-${String(state.permitPosts.length).padStart(12, '0')}`, ...body,
        state: Date.parse(body.validFrom) > Date.now() ? 'SCHEDULED' : 'ACTIVE', createdByMe: true, createdAt: new Date().toISOString() });
      state.permits.unshift(permit);
      await json(route, 201, permit, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const revoke = path.match(/^\/permits\/([^/]+)\/revoke$/);
    if (revoke && method === 'POST') {
      state.revokes.push(request.postDataJSON());
      const permit = state.permits.find((item) => item.permitId === revoke[1])!;
      const body = request.postDataJSON() as { reason: string };
      Object.assign(permit, { state: 'REVOKED', version: permit.version + 1, revokedAt: new Date().toISOString(), revokeReason: body.reason });
      await json(route, 200, permit);
      return;
    }
    if (path === `/units/${UNIT_A}/arrivals` && method === 'GET') { await json(route, 200, { arrivals: state.arrivals, truncated: false }); return; }
    const decision = path.match(/^\/arrivals\/([^/]+)\/decision$/);
    if (decision && method === 'POST') {
      const body = request.postDataJSON() as { decision: 'APPROVE' | 'DENY' };
      state.decisions.push(body);
      const arrival = state.arrivals.find((item) => item.arrivalId === decision[1])!;
      decide(arrival, { state: body.decision === 'APPROVE' ? 'APPROVED' : 'DENIED', decisionSource: 'APP', decidedByMe: true });
      await json(route, 200, arrival);
      return;
    }
    if (path === '/concierge/permits') {
      state.expectedSearches.push(url.search);
      const q = url.searchParams.get('q')?.toLowerCase();
      await json(route, 200, { permits: state.permits.filter((item) => ['ACTIVE', 'SCHEDULED'].includes(item.state)
        && (!q || item.visitorName.toLowerCase().includes(q))), truncated: false });
      return;
    }
    if (path === '/arrivals' && method === 'GET') {
      state.queueSearches.push(url.search);
      const status = url.searchParams.get('status');
      await json(route, 200, { arrivals: state.arrivals.filter((item) => !status || item.state === status), truncated: false });
      return;
    }
    if (path === '/arrivals' && method === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { unitId: string; visitorName: string; documentLast4: string | null; permitId: string | null };
      state.arrivalPosts.push({ key, body });
      const previous = state.arrivalPosts.filter((item) => item.key === key).length > 1;
      if (!previous) state.arrivals.unshift(arrivalFixture({ arrivalId: `80000000-0000-4000-8000-${String(state.arrivalPosts.length).padStart(12, '0')}`,
        ...body, createdAt: new Date().toISOString(), expiresAt: minutes(5) }));
      if (state.loseNextRegister) { state.loseNextRegister = false; await route.abort('connectionreset'); return; }
      const created = state.arrivals.find((item) => item.visitorName === body.visitorName)!;
      await json(route, 201, created, { 'Idempotency-Replayed': String(previous) });
      return;
    }
    const assisted = path.match(/^\/arrivals\/([^/]+)\/assisted-decision$/);
    if (assisted && method === 'POST') {
      const body = request.postDataJSON() as { decision: 'APPROVE' | 'DENY'; source: 'INTERCOM' | 'IN_PERSON'; note: string };
      state.assisted.push(body);
      const arrival = state.arrivals.find((item) => item.arrivalId === assisted[1])!;
      if (state.decidedElsewhere) {
        decide(arrival, { state: 'APPROVED', decisionSource: 'APP' });
        await problem(route, 409, 'ARRIVAL_ALREADY_DECIDED');
        return;
      }
      decide(arrival, { state: body.decision === 'APPROVE' ? 'APPROVED' : 'DENIED', decisionSource: body.source, decisionNote: body.note, decidedByMe: true });
      await json(route, 200, arrival);
      return;
    }
    const cancel = path.match(/^\/arrivals\/([^/]+)\/cancel$/);
    if (cancel && method === 'POST') {
      const body = request.postDataJSON() as { reason: string };
      state.cancels.push(body);
      const arrival = state.arrivals.find((item) => item.arrivalId === cancel[1])!;
      decide(arrival, { state: 'CANCELLED', decisionSource: 'CONCIERGE', decisionNote: body.reason, decidedByMe: true });
      await json(route, 200, arrival);
      return;
    }
    await route.fallback();
  });
  return state;
}

async function openConcierge(page: Page) {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installVisitsApi(page, api, true);
  await signIn(page);
  return state;
}
async function noHorizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
}

test('morador responde a chegada uma vez, convida com chave de operação e revoga com motivo', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installVisitsApi(page, api, false);
  state.arrivals.push(arrivalFixture());
  state.permits.push(permitFixture({ permitId: SCHEDULED, visitorName: 'Visita Agendada', documentLast4: null, purpose: null,
    validFrom: minutes(180), validUntil: minutes(300), state: 'SCHEDULED', createdByMe: true }));
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  await expect(page.getByRole('heading', { name: 'Acessos da unidade' })).toBeVisible();
  const waiting = page.getByRole('list', { name: 'Chegadas aguardando resposta' });
  await expect(waiting.getByText('Pessoa Fictícia')).toBeVisible();
  await expect(waiting.getByText('Sem resposta no prazo nada é liberado', { exact: false })).toBeVisible();
  await waiting.getByRole('button', { name: 'Autorizar entrada' }).click();
  await expect(page.getByText('Entrada de Pessoa Fictícia autorizada. A portaria confere a pessoa antes de liberar.')).toBeVisible();
  expect(state.decisions).toEqual([{ decision: 'APPROVE' }]);
  const recent = page.getByRole('list', { name: 'Chegadas recentes' });
  await expect(recent.getByText('Entrada autorizada')).toBeVisible();
  await expect(recent.getByText('Respondida pelo aplicativo por você', { exact: false })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Chegadas aguardando resposta' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Convidar visitante' }).click();
  const form = page.getByRole('form', { name: 'Convidar visitante' });
  await form.getByLabel('Nome do visitante').fill('  Visita   Nova ');
  await form.getByLabel('Final do documento (opcional)').fill('12ab');
  await form.getByRole('button', { name: 'Criar convite' }).click();
  await expect(page.getByText('Convite criado para Visita Nova', { exact: false })).toBeVisible();
  expect(state.permitPosts).toHaveLength(1);
  const body = state.permitPosts[0]!.body as { visitorName: string; documentLast4: string; purpose: null; validFrom: string; validUntil: string };
  expect(body).toMatchObject({ visitorName: 'Visita Nova', documentLast4: '12AB', purpose: null });
  expect(Date.parse(body.validUntil) - Date.parse(body.validFrom)).toBe(2 * 3_600_000);
  expect(state.permitPosts[0]!.key).toMatch(/^[A-Za-z0-9._:-]{16,200}$/);
  const open = page.getByRole('list', { name: 'Convites em aberto' });
  await expect(open.getByRole('listitem').filter({ hasText: 'Visita Nova' }).getByText('Vale agora')).toBeVisible();
  const scheduled = open.getByRole('listitem').filter({ hasText: 'Visita Agendada' });
  await expect(scheduled.getByText('Agendado', { exact: true })).toBeVisible();
  await scheduled.getByRole('button', { name: 'Revogar convite' }).click();
  const revoke = page.getByRole('form', { name: 'Revogar convite de Visita Agendada' });
  await expect(revoke.getByRole('button', { name: 'Revogar convite' })).toBeDisabled();
  await revoke.getByLabel('Motivo').fill('Visita adiada');
  await revoke.getByRole('button', { name: 'Revogar convite' }).click();
  await expect(page.getByText('Convite de Visita Agendada revogado. A portaria deixa de vê-lo nas visitas previstas.')).toBeVisible();
  expect(state.revokes).toEqual([{ reason: 'Visita adiada', expectedVersion: 0 }]);
  await expect(page.getByRole('list', { name: 'Convites encerrados' }).getByText('Revogado: Visita adiada')).toBeVisible();
});

test('as quatro abas da unidade cabem sem rolagem nem rótulo cortado, também com fonte larga', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installVisitsApi(page, api, false);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  await expect(page.getByRole('heading', { name: 'Acessos da unidade' })).toBeVisible();
  // Fonte de fallback larga (como no runner do CI): o pior caso para "Encomendas".
  await page.addStyleTag({ content: '*{font-family:"DejaVu Sans",sans-serif!important}' });
  const tabs = page.getByRole('navigation', { name: 'Navegação da unidade' });
  for (const width of [360, 390, 430, 480, 481, 560, 640, 720, 721, 800, 900]) {
    await page.setViewportSize({ width, height: 780 });
    await expect(tabs.getByRole('link')).toHaveCount(4);
    await expect(tabs.getByRole('link', { name: 'Acessos' })).toHaveAttribute('aria-current', 'page');
    expect(await noHorizontalOverflow(page), `sem rolagem horizontal em ${width}px`).toBe(true);
    expect(await tabs.evaluate((nav) => [...nav.querySelectorAll('a')].every((link) => link.scrollWidth <= link.clientWidth)), `rótulos inteiros em ${width}px`).toBe(true);
  }
  await page.getByRole('navigation', { name: 'Navegação da unidade' }).getByRole('link', { name: 'Encomendas' }).click();
  await expect(tabs.getByRole('link', { name: 'Encomendas' })).toHaveAttribute('aria-current', 'page');
  for (const width of [360, 481, 721]) {
    await page.setViewportSize({ width, height: 780 });
    expect(await tabs.evaluate((nav) => [...nav.querySelectorAll('a')].every((link) => link.scrollWidth <= link.clientWidth)), `aba atual inteira em ${width}px`).toBe(true);
  }
});

test('acessos indisponíveis no ambiente não mostram dados nem erro técnico', async ({ page }) => {
  await installSyntheticApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  await expect(page.getByText('Os acessos ainda não estão ativos neste ambiente.')).toBeVisible();
  await expect(page.getByText('SYNTHETIC_TEST_ROUTE_NOT_FOUND')).toHaveCount(0);
});

test('portaria registra chegada uma vez, sem ação de entrada, e anota a confirmação assistida depois do prazo', async ({ page }) => {
  const state = await openConcierge(page);
  await page.goto('/contextos');
  await page.getByRole('link', { name: 'Abrir portaria · Condomínio Sintético A' }).click();
  await page.getByRole('navigation', { name: 'Seções da portaria' }).getByRole('link', { name: 'Visitas' }).click();
  await expect(page).toHaveURL(new RegExp(`/portaria/${TENANT_A}/visitas$`));
  await expect(page.getByRole('heading', { name: 'Registrar chegada' })).toBeVisible();
  const form = page.getByRole('form', { name: 'Registrar chegada' });
  await form.getByLabel('Unidade de destino', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Nome informado pela pessoa').fill('  Pessoa   Visitante ');
  await form.getByLabel('Final do documento (opcional)').fill('9z');
  await form.getByRole('button', { name: 'Registrar chegada' }).click();
  await expect(page.getByText('Chegada de Pessoa Visitante registrada para Bloco 1 · 101 — Sintética A.', { exact: false })).toBeVisible();
  expect(state.arrivalPosts.map((item) => item.body)).toEqual([{ unitId: UNIT_A, visitorName: 'Pessoa Visitante', documentLast4: '9Z', permitId: null }]);
  const queue = page.getByRole('list', { name: 'Chegadas' });
  const row = queue.getByRole('listitem').filter({ hasText: 'Pessoa Visitante' });
  await expect(row.getByText('Aguardando a unidade')).toBeVisible();
  await expect(row.getByText('Ainda não há autorização.', { exact: false })).toBeVisible();
  // Nenhuma ação marca entrada ou libera alguém: só confirmação da unidade (com origem) ou encerramento.
  await expect(page.getByRole('button', { name: /entrada|liberar/i })).toHaveCount(0);

  Object.assign(state.arrivals[0]!, { state: 'EXPIRED', expiresAt: minutes(-1) });
  await page.getByRole('button', { name: 'Atualizar' }).first().click();
  await expect(row.getByText('Sem resposta no prazo')).toBeVisible();
  await expect(row.getByText('nada foi liberado', { exact: false })).toBeVisible();
  await row.getByRole('button', { name: 'Registrar confirmação da unidade' }).click();
  const assisted = page.getByRole('form', { name: 'Registrar confirmação de Pessoa Visitante' });
  await expect(assisted.getByRole('button', { name: 'Registrar confirmação' })).toBeDisabled();
  await assisted.getByRole('radio', { name: 'Autorizou a entrada' }).check();
  await assisted.getByRole('radio', { name: 'Pelo interfone' }).check();
  await expect(assisted.getByRole('button', { name: 'Registrar confirmação' })).toBeDisabled();
  await assisted.getByLabel('Quem confirmou e como').fill('Moradora confirmou pelo interfone');
  await assisted.getByRole('button', { name: 'Registrar confirmação' }).click();
  await expect(page.getByText('Entrada de Pessoa Visitante autorizada pela unidade (interfone). O registro ficou com a sua conta.')).toBeVisible();
  expect(state.assisted).toEqual([{ decision: 'APPROVE', source: 'INTERCOM', note: 'Moradora confirmou pelo interfone' }]);
  await expect(row.getByText('Entrada autorizada')).toBeVisible();
  await expect(row.getByText('Respondida pelo interfone, registrada pela portaria (com a sua conta)', { exact: false })).toBeVisible();
  await expect(row.getByRole('button', { name: 'Registrar confirmação da unidade' })).toHaveCount(0);
  await page.setViewportSize({ width: 360, height: 780 });
  expect(await noHorizontalOverflow(page)).toBe(true);
});

test('resultado incerto trava o registro na mesma chave; decisão concorrente não vira sucesso; encerramento com motivo', async ({ page }) => {
  const state = await openConcierge(page);
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const form = page.getByRole('form', { name: 'Registrar chegada' });
  await form.getByLabel('Unidade de destino', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Nome informado pela pessoa').fill('Entregador Fictício');
  state.loseNextRegister = true;
  await form.getByRole('button', { name: 'Registrar chegada' }).click();
  await expect(form.getByText('O resultado do último envio não foi confirmado', { exact: false })).toBeVisible();
  await expect(form.getByLabel('Nome informado pela pessoa')).toBeDisabled();
  await form.getByRole('button', { name: 'Tentar confirmar o mesmo envio' }).click();
  await expect(page.getByText('Esta chegada já estava registrada com os mesmos dados. Nada novo foi criado.')).toBeVisible();
  expect(state.arrivalPosts).toHaveLength(2);
  expect(state.arrivalPosts[1]!.key).toBe(state.arrivalPosts[0]!.key);
  expect(state.arrivals.filter((item) => item.visitorName === 'Entregador Fictício')).toHaveLength(1);

  // A unidade respondeu pelo aplicativo enquanto a portaria registrava: 409 com o estado final, sem sucesso falso.
  state.decidedElsewhere = true;
  const row = page.getByRole('list', { name: 'Chegadas' }).getByRole('listitem').filter({ hasText: 'Entregador Fictício' });
  await row.getByRole('button', { name: 'Registrar confirmação da unidade' }).click();
  const assisted = page.getByRole('form', { name: 'Registrar confirmação de Entregador Fictício' });
  await assisted.getByRole('radio', { name: 'Recusou a entrada' }).check();
  await assisted.getByRole('radio', { name: 'Presencialmente' }).check();
  await assisted.getByLabel('Quem confirmou e como').fill('Morador desceu e recusou');
  await assisted.getByRole('button', { name: 'Registrar confirmação' }).click();
  await expect(page.getByText('Esta chegada já foi respondida. Atualize a lista para ver a decisão. Nada novo foi registrado.')).toBeVisible();
  await expect(page.getByText('recusada pela unidade', { exact: false })).toHaveCount(0);
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  await expect(row.getByText('Respondida pela unidade no aplicativo', { exact: false })).toBeVisible();

  state.arrivals.push(arrivalFixture({ arrivalId: OTHER_ARRIVAL, visitorName: 'Visita que Foi Embora', createdAt: minutes(-3) }));
  await page.getByRole('button', { name: 'Atualizar' }).first().click();
  const gone = page.getByRole('list', { name: 'Chegadas' }).getByRole('listitem').filter({ hasText: 'Visita que Foi Embora' });
  await gone.getByRole('button', { name: 'Encerrar chegada' }).click();
  const cancel = page.getByRole('form', { name: 'Encerrar chegada de Visita que Foi Embora' });
  await expect(cancel.getByRole('button', { name: 'Encerrar chegada' })).toBeDisabled();
  await cancel.getByLabel('Motivo').fill('Visitante foi embora');
  await cancel.getByRole('button', { name: 'Encerrar chegada' }).click();
  await expect(page.getByText('Chegada de Visita que Foi Embora encerrada. O motivo ficou registrado.')).toBeVisible();
  expect(state.cancels).toEqual([{ reason: 'Visitante foi embora' }]);
  await page.getByLabel('Situação').selectOption('CANCELLED');
  await expect.poll(() => state.queueSearches.includes('?status=CANCELLED')).toBe(true);
  const cancelled = page.getByRole('list', { name: 'Chegadas' });
  await expect(cancelled.getByText('Visitante foi embora')).toBeVisible();
  await expect(cancelled.getByRole('listitem')).toHaveCount(1);
});

test('visitas previstas mostram a vigência do servidor e pedem confirmação vinculada ao convite', async ({ page }) => {
  const state = await openConcierge(page);
  state.permits.push(permitFixture(), permitFixture({ permitId: SCHEDULED, visitorName: 'Visita Adiantada', documentLast4: null, purpose: null,
    validFrom: minutes(180), validUntil: minutes(300), state: 'SCHEDULED' }));
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const expected = page.getByRole('list', { name: 'Visitas previstas' });
  const current = expected.getByRole('listitem').filter({ hasText: 'Visita Prevista' });
  await expect(current.getByText('Vale agora')).toBeVisible();
  await expect(current.getByText('documento final 12AB', { exact: false })).toBeVisible();
  const early = expected.getByRole('listitem').filter({ hasText: 'Visita Adiantada' });
  await expect(early.getByText('Agendado', { exact: true })).toBeVisible();
  await expect(early.getByText('Antes do início o convite não autoriza a entrada.', { exact: false })).toBeVisible();
  await page.getByRole('search', { name: 'Filtrar visitas previstas' }).getByLabel('Nome').fill('adiantada');
  await page.getByRole('search', { name: 'Filtrar visitas previstas' }).getByRole('button', { name: 'Buscar' }).click();
  await expect.poll(() => state.expectedSearches.at(-1)).toBe('?q=adiantada');
  await expect(expected.getByRole('listitem')).toHaveCount(1);
  await early.getByRole('button', { name: 'Pedir confirmação à unidade' }).click();
  const ask = page.getByRole('form', { name: 'Pedir confirmação para Visita Adiantada' });
  await ask.getByRole('button', { name: 'Registrar chegada e pedir confirmação' }).click();
  await expect(page.getByText('Chegada registrada: Visita Adiantada aguarda a resposta de Bloco 1 · 101 — Sintética A', { exact: false })).toBeVisible();
  expect(state.arrivalPosts.map((item) => item.body)).toEqual([{ unitId: UNIT_A, visitorName: 'Visita Adiantada', documentLast4: null, permitId: SCHEDULED }]);
  const row = page.getByRole('list', { name: 'Chegadas' }).getByRole('listitem').filter({ hasText: 'Visita Adiantada' });
  await expect(row.getByText('Vinculada a um convite da unidade.')).toBeVisible();
  await expect(row.getByText('Aguardando a unidade')).toBeVisible();
});
