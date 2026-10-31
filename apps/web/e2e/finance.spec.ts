import { test, expect, type Page, type Route } from '@playwright/test';
import { TENANT_A, UNIT_A, installSyntheticApi, signIn } from './fixtures';
import { installManagementApi } from './management-fixtures';
import { scanAccessibility } from './a11y';

test.use({ timezoneId: 'America/Sao_Paulo' });

const CHARGE = '55000000-0000-4000-8000-0000000000f1';
const OWNER_MEMBERSHIP = '51000000-0000-4000-8000-0000000000f1';
const localDate = (days: number) => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date(Date.now() + days * 86_400_000));
const charge = (overrides: Record<string, unknown> = {}) => ({ chargeId: CHARGE, unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: null, kind: 'FINE',
  description: 'Multa sintética', amountCents: 15000, dueDate: localDate(5), billingType: 'BOLETO', state: 'OPEN', asaasStatus: 'PENDING', competence: null,
  invoiceUrl: 'https://sandbox.asaas.com/i/000000000001', bankSlipUrl: 'https://sandbox.asaas.com/b/pdf/000000000001', paidAt: null, paidCents: null,
  issueError: null, cancelReason: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), version: 1, ...overrides });

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}

/** API sintética do financeiro (gestão e unidade), registrada por último (precedência). */
async function installFinanceApi(page: Page) {
  const state = { charges: [] as ReturnType<typeof charge>[], creates: [] as { key: string | null; body: unknown }[], payers: [] as unknown[],
    cancels: [] as unknown[], paymentReads: 0 };
  await page.route('**/v1/tenants/*/management/finance/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(`/v1/tenants/${TENANT_A}/management/finance`, '');
    if (path === '/units' && request.method() === 'GET') {
      await json(route, 200, [{ unitId: UNIT_A, unitLabel: '101 — Sintética A', blockName: null, rented: false,
        payer: state.payers.length ? { payerId: '53000000-0000-4000-8000-0000000000f1', membershipId: OWNER_MEMBERSHIP, displayName: 'Dona Sintética',
          role: 'OWNER', legalName: 'Dona Sintética', document: '***.456.789-**', email: 'd***@example.invalid', customerSynced: true,
          createdAt: new Date().toISOString(), version: 1 } : null,
        candidates: [{ membershipId: OWNER_MEMBERSHIP, displayName: 'Dona Sintética', role: 'OWNER' }] }]);
      return;
    }
    if (path === `/units/${UNIT_A}/payer` && request.method() === 'PUT') {
      state.payers.push(request.postDataJSON());
      await json(route, 200, { payerId: '53000000-0000-4000-8000-0000000000f1', membershipId: OWNER_MEMBERSHIP, displayName: 'Dona Sintética',
        role: 'OWNER', legalName: 'Dona Sintética', document: '***.456.789-**', email: 'd***@example.invalid', customerSynced: true,
        createdAt: new Date().toISOString(), version: 1 });
      return;
    }
    if (path === '/charges' && request.method() === 'GET') { await json(route, 200, { charges: state.charges, truncated: false }); return; }
    if (path === '/charges' && request.method() === 'POST') {
      const body = request.postDataJSON() as { dueDate: string; amountCents: number; billingType: string; description: string };
      state.creates.push({ key: request.headers()['idempotency-key'] ?? null, body });
      const created = charge({ dueDate: body.dueDate, amountCents: body.amountCents, billingType: body.billingType, description: body.description });
      state.charges = [created];
      await json(route, 201, created);
      return;
    }
    if (path === `/charges/${CHARGE}/cancel` && request.method() === 'POST') {
      state.cancels.push(request.postDataJSON());
      state.charges = [charge({ state: 'CANCELLED', cancelReason: 'Lançada por engano', version: 2 })];
      await json(route, 200, state.charges[0]);
      return;
    }
    await json(route, 404, { code: 'SYNTHETIC_TEST_ROUTE_NOT_FOUND', status: 404 });
  });
  await page.route(`**/v1/tenants/${TENANT_A}/units/${UNIT_A}/finance/**`, async (route) => {
    const path = new URL(route.request().url()).pathname.replace(`/v1/tenants/${TENANT_A}/units/${UNIT_A}/finance`, '');
    if (path === '/charges') { await json(route, 200, { charges: [charge(), charge({ chargeId: '55000000-0000-4000-8000-0000000000f2', state: 'PAID',
      description: 'Taxa condominial sintética', kind: 'CONDO_FEE', paidAt: localDate(-1), paidCents: 45000, amountCents: 45000 })], truncated: false }); return; }
    if (path === `/charges/${CHARGE}/payment`) {
      state.paymentReads += 1;
      await json(route, 200, { chargeId: CHARGE, billingType: 'BOLETO', identificationField: '23793381286000000000300000000000100000000015000', pixPayload: null,
        invoiceUrl: 'https://sandbox.asaas.com/i/000000000001', bankSlipUrl: 'https://sandbox.asaas.com/b/pdf/000000000001', dueDate: localDate(5), amountCents: 15000 });
      return;
    }
    await json(route, 404, { code: 'SYNTHETIC_TEST_ROUTE_NOT_FOUND', status: 404 });
  });
  return state;
}

