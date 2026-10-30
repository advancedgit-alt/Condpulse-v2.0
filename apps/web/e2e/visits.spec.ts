import { test, expect, type Page, type Route } from '@playwright/test';
import { scanAccessibility } from './a11y';
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
  revokedAt: string | null; revokeReason: string | null; version: number; createdAt: string; shareLinkActive: boolean; reservationId: string | null; ruleId: string | null;
}
interface SyntheticArrival {
  arrivalId: string; unitId: string; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  permitId: string | null; state: 'WAITING' | 'APPROVED' | 'DENIED' | 'EXPIRED' | 'CANCELLED'; expiresAt: string; decidedAt: string | null;
  decisionSource: 'APP' | 'INTERCOM' | 'IN_PERSON' | 'CONCIERGE' | null; decisionNote: string | null; decidedByMe: boolean; version: number; createdAt: string;
}
interface SyntheticCorrection { eventId: string; correctedAt: string; reason: string; recordedByMe: boolean }
interface SyntheticRecord {
  entryId: string; unitId: string; unitLabel: string; blockName: string | null; visitorName: string; documentLast4: string | null;
  arrivalId: string | null; permitId: string | null; linkedPermitId: string | null; eventType: 'MANUAL_IN'; source: 'CONCIERGE'; evidence: string;
  note: string | null; enteredAt: string; recordedByMe: boolean; correction: SyntheticCorrection | null;
  exit: null | { eventId: string; exitedAt: string; note: string | null; recordedByMe: boolean; correction: SyntheticCorrection | null };
}
interface VisitState {
  records: SyntheticRecord[]; entryPosts: { key: string; body: unknown }[]; exitPosts: { key: string; body: unknown }[];
  correctionPosts: { key: string; body: unknown }[]; refuseEntry: string | null; loseNextEntry: boolean; entryKeys: Map<string, SyntheticRecord>;
  permits: SyntheticPermit[]; arrivals: SyntheticArrival[];
  permitPosts: { key: string; body: unknown }[]; arrivalPosts: { key: string; body: unknown }[];
  decisions: unknown[]; assisted: unknown[]; cancels: unknown[]; revokes: unknown[]; expectedSearches: string[]; queueSearches: string[];
  loseNextRegister: boolean; decidedElsewhere: boolean; refuseLinked: string | null; loseNextPermit: boolean; permitFailure: string | null;
  linkPosts: { permitId: string; csrf: string | undefined; key: string | undefined }[]; linkRevokes: string[]; tokens: string[];
}

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000f' });
}
const permitFixture = (overrides: Partial<SyntheticPermit> = {}): SyntheticPermit => ({ permitId: PERMIT, unitId: UNIT_A, unitLabel: '101 — Sintética A',
  blockName: 'Bloco 1', visitorName: 'Visita Prevista', documentLast4: '12AB', purpose: 'Visita familiar', validFrom: minutes(-30), validUntil: minutes(90),
  state: 'ACTIVE', createdByMe: false, revokedAt: null, revokeReason: null, version: 0, createdAt: minutes(-60), shareLinkActive: false, reservationId: null, ruleId: null, ...overrides });
const arrivalFixture = (overrides: Partial<SyntheticArrival> = {}): SyntheticArrival => ({ arrivalId: ARRIVAL, unitId: UNIT_A, unitLabel: '101 — Sintética A',
  blockName: 'Bloco 1', visitorName: 'Pessoa Fictícia', documentLast4: null, permitId: null, state: 'WAITING', expiresAt: minutes(5), decidedAt: null,
  decisionSource: null, decisionNote: null, decidedByMe: false, version: 0, createdAt: minutes(0), ...overrides });

