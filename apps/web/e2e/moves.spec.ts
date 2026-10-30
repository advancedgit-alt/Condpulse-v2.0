import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const MOVE = '9a000000-0000-4000-8000-000000000001';
const OTHER = '9a000000-0000-4000-8000-000000000002';
const ZONE = 'America/Sao_Paulo';
/** Data local do condomínio daqui a N dias (o condomínio sintético fica em São Paulo, UTC-3, sem horário de verão). */
const localDate = (days: number) => new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(new Date(Date.now() + days * 86_400_000));
const at = (date: string, hour: number, minute = 0) => new Date(`${date}T${String(hour + 3).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00Z`).toISOString();

interface Rules { rulesText: string; weekDays: number[]; startTime: string; endTime: string; minNoticeHours: number; maxDurationMinutes: number; version: number; updatedAt: string }
interface SyntheticMove {
  moveId: string; unitId: string; unitLabel: string; blockName: string | null; kind: string; startsAt: string; endsAt: string; providerName: string;
  vehiclePlate: string | null; items: string | null; rulesVersion: number; state: string; decisionNote: string | null; decidedAt: string | null;
  createdByMe: boolean; createdAt: string; version: number;
}
interface MoveState {
  rules: Rules | null; moves: SyntheticMove[]; creates: { key: string; body: Record<string, unknown> }[]; keys: Map<string, SyntheticMove>;
  ruleUpdates: unknown[]; decisions: unknown[]; cancels: unknown[]; managedQueries: string[]; conciergeQueries: string[];
  loseNextCreate: boolean; rulesChangedOnCreate: boolean;
}

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000e' });
}
const rules = (version = 1): Rules => ({ rulesText: 'Use o elevador de serviço com proteção nas paredes.', weekDays: [1, 2, 3, 4, 5, 6, 7], startTime: '08:00',
  endTime: '18:00', minNoticeHours: 24, maxDurationMinutes: 240, version, updatedAt: new Date().toISOString() });
const synthetic = (overrides: Partial<SyntheticMove> = {}): SyntheticMove => ({ moveId: MOVE, unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: 'Bloco 1',
  kind: 'MOVE_IN', startsAt: at(localDate(3), 9), endsAt: at(localDate(3), 12), providerName: 'Transportadora Fictícia', vehiclePlate: 'ABC1D23',
  items: 'Sofá e caixas', rulesVersion: 1, state: 'REQUESTED', decisionNote: null, decidedAt: null, createdByMe: true, createdAt: new Date().toISOString(),
  version: 0, ...overrides });