test('gestão define o pagador, cria cobrança com chave de operação e cancela com motivo', async ({ page }) => {
  await installSyntheticApi(page, { manager: true });
  await installManagementApi(page, { assurance: 'AAL2', verifiedFactor: true });
  const state = await installFinanceApi(page);
  await signIn(page);
  await page.goto(`/gestao/${TENANT_A}/financeiro`);
  await expect(page.getByRole('heading', { name: 'Financeiro do condomínio' })).toBeVisible();
  await expect(page.getByText('Nenhuma cobrança nesta situação.')).toBeVisible();
  await page.getByRole('button', { name: 'Pagadores' }).click();
  await page.getByRole('button', { name: 'Definir pagador' }).click();
  const payerForm = page.getByRole('form', { name: /Pagador da unidade/ });
  await payerForm.getByLabel('Quem paga').selectOption(OWNER_MEMBERSHIP);
  await payerForm.getByLabel('CPF ou CNPJ').fill('123.456.789-09');
  await payerForm.getByLabel('E-mail').fill('dona@example.invalid');
  await payerForm.getByLabel('Motivo (auditoria)').fill('Definido em assembleia sintética');
  await payerForm.getByRole('button', { name: 'Salvar pagador' }).click();
  await expect(page.getByText('Pagador definido: Dona Sintética.')).toBeVisible();
  expect(state.payers).toEqual([{ membershipId: OWNER_MEMBERSHIP, legalName: 'Dona Sintética', document: '123.456.789-09', email: 'dona@example.invalid',
    reason: 'Definido em assembleia sintética' }]);
  await expect(page.getByText(/\*\*\*\.456\.789-\*\*/)).toBeVisible();

  await page.getByRole('button', { name: 'Nova cobrança' }).click();
  const form = page.getByRole('form', { name: 'Nova cobrança' });
  await form.getByLabel('Unidade', { exact: true }).selectOption(UNIT_A);
  await form.getByLabel('Tipo', { exact: true }).selectOption('FINE');
  await form.getByLabel(/Descrição/).fill('Multa sintética');
  await form.getByLabel('Valor (R$)').fill('150,00');
  await form.getByLabel('Vencimento', { exact: true }).fill(localDate(5));
  await form.getByLabel('Motivo (auditoria)').fill('Aprovada pelo conselho sintético');
  await form.getByRole('button', { name: 'Criar e emitir' }).click();
  await expect(page.getByText(/Cobrança emitida: R\$\s150,00/)).toBeVisible();
  expect(state.creates).toHaveLength(1);
  expect(state.creates[0]!.key).toMatch(/^web:/);
  expect(state.creates[0]!.body).toEqual({ unitId: UNIT_A, kind: 'FINE', billingType: 'BOLETO', description: 'Multa sintética', amountCents: 15000,
    dueDate: localDate(5), reason: 'Aprovada pelo conselho sintético' });

  await page.getByRole('button', { name: 'Cobranças', exact: true }).click();
  const row = page.getByRole('list', { name: 'Cobranças' }).getByRole('listitem').filter({ hasText: 'Multa sintética' });
  await row.getByRole('button', { name: 'Cancelar cobrança' }).click();
  await row.getByLabel('Motivo do cancelamento').fill('Lançada por engano');
  await row.getByRole('button', { name: 'Confirmar cancelamento' }).click();
  await expect(page.getByText('Cobrança cancelada no Asaas e no CondPulse.')).toBeVisible();
  expect(state.cancels).toEqual([{ reason: 'Lançada por engano', expectedVersion: 1 }]);
});

test('morador vê as cobranças da unidade e copia a linha digitável da segunda via', async ({ page }) => {
  await installSyntheticApi(page);
  const state = await installFinanceApi(page);
  await signIn(page);
  await page.goto(`/morador/${TENANT_A}/${UNIT_A}/financeiro`);
  await expect(page.getByRole('heading', { name: 'Cobranças da unidade' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('link', { name: 'Financeiro' })).toBeVisible();
  const open = page.getByRole('list', { name: 'Cobranças em aberto' });
  await expect(open.getByText('Multa sintética')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Histórico de cobranças' }).getByText('Taxa condominial sintética')).toBeVisible();
  expect(state.paymentReads).toBe(0);
  await open.getByRole('button', { name: 'Segunda via' }).click();
  await expect(page.getByText('23793381286000000000300000000000100000000015000')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Abrir fatura no Asaas' })).toHaveAttribute('href', 'https://sandbox.asaas.com/i/000000000001');
  expect(state.paymentReads).toBe(1);
  await scanAccessibility(page, 'financeiro da unidade');
});