/** API sintética de visitas, registrada por último (precedência); identidade com portaria quando pedido. */
async function installVisitsApi(page: Page, api: ApiState, concierge: boolean): Promise<VisitState> {
  const state: VisitState = { records: [], entryPosts: [], exitPosts: [], correctionPosts: [], refuseEntry: null, loseNextEntry: false,
    entryKeys: new Map(), permits: [], arrivals: [],
    permitPosts: [], arrivalPosts: [], decisions: [], assisted: [], cancels: [], revokes: [], expectedSearches: [], queueSearches: [],
    loseNextRegister: false, decidedElsewhere: false, refuseLinked: null, loseNextPermit: false, permitFailure: null,
    linkPosts: [], linkRevokes: [], tokens: [] };
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
      if (state.permitFailure) { const code = state.permitFailure; state.permitFailure = null; await problem(route, 400, code); return; }
      // Mesma chave e mesmos dados: devolve o convite já criado (repetição idempotente).
      const previous = state.permitPosts.findIndex((item) => item.key === key);
      if (previous < state.permitPosts.length - 1) {
        const replay = state.permits.find((item) => item.permitId === `70000000-0000-4000-8000-${String(previous + 1).padStart(12, '0')}`);
        if (replay) { await json(route, 201, replay, { 'Idempotency-Replayed': 'true' }); return; }
      }
      const permit = permitFixture({ permitId: `70000000-0000-4000-8000-${String(state.permitPosts.length).padStart(12, '0')}`, ...body,
        state: Date.parse(body.validFrom) > Date.now() ? 'SCHEDULED' : 'ACTIVE', createdByMe: true, createdAt: new Date().toISOString() });
      state.permits.unshift(permit);
      if (state.loseNextPermit) { state.loseNextPermit = false; await route.abort('connectionreset'); return; }
      await json(route, 201, permit, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const share = path.match(/^\/permits\/([^/]+)\/link$/);
    if (share && method === 'POST') {
      state.linkPosts.push({ permitId: share[1]!, csrf: request.headers()['x-csrf-token'], key: request.headers()['idempotency-key'] });
      const permit = state.permits.find((item) => item.permitId === share[1])!;
      const token = `${String.fromCharCode(65 + state.linkPosts.length)}`.repeat(21) + '_-' + 'z'.repeat(20);
      state.tokens.push(token);
      permit.shareLinkActive = true;
      await json(route, 201, { token, validUntil: permit.validUntil });
      return;
    }
    const unshare = path.match(/^\/permits\/([^/]+)\/link\/revoke$/);
    if (unshare && method === 'POST') {
      state.linkRevokes.push(unshare[1]!);
      const permit = state.permits.find((item) => item.permitId === unshare[1])!;
      permit.shareLinkActive = false;
      await json(route, 200, permit);
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
      if (body.permitId && state.refuseLinked) { await problem(route, 409, state.refuseLinked); return; }
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
    if (path === '/access-events' && method === 'GET') { await json(route, 200, { records: state.records, truncated: false }); return; }
    if (path === `/units/${UNIT_A}/access-events` && method === 'GET') {
      await json(route, 200, { records: state.records.filter((item) => item.unitId === UNIT_A), truncated: false });
      return;
    }
    if (path === '/access-events/entries' && method === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as { arrivalId: string | null; permitId: string | null; evidence: string; note: string | null };
      state.entryPosts.push({ key, body });
      if (state.refuseEntry) { await problem(route, 409, state.refuseEntry); return; }
      // Mesma chave: o servidor devolve o registro já gravado, sem criar outro.
      const previous = state.entryKeys.get(key);
      if (previous) { await json(route, 201, previous, { 'Idempotency-Replayed': 'true' }); return; }
      const arrival = body.arrivalId ? state.arrivals.find((item) => item.arrivalId === body.arrivalId)! : null;
      const basis = arrival ?? state.permits.find((item) => item.permitId === body.permitId)!;
      const record: SyntheticRecord = { entryId: `90000000-0000-4000-8000-${String(state.entryPosts.length).padStart(12, '0')}`, unitId: basis.unitId,
        unitLabel: basis.unitLabel, blockName: basis.blockName, visitorName: basis.visitorName, documentLast4: basis.documentLast4, arrivalId: body.arrivalId,
        permitId: body.permitId, linkedPermitId: arrival?.permitId ?? null, eventType: 'MANUAL_IN', source: 'CONCIERGE', evidence: body.evidence,
        note: body.note, enteredAt: new Date().toISOString(), recordedByMe: true, correction: null, exit: null };
      state.entryKeys.set(key, record);
      state.records.unshift(record);
      // Gravado e resposta perdida: o resultado fica incerto para a portaria.
      if (state.loseNextEntry) { state.loseNextEntry = false; await route.abort('connectionreset'); return; }
      await json(route, 201, record, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const accessExit = path.match(/^\/access-events\/([^/]+)\/exit$/);
    if (accessExit && method === 'POST') {
      const body = request.postDataJSON() as { note: string | null };
      state.exitPosts.push({ key: request.headers()['idempotency-key']!, body });
      const record = state.records.find((item) => item.entryId === accessExit[1])!;
      record.exit = { eventId: `91000000-0000-4000-8000-${String(state.exitPosts.length).padStart(12, '0')}`, exitedAt: new Date().toISOString(),
        note: body.note, recordedByMe: true, correction: null };
      await json(route, 201, record, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const accessCorrection = path.match(/^\/access-events\/([^/]+)\/correction$/);
    if (accessCorrection && method === 'POST') {
      const body = request.postDataJSON() as { reason: string };
      state.correctionPosts.push({ key: request.headers()['idempotency-key']!, body });
      const correction = { eventId: `92000000-0000-4000-8000-${String(state.correctionPosts.length).padStart(12, '0')}`, correctedAt: new Date().toISOString(),
        reason: body.reason, recordedByMe: true };
      const record = state.records.find((item) => item.entryId === accessCorrection[1] || item.exit?.eventId === accessCorrection[1])!;
      if (record.entryId === accessCorrection[1]) record.correction = correction; else record.exit!.correction = correction;
      await json(route, 201, record, { 'Idempotency-Replayed': 'false' });
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
  await scanAccessibility(page, 'unidade/acessos');
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

// A imagem de produção aplica CSP (style-src 'self'): só este ensaio de layout injeta a fonte larga sem CSP.
test.describe('fonte larga', () => {
  test.use({ bypassCSP: true });
  test('as quatro abas da unidade cabem sem rolagem nem rótulo cortado, também com fonte larga', async ({ page }) => {
    const api = await installSyntheticApi(page);
    await installVisitsApi(page, api, false);
    await signIn(page);
    await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
    await expect(page.getByRole('heading', { name: 'Acessos da unidade' })).toBeVisible();
    // Fonte de fallback larga (como no runner do CI): o pior caso para "Encomendas".
    await page.addStyleTag({ content: '*{font-family:"DejaVu Sans",sans-serif!important}' });
    const tabs = page.getByRole('navigation', { name: 'Navegação da unidade' });
    for (const width of [320, 340, 360, 390, 430, 480, 481, 560, 640, 720, 721, 800, 900]) {
      await page.setViewportSize({ width, height: 780 });
      await expect(tabs.getByRole('link')).toHaveCount(4);
      await expect(tabs.getByRole('link', { name: 'Acessos' })).toHaveAttribute('aria-current', 'page');
      // Página inteira a partir de 360 px (PRD); abaixo disso, ao menos as abas continuam inteiras.
      if (width >= 360) expect(await noHorizontalOverflow(page), `sem rolagem horizontal em ${width}px`).toBe(true);
      expect(await tabs.evaluate((nav) => [...nav.querySelectorAll('a')].every((link) => link.scrollWidth <= link.clientWidth)), `rótulos inteiros em ${width}px`).toBe(true);
      expect(await tabs.evaluate((nav) => nav.scrollWidth <= nav.clientWidth), `abas sem rolagem em ${width}px`).toBe(true);
    }
    await page.getByRole('navigation', { name: 'Navegação da unidade' }).getByRole('link', { name: 'Encomendas' }).click();
    await expect(tabs.getByRole('link', { name: 'Encomendas' })).toHaveAttribute('aria-current', 'page');
    for (const width of [320, 360, 481, 721]) {
      await page.setViewportSize({ width, height: 780 });
      expect(await tabs.evaluate((nav) => [...nav.querySelectorAll('a')].every((link) => link.scrollWidth <= link.clientWidth)), `aba atual inteira em ${width}px`).toBe(true);
    }
  });
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
  await scanAccessibility(page, 'portaria/visitas');
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

test('portaria registra entrada só de quem está autorizado e corrige a saída sem apagar o registro', async ({ page }) => {
  const state = await openConcierge(page);
  state.arrivals.push(arrivalFixture({ visitorName: 'Pessoa Autorizada', state: 'APPROVED', decidedAt: minutes(-1), decisionSource: 'APP', version: 1 }),
    arrivalFixture({ arrivalId: OTHER_ARRIVAL, visitorName: 'Pessoa Aguardando', createdAt: minutes(-2) }));
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const queue = page.getByRole('list', { name: 'Chegadas' });
  const waitingRow = queue.getByRole('listitem').filter({ hasText: 'Pessoa Aguardando' });
  await expect(waitingRow.getByText('Aguardando a unidade')).toBeVisible();
  await expect(waitingRow.getByRole('button', { name: 'Registrar entrada' })).toHaveCount(0);
  const approvedRow = queue.getByRole('listitem').filter({ hasText: 'Pessoa Autorizada' });
  await approvedRow.getByRole('button', { name: 'Registrar entrada' }).click();
  const form = page.getByRole('form', { name: 'Registrar entrada de Pessoa Autorizada' });
  await expect(form.getByText('Não representa passagem detectada por equipamento.', { exact: false })).toBeVisible();
  await expect(form.getByRole('button', { name: 'Registrar entrada' })).toBeDisabled();
  await form.getByRole('radio', { name: 'Conferi o documento' }).check();
  await form.getByRole('button', { name: 'Registrar entrada' }).click();
  await expect(page.getByText('Entrada registrada: Pessoa Autorizada em Bloco 1 · 101 — Sintética A', { exact: false })).toBeVisible();
  expect(state.entryPosts.map((item) => item.body)).toEqual([{ arrivalId: ARRIVAL, permitId: null, evidence: 'DOCUMENT_CHECKED', note: null }]);
  await expect(approvedRow.getByText('Entrada registrada pela portaria em', { exact: false })).toBeVisible();
  await expect(approvedRow.getByRole('button', { name: 'Registrar entrada' })).toHaveCount(0);

  const log = page.getByRole('list', { name: 'Registros de entrada e saída' });
  const row = log.getByRole('listitem').filter({ hasText: 'Pessoa Autorizada' });
  await expect(row.getByText('Sem registro de saída')).toBeVisible();
  await expect(row.getByText('chegada autorizada pela unidade', { exact: false })).toBeVisible();
  await row.getByRole('button', { name: 'Registrar saída' }).click();
  await page.getByRole('form', { name: 'Registrar saída de Pessoa Autorizada' }).getByRole('button', { name: 'Registrar saída' }).click();
  await expect(page.getByText('Saída registrada: Pessoa Autorizada', { exact: false })).toBeVisible();
  await expect(row.getByText('Saída registrada', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: 'Corrigir saída' }).click();
  const correction = page.getByRole('form', { name: 'Corrigir saída de Pessoa Autorizada' });
  await expect(correction.getByRole('button', { name: 'Registrar correção' })).toBeDisabled();
  await correction.getByLabel('Motivo da correção').fill('Saída de outra pessoa');
  await correction.getByRole('button', { name: 'Registrar correção' }).click();
  await expect(page.getByText('Correção registrada: saída de Pessoa Autorizada. O registro original continua guardado.')).toBeVisible();
  expect(state.correctionPosts.map((item) => item.body)).toEqual([{ reason: 'Saída de outra pessoa' }]);
  await expect(row.getByText('Saída corrigida em', { exact: false })).toContainText('Saída de outra pessoa');
  await expect(row.getByText('Sem registro de saída')).toBeVisible();
  await expect(row.getByRole('button', { name: 'Registrar saída' })).toBeVisible();
  await expect(page.getByRole('button', { name: /liberar|marcar entrada/i })).toHaveCount(0);
});

test('convite em vigor permite registrar entrada; agendado não; recusa do servidor não vira sucesso', async ({ page }) => {
  const state = await openConcierge(page);
  state.permits.push(permitFixture(), permitFixture({ permitId: SCHEDULED, visitorName: 'Visita Adiantada', documentLast4: null, purpose: null,
    validFrom: minutes(180), validUntil: minutes(300), state: 'SCHEDULED' }));
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const expected = page.getByRole('list', { name: 'Visitas previstas' });
  const early = expected.getByRole('listitem').filter({ hasText: 'Visita Adiantada' });
  await expect(early.getByRole('button', { name: 'Pedir confirmação à unidade' })).toBeVisible();
  await expect(early.getByRole('button', { name: 'Registrar entrada' })).toHaveCount(0);
  const current = expected.getByRole('listitem').filter({ hasText: 'Visita Prevista' });
  state.refuseEntry = 'ACCESS_PERMIT_NOT_CURRENT';
  await current.getByRole('button', { name: 'Registrar entrada' }).click();
  let form = page.getByRole('form', { name: 'Registrar entrada de Visita Prevista' });
  await form.getByRole('radio', { name: 'Reconhecida por quem autorizou, na portaria' }).check();
  await form.getByRole('button', { name: 'Registrar entrada' }).click();
  await expect(page.getByText('O convite não vale agora (antes do início, vencido, revogado ou de quem perdeu o vínculo) e não autoriza a entrada. Nada novo foi registrado.')).toBeVisible();
  await expect(page.getByText('Entrada registrada:', { exact: false })).toHaveCount(0);
  state.refuseEntry = null;
  await current.getByRole('button', { name: 'Registrar entrada' }).click();
  form = page.getByRole('form', { name: 'Registrar entrada de Visita Prevista' });
  await form.getByRole('radio', { name: 'Reconhecida por quem autorizou, na portaria' }).check();
  await form.getByLabel('Observação interna (opcional)').fill('  Chegou com a moradora ');
  await form.getByRole('button', { name: 'Registrar entrada' }).click();
  await expect(page.getByText('Entrada registrada: Visita Prevista', { exact: false })).toBeVisible();
  expect(state.entryPosts.at(-1)!.body).toEqual({ arrivalId: null, permitId: PERMIT, evidence: 'RECOGNIZED_BY_UNIT', note: 'Chegou com a moradora' });
  expect(state.entryPosts.at(-1)!.key).not.toBe(state.entryPosts.at(-2)!.key);
  await expect(current.getByText('Última entrada registrada em', { exact: false })).toBeVisible();
});

test('entrada pelo convite com resposta perdida continua travada ao fechar e reabrir, sem segunda entrada', async ({ page }) => {
  const state = await openConcierge(page);
  state.permits.push(permitFixture());
  // Entrada anterior pela chegada pedida a partir do convite também conta como última entrada do convite.
  state.records.push({ entryId: '90000000-0000-4000-8000-0000000000d1', unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: 'Bloco 1',
    visitorName: 'Visita Prevista', documentLast4: '12AB', arrivalId: OTHER_ARRIVAL, permitId: null, linkedPermitId: PERMIT, eventType: 'MANUAL_IN',
    source: 'CONCIERGE', evidence: 'DOCUMENT_CHECKED', note: null, enteredAt: minutes(-60), recordedByMe: false, correction: null,
    exit: { eventId: '91000000-0000-4000-8000-0000000000d1', exitedAt: minutes(-40), note: null, recordedByMe: false, correction: null } });
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const current = page.getByRole('list', { name: 'Visitas previstas' }).getByRole('listitem').filter({ hasText: 'Visita Prevista' });
  await expect(current.getByText('Última entrada registrada em', { exact: false })).toContainText('saída em');
  state.loseNextEntry = true;
  await current.getByRole('button', { name: 'Registrar entrada' }).click();
  let form = page.getByRole('form', { name: 'Registrar entrada de Visita Prevista' });
  await expect(form.getByText('Fica com a portaria e não aparece para a unidade.', { exact: false })).toBeVisible();
  await form.getByRole('radio', { name: 'Conferi o documento' }).check();
  await form.getByLabel('Observação interna (opcional)').fill('Primeira tentativa');
  await form.getByRole('button', { name: 'Registrar entrada' }).click();
  await expect(form.getByText('O resultado não foi confirmado e o registro ficou travado', { exact: false })).toBeVisible();
  // Fechar o formulário não descarta a pendência: ao reabrir, mesmos dados e mesma chave.
  await form.getByRole('button', { name: 'Voltar' }).click();
  await expect(form).toHaveCount(0);
  await current.getByRole('button', { name: 'Registrar entrada' }).click();
  form = page.getByRole('form', { name: 'Registrar entrada de Visita Prevista' });
  await expect(form.getByText('O resultado não foi confirmado e o registro ficou travado', { exact: false })).toBeVisible();
  await expect(form.getByRole('radio', { name: 'Conferi o documento' })).toBeChecked();
  await expect(form.getByLabel('Observação interna (opcional)')).toHaveValue('Primeira tentativa');
  await form.getByRole('button', { name: 'Tentar confirmar o mesmo registro' }).click();
  await expect(page.getByText('Esta entrada já estava registrada: Visita Prevista', { exact: false })).toBeVisible();
  expect(state.entryPosts).toHaveLength(2);
  expect(state.entryPosts[1]!.key).toBe(state.entryPosts[0]!.key);
  expect(state.records.filter((record) => record.permitId === PERMIT)).toHaveLength(1);
  // Pendência resolvida: uma nova entrada pelo convite (saiu e voltou) começa limpa, com chave nova.
  await current.getByRole('button', { name: 'Registrar entrada' }).click();
  form = page.getByRole('form', { name: 'Registrar entrada de Visita Prevista' });
  await expect(form.getByText('O resultado não foi confirmado', { exact: false })).toHaveCount(0);
  await expect(form.getByRole('radio', { name: 'Conferi o documento' })).not.toBeChecked();
});

test('morador vê o histórico registrado pela portaria, com saída e correção', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installVisitsApi(page, api, false);
  const base = { unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: 'Bloco 1', documentLast4: null, permitId: null, linkedPermitId: null, eventType: 'MANUAL_IN' as const,
    source: 'CONCIERGE' as const, evidence: 'DOCUMENT_CHECKED', note: null, recordedByMe: false };
  state.records.push(
    { ...base, entryId: '90000000-0000-4000-8000-0000000000c1', visitorName: 'Visita com Saída', arrivalId: ARRIVAL, enteredAt: minutes(-120), correction: null,
      exit: { eventId: '91000000-0000-4000-8000-0000000000c1', exitedAt: minutes(-60), note: null, recordedByMe: false, correction: null } },
    { ...base, entryId: '90000000-0000-4000-8000-0000000000c2', visitorName: 'Visita Corrigida', arrivalId: null, permitId: PERMIT, enteredAt: minutes(-30),
      correction: { eventId: '92000000-0000-4000-8000-0000000000c2', correctedAt: minutes(-20), reason: 'Pessoa não entrou', recordedByMe: false }, exit: null },
    { ...base, entryId: '90000000-0000-4000-8000-0000000000c3', visitorName: 'Visita sem Saída', arrivalId: OTHER_ARRIVAL, enteredAt: minutes(-10), correction: null, exit: null });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  const history = page.getByRole('list', { name: 'Entradas e saídas registradas' });
  await expect(history.getByRole('listitem').filter({ hasText: 'Visita com Saída' }).getByText('Saída registrada', { exact: true })).toBeVisible();
  await expect(history.getByRole('listitem').filter({ hasText: 'Visita com Saída' }).getByText('Saída registrada pela portaria em', { exact: false })).toBeVisible();
  await expect(history.getByRole('listitem').filter({ hasText: 'Visita Corrigida' }).getByText('Corrigido pela portaria: Pessoa não entrou')).toBeVisible();
  await expect(history.getByRole('listitem').filter({ hasText: 'Visita sem Saída' }).getByText('Sem registro de saída')).toBeVisible();
  await expect(history.getByText('Entrada registrada pela portaria em', { exact: false })).toHaveCount(3);
  await expect(page.getByText(/equipamento|sensor/i)).toHaveCount(0);
});

test('convite com resposta perdida trava na mesma chave e destrava quando a repetição é recusada', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installVisitsApi(page, api, false);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  await page.getByRole('button', { name: 'Convidar visitante' }).click();
  const form = page.getByRole('form', { name: 'Convidar visitante' });
  await form.getByLabel('Nome do visitante').fill('Visita Incerta');
  state.loseNextPermit = true;
  await form.getByRole('button', { name: 'Criar convite' }).click();
  await expect(form.getByText('O resultado não foi confirmado e os dados ficaram travados.', { exact: false })).toBeVisible();
  await expect(form.getByLabel('Nome do visitante')).toBeDisabled();
  await form.getByRole('button', { name: 'Tentar confirmar o mesmo convite' }).click();
  await expect(page.getByText('Este convite já estava criado para Visita Incerta', { exact: false })).toBeVisible();
  expect(state.permitPosts).toHaveLength(2);
  expect(state.permitPosts[1]!.key).toBe(state.permitPosts[0]!.key);
  expect(state.permits.filter((item) => item.visitorName === 'Visita Incerta')).toHaveLength(1);
  // Repetição recusada por regra prova que nada foi gravado: o formulário volta a ser editável.
  await page.getByRole('button', { name: 'Convidar visitante' }).click();
  const again = page.getByRole('form', { name: 'Convidar visitante' });
  await again.getByLabel('Nome do visitante').fill('Visita Atrasada');
  state.loseNextPermit = true;
  await again.getByRole('button', { name: 'Criar convite' }).click();
  await expect(again.getByText('O resultado não foi confirmado e os dados ficaram travados.', { exact: false })).toBeVisible();
  await expect(again.getByLabel('Nome do visitante')).toBeDisabled();
  expect(state.permits[0]!.visitorName).toBe('Visita Atrasada');
  state.permits.shift();
  state.permitFailure = 'VISIT_PERIOD_INVALID';
  await again.getByRole('button', { name: 'Tentar confirmar o mesmo convite' }).click();
  await expect(again.getByText('O convite começa a partir de agora', { exact: false })).toBeVisible();
  await expect(again.getByLabel('Nome do visitante')).toBeEnabled();
});

test('convite revogado no servidor não vincula chegada e a portaria vê o motivo', async ({ page }) => {
  const state = await openConcierge(page);
  state.permits.push(permitFixture());
  state.refuseLinked = 'VISIT_PERMIT_REVOKED';
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const row = page.getByRole('list', { name: 'Visitas previstas' }).getByRole('listitem').filter({ hasText: 'Visita Prevista' });
  await row.getByRole('button', { name: 'Pedir confirmação à unidade' }).click();
  await page.getByRole('form', { name: 'Pedir confirmação para Visita Prevista' }).getByRole('button', { name: 'Registrar chegada e pedir confirmação' }).click();
  await expect(page.getByText('Este convite foi revogado. Nada foi registrado.')).toBeVisible();
  await expect(page.getByText('Chegada registrada:', { exact: false })).toHaveCount(0);
  expect(state.arrivals).toHaveLength(0);
});

test('morador compartilha o link do convite: token só no fragmento, copiar, gerar outro e revogar', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const api = await installSyntheticApi(page);
  const state = await installVisitsApi(page, api, false);
  state.permits.push(permitFixture({ visitorName: 'Visita Link', createdByMe: true }));
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  const share = page.getByLabel('Link do convite de Visita Link');
  await share.getByRole('button', { name: 'Compartilhar link' }).click();
  await expect.poll(() => state.tokens.length).toBe(1);
  const field = share.getByRole('textbox', { name: /Link para enviar a/ });
  await expect(field).toHaveValue(new RegExp(`/visita#${state.tokens[0]}$`));
  expect(new URL(await field.inputValue()).pathname).toBe('/visita');
  expect(state.linkPosts).toHaveLength(1);
  expect(state.linkPosts[0]!.csrf).toBeTruthy();
  expect(state.linkPosts[0]!.key).toBeUndefined();
  await expect(share.getByText('Este link aparece só agora', { exact: false })).toBeVisible();
  await share.getByRole('button', { name: 'Copiar link' }).click();
  await expect(share.getByText('Link copiado.')).toBeVisible();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(await field.inputValue());
  await share.getByRole('button', { name: 'Gerar novo link' }).click();
  await expect.poll(() => state.tokens.length).toBe(2);
  await expect(field).toHaveValue(new RegExp(`/visita#${state.tokens[1]}$`));
  expect(state.tokens[0]).not.toBe(state.tokens[1]);
  await share.getByRole('button', { name: 'Revogar link' }).click();
  await expect(share.getByRole('textbox')).toHaveCount(0);
  await expect(share.getByRole('button', { name: 'Compartilhar link' })).toBeVisible();
  expect(state.linkRevokes).toEqual([state.permits[0]!.permitId]);
  // O convite continua em aberto: revogar o link não revoga o convite.
  await expect(page.getByRole('list', { name: 'Convites em aberto' }).getByText('Visita Link')).toBeVisible();
});

test('convite encerrado não oferece link', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installVisitsApi(page, api, false);
  state.permits.push(permitFixture({ visitorName: 'Visita Antiga', state: 'EXPIRED', validFrom: minutes(-300), validUntil: minutes(-60) }));
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/acessos`);
  await expect(page.getByRole('list', { name: 'Convites encerrados' }).getByText('Visita Antiga')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Compartilhar link' })).toHaveCount(0);
});
