// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createStayRulesApi, stayRules } from './stayRules';
import { stayKinds } from './stays';

const rules = () => stayKinds.map((kind) => ({ kind, maxMinutes: 540, l1AfterMinutes: 30, l2AfterMinutes: 60,
  source: 'TENANT_RULE', version: 0, enabled: true, startsLocal: '08:00:00', endsLocal: '17:00:00' }));
const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
describe('regras de permanência do condomínio', () => {
  it('exige categorias únicas, horário válido e escaladas ordenadas', () => {
    expect(stayRules({ rules: rules() })[0]!.startsLocal).toBe('08:00');
    const repeated = rules(); repeated[1] = repeated[0]!;
    expect(() => stayRules({ rules: repeated })).toThrow();
    for (const invalid of [{ startsLocal: '25:00' }, { endsLocal: '08:00' }, { endsLocal: null }, { enabled: 'false' }, { l2AfterMinutes: 30 }]) {
      const rows = rules(); Object.assign(rows[0]!, invalid); expect(() => stayRules({ rules: rows })).toThrow();
    }
  });
  it('desliga por versão, usa CSRF e não repete automaticamente a gravação', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>().mockImplementation(async (input) => String(input) === '/auth/csrf'
      ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : json({ rules: rules() }));
    const client = createStayRulesApi(new CondPulseApi({ fetch: fetcher }));
    const tenant = '00000000-0000-4000-8000-000000000001';
    const body = { maxMinutes: 540, l1AfterMinutes: 30, l2AfterMinutes: 60, version: 0, enabled: false,
      scheduleEnabled: false, startsLocal: null, endsLocal: null };
    await client.update(tenant, 'SERVICE_PROVIDER', body);
    const calls = fetcher.mock.calls.filter(([input]) => String(input).includes('/stay-rules/'));
    expect(calls).toHaveLength(1); expect(calls[0]![1]?.method).toBe('PUT');
    expect(JSON.parse(String(calls[0]![1]?.body))).toEqual(body);
  });
});
