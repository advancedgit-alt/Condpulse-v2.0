import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, type CondPulseApi } from '../src/api/client';
import { ContractError, type Amenity, type Reservation } from '../src/api/reservations';
import { waitlistEntry, type WaitlistEntry } from '../src/api/waitlist';
import { Reservations } from '../src/ui/Reservations';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const SALAO = '30000000-0000-4000-8000-000000000001';
const now = () => new Date('2030-01-07T11:10:00Z'); // segunda, 08:10 em São Paulo
const policy = { weekDays: [1, 2, 3, 4, 5], opensAt: '08:00', closesAt: '12:00', minimumLeadMinutes: 60, maximumAdvanceDays: 10,
  maximumDurationMinutes: 120, maximumActivePerUnit: 1, cancellationLeadMinutes: 120 };
const salao: Amenity = { amenityId: SALAO, name: 'Salão fictício', capacity: 40, policyVersion: 3, feeCents: 15000, bufferMinutes: 0,
  approvalRequired: false, bookingState: 'AVAILABLE_FOR_REQUEST', policy };
const base: WaitlistEntry = { waitlistId: '79000000-0000-4000-8000-000000000001', unitId: UNIT, amenityId: SALAO, state: 'WAITING',
  startsAt: '2030-01-08T13:00:00Z', endsAt: '2030-01-08T14:00:00Z', occupiedFrom: '2030-01-08T13:00:00Z', occupiedUntil: '2030-01-08T14:00:00Z',
  acceptedPolicyVersion: 3, approvalRequired: false, feeCents: 15000, offerReservationId: null, offeredAt: null, offerExpiresAt: null,
  closedAt: null, createdByMe: true, position: 2, version: 0, createdAt: '2030-01-07T10:00:00Z' };
const offer = (extra: Partial<WaitlistEntry> = {}): WaitlistEntry => ({ ...base, state: 'OFFERED', position: null, version: 1,
  offerReservationId: '78000000-0000-4000-8000-000000000001', offeredAt: '2030-01-07T11:05:00Z', offerExpiresAt: '2030-01-07T11:20:00Z', ...extra });

function fakeApi() {
  const state = { entries: [] as WaitlistEntry[], blocked: [] as { occupiedFrom: string; occupiedUntil: string }[],
    joins: [] as { body: unknown; key: string }[], accepts: [] as { body: unknown; key: string }[], leaves: [] as unknown[],
    creates: [] as { body: unknown; key: string }[], errors: { join: [] as ApiError[], accept: [] as ApiError[], leave: [] as ApiError[],
      create: [] as ApiError[], list: [] as ApiError[] }, accepted: 'CONFIRMED' as Reservation['status'] };
  const reservationOf = (status: Reservation['status']): Reservation => ({ reservationId: '78000000-0000-4000-8000-000000000001', unitId: UNIT,
    amenityId: SALAO, status, startsAt: base.startsAt, endsAt: base.endsAt, occupiedFrom: base.startsAt, occupiedUntil: base.endsAt,
    acceptedPolicyVersion: 3, feeCents: 15000, version: 1, decisionReason: null });
  const api = {
    me: jest.fn(),
    amenities: jest.fn(async () => [salao]),
    availability: jest.fn(async (_t: string, _u: string, amenityId: string, range: { from: string; until: string }) =>
      ({ amenityId, from: range.from, until: range.until, observedAt: now().toISOString(), blockedIntervals: state.blocked })),
    reservations: jest.fn(async () => ({ reservations: [], truncated: false })),
    createReservation: jest.fn(async (_t: string, body: unknown, key: string) => {
      state.creates.push({ body, key });
      const error = state.errors.create.shift(); if (error) throw error;
      return { reservation: reservationOf('CONFIRMED'), replayed: false };
    }),
    cancelReservation: jest.fn(),
    unitWaitlist: jest.fn(async () => {
      const error = state.errors.list.shift(); if (error) throw error;
      return { entries: state.entries, truncated: false };
    }),
    joinWaitlist: jest.fn(async (_t: string, body: { startsAt: string; endsAt: string }, key: string) => {
      state.joins.push({ body, key });
      const error = state.errors.join.shift(); if (error) throw error;
      const replayed = state.joins.filter((item) => item.key === key).length > 1;
      const entry = { ...base, startsAt: body.startsAt, endsAt: body.endsAt, occupiedFrom: body.startsAt, occupiedUntil: body.endsAt, position: 3 };
      state.entries = [entry];
      return { entry, replayed };
    }),
    leaveWaitlist: jest.fn(async (_t: string, id: string, body: unknown) => {
      state.leaves.push(body);
      const error = state.errors.leave.shift(); if (error) throw error;
      const current = state.entries.find((item) => item.waitlistId === id)!;
      const left = { ...current, state: 'LEFT' as const, position: null, offerReservationId: null, offerExpiresAt: null, version: current.version + 1 };
      state.entries = [left];
      return left;
    }),
    acceptOffer: jest.fn(async (_t: string, id: string, body: unknown, key: string) => {
      state.accepts.push({ body, key });
      const error = state.errors.accept.shift(); if (error) throw error;
      const current = state.entries.find((item) => item.waitlistId === id)!;
      const accepted = { ...current, state: 'ACCEPTED' as const, version: current.version + 1 };
      state.entries = [accepted];
      return { entry: accepted, reservation: reservationOf(state.accepted) };
    }),
  };
  return { api: api as unknown as CondPulseApi, state, mocks: api };
}

