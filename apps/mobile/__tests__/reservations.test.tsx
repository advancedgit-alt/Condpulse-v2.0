import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, type CondPulseApi } from '../src/api/client';
import type { Amenity, Reservation } from '../src/api/reservations';
import { Reservations } from '../src/ui/Reservations';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const SALAO = '30000000-0000-4000-8000-000000000001';
const QUADRA = '30000000-0000-4000-8000-000000000002';
const now = () => new Date('2030-01-07T11:10:00Z'); // segunda, 08:10 em São Paulo
const policy = { weekDays: [1, 2, 3, 4, 5], opensAt: '08:00', closesAt: '12:00', minimumLeadMinutes: 60, maximumAdvanceDays: 10,
  maximumDurationMinutes: 120, maximumActivePerUnit: 1, cancellationLeadMinutes: 120 };
const salao: Amenity = { amenityId: SALAO, name: 'Salão fictício', capacity: 40, policyVersion: 3, feeCents: 15000, bufferMinutes: 0,
  approvalRequired: true, bookingState: 'AVAILABLE_FOR_APPROVAL_REQUEST', policy };
const quadra: Amenity = { ...salao, amenityId: QUADRA, name: 'Quadra fictícia', approvalRequired: false, bookingState: 'POLICY_NOT_CONFIGURED', policy: null };

function fakeApi() {
  const state = { reservations: [] as Reservation[], availabilityCalls: 0, blocked: [] as { occupiedFrom: string; occupiedUntil: string }[],
    creates: [] as { body: unknown; key: string }[], cancels: [] as { body: unknown; key: string }[], createError: null as ApiError | null };
  const api = {
    me: jest.fn(),
    amenities: jest.fn(async () => [salao, quadra]),
    availability: jest.fn(async (_tenant: string, _unit: string, amenityId: string, range: { from: string; until: string }) => {
      state.availabilityCalls += 1;
      return { amenityId, from: range.from, until: range.until, observedAt: now().toISOString(), blockedIntervals: state.blocked };
    }),
    reservations: jest.fn(async () => ({ reservations: state.reservations, truncated: false })),
    createReservation: jest.fn(async (_tenant: string, body: { unitId: string; amenityId: string; startsAt: string; endsAt: string; acceptedPolicyVersion: number }, key: string) => {
      state.creates.push({ body, key });
      if (state.createError) { const error = state.createError; state.createError = null; throw error; }
      const created: Reservation = { reservationId: '41000000-0000-4000-8000-000000000001', unitId: body.unitId, amenityId: body.amenityId,
        status: salao.approvalRequired ? 'PENDING_APPROVAL' : 'CONFIRMED', startsAt: body.startsAt, endsAt: body.endsAt,
        occupiedFrom: body.startsAt, occupiedUntil: body.endsAt, acceptedPolicyVersion: body.acceptedPolicyVersion, feeCents: 15000, version: 0, decisionReason: null };
      state.reservations = [created];
      return { reservation: created, replayed: false };
    }),
    cancelReservation: jest.fn(async (_tenant: string, id: string, body: { unitId: string; expectedVersion: number }, key: string) => {
      state.cancels.push({ body, key });
      const current = state.reservations.find((item) => item.reservationId === id)!;
      const cancelled: Reservation = { ...current, status: 'CANCELLED', version: current.version + 1, decisionReason: 'Cancelada pelo autor' };
      state.reservations = [cancelled];
      return { reservation: cancelled, replayed: false };
    }),
  };
  return { api: api as unknown as CondPulseApi, state, mocks: api };
}

const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount() {
  const fake = fakeApi();
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => `test:key-${String(++counter).padStart(12, '0')}`}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
  </QueryClientProvider>);
  return fake;
}
async function pickSlot(label: string) {
  await fireEvent.press(await screen.findByRole('button', { name: 'Ver horários de Salão fictício' }));
  await fireEvent.press(await screen.findByRole('button', { name: `Horário ${label}` }));
}

