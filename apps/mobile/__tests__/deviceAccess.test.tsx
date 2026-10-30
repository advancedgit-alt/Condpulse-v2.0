import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import { credential, deviceEvent } from '../src/api/access';
import { ContractError } from '../src/api/reservations';
import { DeviceAccess } from '../src/ui/DeviceAccess';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const at = '2030-01-07T11:10:00Z';
const devices = (state: string) => [
  { deviceId: '44000000-0000-4000-8000-000000000001', deviceLabel: 'Entrada social', direction: 'ENTRY', state, lastOutcome: null,
    confirmedAt: state === 'CONFIRMED' ? at : null, lastReason: null, lastAttemptAt: null },
  { deviceId: '44000000-0000-4000-8000-000000000002', deviceLabel: 'Garagem', direction: 'BOTH', state: 'PENDING', lastOutcome: null,
    confirmedAt: null, lastReason: null, lastAttemptAt: null }];
const card = (overrides: Record<string, unknown> = {}) => ({ credentialId: '45000000-0000-4000-8000-000000000001', connectorId: '43000000-0000-4000-8000-000000000001',
  membershipId: '51000000-0000-4000-8000-000000000001', unitId: UNIT, unitLabel: '101', holderName: 'Morador Fictício', kind: 'CARD', label: 'Cartão final 1234',
  desiredState: 'REVOKED', desiredVersion: 2, validUntil: null, status: 'REVOKING', revokeReason: 'Perdido', revokedAt: at, createdAt: at, version: 1,
  devices: devices('CONFIRMED'), ...overrides });
const event = (overrides: Record<string, unknown> = {}) => ({ eventId: '46000000-0000-4000-8000-000000000001', kind: 'PASSAGE_DETECTED', direction: 'ENTRY',
  evidence: 'TURNSTILE_SENSOR', occurredAt: at, deviceLabel: 'Entrada social', credentialLabel: 'Cartão final 1234', unitLabel: '101', state: 'ACCEPTED',
  quarantineReason: null, exitRecorded: false, ...overrides });

function fakeApi() {
  const state = { credentials: [card()] as unknown[], events: [event(), event({ eventId: '46000000-0000-4000-8000-000000000002', kind: 'DENIED', direction: null,
    exitRecorded: null })] as unknown[], credentialError: null as Error | null, eventError: null as Error | null, credentialCalls: 0, eventCalls: 0 };
  const api = {
    unitCredentials: jest.fn(async () => { state.credentialCalls++; if (state.credentialError) throw state.credentialError;
      return { credentials: state.credentials.map((item) => credential(item, UNIT)), truncated: false }; }),
    unitDeviceEvents: jest.fn(async () => { state.eventCalls++; if (state.eventError) throw state.eventError;
      return { events: state.events.map(deviceEvent), truncated: false }; }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
async function mount(fake = fakeApi()) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <DeviceAccess api={fake.api} accountId={ACCOUNT} tenantId={TENANT} unitId={UNIT} timeZone="America/Sao_Paulo" />
  </QueryClientProvider>);
  return fake;
}

describe('contrato das credenciais e eventos no celular', () => {
  it('recusa situação incoerente, credencial de outra unidade, evento interno e saída fora da passagem de entrada', () => {
    expect(credential(card(), UNIT).status).toBe('REVOKING');
    expect(() => credential(card({ status: 'REVOKED' }), UNIT)).toThrow(ContractError);
    expect(() => credential(card(), '20000000-0000-4000-8000-000000000009')).toThrow(ContractError);
    expect(() => credential(card({ devices: [{ ...devices('CONFIRMED')[0], confirmedAt: null }] }), UNIT)).toThrow(ContractError);
    expect(() => deviceEvent(event({ kind: 'DEVICE_OFFLINE' }))).toThrow(ContractError);
    expect(() => deviceEvent(event({ state: 'QUARANTINED' }))).toThrow(ContractError);
    expect(() => deviceEvent(event({ kind: 'DENIED', exitRecorded: false }))).toThrow(ContractError);
    expect(deviceEvent(event({ direction: null, exitRecorded: null })).direction).toBeNull();
  });
  it('rotas da unidade', async () => {
    const urls: string[] = [];
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never,
      fetch: async (url) => { urls.push(String(url)); return new Response(JSON.stringify(String(url).endsWith('device-events') ? { events: [], truncated: false }
        : { credentials: [], truncated: false }), { status: 200, headers: { 'Content-Type': 'application/json' } }); } });
    await api.unitCredentials(TENANT, UNIT);
    await api.unitDeviceEvents(TENANT, UNIT);
    expect(urls).toEqual([`https://api.example.invalid/v1/tenants/${TENANT}/units/${UNIT}/access-credentials`,
      `https://api.example.invalid/v1/tenants/${TENANT}/units/${UNIT}/device-events`]);
  });
});

