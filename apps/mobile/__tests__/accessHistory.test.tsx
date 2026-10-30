import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import { accessRecord, accessRecordPage } from '../src/api/accessHistory';
import { ContractError } from '../src/api/reservations';
import { AccessHistory } from '../src/ui/AccessHistory';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const OTHER_UNIT = '20000000-0000-4000-8000-000000000002';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const wire = (overrides: Record<string, unknown> = {}) => ({ entryId: '57000000-0000-4000-8000-000000000001', unitId: UNIT, unitLabel: '101', blockName: null,
  visitorName: 'Visita Fictícia', documentLast4: '12AB', arrivalId: '56000000-0000-4000-8000-000000000001', permitId: null, linkedPermitId: null,
  eventType: 'MANUAL_IN', source: 'CONCIERGE', evidence: 'DOCUMENT_CHECKED', note: null, enteredAt: '2030-01-07T11:00:00Z', recordedByMe: false,
  correction: null, exit: null, ...overrides });
const exit = (overrides: Record<string, unknown> = {}) => ({ eventId: '58000000-0000-4000-8000-000000000001', exitedAt: '2030-01-07T13:00:00Z', note: null,
  recordedByMe: false, correction: null, ...overrides });
const fix = (reason: string) => ({ eventId: '59000000-0000-4000-8000-000000000001', correctedAt: '2030-01-07T14:00:00Z', reason, recordedByMe: false });

function fakeApi(records = [wire(), wire({ entryId: '57000000-0000-4000-8000-000000000002', visitorName: 'Prestador Fictício', arrivalId: null,
  permitId: '48000000-0000-4000-8000-000000000001', exit: exit() }), wire({ entryId: '57000000-0000-4000-8000-000000000003', visitorName: 'Registro Errado',
  correction: fix('Unidade errada') }), wire({ entryId: '57000000-0000-4000-8000-000000000004', visitorName: 'Saída Corrigida', exit: exit({ correction: fix('Saída lançada antes') }) })]) {
  const state = { records, error: null as ApiError | null, truncated: false };
  const api = { unitAccessRecords: jest.fn(async () => { if (state.error) throw state.error; return accessRecordPage({ records: state.records, truncated: state.truncated }, UNIT); }) };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
async function mount(fake = fakeApi()) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <AccessHistory api={fake.api} accountId={ACCOUNT} tenantId={TENANT} unitId={UNIT} timeZone="America/Sao_Paulo" />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));

describe('entradas e saídas da unidade no celular', () => {
  it('contrato: só registro manual da própria unidade, sem observação interna da portaria', async () => {
    expect(accessRecord(wire(), UNIT).viaArrival).toBe(true);
    expect(() => accessRecord(wire(), OTHER_UNIT)).toThrow(ContractError);
    expect(() => accessRecord(wire({ note: 'Observação interna' }), UNIT)).toThrow(ContractError);
    expect(() => accessRecord(wire({ exit: exit({ note: 'Observação interna' }) }), UNIT)).toThrow(ContractError);
    expect(() => accessRecord(wire({ eventType: 'DEVICE_IN' }), UNIT)).toThrow(ContractError);
    expect(() => accessRecord(wire({ permitId: '48000000-0000-4000-8000-000000000001' }), UNIT)).toThrow(ContractError);
    const calls: string[] = [];
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never,
      fetch: async (url) => { calls.push(url); return new Response(JSON.stringify({ records: [wire()], truncated: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }); } });
    expect((await api.unitAccessRecords(TENANT, UNIT)).truncated).toBe(true);
    expect(calls).toEqual([`https://api.example.invalid/v1/tenants/${TENANT}/units/${UNIT}/access-events`]);
  });

  it('mostra entrada sem saída, saída, correções e atualiza', async () => {
    const fake = await mount();
    expect(await screen.findByText('Visita Fictícia · Sem registro de saída')).toBeTruthy();
    expect(screen.getAllByText(/chegada autorizada pela unidade$/).length).toBeGreaterThan(0);
    expect(screen.getByText('Prestador Fictício · Saída registrada')).toBeTruthy();
    expect(screen.getByText(/convite da unidade$/)).toBeTruthy();
    expect(screen.getByText('Registro Errado · Registro corrigido')).toBeTruthy();
    expect(screen.getByText('Corrigido pela portaria: Unidade errada')).toBeTruthy();
    expect(screen.getByText('Saída Corrigida · Sem registro de saída')).toBeTruthy();
    expect(screen.getByText(/\(corrigida: Saída lançada antes\)$/)).toBeTruthy();
    const before = (fake.api.unitAccessRecords as jest.Mock).mock.calls.length;
    await press('Atualizar entradas e saídas');
    await waitFor(() => expect((fake.api.unitAccessRecords as jest.Mock).mock.calls.length).toBe(before + 1));
  }, 20000);

  it('vazio, limite, falha com nova tentativa e vínculo sem acesso escondendo a seção', async () => {
    await mount(fakeApi([]));
    expect(await screen.findByText(/^Nenhum registro\./)).toBeTruthy();
    await cleanup();
    const fake = fakeApi();
    fake.state.truncated = true;
    fake.state.error = new ApiError(503, 'HTTP_ERROR', 'Histórico indisponível agora.', false);
    await mount(fake);
    expect(await screen.findByText('Histórico indisponível agora.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.error = null;
    await press('Tentar consultar entradas de novo');
    expect(await screen.findByText('O histórico mostra as 100 entradas mais recentes.')).toBeTruthy();
    await cleanup();
    const denied = fakeApi();
    denied.state.error = new ApiError(404, 'UNIT_ACCESS_DENIED', 'Unidade não disponível para esta operação.', false);
    await mount(denied);
    await waitFor(() => expect(denied.api.unitAccessRecords).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText('Entradas e saídas')).toBeNull());
  }, 20000);
});
