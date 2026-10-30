import { cleanup, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, type CondPulseApi } from '../src/api/client';
import type { Reservation } from '../src/api/reservations';
import type { Permit } from '../src/api/visits';
import { ReservationGuests, type GuestPendingStore } from '../src/ui/ReservationGuests';
import { createApi } from '../src/api/client';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const RESERVATION = '41000000-0000-4000-8000-000000000001';
const reservation: Reservation = { reservationId: RESERVATION, unitId: UNIT, amenityId: '30000000-0000-4000-8000-000000000001', status: 'CONFIRMED',
  startsAt: '2030-01-10T22:00:00Z', endsAt: '2030-01-11T02:00:00Z', occupiedFrom: '2030-01-10T22:00:00Z', occupiedUntil: '2030-01-11T02:30:00Z',
  acceptedPolicyVersion: 1, feeCents: 0, version: 0, decisionReason: null };
const guest = (overrides: Partial<Permit> = {}): Permit => ({ permitId: '48000000-0000-4000-8000-0000000000a1', unitId: UNIT, visitorName: 'Convidada Um',
  documentLast4: null, purpose: null, validFrom: '2030-01-10T21:00:00Z', validUntil: '2030-01-11T02:00:00Z', state: 'SCHEDULED', createdByMe: true,
  revokedAt: null, revokeReason: null, version: 0, shareLinkActive: false, reservationId: RESERVATION, ruleId: null, ...overrides });

