// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createFinanceApi, financeCharge, financePayer, formatCents, formatDay, parseCents, paymentInfo } from './finance';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const chargeId = '55000000-0000-4000-8000-000000000001';
const charge = {
  chargeId, unitId: unit, unitLabel: 'FICTÍCIA 101', blockName: null, kind: 'FINE', description: 'Multa fictícia', amountCents: 15000,
  dueDate: '2030-01-10', billingType: 'BOLETO', state: 'OPEN', asaasStatus: 'PENDING', competence: null,
  invoiceUrl: 'https://sandbox.asaas.com/i/000000000001', bankSlipUrl: 'https://sandbox.asaas.com/b/pdf/000000000001', paidAt: null,
  paidCents: null, issueError: null, cancelReason: null, createdAt: '2030-01-01T12:00:00Z', updatedAt: '2030-01-01T12:00:00Z', version: 1,
};
const payer = { payerId: '53000000-0000-4000-8000-000000000001', membershipId: '51000000-0000-4000-8000-000000000001', displayName: 'Dona Fictícia',
  role: 'OWNER', legalName: 'Dona Fictícia', document: '***.456.789-**', email: 'd***@example.invalid', customerSynced: true,
  createdAt: '2030-01-01T12:00:00Z', version: 1 };

describe('contrato do financeiro condominial', () => {
  it('valores, datas e links validados; documento só mascarado; link fora do Asaas recusado', () => {
    expect(financeCharge(charge).amountCents).toBe(15000);
    expect(() => financeCharge({ ...charge, invoiceUrl: 'https://outro.example/i/1' })).toThrow();
    expect(() => financeCharge({ ...charge, invoiceUrl: 'http://sandbox.asaas.com/i/1' })).toThrow();
    expect(() => financeCharge({ ...charge, state: 'PAID' })).toThrow();
    expect(() => financeCharge({ ...charge, amountCents: -1 })).toThrow();
    expect(() => financeCharge({ ...charge, dueDate: '2030-01-10T00:00:00Z' })).toThrow();
    expect(financePayer(payer).document).toBe('***.456.789-**');
    expect(() => financePayer({ ...payer, document: '123.456.789-09' })).toThrow();
    expect(() => financePayer({ ...payer, document: '12345678909' })).toThrow();
    expect(formatCents(15050)).toMatch(/150,50/);
    expect(formatDay('2030-01-10')).toBe('10/01/2030');
    expect(parseCents('1.234,56')).toBe(123456); expect(parseCents('150')).toBe(15000); expect(parseCents('150.5')).toBe(15050);
    expect(parseCents('R$ 10,00')).toBe(1000); expect(parseCents('abc')).toBeNull(); expect(parseCents('1,234')).toBeNull();
  });
  it('segunda via: boleto exige linha, PIX exige copia e cola, sempre da cobrança pedida', () => {
    const boleto = { chargeId, billingType: 'BOLETO', identificationField: '23793381286000000000300000000000100000000015000', pixPayload: null,
      invoiceUrl: charge.invoiceUrl, bankSlipUrl: charge.bankSlipUrl, dueDate: '2030-01-10', amountCents: 15000 };
    expect(paymentInfo(boleto, chargeId).identificationField).toContain('2379');
    expect(() => paymentInfo({ ...boleto, identificationField: null }, chargeId)).toThrow();
    expect(() => paymentInfo(boleto, '55000000-0000-4000-8000-000000000002')).toThrow();
    const pix = { ...boleto, billingType: 'PIX', identificationField: null, pixPayload: '00020101021226800014br.gov.bcb.pix2558SINTETICO' };
    expect(paymentInfo(pix, chargeId).pixPayload).toMatch(/^000201/);
  });
  it('rotas, corpos e chave de operação; unidade nunca recebe cobrança de outra unidade nem pendente', async () => {
    let reply: () => Response = () => json(charge, 201);
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createFinanceApi(new CondPulseApi({ fetch: fetcher }));
    const body = { unitId: unit, kind: 'FINE' as const, description: 'Multa fictícia', amountCents: 15000, dueDate: '2030-01-10',
      billingType: 'BOLETO' as const, reason: 'Aprovada em assembleia' };
    expect((await client.create(tenant, 'web:00000000-0000-4000-8000-000000000009', body)).state).toBe('OPEN');
    let call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/management/finance/charges`);
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('web:00000000-0000-4000-8000-000000000009');
    expect(JSON.parse(String(call[1]?.body))).toEqual(body);
    await expect(client.create(tenant, 'web:00000000-0000-4000-8000-000000000010', { ...body, amountCents: 1 })).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ ...charge, state: 'CANCELLED', cancelReason: 'Erro' });
    await client.cancel(tenant, chargeId, 1, 'Erro de lançamento');
    call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/management/finance/charges/${chargeId}/cancel`);
    expect(JSON.parse(String(call[1]?.body))).toEqual({ reason: 'Erro de lançamento', expectedVersion: 1 });
    reply = () => json(charge);
    await expect(client.cancel(tenant, chargeId, 1, 'x')).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ charges: [charge], truncated: false });
    expect((await client.unitCharges(tenant, unit)).charges).toHaveLength(1);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/units/${unit}/finance/charges`);
    reply = () => json({ charges: [{ ...charge, unitId: '20000000-0000-4000-8000-000000000002' }], truncated: false });
    await expect(client.unitCharges(tenant, unit)).rejects.toThrow();
    reply = () => json({ charges: [{ ...charge, state: 'PENDING_ISSUE', invoiceUrl: null, bankSlipUrl: null }], truncated: false });
    await expect(client.unitCharges(tenant, unit)).rejects.toThrow();
    reply = () => json({ created: 3, skipped: 1, issued: 3, pending: 0 });
    expect(await client.generate(tenant, '2030-01', 'Competência')).toEqual({ created: 3, skipped: 1, issued: 3, pending: 0 });
    reply = () => json({ referenceDate: '2030-02-01', totalCents: 15000, units: [{ unitId: unit, unitLabel: 'FICTÍCIA 101', blockName: null, charges: 1,
      totalCents: 15000, oldestDue: '2030-01-10' }] });
    expect((await client.delinquency(tenant)).units[0]!.totalCents).toBe(15000);
    reply = () => json({ referenceDate: '2030-01-05', totalCents: 15000, units: [{ unitId: unit, unitLabel: 'FICTÍCIA 101', blockName: null, charges: 1,
      totalCents: 15000, oldestDue: '2030-01-10' }] });
    await expect(client.delinquency(tenant)).rejects.toThrow();
  });
});