/** API sintética das mudanças (unidade, gestão e portaria), registrada por último (precedência). */
async function installMovesApi(page: Page, initial: Partial<MoveState> = {}): Promise<MoveState> {
  const state: MoveState = { rules: null, moves: [], creates: [], keys: new Map(), ruleUpdates: [], decisions: [], cancels: [], managedQueries: [],
    conciergeQueries: [], loseNextCreate: false, rulesChangedOnCreate: false, ...initial };
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    if (path === '/move-rules') { await json(route, 200, { rules: state.rules }); return; }
    if (path === '/management/move-rules') {
      const body = request.postDataJSON() as Omit<Rules, 'version' | 'updatedAt'> & { expectedVersion: number | null };
      state.ruleUpdates.push(body);
      const { expectedVersion: _expected, ...fields } = body;
      state.rules = { ...fields, version: (state.rules?.version ?? 0) + 1, updatedAt: new Date().toISOString() };
      await json(route, 200, state.rules);
      return;
    }
    if (path === `/units/${UNIT_A}/moves`) {
      if (request.method() === 'GET') { await json(route, 200, { moves: state.moves, truncated: false }); return; }
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as Record<string, unknown>;
      state.creates.push({ key, body });
      if (state.rulesChangedOnCreate) {
        state.rulesChangedOnCreate = false; state.rules = { ...rules(2), rulesText: 'Regras novas: proteja o piso.' };
        await problem(route, 409, 'MOVE_RULES_CHANGED'); return;
      }
      const previous = state.keys.get(key);
      if (previous) { await json(route, 201, previous, { 'Idempotency-Replayed': 'true' }); return; }
      const created = synthetic({ kind: body.kind as string, startsAt: body.startsAt as string, endsAt: body.endsAt as string,
        providerName: body.providerName as string, vehiclePlate: body.vehiclePlate as string | null, items: body.items as string | null,
        rulesVersion: body.acceptedRulesVersion as number, blockName: null });
      state.keys.set(key, created); state.moves.unshift(created);
      if (state.loseNextCreate) { state.loseNextCreate = false; await route.abort('connectionreset'); return; }
      await json(route, 201, created, { 'Idempotency-Replayed': 'false' });
      return;
    }
    const cancel = /^\/moves\/([^/]+)\/cancel$/.exec(path);
    if (cancel) {
      const body = request.postDataJSON() as { note: string | null; expectedVersion: number };
      state.cancels.push(body);
      const item = state.moves.find((candidate) => candidate.moveId === cancel[1])!;
      Object.assign(item, { state: 'CANCELLED', decisionNote: body.note, version: item.version + 1 });
      await json(route, 200, item);
      return;
    }
    if (path === '/management/moves') {
      const filter = url.searchParams.get('state');
      state.managedQueries.push(filter ?? 'ALL');
      await json(route, 200, { moves: state.moves.filter((item) => !filter || item.state === filter), truncated: false });
      return;
    }
    const decision = /^\/management\/moves\/([^/]+)\/decision$/.exec(path);
    if (decision) {
      const body = request.postDataJSON() as { decision: string; note: string | null; expectedVersion: number };
      state.decisions.push(body);
      const item = state.moves.find((candidate) => candidate.moveId === decision[1])!;
      Object.assign(item, { state: body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', decisionNote: body.note, decidedAt: new Date().toISOString(),
        version: item.version + 1 });
      await json(route, 200, item);
      return;
    }
    if (path === '/concierge/moves') {
      const from = url.searchParams.get('from')!; const until = url.searchParams.get('until')!;
      state.conciergeQueries.push(from);
      await json(route, 200, { moves: state.moves.filter((item) => item.state === 'APPROVED' && Date.parse(item.startsAt) < Date.parse(until)
        && Date.parse(item.endsAt) > Date.parse(from)), truncated: false });
      return;
    }
    await route.fallback();
  });
  return state;
}

