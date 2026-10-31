import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, UNIT_A, installSyntheticApi, signIn, type ApiState } from './fixtures';

test.use({ timezoneId: 'UTC' });

const RULE = '76000000-0000-4000-8000-0000000000a1';
const WINDOW = '75000000-0000-4000-8000-0000000000a1';

async function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store', ...headers }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-00000000000f' });
}
/** Dia de hoje no fuso do condomínio (America/Sao_Paulo), como o servidor calcula. */
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
const plusDays = (day: string, amount: number) => new Date(Date.parse(`${day}T00:00:00Z`) + amount * 86_400_000).toISOString().slice(0, 10);

type Rule = Record<string, unknown> & { ruleId: string; state: string; version: number; visitorName: string };
interface RuleState { rules: Rule[]; posts: { key: string; body: Record<string, unknown> }[]; revokes: unknown[]; loseNext: boolean;
  failure: string | null; denied: boolean; permits: Record<string, unknown>[] }

const ruleFixture = (overrides: Partial<Rule> = {}): Rule => ({ ruleId: RULE, unitId: UNIT_A, visitorName: 'Diarista Fictícia', documentLast4: null,
  purpose: 'Limpeza', weekDays: [2, 4], startTime: '08:00', endTime: '17:00', validFrom: today(), validUntil: plusDays(today(), 30),
  state: 'ACTIVE', createdByMe: false, revokedAt: null, revokeReason: null, version: 0, createdAt: new Date().toISOString(),
  nextWindowFrom: new Date(Date.now() + 86_400_000).toISOString(), nextWindowUntil: new Date(Date.now() + 90_000_000).toISOString(), ...overrides });

