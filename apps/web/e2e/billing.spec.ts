import { test, expect, type Page, type Route } from '@playwright/test';
import { ACCOUNT, TENANT_A, TENANT_B, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';

test.use({ timezoneId: 'UTC' });

const PLAN = '83000000-0000-4000-8000-000000000001';
const SUBSCRIPTION = '82000000-0000-4000-8000-000000000001';
const CHARGE = '81000000-0000-4000-8000-000000000001';
const PAYMENT = '84000000-0000-4000-8000-000000000001';

interface Charge {
  chargeId: string; subscriptionId: string; tenantId: string; tenantName: string; periodStart: string; periodEnd: string; amountCents: number;
  dueOn: string; status: 'OPEN' | 'PAID' | 'CANCELLED'; overdue: boolean; cancelReason: string | null; version: number; createdAt: string;
  payment: null | { paymentId: string; amountCents: number; paidOn: string; method: string; reference: string; recordedBy: string; recordedAt: string };
}
interface BillingState { billing: boolean; charges: Charge[]; payments: unknown[]; voids: unknown[]; subscriptions: unknown[]; failNextPayment: boolean; requests: string[] }

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-000000000009' });
}

/** API sintética do painel master com o financeiro; registrada por último, tem precedência sobre as demais rotas. */
async function installBillingApi(page: Page, billing = true): Promise<BillingState> {
  const created = '2030-01-05T12:00:00.000Z';
  const state: BillingState = { billing, payments: [], voids: [], subscriptions: [], failNextPayment: false, requests: [], charges: [
    { chargeId: CHARGE, subscriptionId: SUBSCRIPTION, tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', periodStart: '2030-01-05',
      periodEnd: '2030-02-05', amountCents: 49_900, dueOn: '2030-01-10', status: 'OPEN', overdue: true, cancelReason: null, version: 0,
      createdAt: created, payment: null },
  ] };
  await page.route('**/v1/platform/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    state.requests.push(`${request.method()} ${path}${url.search}`);
    if (path === '/v1/platform/session') {
      await json(route, 200, { accountId: ACCOUNT, role: 'PLATFORM_ADMIN', mfaRequired: false,
        capabilities: state.billing ? ['billing.manage', 'billing.read', 'platform.audit.read', 'tenants.manage', 'tenants.read']
          : ['platform.audit.read', 'tenants.manage', 'tenants.read'] });
      return;
    }
    if (path === '/v1/platform/tenants') {
      await json(route, 200, { tenants: [{ tenantId: TENANT_A, name: 'Condomínio Sintético A', timeZone: 'America/Sao_Paulo', active: true,
        version: 0, createdAt: created, updatedAt: created, onboarding: null }], truncated: false });
      return;
    }
    if (!state.billing) { await problem(route, 403, 'PLATFORM_ACCESS_DENIED'); return; }
    if (path === '/v1/platform/billing/charges') {
      const status = url.searchParams.get('status');
      const charges = state.charges.filter((charge) => !status || (status === 'OVERDUE' ? charge.status === 'OPEN' && charge.overdue : charge.status === status));
      await json(route, 200, { charges, truncated: false });
      return;
    }
    if (request.method() === 'POST' && path === `/v1/platform/billing/charges/${CHARGE}/payments`) {
      const body = request.postDataJSON() as { amountCents: number; paidOn: string; method: string; reference: string; expectedVersion: number };
      state.payments.push(body);
      const charge = state.charges[0]!;
      if (state.failNextPayment) { state.failNextPayment = false; await problem(route, 503, 'BILLING_DEPENDENCY_UNAVAILABLE'); return; }
      if (body.amountCents !== charge.amountCents) { await problem(route, 422, 'BILLING_PAYMENT_AMOUNT_MISMATCH'); return; }
      if (body.expectedVersion !== charge.version) { await problem(route, 409, 'BILLING_CHARGE_CHANGED'); return; }
      Object.assign(charge, { status: 'PAID', overdue: false, version: charge.version + 1, payment: { paymentId: PAYMENT, amountCents: body.amountCents,
        paidOn: body.paidOn, method: body.method, reference: body.reference, recordedBy: ACCOUNT, recordedAt: new Date().toISOString() } });
      await json(route, 200, charge);
      return;
    }
    if (request.method() === 'POST' && path === `/v1/platform/billing/payments/${PAYMENT}/void`) {
      const body = request.postDataJSON() as { expectedChargeVersion: number; reason: string };
      state.voids.push(body);
      const charge = state.charges[0]!;
      Object.assign(charge, { status: 'OPEN', overdue: true, version: charge.version + 1, payment: null });
      await json(route, 200, charge);
      return;
    }
    if (path === '/v1/platform/billing/plans') {
      await json(route, 200, [{ planId: PLAN, name: 'Plano Sintético', priceCents: 49_900, period: 'MONTHLY', active: true, version: 0,
        createdAt: created, updatedAt: created }]);
      return;
    }
    if (path === '/v1/platform/billing/subscriptions' && request.method() === 'POST') {
      state.subscriptions.push(request.postDataJSON());
      await problem(route, 409, 'BILLING_START_OVERLAP');
      return;
    }
    if (path === '/v1/platform/billing/subscriptions') {
      await json(route, 200, [{ subscriptionId: SUBSCRIPTION, tenantId: TENANT_A, tenantName: 'Condomínio Sintético A', planId: PLAN,
        planName: 'Plano Sintético', priceCents: 49_900, period: 'MONTHLY', startsOn: '2030-01-05', endsOn: null, dueDay: 10, status: 'ACTIVE',
        version: 0, createdAt: created },
      { subscriptionId: '82000000-0000-4000-8000-000000000002', tenantId: TENANT_B, tenantName: 'Condomínio Sintético B', planId: PLAN,
        planName: 'Plano Sintético', priceCents: 49_900, period: 'MONTHLY', startsOn: '2030-01-05', endsOn: '2099-01-05', dueDay: 10, status: 'ENDED',
        version: 1, createdAt: created }]);
      return;
    }
    if (path === '/v1/platform/audit-events') { await json(route, 200, { events: [], nextCursor: null }); return; }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });
  return state;
}

