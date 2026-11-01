// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createStaysApi, stay, stayDayClose, stayOrigin, stayPage } from './stays';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
export const openStay = {
  stayId: 'a0000000-0000-4000-8000-000000000001', unitId: unit, unitLabel: '101', blockName: null, visitorName: 'Visita Fictícia', documentLast4: null,
  kind: 'VISITOR', origin: 'MANUAL', basis: 'PERMIT', enteredAt: '2030-01-01T12:00:00Z', expectedExitAt: '2030-01-01T14:00:00Z', expectedSource: 'PERMIT',
  state: 'OPEN', closure: null, closedAt: null, level: 'L1', recordedLevel: 'DUE', overdue: true, justified: false, justification: null, justifiedAt: null,
  entryEventId: '90000000-0000-4000-8000-000000000001', exitEventId: null,
};
const closed = { ...openStay, stayId: 'a0000000-0000-4000-8000-000000000002', state: 'CLOSED', closure: 'RESIDENT_CONFIRMED', closedAt: '2030-01-01T13:00:00Z',
  level: 'NONE', overdue: false };

describe('contrato das permanências (V055)', () => {
  it('permanência aberta não tem fechamento; fechada diz como e quando; vencida só aberta', () => {
    expect(stay(openStay).overdue).toBe(true);
    expect(stay(closed).closure).toBe('RESIDENT_CONFIRMED');
    expect(() => stay({ ...openStay, closure: 'EXIT_RECORDED' })).toThrow();
    expect(() => stay({ ...closed, closedAt: null })).toThrow();
    expect(() => stay({ ...closed, overdue: true })).toThrow();
    expect(() => stay({ ...openStay, kind: 'PEDESTRE' })).toThrow();
    expect(() => stay({ ...openStay, level: 'L3' })).toThrow();
    expect(stayOrigin(stay(openStay))).toBe('registro manual da portaria, por convite');
  });
  it('página sem repetição e com no máximo 200 linhas', () => {
    expect(stayPage({ stays: [openStay, closed], truncated: false, generatedAt: '2030-01-01T14:30:00Z' }).stays).toHaveLength(2);
    expect(() => stayPage({ stays: [openStay, openStay], truncated: false, generatedAt: '2030-01-01T14:30:00Z' })).toThrow();
    expect(() => stayPage({ stays: Array.from({ length: 201 }, () => openStay), truncated: true, generatedAt: '2030-01-01T14:30:00Z' })).toThrow();
  });
  it('fechamento do dia: só entradas do dia, sem anuladas, abertas contadas e hash da lista', () => {
    const report = { date: '2030-01-01', timeZone: 'America/Sao_Paulo', from: '2030-01-01T03:00:00Z', until: '2030-01-02T03:00:00Z',
      generatedAt: '2030-01-02T10:00:00Z', entries: [openStay], truncated: false, stillOpen: 1, listHash: 'a'.repeat(64) };
    expect(stayDayClose(report, '2030-01-01').stillOpen).toBe(1);
    expect(() => stayDayClose(report, '2030-01-02')).toThrow();
    expect(() => stayDayClose({ ...report, stillOpen: 0 })).toThrow();
    expect(() => stayDayClose({ ...report, entries: [{ ...openStay, enteredAt: '2030-01-02T04:00:00Z' }] })).toThrow();
    expect(() => stayDayClose({ ...report, entries: [{ ...closed, state: 'VOIDED', closure: 'ENTRY_CORRECTED' }], stillOpen: 0 })).toThrow();
    expect(() => stayDayClose({ ...report, listHash: 'xyz' })).toThrow();
  });
  it('rotas: painel, vencidas, fechamento, justificativa e confirmação da unidade', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === '/auth/csrf') return json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' });
      if (init?.method === 'POST') return json(url.endsWith('/justification') ? { ...openStay, justified: true, justification: 'Aguardando morador', justifiedAt: '2030-01-01T14:31:00Z' } : closed,
        200, { 'Idempotency-Replayed': 'true' });
      if (url.includes('/day-close')) return json({ date: '2030-01-01', timeZone: 'America/Sao_Paulo', from: '2030-01-01T03:00:00Z', until: '2030-01-02T03:00:00Z',
        generatedAt: '2030-01-02T10:00:00Z', entries: [], truncated: false, stillOpen: 0, listHash: 'b'.repeat(64) });
      return json({ stays: [openStay], truncated: false, generatedAt: '2030-01-01T14:30:00Z' });
    });
    const client = createStaysApi(new CondPulseApi({ fetch: fetcher }));
    await client.open(tenant, false); await client.open(tenant, true); await client.dayClose(tenant, '2030-01-01'); await client.unitOpen(tenant, unit);
    const justified = await client.justify(tenant, openStay.stayId, 'Aguardando morador');
    const confirmed = await client.confirmExit(tenant, unit, closed.stayId);
    expect(justified.stay.justified).toBe(true); expect(confirmed.replayed).toBe(true);
    const urls = fetcher.mock.calls.map(([input]) => String(input)).filter((url) => url !== '/auth/csrf');
    expect(urls).toEqual([`/v1/tenants/${tenant}/stays`, `/v1/tenants/${tenant}/stays?view=overdue`, `/v1/tenants/${tenant}/stays/day-close?date=2030-01-01`,
      `/v1/tenants/${tenant}/units/${unit}/stays`, `/v1/tenants/${tenant}/stays/${openStay.stayId}/justification`,
      `/v1/tenants/${tenant}/units/${unit}/stays/${closed.stayId}/exit-confirmation`]);
    const body = fetcher.mock.calls.find(([input]) => String(input).endsWith('/justification'))![1]!.body;
    expect(JSON.parse(String(body))).toEqual({ reason: 'Aguardando morador' });
  });
});