async function installRules(page: Page, api: ApiState, role: 'RESIDENT' | 'DEPENDENT' = 'RESIDENT'): Promise<RuleState> {
  const state: RuleState = { rules: [], posts: [], revokes: [], loseNext: false, failure: null, denied: false, permits: [] };
  if (role === 'DEPENDENT') {
    await page.route('**/v1/me', async (route) => {
      if (!api.authenticated) { await route.fallback(); return; }
      await json(route, 200, { accountId: ACCOUNT, memberships: [{ membershipId: '50000000-0000-4000-8000-0000000000d1', tenantId: TENANT_A,
        tenantName: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', unitId: UNIT_A, role: 'DEPENDENT' }] });
    });
  }
  await page.route(`**/v1/tenants/${TENANT_A}/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}`, '');
    const method = request.method();
    if (path === `/units/${UNIT_A}/permits` && method === 'GET') { await json(route, 200, { permits: state.permits, truncated: false }); return; }
    if (path === `/units/${UNIT_A}/arrivals`) { await json(route, 200, { arrivals: [], truncated: false }); return; }
    if (path === `/units/${UNIT_A}/access-events`) { await json(route, 200, { records: [], truncated: false }); return; }
    if (path === `/units/${UNIT_A}/visit-rules` && method === 'GET') {
      if (state.denied) { await problem(route, 403, 'UNIT_ACCESS_DENIED'); return; }
      await json(route, 200, { rules: state.rules, truncated: false }); return;
    }
    if (path === `/units/${UNIT_A}/visit-rules` && method === 'POST') {
      const key = request.headers()['idempotency-key']!;
      const body = request.postDataJSON() as Record<string, unknown>;
      state.posts.push({ key, body });
      if (state.failure) { const code = state.failure; state.failure = null; await problem(route, 400, code); return; }
      const existing = state.rules.find((rule) => rule.key === key);
      if (!existing) state.rules.unshift(ruleFixture({ key, ruleId: `76000000-0000-4000-8000-${String(state.posts.length).padStart(12, '0')}`,
        ...body, createdByMe: true }));
      if (state.loseNext) { state.loseNext = false; await route.abort('connectionreset'); return; }
      const { key: _key, ...rule } = state.rules.find((item) => item.key === key)!;
      await json(route, 201, rule, { 'Idempotency-Replayed': existing ? 'true' : 'false' }); return;
    }
    const revoke = /^\/visit-rules\/([^/]+)\/revoke$/.exec(path);
    if (revoke && method === 'POST') {
      const body = request.postDataJSON() as { reason: string; expectedVersion: number };
      state.revokes.push(body);
      const rule = state.rules.find((item) => item.ruleId === revoke[1])!;
      if (rule.version !== body.expectedVersion) { await problem(route, 409, 'VISIT_RULE_CHANGED'); return; }
      Object.assign(rule, { state: 'REVOKED', revokedAt: new Date().toISOString(), revokeReason: body.reason, version: rule.version + 1,
        nextWindowFrom: null, nextWindowUntil: null });
      const { key: _key, ...result } = rule;
      await json(route, 200, result); return;
    }
    await route.fallback();
  });
  return state;
}

test('prestador recorrente: cadastrar com chave, repetir incerto sem duplicar, ver janela e revogar com motivo', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installRules(page, api);
  state.permits.push({ permitId: WINDOW, unitId: UNIT_A, unitLabel: '101', blockName: null, visitorName: 'Diarista Fictícia', documentLast4: null,
    purpose: 'Limpeza', validFrom: new Date(Date.now() - 600_000).toISOString(), validUntil: new Date(Date.now() + 3_600_000).toISOString(),
    state: 'ACTIVE', createdByMe: true, revokedAt: null, revokeReason: null, version: 0, createdAt: new Date().toISOString(),
    shareLinkActive: false, reservationId: null, ruleId: RULE });
  state.loseNext = true;
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/acessos`);
  await expect(page.getByRole('heading', { name: 'Prestadores recorrentes' })).toBeVisible();
  await expect(page.getByText('Nenhum prestador recorrente')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Convites em aberto' }).getByText('Janela de prestador recorrente: revogar aqui cancela só este dia.')).toBeVisible();

  await page.getByRole('button', { name: 'Cadastrar prestador' }).click();
  const form = page.getByRole('form', { name: 'Cadastrar prestador recorrente' });
  await form.getByLabel('Nome do prestador').fill('  Diarista   Fictícia ');
  await form.getByLabel('Serviço (opcional)').fill('Limpeza');
  const submit = form.getByRole('button', { name: 'Cadastrar prestador' });
  await expect(submit).toBeDisabled();
  await form.getByRole('checkbox', { name: 'terça' }).check();
  await form.getByRole('checkbox', { name: 'quinta' }).check();
  await form.getByRole('checkbox', { name: 'segunda' }).check();
  await form.getByRole('checkbox', { name: 'segunda' }).uncheck();
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(form.getByText('O resultado não foi confirmado e os dados ficaram travados', { exact: false })).toBeVisible();
  await expect(form.getByLabel('Nome do prestador')).toBeDisabled();
  await form.getByRole('button', { name: 'Tentar confirmar a mesma regra' }).click();
  await expect(page.getByText('Esta regra já estava cadastrada: Diarista Fictícia, terça, quinta, das 08:00 às 17:00.')).toBeVisible();
  expect(state.posts).toHaveLength(2);
  expect(state.posts[1]!.key).toBe(state.posts[0]!.key);
  expect(state.posts[0]!.key).toMatch(/^[A-Za-z0-9._:-]{16,200}$/);
  expect(state.posts[0]!.body).toEqual({ visitorName: 'Diarista Fictícia', documentLast4: null, purpose: 'Limpeza', weekDays: [2, 4],
    startTime: '08:00', endTime: '17:00', validFrom: today(), validUntil: plusDays(today(), 30) });
  expect(state.rules).toHaveLength(1);

  const active = page.getByRole('list', { name: 'Prestadores ativos' });
  const row = active.getByRole('listitem').filter({ hasText: 'Diarista Fictícia' });
  await expect(row.getByText('terça, quinta, das 08:00 às 17:00', { exact: false })).toBeVisible();
  await expect(row.getByText('Próxima janela:', { exact: false })).toBeVisible();
  await expect(row.getByText('Criada por você')).toBeVisible();
  await row.getByRole('button', { name: 'Revogar regra' }).click();
  const revoke = page.getByRole('form', { name: 'Revogar regra de Diarista Fictícia' });
  await expect(revoke.getByRole('button', { name: 'Revogar regra' })).toBeDisabled();
  await revoke.getByRole('button', { name: 'Voltar' }).click();
  await expect(revoke).toHaveCount(0);
  await row.getByRole('button', { name: 'Revogar regra' }).click();
  await revoke.getByLabel('Motivo').fill('Contrato encerrado');
  await revoke.getByRole('button', { name: 'Revogar regra' }).click();
  await expect(page.getByText('Regra de Diarista Fictícia revogada. As próximas janelas foram canceladas.')).toBeVisible();
  expect(state.revokes).toEqual([{ reason: 'Contrato encerrado', expectedVersion: 0 }]);
  await expect(page.getByRole('list', { name: 'Prestadores encerrados' }).getByText('Revogada: Contrato encerrado')).toBeVisible();
});

test('regras inválidas não são enviadas; recusa do servidor aparece; versão mudada fecha sem alterar', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installRules(page, api);
  state.rules.push(ruleFixture({ createdByMe: true }));
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/acessos`);
  await page.getByRole('button', { name: 'Cadastrar prestador' }).click();
  const form = page.getByRole('form', { name: 'Cadastrar prestador recorrente' });
  const submit = form.getByRole('button', { name: 'Cadastrar prestador' });
  await form.getByLabel('Nome do prestador').fill('Cuidador Fictício');
  await form.getByRole('checkbox', { name: 'sábado' }).check();
  await form.getByLabel('Chega a partir de').fill('18:00');
  await form.getByLabel('Até').fill('08:00');
  await expect(form.getByText('O fim precisa ser depois do início, no mesmo dia.')).toBeVisible();
  await expect(submit).toBeDisabled();
  await form.getByLabel('Até').fill('20:00');
  await form.getByLabel('Último dia').fill(plusDays(today(), 181));
  await expect(form.getByText('A regra vale por até 180 dias.')).toBeVisible();
  await expect(submit).toBeDisabled();
  await form.getByLabel('Último dia').fill(plusDays(today(), 10));
  await form.getByLabel('Final do documento (opcional)').fill('1');
  await expect(submit).toBeDisabled();
  await form.getByLabel('Final do documento (opcional)').fill('9z');
  expect(state.posts).toHaveLength(0);
  state.failure = 'VISIT_RULE_PERIOD_INVALID';
  await submit.click();
  await expect(form.getByText('A regra começa hoje ou depois (até 90 dias à frente) e dura até 180 dias.')).toBeVisible();
  expect(state.posts[0]!.body).toMatchObject({ documentLast4: '9Z', weekDays: [6], startTime: '18:00', endTime: '20:00' });
  await form.getByRole('button', { name: 'Voltar' }).click();

  // Outra pessoa revogou ou mudou a regra: o formulário fecha com aviso e nada é alterado.
  const row = page.getByRole('list', { name: 'Prestadores ativos' }).getByRole('listitem').filter({ hasText: 'Diarista Fictícia' });
  await row.getByRole('button', { name: 'Revogar regra' }).click();
  state.rules[0]!.version = 3;
  const revoke = page.getByRole('form', { name: 'Revogar regra de Diarista Fictícia' });
  await revoke.getByLabel('Motivo').fill('Encerrado');
  await revoke.getByRole('button', { name: 'Revogar regra' }).click();
  await expect(page.getByText('A regra foi alterada. Atualize antes de continuar. Nada foi alterado.')).toBeVisible();
  expect(state.rules[0]!.state).toBe('ACTIVE');
});