test('pagamento manual só vira "paga" com a confirmação do servidor; divergência e resultado incerto não viram sucesso', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installBillingApi(page);
  await signIn(page);
  await page.goto('/master/financeiro');
  await expect(page.getByRole('heading', { name: 'Assinatura dos condomínios' })).toBeVisible();
  const row = page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' });
  await expect(row.getByText('Vencida', { exact: true })).toBeVisible();
  await expect(row.getByText('R$ 499,00', { exact: false })).toBeVisible();
  await row.getByRole('button', { name: 'Registrar pagamento' }).click();
  const form = page.getByRole('form', { name: 'Registrar pagamento de Condomínio Sintético A' });
  await expect(form.getByLabel('Valor pago (R$)')).toHaveValue('499,00');
  await form.getByLabel('Data do pagamento').fill('2026-01-08');
  await form.getByLabel('Forma de pagamento').selectOption('PIX');
  await form.getByLabel('Valor pago (R$)').fill('400,00');
  await form.getByLabel('Referência do comprovante').fill('E2E-SINTETICO-001');
  await form.getByRole('button', { name: 'Registrar pagamento' }).click();
  await expect(form.getByText('O valor pago precisa ser igual ao da cobrança. Pagamento parcial não é registrado.')).toBeVisible();
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  state.failNextPayment = true;
  await form.getByLabel('Valor pago (R$)').fill('499,00');
  await form.getByRole('button', { name: 'Registrar pagamento' }).click();
  await expect(form.getByText('Atualize a lista e confira o resultado antes de repetir.', { exact: false })).toBeVisible();
  await expect(page.getByText('registrado: cobrança', { exact: false })).toHaveCount(0);
  await form.getByRole('button', { name: 'Registrar pagamento' }).click();
  await expect(page.getByText('Pagamento de R$ 499,00 registrado: cobrança de Condomínio Sintético A paga.')).toBeVisible();
  expect(state.payments).toEqual([
    { amountCents: 40_000, paidOn: '2026-01-08', method: 'PIX', reference: 'E2E-SINTETICO-001', expectedVersion: 0 },
    { amountCents: 49_900, paidOn: '2026-01-08', method: 'PIX', reference: 'E2E-SINTETICO-001', expectedVersion: 0 },
    { amountCents: 49_900, paidOn: '2026-01-08', method: 'PIX', reference: 'E2E-SINTETICO-001', expectedVersion: 0 },
  ]);
  // Lista "Em aberto" atualizada não mostra mais a cobrança; "Pagas" mostra o pagamento registrado.
  await expect(page.getByRole('list', { name: 'Cobranças' })).toHaveCount(0);
  await page.getByLabel('Situação').selectOption('PAID');
  const paid = page.getByRole('listitem').filter({ hasText: 'Condomínio Sintético A' });
  await expect(paid.getByText('Paga', { exact: true })).toBeVisible();
  await expect(paid.getByText('comprovante E2E-SINTETICO-001', { exact: false })).toBeVisible();
  await paid.getByRole('button', { name: 'Estornar pagamento' }).click();
  await page.getByLabel('Motivo (fica na auditoria da plataforma)').fill('Registro no condomínio errado');
  await page.getByRole('form', { name: 'Estornar pagamento de Condomínio Sintético A' }).getByRole('button', { name: 'Estornar pagamento' }).click();
  await expect(page.getByText('Pagamento estornado: a cobrança de Condomínio Sintético A voltou a ficar em aberto.')).toBeVisible();
  expect(state.voids).toEqual([{ expectedChargeVersion: 1, reason: 'Registro no condomínio errado' }]);
});