describe('meu acesso pelos equipamentos', () => {
  it('mostra revogação pendente por equipamento e eventos sem inferir presença; atualizar consulta de novo', async () => {
    const fake = await mount();
    expect(await screen.findByText('Revogação pendente nos equipamentos')).toBeTruthy();
    expect(screen.getByText('Entrada social: revogação confirmada')).toBeTruthy();
    expect(screen.getByText('Garagem: revogação pendente')).toBeTruthy();
    expect(await screen.findByText('Passagem de entrada')).toBeTruthy();
    expect(screen.getByText('Sem registro de saída')).toBeTruthy();
    expect(screen.getByText('Acesso negado pelo equipamento')).toBeTruthy();
    expect(screen.getByText(/não significa que você continua no condomínio/)).toBeTruthy();
    fake.state.credentials = []; fake.state.events = [];
    await fireEvent.press(screen.getByRole('button', { name: 'Atualizar equipamentos' }));
    expect(await screen.findByText('A gestão ainda não cadastrou cartão ou QR para você.')).toBeTruthy();
    expect(await screen.findByText('Nenhum evento dos equipamentos nos últimos 7 dias.')).toBeTruthy();
  });
  it('falha na primeira consulta oferece nova tentativa; falha ao atualizar avisa que os dados são antigos', async () => {
    const fake = fakeApi();
    fake.state.credentialError = new ApiError(503, 'CREDENTIAL_DEPENDENCY_UNAVAILABLE', 'Credenciais indisponíveis agora.', false);
    await mount(fake);
    expect(await screen.findByText('Credenciais indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.credentialError = null;
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar de novo' }));
    expect(await screen.findByText('Revogação pendente nos equipamentos')).toBeTruthy();
    expect(await screen.findByText('Passagem de entrada')).toBeTruthy();
    fake.state.eventError = new ApiError(503, 'CREDENTIAL_DEPENDENCY_UNAVAILABLE', 'Eventos indisponíveis agora.', false);
    await fireEvent.press(screen.getByRole('button', { name: 'Atualizar equipamentos' }));
    expect(await screen.findByText('Não foi possível atualizar. Os dados abaixo são da última consulta.', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByText('Passagem de entrada')).toBeTruthy();
  }, 15000);
  it('falha só nos eventos oferece nova tentativa dos eventos', async () => {
    const fake = fakeApi();
    fake.state.eventError = new ApiError(503, 'CREDENTIAL_DEPENDENCY_UNAVAILABLE', 'Eventos indisponíveis agora.', false);
    await mount(fake);
    expect(await screen.findByText('Eventos indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.eventError = null;
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar consultar eventos de novo' }));
    expect(await screen.findByText('Passagem de entrada')).toBeTruthy();
  }, 15000);
  it('função desligada no ambiente esconde a seção', async () => {
    const fake = fakeApi();
    fake.state.credentialError = new ApiError(404, 'API_NOT_AVAILABLE', 'Indisponível.', false);
    await mount(fake);
    await waitFor(() => expect(fake.state.credentialCalls).toBeGreaterThan(0));
    await waitFor(() => expect(screen.queryByText('Meu acesso pelos equipamentos')).toBeNull());
  }, 15000);
});
