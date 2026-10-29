// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { billingCharge, billingPlan, billingSubscription, createBillingApi } from './billing';

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const ids = {
  charge: '81000000-0000-4000-8000-000000000001', subscription: '82000000-0000-4000-8000-000000000001',
  tenant: '20000000-0000-4000-8000-000000000001', plan: '83000000-0000-4000-8000-000000000001',
  payment: '84000000-0000-4000-8000-000000000001', account: '10000000-0000-4000-8000-000000000001',
};
const open = {
  chargeId: ids.charge, subscriptionId: ids.subscription, tenantId: ids.tenant, tenantName: 'Condomínio Fictício',
  periodStart: '2030-01-05', periodEnd: '2030-02-05', amountCents: 49_900, dueOn: '2030-01-10', status: 'OPEN', overdue: false,
  cancelReason: null, version: 0, createdAt: '2030-01-05T12:00:00Z', payment: null,
};
const payment = { paymentId: ids.payment, amountCents: 49_900, paidOn: '2030-01-08', method: 'PIX', reference: 'E2E-FICTICIO',
  recordedBy: ids.account, recordedAt: '2030-01-08T15:00:00Z' };

describe('contratos do financeiro da plataforma', () => {
  it('"paga" só com pagamento registrado do mesmo valor; atraso só em aberto; motivo só no cancelamento', () => {
    expect(billingCharge(open).status).toBe('OPEN');
    expect(billingCharge({ ...open, status: 'PAID', version: 1, payment }).payment?.reference).toBe('E2E-FICTICIO');
    expect(() => billingCharge({ ...open, status: 'PAID', payment: null })).toThrow();
    expect(() => billingCharge({ ...open, status: 'PAID', payment: { ...payment, amountCents: 100 } })).toThrow();
    expect(() => billingCharge({ ...open, payment })).toThrow();
    expect(() => billingCharge({ ...open, status: 'PAID', overdue: true, payment })).toThrow();
    expect(() => billingCharge({ ...open, status: 'CANCELLED', cancelReason: null })).toThrow();
    expect(() => billingCharge({ ...open, cancelReason: 'x' })).toThrow();
    expect(() => billingCharge({ ...open, amountCents: 1.5 })).toThrow();
    expect(() => billingCharge({ ...open, dueOn: '10/01/2030' })).toThrow();
    expect(() => billingCharge({ ...open, payment: undefined })).toThrow();
  });
  it('assinatura encerrada tem data de encerramento e vencimento entre 1 e 28', () => {
    const subscription = { subscriptionId: ids.subscription, tenantId: ids.tenant, tenantName: 'Condomínio Fictício', planId: ids.plan,
      planName: 'Plano Fictício', priceCents: 49_900, period: 'MONTHLY', startsOn: '2030-01-05', endsOn: null, dueDay: 10, status: 'ACTIVE',
      version: 0, createdAt: '2030-01-05T12:00:00Z' };
    expect(billingSubscription(subscription).dueDay).toBe(10);
    expect(billingSubscription({ ...subscription, status: 'ENDED', endsOn: '2030-06-05' }).endsOn).toBe('2030-06-05');
    expect(() => billingSubscription({ ...subscription, status: 'ENDED' })).toThrow();
    expect(() => billingSubscription({ ...subscription, endsOn: '2030-06-05' })).toThrow();
    expect(() => billingSubscription({ ...subscription, dueDay: 29 })).toThrow();
    expect(() => billingPlan({ planId: ids.plan, name: 'X', priceCents: -1, period: 'MONTHLY', active: true, version: 0,
      createdAt: '2030-01-05T12:00:00Z', updatedAt: '2030-01-05T12:00:00Z' })).toThrow();
  });
  it('filtro vai na consulta; pagamento usa POST com CSRF e versão lida; resposta incoerente é resultado incerto', async () => {
    let reply: () => Response = () => json({ ...open, status: 'PAID', version: 1, payment });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input, init) => {
      const path = String(input);
      if (path === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (init?.method === 'POST') return reply();
      return json({ charges: [open], truncated: false });
    });
    const client = createBillingApi(new CondPulseApi({ fetch: fetcher }));
    expect((await client.charges('OVERDUE')).charges).toHaveLength(1);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe('/v1/platform/billing/charges?status=OVERDUE');
    await client.charges(null);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe('/v1/platform/billing/charges');
    const body = { amountCents: 49_900, paidOn: '2030-01-08', method: 'PIX' as const, reference: 'E2E-FICTICIO', expectedVersion: 0 };
    expect((await client.recordPayment(ids.charge, body)).status).toBe('PAID');
    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(String(post[0])).toBe(`/v1/platform/billing/charges/${ids.charge}/payments`);
    expect(new Headers(post[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(post[1]?.body))).toEqual(body);
    reply = () => json({ ...open, status: 'PAID', version: 1, payment: null });
    await expect(client.recordPayment(ids.charge, body)).rejects.toMatchObject({ uncertain: true });
    await expect(client.recordPayment('nao-e-uuid', body)).rejects.toBeTruthy();
  });
});