test('conta sem capacidade financeira não vê a aba nem consulta o financeiro', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installBillingApi(page, false);
  await signIn(page);
  await page.goto('/master/financeiro');
  await expect(page.getByRole('heading', { name: 'Condomínios da plataforma' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Financeiro' })).toHaveCount(0);
  expect(state.requests.some((request) => request.includes('/billing/'))).toBe(false);
});

test('nova assinatura busca o condomínio pelo nome e início sobreposto não vira sucesso; encerramento futuro aparece agendado', async ({ page }) => {
  await installSyntheticApi(page);
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installBillingApi(page);
  await signIn(page);
  await page.goto('/master/financeiro');
  await page.getByRole('button', { name: 'Assinaturas', exact: true }).click();
  const scheduled = page.getByRole('list', { name: 'Assinaturas' }).getByRole('listitem').filter({ hasText: 'Condomínio Sintético B' });
  await expect(scheduled.getByText('Encerramento agendado', { exact: true })).toBeVisible();
  await expect(scheduled.getByText('encerra em 05/01/2099', { exact: false })).toBeVisible();
  await expect(scheduled.getByRole('button', { name: 'Gerar cobrança' })).toBeVisible();
  await expect(scheduled.getByRole('button', { name: 'Encerrar assinatura' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Nova assinatura', exact: true }).click();
  const form = page.getByRole('form', { name: 'Nova assinatura' });
  await form.getByLabel('Buscar condomínio por nome ou código').fill('  Sintético  ');
  await form.getByRole('button', { name: 'Buscar', exact: true }).click();
  await expect.poll(() => state.requests.some((item) => item === `GET /v1/platform/tenants?q=${encodeURIComponent('Sintético')}`)).toBe(true);
  await form.getByLabel('Condomínio', { exact: true }).selectOption(TENANT_A);
  await form.getByLabel('Plano', { exact: true }).selectOption(PLAN);
  await form.getByLabel('Início da assinatura (dia 1 a 28)').fill('2030-02-05');
  await form.getByLabel('Motivo (fica na auditoria da plataforma)').fill('Contrato sintético');
  await form.getByRole('button', { name: 'Criar assinatura' }).click();
  await expect(form.getByText('O início precisa ser depois do encerramento da assinatura anterior do condomínio.')).toBeVisible();
  await expect(page.getByText('Assinatura criada', { exact: false })).toHaveCount(0);
  await expect(page.getByText('PRIVATE-SERVER-DETAIL')).toHaveCount(0);
  expect(state.subscriptions).toEqual([{ tenantId: TENANT_A, planId: PLAN, startsOn: '2030-02-05', dueDay: 10, reason: 'Contrato sintético' }]);
});