test('dependente sem permissão não vê prestadores; dependente só revoga a regra que criou', async ({ page }) => {
  const api = await installSyntheticApi(page);
  const state = await installRules(page, api, 'DEPENDENT');
  state.denied = true;
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/acessos`);
  await expect(page.getByText('Prestadores recorrentes não estão disponíveis para o seu vínculo. Peça a um responsável pela unidade.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cadastrar prestador' })).toHaveCount(0);

  state.denied = false;
  state.rules.push(ruleFixture({ visitorName: 'Regra Do Adulto' }),
    ruleFixture({ ruleId: '76000000-0000-4000-8000-0000000000a2', visitorName: 'Regra Do Dependente', createdByMe: true, nextWindowFrom: null, nextWindowUntil: null }));
  await page.getByRole('button', { name: 'Atualizar' }).click();
  const active = page.getByRole('list', { name: 'Prestadores ativos' });
  await expect(active.getByRole('listitem')).toHaveCount(2);
  await expect(active.getByRole('listitem').filter({ hasText: 'Regra Do Adulto' }).getByRole('button', { name: 'Revogar regra' })).toHaveCount(0);
  const own = active.getByRole('listitem').filter({ hasText: 'Regra Do Dependente' });
  await expect(own.getByText('Sem janela nos próximos dias.')).toBeVisible();
  await expect(own.getByRole('button', { name: 'Revogar regra' })).toBeVisible();
});

test('falha ao consultar prestadores mostra erro com nova tentativa', async ({ page }) => {
  const api = await installSyntheticApi(page);
  await installRules(page, api);
  let failures = 1;
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/visit-rules`, async (route) => {
    if (failures-- > 0) { await problem(route, 500, 'INTERNAL_ERROR'); return; }
    await route.fallback();
  });
  await signIn(page);
  await page.goto(`/c/${TENANT_A}/morador/${UNIT_A}/acessos`);
  const section = page.getByRole('region', { name: 'Prestadores recorrentes' });
  await section.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(section.getByText('Nenhum prestador recorrente')).toBeVisible();
});