describe('reservas no celular', () => {
  it('mostra regras e taxa, exige aceite e deixa pedido com aprovação como aguardando, nunca confirmado', async () => {
    const fake = await mount();
    expect(await screen.findByText('Salão fictício')).toBeTruthy();
    expect(screen.getByText('A gestão ainda precisa configurar as regras desta área.')).toBeTruthy();
    expect(screen.getByText('Nenhuma reserva da unidade neste período.')).toBeTruthy();
    fake.state.blocked = [{ occupiedFrom: '2030-01-07T13:00:00Z', occupiedUntil: '2030-01-07T14:00:00Z' }];
    await fireEvent.press(screen.getByRole('button', { name: 'Ver horários de Salão fictício' }));
    expect(await screen.findByText(/taxa informativa R\$\s?150,00/)).toBeTruthy();
    expect(screen.getByText(/Sujeita a aprovação da gestão/)).toBeTruthy();
    expect(await screen.findByRole('button', { name: '10:00–11:00 ocupado' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Horário 11:00–12:00' }));
    const send = screen.getByRole('button', { name: 'Solicitar reserva' });
    expect(send).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/Pedido enviado: Salão fictício.*Aguardando aprovação da gestão — ainda não é uma reserva confirmada\./)).toBeTruthy();
    expect(fake.state.creates).toEqual([{ key: 'test:key-000000000001', body: { unitId: UNIT, amenityId: SALAO,
      startsAt: '2030-01-07T14:00:00.000Z', endsAt: '2030-01-07T15:00:00.000Z', acceptedPolicyVersion: 3 } }]);
    const list = await screen.findByLabelText('Reservas da unidade');
    await waitFor(() => expect(within(list).getByText('Aguardando aprovação')).toBeTruthy());
    expect(within(list).queryByText('Confirmada')).toBeNull();
    expect(within(list).getByText(/ainda não é uma reserva confirmada/)).toBeTruthy();
  });

  it('conflito com outra unidade atualiza a disponibilidade e pede outro horário', async () => {
    const fake = await mount();
    await pickSlot('09:30–10:30');
    const before = fake.state.availabilityCalls;
    fake.state.createError = new ApiError(409, 'RESERVATION_CONFLICT', 'Este horário acabou de ser ocupado. A disponibilidade foi atualizada: escolha outro horário.');
    fake.state.blocked = [{ occupiedFrom: '2030-01-07T12:30:00Z', occupiedUntil: '2030-01-07T13:30:00Z' }];
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/Este horário acabou de ser ocupado/)).toBeTruthy();
    await waitFor(() => expect(fake.state.availabilityCalls).toBeGreaterThan(before));
    expect(await screen.findByRole('button', { name: '09:30–10:30 ocupado' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Solicitar reserva' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Horário 10:30–11:30' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/Pedido enviado/)).toBeTruthy();
    expect(fake.state.creates[1]!.key).not.toBe(fake.state.creates[0]!.key);
  });

  it('resultado incerto trava a escolha e repete com a mesma chave', async () => {
    const fake = await mount();
    await pickSlot('10:00–11:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/o mesmo pedido não é gravado duas vezes/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Voltar às áreas' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Horário 10:30–11:30' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }));
    expect(await screen.findByText(/Pedido enviado/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]!.key).toBe(fake.state.creates[0]!.key);
  });

  it('cancelamento pede confirmação, envia a versão lida e mostra a situação nova', async () => {
    const fake = await mount();
    fake.state.reservations = [{ reservationId: '41000000-0000-4000-8000-000000000009', unitId: UNIT, amenityId: SALAO, status: 'CONFIRMED',
      startsAt: '2030-01-09T13:00:00Z', endsAt: '2030-01-09T14:00:00Z', occupiedFrom: '2030-01-09T13:00:00Z', occupiedUntil: '2030-01-09T14:00:00Z',
      acceptedPolicyVersion: 3, feeCents: 15000, version: 4, decisionReason: null }];
    await cleanup();
    for (const client of clients.splice(0)) client.clear();
    const queries = newQueryClient(Infinity); clients.push(queries);
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => 'test:key-cancel-000001'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    const list = await screen.findByLabelText('Reservas da unidade');
    expect(within(list).getByText('Confirmada')).toBeTruthy();
    await fireEvent.press(within(list).getByRole('button', { name: /^Cancelar reserva/ }));
    await fireEvent.press(within(list).getByRole('button', { name: 'Manter reserva' }));
    expect(fake.state.cancels).toHaveLength(0);
    await fireEvent.press(within(list).getByRole('button', { name: /^Cancelar reserva/ }));
    await fireEvent.press(within(list).getByRole('button', { name: 'Confirmar cancelamento' }));
    await waitFor(() => expect(within(screen.getByLabelText('Reservas da unidade')).getByText('Cancelada')).toBeTruthy());
    expect(fake.state.cancels).toEqual([{ key: 'test:key-cancel-000001', body: { unitId: UNIT, expectedVersion: 4 } }]);
    expect(screen.getByText('Motivo: Cancelada pelo autor')).toBeTruthy();
  });

  it('erro ao carregar as áreas mostra mensagem e tenta de novo', async () => {
    const fake = fakeApi();
    fake.mocks.amenities.mockRejectedValueOnce(new ApiError(503, 'HTTP_ERROR', 'Não foi possível concluir agora. Tente novamente mais tarde.'));
    const queries = newQueryClient(Infinity); clients.push(queries);
    queries.setDefaultOptions({ queries: { retry: false } });
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => 'test:key-unused-000001'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    expect(await screen.findByText('Não foi possível concluir agora. Tente novamente mais tarde.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByText('Salão fictício')).toBeTruthy();
  });

  it('cancelar libera o horário na escolha aberta, e dias além dos primeiros 14 ficam acessíveis', async () => {
    const fake = fakeApi();
    const own: Reservation = { reservationId: '41000000-0000-4000-8000-000000000010', unitId: UNIT, amenityId: SALAO, status: 'CONFIRMED',
      startsAt: '2030-01-07T13:00:00Z', endsAt: '2030-01-07T14:00:00Z', occupiedFrom: '2030-01-07T13:00:00Z', occupiedUntil: '2030-01-07T14:00:00Z',
      acceptedPolicyVersion: 3, feeCents: 15000, version: 1, decisionReason: null };
    fake.state.reservations = [own];
    fake.state.blocked = [{ occupiedFrom: own.occupiedFrom, occupiedUntil: own.occupiedUntil }];
    const original = fake.mocks.cancelReservation.getMockImplementation()!;
    fake.mocks.cancelReservation.mockImplementation(async (...args) => { fake.state.blocked = []; return original(...args); });
    fake.mocks.amenities.mockImplementation(async () => [{ ...salao, policy: { ...policy, weekDays: [1, 2, 3, 4, 5, 6, 7], maximumAdvanceDays: 30 } }, quadra]);
    const queries = newQueryClient(Infinity); clients.push(queries);
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => 'test:key-cancel-000002'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    await fireEvent.press(await screen.findByRole('button', { name: 'Ver horários de Salão fictício' }));
    expect(await screen.findByRole('button', { name: '10:00–11:00 ocupado' })).toBeDisabled();
    const list = screen.getByLabelText('Reservas da unidade');
    await fireEvent.press(within(list).getByRole('button', { name: /^Cancelar reserva/ }));
    await fireEvent.press(within(list).getByRole('button', { name: 'Confirmar cancelamento' }));
    expect(await screen.findByRole('button', { name: 'Horário 10:00–11:00' })).toBeEnabled();
    // 31 dias permitidos: 14 por página.
    expect(screen.getByRole('button', { name: 'Dias anteriores' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Próximos dias' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Próximos dias' }));
    expect(screen.getByRole('button', { name: 'Próximos dias' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /6 de fev/ })).toBeTruthy();
  });
});