test('unidade lê as regras, pede mudança com aceite e chave pendente e retira o pedido antes do início', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installMovesApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const section = page.getByRole('region', { name: 'Mudança e entrega de móveis' });
  await expect(section.getByText('A gestão ainda não publicou as regras de mudança.', { exact: false })).toBeVisible();
  await expect(section.getByRole('button', { name: 'Pedir mudança' })).toHaveCount(0);
  await expect(section.getByText('Nenhum pedido de mudança')).toBeVisible();
  state.rules = rules();
  await section.getByRole('button', { name: 'Atualizar mudanças' }).click();
  await expect(section.getByText('Regras de mudança (versão 1)')).toBeVisible();
  await expect(section.getByText('todos os dias, das 08:00 às 18:00; antecedência mínima de 24 h; até 4 h por pedido.').first()).toBeVisible();
  await section.getByRole('button', { name: 'Pedir mudança' }).click();
  const form = page.getByRole('form', { name: 'Pedir mudança' });
  await form.getByLabel('Tipo').selectOption('FURNITURE_DELIVERY');
  await form.getByLabel('Data').fill(localDate(3));
  await form.getByLabel('Início').fill('09:00');
  await form.getByLabel('Fim').fill('08:30');
  await expect(form.getByText('O fim precisa ser depois do início.')).toBeVisible();
  await form.getByLabel('Fim').fill('15:00');
  await expect(form.getByText('As regras permitem até 240 minutos por pedido.')).toBeVisible();
  await form.getByLabel('Fim').fill('12:00');
  await form.getByLabel('Responsável ou transportadora').fill('  Transportadora   Fictícia ');
  await form.getByLabel('Placa do veículo (opcional)').fill('AB');
  await expect(form.getByText('Informe a placa com 5 a 8 letras e números, ou deixe em branco.')).toBeVisible();
  await form.getByLabel('Placa do veículo (opcional)').fill('abc-1d23');
  await form.getByLabel('Itens (opcional)').fill('Sofá e caixas');
  const send = form.getByRole('button', { name: 'Enviar pedido de mudança' });
  await expect(send).toBeDisabled();
  await form.getByLabel('Li e aceito as regras de mudança (versão 1)').check();
  state.loseNextCreate = true;
  await send.click();
  await expect(form.getByText('O resultado não foi confirmado e o pedido ficou travado', { exact: false })).toBeVisible();
  await form.getByRole('button', { name: 'Voltar' }).click();
  await section.getByRole('button', { name: 'Pedir mudança' }).click();
  const reopened = page.getByRole('form', { name: 'Pedir mudança' });
  await expect(reopened.getByLabel('Responsável ou transportadora')).toHaveValue('  Transportadora   Fictícia ');
  await reopened.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }).click();
  await expect(section.getByText('Este pedido já estava enviado (aguardando a gestão).')).toBeVisible();
  const body = { kind: 'FURNITURE_DELIVERY', startsAt: at(localDate(3), 9), endsAt: at(localDate(3), 12), providerName: 'Transportadora Fictícia',
    vehiclePlate: 'ABC1D23', items: 'Sofá e caixas', acceptedRulesVersion: 1 };
  expect(state.creates.map((item) => item.body)).toEqual([body, body]);
  expect(state.creates[1]!.key).toBe(state.creates[0]!.key);
  const row = section.getByRole('list', { name: 'Pedidos de mudança da unidade' }).getByRole('listitem');
  await expect(row.getByText('Entrega de móveis')).toBeVisible();
  await expect(row.getByText('Aguardando a gestão')).toBeVisible();
  await expect(row.getByText('placa ABC1D23', { exact: false })).toBeVisible();
  await row.getByRole('button', { name: 'Retirar pedido' }).click();
  await row.getByRole('button', { name: 'Voltar' }).click();
  expect(state.cancels).toEqual([]);
  await row.getByRole('button', { name: 'Retirar pedido' }).click();
  await row.getByRole('button', { name: 'Confirmar retirada' }).click();
  await expect(section.getByText('Pedido de mudança retirado.')).toBeVisible();
  await expect(row.getByText('Cancelada')).toBeVisible();
  await expect(row.getByRole('button', { name: 'Retirar pedido' })).toHaveCount(0);
  expect(state.cancels).toEqual([{ note: null, expectedVersion: 0 }]);
});

test('regras alteradas durante o pedido pedem novo aceite da versão atual', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installMovesApi(page, { rules: rules() });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const section = page.getByRole('region', { name: 'Mudança e entrega de móveis' });
  await section.getByRole('button', { name: 'Pedir mudança' }).click();
  const form = page.getByRole('form', { name: 'Pedir mudança' });
  await form.getByLabel('Data').fill(localDate(3));
  await form.getByLabel('Início').fill('10:00');
  await form.getByLabel('Fim').fill('11:00');
  await form.getByLabel('Responsável ou transportadora').fill('Pessoa Fictícia');
  await form.getByLabel('Li e aceito as regras de mudança (versão 1)').check();
  state.rulesChangedOnCreate = true;
  await form.getByRole('button', { name: 'Enviar pedido de mudança' }).click();
  await expect(form.getByText('As regras de mudança foram alteradas. Leia a versão atual antes de continuar.')).toBeVisible();
  await expect(section.getByText('Regras de mudança (versão 2)', { exact: true })).toBeVisible();
  await expect(section.getByText('Regras novas: proteja o piso.')).toBeVisible();
  await expect(form.getByLabel('Li e aceito as regras de mudança (versão 2)')).not.toBeChecked();
  await expect(form.getByRole('button', { name: 'Enviar pedido de mudança' })).toBeDisabled();
  await form.getByLabel('Li e aceito as regras de mudança (versão 2)').check();
  await form.getByRole('button', { name: 'Enviar pedido de mudança' }).click();
  await expect(section.getByText('Pedido de mudança de entrada enviado', { exact: false })).toBeVisible();
  expect(state.creates.map((item) => item.body.acceptedRulesVersion)).toEqual([1, 2]);
  expect(state.creates[1]!.key).not.toBe(state.creates[0]!.key);
});