const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(setup?: (fake: ReturnType<typeof fakeApi>) => void) {
  const fake = fakeApi();
  setup?.(fake);
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => `test:wait-${String(++counter).padStart(12, '0')}`}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
  </QueryClientProvider>);
  return fake;
}
const uncertain = () => new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);

describe('contrato da fila de espera no celular', () => {
  it('aceita entrada coerente e falha fechada com oferta sem retenção ou espera com retenção', () => {
    expect(waitlistEntry(base).position).toBe(2);
    expect(waitlistEntry(offer()).state).toBe('OFFERED');
    expect(() => waitlistEntry({ ...base, state: 'OFFERED' })).toThrow(ContractError);
    expect(() => waitlistEntry({ ...base, offerReservationId: '78000000-0000-4000-8000-000000000001' })).toThrow(ContractError);
    expect(() => waitlistEntry({ ...base, state: 'HELD' })).toThrow(ContractError);
    expect(() => waitlistEntry({ ...base, position: 0 })).toThrow(ContractError);
  });
});

describe('fila de espera no celular', () => {
  it('horário ocupado: entrar na fila exige aceite das regras, mostra a posição e repete incerto com a mesma chave', async () => {
    const fake = await mount((f) => { f.state.blocked = [{ occupiedFrom: '2030-01-07T13:00:00Z', occupiedUntil: '2030-01-07T14:00:00Z' }]; });
    expect(await screen.findByText(/Nenhuma fila de espera/)).toBeTruthy();
    await fireEvent.press(await screen.findByRole('button', { name: 'Ver horários de Salão fictício' }));
    expect(await screen.findByRole('button', { name: '10:00–11:00 ocupado' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Entrar na fila de 10:00–11:00' }));
    const join = screen.getByRole('button', { name: 'Entrar na fila de espera' });
    expect(join).toBeDisabled();
    fake.state.errors.join.push(uncertain());
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Entrar na fila de espera' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Tentar confirmar a mesma entrada' }));
    expect(await screen.findByText(/Você já estava na fila deste período\. Posição: 3\./)).toBeTruthy();
    expect(fake.state.joins).toHaveLength(2);
    expect(fake.state.joins[1]!.key).toBe(fake.state.joins[0]!.key);
    expect(fake.state.joins[0]!.body).toEqual({ unitId: UNIT, amenityId: SALAO, startsAt: '2030-01-07T13:00:00.000Z',
      endsAt: '2030-01-07T14:00:00.000Z', acceptedPolicyVersion: 3 });
    const list = await screen.findByLabelText('Entradas da fila de espera');
    expect(within(list).getByText('Na fila')).toBeTruthy();
  });

  it('período livre mas com fila: a reserva direta é recusada e a tela oferece entrar na fila', async () => {
    const fake = await mount((f) => { f.state.errors.create.push(new ApiError(409, 'WAITLIST_PRIORITY',
      'Há fila de espera para este período: a vaga vai primeiro para quem aguarda. Entre na fila ou escolha outro horário.')); });
    await fireEvent.press(await screen.findByRole('button', { name: 'Ver horários de Salão fictício' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Horário 10:00–11:00' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Reservar' }));
    expect(await screen.findByText(/Há fila de espera para este período/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Entrar na fila de espera' }));
    expect(await screen.findByText(/entrou na fila de espera deste período/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(1);
    expect(fake.state.joins).toHaveLength(1);
  });

  it('entrada recusada pelo servidor mostra o motivo sem travar a chave', async () => {
    const fake = await mount((f) => { f.state.blocked = [{ occupiedFrom: '2030-01-07T13:00:00Z', occupiedUntil: '2030-01-07T14:00:00Z' }]; });
    await fireEvent.press(await screen.findByRole('button', { name: 'Ver horários de Salão fictício' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Entrar na fila de 10:00–11:00' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    fake.state.errors.join.push(new ApiError(409, 'WAITLIST_LIMIT_REACHED', 'A unidade já está em 5 filas de espera abertas.'));
    await fireEvent.press(screen.getByRole('button', { name: 'Entrar na fila de espera' }));
    expect(await screen.findByText('A unidade já está em 5 filas de espera abertas.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Entrar na fila de espera' }));
    await waitFor(() => expect(fake.state.joins).toHaveLength(2));
    expect(fake.state.joins[1]!.key).not.toBe(fake.state.joins[0]!.key);
  });

  it('na fila: mostra a posição e sair pede confirmação (voltar desiste)', async () => {
    const fake = await mount((f) => { f.state.entries = [base]; });
    const list = await screen.findByLabelText('Entradas da fila de espera');
    expect(within(list).getByText('Posição na fila: 2.')).toBeTruthy();
    await fireEvent.press(within(list).getByRole('button', { name: 'Sair da fila de Salão fictício' }));
    await fireEvent.press(within(list).getByRole('button', { name: 'Voltar' }));
    expect(fake.state.leaves).toHaveLength(0);
    await fireEvent.press(within(list).getByRole('button', { name: 'Sair da fila de Salão fictício' }));
    await fireEvent.press(within(list).getByRole('button', { name: 'Confirmar saída' }));
    expect(await screen.findByText('Você saiu da fila de Salão fictício.')).toBeTruthy();
    expect(fake.state.leaves).toEqual([{ unitId: UNIT, expectedVersion: 0 }]);
    await waitFor(() => expect(within(screen.getByLabelText('Entradas da fila de espera')).getByText('Saiu da fila')).toBeTruthy());
  });

  it('vaga oferecida: aceitar confirma a reserva; resultado incerto repete com a mesma chave', async () => {
    const fake = await mount((f) => { f.state.entries = [offer()]; f.state.errors.accept.push(uncertain()); });
    const list = await screen.findByLabelText('Entradas da fila de espera');
    expect(within(list).getByText(/Aceite até 08:20\. Ao aceitar, a reserva é confirmada\./)).toBeTruthy();
    await fireEvent.press(within(list).getByRole('button', { name: 'Aceitar vaga de Salão fictício' }));
    expect(await screen.findByText(/A próxima tentativa usa o mesmo envio/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Recusar vaga de Salão fictício' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Aceitar vaga de Salão fictício' }));
    expect(await screen.findByText('Reserva de Salão fictício confirmada pela fila de espera.')).toBeTruthy();
    expect(fake.state.accepts).toHaveLength(2);
    expect(fake.state.accepts[1]!.key).toBe(fake.state.accepts[0]!.key);
    expect(fake.state.accepts[0]!.body).toEqual({ unitId: UNIT, expectedVersion: 1 });
  });

  it('vaga oferecida com aprovação vira pedido; recusar passa a vez; oferta vencida não aceita', async () => {
    const fake = await mount((f) => { f.state.entries = [offer({ approvalRequired: true })]; f.state.accepted = 'PENDING_APPROVAL'; });
    const list = await screen.findByLabelText('Entradas da fila de espera');
    expect(within(list).getByText(/vira pedido sujeito à aprovação da gestão/)).toBeTruthy();
    await fireEvent.press(within(list).getByRole('button', { name: 'Aceitar vaga de Salão fictício' }));
    expect(await screen.findByText(/foi enviado para aprovação da gestão\. Ainda não é uma reserva confirmada\./)).toBeTruthy();
    await cleanup();
    const second = await mount((f) => { f.state.entries = [offer({ waitlistId: '79000000-0000-4000-8000-000000000002' })]; });
    await fireEvent.press(await screen.findByRole('button', { name: 'Recusar vaga de Salão fictício' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar recusa' }));
    expect(await screen.findByText('Vaga de Salão fictício recusada. A vez passa ao próximo da fila.')).toBeTruthy();
    expect(second.state.leaves).toHaveLength(1);
    await cleanup();
    await mount((f) => { f.state.entries = [offer({ offerExpiresAt: '2030-01-07T11:00:00Z' })]; });
    expect(await screen.findByText('O prazo desta oferta terminou. Atualize a fila.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Aceitar vaga de Salão fictício' })).toBeDisabled();
    expect(fake.state.accepts).toHaveLength(1);
  });

  it('aceite recusado (prazo vencido no servidor) mostra o motivo e libera a chave', async () => {
    const fake = await mount((f) => { f.state.entries = [offer()];
      f.state.errors.accept.push(new ApiError(409, 'WAITLIST_OFFER_EXPIRED', 'O prazo da oferta terminou e a vaga seguiu para o próximo da fila.')); });
    await fireEvent.press(await screen.findByRole('button', { name: 'Aceitar vaga de Salão fictício' }));
    expect(await screen.findByText('O prazo da oferta terminou e a vaga seguiu para o próximo da fila.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Recusar vaga de Salão fictício' })).not.toBeDisabled();
    expect(fake.state.accepts).toHaveLength(1);
  });

  it('entrada de outra pessoa da unidade não tem ações; erro ao consultar mostra nova tentativa', async () => {
    await mount((f) => { f.state.entries = [{ ...base, createdByMe: false }]; });
    expect(await screen.findByText(/somente quem entrou na fila pode alterá-la/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sair da fila de Salão fictício' })).toBeNull();
    await cleanup();
    const unavailable = () => new ApiError(503, 'RESERVATION_DEPENDENCY_UNAVAILABLE', 'Não foi possível concluir agora. Confira suas reservas antes de repetir.');
    // A consulta repete uma vez antes de mostrar o erro (mesma política das outras listas).
    const fake = await mount((f) => { f.state.errors.list.push(unavailable(), unavailable()); });
    expect(await screen.findByText('Não foi possível concluir agora. Confira suas reservas antes de repetir.', {}, { timeout: 4000 })).toBeTruthy();
    fake.state.entries = [base];
    await fireEvent.press(screen.getByRole('button', { name: 'Atualizar fila' }));
    expect(await screen.findByText('Posição na fila: 2.')).toBeTruthy();
  });
});