function fakeApi() {
  const state = { guests: [guest()] as Permit[], adds: [] as { body: unknown; key: string }[], removes: [] as unknown[], addError: null as ApiError | null,
    keys: new Map<string, Permit>(), listError: null as ApiError | null };
  const api = {
    reservationGuests: jest.fn(async () => { if (state.listError) throw state.listError; return { permits: state.guests, truncated: false }; }),
    addGuest: jest.fn(async (_t: string, _u: string, _r: string, body: { visitorName: string; documentLast4: string | null }, key: string) => {
      state.adds.push({ body, key });
      const previous = state.keys.get(key);
      if (previous) return { permit: previous, replayed: true };
      const created = guest({ permitId: `48000000-0000-4000-8000-0000000000a${state.guests.length + 1}`, visitorName: body.visitorName, documentLast4: body.documentLast4 });
      state.keys.set(key, created); state.guests = [...state.guests, created];
      if (state.addError) { const error = state.addError; state.addError = null; throw error; }
      return { permit: created, replayed: false };
    }),
    revokePermit: jest.fn(async (_t: string, _u: string, id: string, body: unknown) => {
      state.removes.push(body);
      state.guests = state.guests.map((item) => item.permitId === id ? guest({ ...item, state: 'REVOKED', revokedAt: '2030-01-07T12:00:00Z', revokeReason: 'x', version: 1 }) : item);
      return state.guests.find((item) => item.permitId === id);
    }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(fake = fakeApi(), pending?: GuestPendingStore) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <ReservationGuests api={fake.api} scope={['private', 'conta', 'tenant', TENANT, 'unit', UNIT]} tenantId={TENANT} unitId={UNIT} reservation={reservation}
      accountId="10000000-0000-4000-8000-000000000001" pending={pending} timeZone="America/Sao_Paulo" newKey={() => `test:guest-${String(++counter).padStart(12, '0')}`} />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));


describe('convidados da reserva no celular', () => {
  it('lista sob demanda, adiciona com chave mantida depois de resultado incerto e remove com confirmação', async () => {
    const fake = await mount();
    expect(fake.api.reservationGuests).not.toHaveBeenCalled();
    const toggle = (await screen.findAllByRole('button')).find((item) => /^Convidados de /.test(item.props.accessibilityLabel))!;
    await fireEvent.press(toggle);
    expect(await screen.findByText('Convidada Um · Agendado')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Adicionar convidado' })).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Nome do convidado'), '  Convidado   Dois ');
    await fireEvent.changeText(screen.getByLabelText('Final do documento do convidado (opcional)'), '12345');
    expect(screen.getByText('Informe só os 2 a 4 últimos caracteres do documento, ou deixe em branco.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Final do documento do convidado (opcional)'), '9z');
    fake.state.addError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
    await press('Adicionar convidado');
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo convidado' })).toBeTruthy();
    expect(screen.getByLabelText('Nome do convidado').props.editable).toBe(false);
    await press('Tentar confirmar o mesmo convidado');
    expect(await screen.findByText(/Convidado Dois já estava convidado\(a\)/)).toBeTruthy();
    expect(fake.state.adds).toHaveLength(2);
    expect(fake.state.adds[1]!.key).toBe(fake.state.adds[0]!.key);
    expect(fake.state.adds[0]!.body).toEqual({ visitorName: 'Convidado Dois', documentLast4: '9Z' });
    await press('Remover Convidada Um');
    await press('Manter Convidada Um');
    expect(fake.state.removes).toEqual([]);
    await press('Remover Convidada Um');
    await press('Confirmar remoção de Convidada Um');
    expect(await screen.findByText('Convite de Convidada Um revogado.')).toBeTruthy();
    expect(fake.state.removes).toEqual([{ reason: 'Convidado removido pela unidade', expectedVersion: 0 }]);
    expect(await screen.findByText('Convidada Um · Revogado')).toBeTruthy();
    expect(screen.getByText(/Cancelar a reserva revoga todos/)).toBeTruthy();
  }, 20000);

  it('limite de convidados é decidido pelo servidor; falha ao consultar oferece nova tentativa', async () => {
    const fake = fakeApi();
    fake.state.addError = new ApiError(409, 'RESERVATION_GUEST_LIMIT', 'A reserva já tem o número máximo de convidados da área.', false);
    await mount(fake);
    await fireEvent.press((await screen.findAllByRole('button')).find((item) => /^Convidados de /.test(item.props.accessibilityLabel))!);
    await fireEvent.changeText(await screen.findByLabelText('Nome do convidado'), 'Convidado Extra');
    await press('Adicionar convidado');
    expect(await screen.findByText('A reserva já tem o número máximo de convidados da área.')).toBeTruthy();
    await cleanup();
    const failing = fakeApi();
    failing.state.listError = new ApiError(503, 'VISIT_DEPENDENCY_UNAVAILABLE', 'Convidados indisponíveis agora.', false);
    await mount(failing);
    await fireEvent.press((await screen.findAllByRole('button')).find((item) => /^Convidados de /.test(item.props.accessibilityLabel))!);
    expect(await screen.findByText('Convidados indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    failing.state.listError = null;
    await press('Tentar consultar convidados de novo');
    expect(await screen.findByText('Convidada Um · Agendado')).toBeTruthy();
  }, 20000);

  it('envio incerto sobrevive à troca de vínculo e repete a mesma chave', async () => {
    const pending: GuestPendingStore = new Map();
    const fake = fakeApi();
    fake.state.addError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
    await mount(fake, pending);
    await fireEvent.press((await screen.findAllByRole('button')).find((item) => /^Convidados de /.test(item.props.accessibilityLabel))!);
    await fireEvent.changeText(await screen.findByLabelText('Nome do convidado'), 'Convidado Incerto');
    await press('Adicionar convidado');
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo convidado' })).toBeTruthy();
    await cleanup();
    await mount(fake, pending);
    await fireEvent.press((await screen.findAllByRole('button')).find((item) => /^Convidados de /.test(item.props.accessibilityLabel))!);
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo convidado' })).toBeTruthy();
    expect(screen.getByLabelText('Nome do convidado').props.value).toBe('Convidado Incerto');
    await press('Tentar confirmar o mesmo convidado');
    expect(await screen.findByText(/Convidado Incerto já estava convidado\(a\)/)).toBeTruthy();
    expect(fake.state.adds).toHaveLength(2);
    expect(fake.state.adds[1]!.key).toBe(fake.state.adds[0]!.key);
    expect(pending.size).toBe(0);
  }, 20000);

  it('lista de convidados aceita até 500 itens, como o servidor devolve', async () => {
    const permits = Array.from({ length: 150 }, (_, index) => guest({ permitId: `48000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
      visitorName: `Convidado ${index}`, state: index % 2 ? 'REVOKED' : 'SCHEDULED', revokedAt: index % 2 ? '2030-01-07T12:00:00Z' : null,
      revokeReason: index % 2 ? 'Removido' : null, version: index % 2 }));
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never,
      fetch: async () => new Response(JSON.stringify({ permits, truncated: false }), { status: 200, headers: { 'Content-Type': 'application/json' } }) });
    expect((await api.reservationGuests(TENANT, UNIT, RESERVATION)).permits).toHaveLength(150);
  });
});