test('regras novas trazidas por atualização desfazem o aceite da versão anterior', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installMovesApi(page, { rules: rules() });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const section = page.getByRole('region', { name: 'Mudança e entrega de móveis' });
  await section.getByRole('button', { name: 'Pedir mudança' }).click();
  const form = page.getByRole('form', { name: 'Pedir mudança' });
  await form.getByLabel('Data').fill(localDate(3));
  await form.getByLabel('Início').fill('10:00');
  await form.getByLabel('Fim').fill('11:00');
  await form.getByLabel('Responsável ou transportadora').fill('Pessoa Fictícia');
  await form.getByLabel('Li e aceito as regras de mudança (versão 1)').check();
  // A gestão publica outra versão e a tela é atualizada antes do envio: o aceite da versão 1 não vale para a 2.
  state.rules = { ...rules(2), rulesText: 'Regras novas: proteja o piso.' };
  await section.getByRole('button', { name: 'Atualizar mudanças' }).click();
  await expect(section.getByText('Regras de mudança (versão 2)', { exact: true })).toBeVisible();
  await expect(form.getByLabel('Li e aceito as regras de mudança (versão 2)')).not.toBeChecked();
  await expect(form.getByRole('button', { name: 'Enviar pedido de mudança' })).toBeDisabled();
  expect(state.creates).toHaveLength(0);
  await form.getByLabel('Li e aceito as regras de mudança (versão 2)').check();
  await form.getByRole('button', { name: 'Enviar pedido de mudança' }).click();
  await expect(section.getByText('Pedido de mudança de entrada enviado', { exact: false })).toBeVisible();
  expect(state.creates.map((item) => item.body.acceptedRulesVersion)).toEqual([2]);
});

test('edição de regras guarda a versão aberta: publicação de outra pessoa no meio não é sobrescrita em silêncio', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installMovesApi(page, { rules: rules() });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/mudancas`);
  await page.getByRole('button', { name: 'Editar regras' }).click();
  const rulesForm = page.getByRole('form', { name: 'Editar regras de mudança' });
  await rulesForm.getByLabel('Regras exibidas às unidades').fill('Texto editado a partir da versão 1.');
  // Outra pessoa publica a versão 2 e a consulta é atualizada ao voltar à janela.
  state.rules = { ...rules(2), rulesText: 'Publicada por outra pessoa.' };
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')));
  await expect(page.getByText('Regras de mudança (versão 2)')).toBeVisible();
  await rulesForm.getByRole('button', { name: 'Salvar regras' }).click();
  await expect.poll(() => state.ruleUpdates.length).toBe(1);
  expect((state.ruleUpdates[0] as { expectedVersion: number }).expectedVersion).toBe(1);
});

test('envio incerto continua confirmável mesmo se as regras novas recusariam o período', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installMovesApi(page, { rules: rules() });
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/inicio`);
  const section = page.getByRole('region', { name: 'Mudança e entrega de móveis' });
  await section.getByRole('button', { name: 'Pedir mudança' }).click();
  const form = page.getByRole('form', { name: 'Pedir mudança' });
  await form.getByLabel('Data').fill(localDate(3));
  await form.getByLabel('Início').fill('09:00');
  await form.getByLabel('Fim').fill('12:00');
  await form.getByLabel('Responsável ou transportadora').fill('Pessoa Fictícia');
  await form.getByLabel('Li e aceito as regras de mudança (versão 1)').check();
  state.loseNextCreate = true;
  await form.getByRole('button', { name: 'Enviar pedido de mudança' }).click();
  const again = form.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' });
  await expect(again).toBeVisible();
  // A gestão reduz a duração máxima para 2 h: o período de 3 h já enviado não é revalidado na repetição.
  state.rules = { ...rules(2), maxDurationMinutes: 120 };
  await section.getByRole('button', { name: 'Atualizar mudanças' }).click();
  await expect(section.getByText('Regras de mudança (versão 2)', { exact: true })).toBeVisible();
  await expect(again).toBeEnabled();
  await again.click();
  await expect(section.getByText('Este pedido já estava enviado', { exact: false })).toBeVisible();
  expect(state.creates).toHaveLength(2);
  expect(state.creates[1]!.key).toBe(state.creates[0]!.key);
  expect(state.creates[1]!.body).toEqual(state.creates[0]!.body);
});

test('gestão publica as regras, aprova, recusa com motivo e cancela aprovada com motivo', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installMovesApi(page, { moves: [synthetic(), synthetic({ moveId: OTHER, kind: 'MOVE_OUT', startsAt: at(localDate(4), 9),
    endsAt: at(localDate(4), 11), vehiclePlate: null, items: null, createdByMe: false })] });
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/mudancas`);
  await expect(page.getByRole('heading', { name: 'Mudanças e entregas de móveis' })).toBeVisible();
  await expect(page.getByText('Regras de mudança ainda não publicadas')).toBeVisible();
  await page.getByRole('button', { name: 'Publicar regras' }).click();
  const rulesForm = page.getByRole('form', { name: 'Editar regras de mudança' });
  await rulesForm.getByRole('button', { name: 'Voltar' }).click();
  expect(state.ruleUpdates).toEqual([]);
  await page.getByRole('button', { name: 'Publicar regras' }).click();
  await expect(rulesForm.getByRole('button', { name: 'Salvar regras' })).toBeDisabled();
  await rulesForm.getByLabel('Regras exibidas às unidades').fill('Use o elevador de serviço.');
  await rulesForm.getByLabel('Sábado').uncheck();
  await rulesForm.getByLabel('Início do horário').fill('09:00');
  await rulesForm.getByLabel('Fim do horário').fill('17:00');
  await rulesForm.getByLabel('Antecedência mínima (horas)').fill('900');
  await expect(rulesForm.getByRole('button', { name: 'Salvar regras' })).toBeDisabled();
  await rulesForm.getByLabel('Antecedência mínima (horas)').fill('24');
  await rulesForm.getByLabel('Duração máxima por pedido (minutos)').fill('180');
  await rulesForm.getByRole('button', { name: 'Salvar regras' }).click();
  await expect(page.getByText('Regras de mudança (versão 1)')).toBeVisible();
  expect(state.ruleUpdates).toEqual([{ rulesText: 'Use o elevador de serviço.', weekDays: [1, 2, 3, 4, 5], startTime: '09:00', endTime: '17:00',
    minNoticeHours: 24, maxDurationMinutes: 180, expectedVersion: null }]);
  await page.getByRole('button', { name: 'Editar regras' }).click();
  await expect(page.getByRole('form', { name: 'Editar regras de mudança' }).getByLabel('Regras exibidas às unidades')).toHaveValue('Use o elevador de serviço.');
  await page.getByRole('form', { name: 'Editar regras de mudança' }).getByRole('button', { name: 'Voltar' }).click();

  const list = page.getByRole('list', { name: 'Pedidos de mudança' });
  const first = list.getByRole('listitem').filter({ hasText: 'Mudança de entrada' });
  await first.getByRole('button', { name: 'Aprovar' }).click();
  await first.getByLabel('Nota para a unidade (opcional)').fill('Use a garagem.');
  await first.getByRole('button', { name: 'Confirmar aprovação' }).click();
  await expect(page.getByText('Mudança aprovada. A portaria já vê o período.')).toBeVisible();
  const second = list.getByRole('listitem').filter({ hasText: 'Mudança de saída' });
  await second.getByRole('button', { name: 'Recusar' }).click();
  await expect(second.getByRole('button', { name: 'Confirmar recusa' })).toBeDisabled();
  await second.getByLabel('Motivo da recusa').fill('Elevador em manutenção nesse dia.');
  await second.getByRole('button', { name: 'Confirmar recusa' }).click();
  await expect(page.getByText('Pedido recusado. A unidade vê o motivo.')).toBeVisible();
  expect(state.decisions).toEqual([{ decision: 'APPROVE', note: 'Use a garagem.', expectedVersion: 0 },
    { decision: 'REJECT', note: 'Elevador em manutenção nesse dia.', expectedVersion: 0 }]);
  await expect(page.getByText('Nenhum pedido neste filtro.')).toBeVisible();

  await page.getByLabel('Filtrar pedidos de mudança').selectOption('APPROVED');
  const approved = page.getByRole('list', { name: 'Pedidos de mudança' }).getByRole('listitem');
  await expect(approved.getByText('Nota da gestão: Use a garagem.')).toBeVisible();
  await approved.getByRole('button', { name: 'Cancelar pedido' }).click();
  await approved.getByRole('button', { name: 'Voltar' }).click();
  await approved.getByRole('button', { name: 'Cancelar pedido' }).click();
  await approved.getByLabel('Motivo do cancelamento').fill('Obra no hall.');
  await approved.getByRole('button', { name: 'Confirmar cancelamento' }).click();
  await expect(page.getByText('Pedido cancelado. A unidade vê o motivo.')).toBeVisible();
  expect(state.cancels).toEqual([{ note: 'Obra no hall.', expectedVersion: 1 }]);
  await page.getByLabel('Filtrar pedidos de mudança').selectOption('ALL');
  await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
  await expect(page.getByRole('list', { name: 'Pedidos de mudança' }).getByRole('listitem')).toHaveCount(2);
  expect(state.managedQueries).toContain('APPROVED');
  expect(state.managedQueries.at(-1)).toBe('ALL');
});

async function openConcierge(page: Page, api: ApiState) {
  await page.route('**/v1/me', async (route) => {
    if (!api.authenticated) { await route.fallback(); return; }
    await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000c1', tenantId: TENANT_A,
      tenantName: 'Condomínio Sintético A', timeZone: ZONE, unitId: null, role: 'CONCIERGE' }] });
  });
}

test('portaria vê só as mudanças aprovadas do dia escolhido', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  await openConcierge(page, api);
  const state = await installMovesApi(page, { moves: [
    synthetic({ state: 'APPROVED', decidedAt: new Date().toISOString(), startsAt: at(localDate(0), 20), endsAt: at(localDate(0), 21), version: 1 }),
    synthetic({ moveId: OTHER, startsAt: at(localDate(0), 20), endsAt: at(localDate(0), 21) }),
  ] });
  await signIn(page);
  await page.goto(`/portaria/${TENANT_A}/visitas`);
  const section = page.getByRole('region', { name: 'Mudanças aprovadas' });
  const list = section.getByRole('list', { name: 'Mudanças aprovadas do dia' });
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await expect(list.getByText('Bloco 1 · 101 — Sintética A', { exact: false })).toBeVisible();
  await expect(list.getByText('placa ABC1D23', { exact: false })).toBeVisible();
  await section.getByLabel('Dia', { exact: true }).fill(localDate(1));
  await expect(section.getByText('Nenhuma mudança aprovada neste dia.')).toBeVisible();
  await section.getByRole('button', { name: 'Atualizar' }).click();
  await expect.poll(() => state.conciergeQueries.length).toBeGreaterThanOrEqual(3);
  expect(state.conciergeQueries[0]).toBe(at(localDate(0), 0));
  expect(state.conciergeQueries.at(-1)).toBe(at(localDate(1), 0));
});
