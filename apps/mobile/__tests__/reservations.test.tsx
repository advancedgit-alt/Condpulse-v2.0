import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, type CondPulseApi } from '../src/api/client';
import type { Amenity, Reservation } from '../src/api/reservations';
import { Reservations, type PendingStore } from '../src/ui/Reservations';

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
    creates: [] as { body: unknown; key: string }[], cancels: [] as { body: unknown; key: string }[], createError: null as ApiError | null, limit: 100 };
  const api = {
    me: jest.fn(),
    amenities: jest.fn(async () => [salao, quadra]),
    availability: jest.fn(async (_tenant: string, _unit: string, amenityId: string, range: { from: string; until: string }) => {
      state.availabilityCalls += 1;
      return { amenityId, from: range.from, until: range.until, observedAt: now().toISOString(), blockedIntervals: state.blocked };
    }),
    // Mesmo critério do servidor: sobreposição com o período, ordem por início e corte em \`limit\`.
    reservations: jest.fn(async (_tenant: string, _unit: string, range: { from: string; until: string }) => {
      const rows = state.reservations.filter((item) => Date.parse(item.startsAt) < Date.parse(range.until) && Date.parse(item.endsAt) > Date.parse(range.from))
        .sort((x, y) => x.startsAt.localeCompare(y.startsAt));
      return { reservations: rows.slice(0, state.limit), truncated: rows.length > state.limit };
    }),
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
  return Object.assign(fake, { queries });
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

  it('regras alteradas depois do aceite exigem novo aceite e enviam a versão nova', async () => {
    const fake = await mount();
    await pickSlot('10:00–11:00');
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    expect(screen.getByRole('button', { name: 'Solicitar reserva' })).toBeEnabled();
    // A gestão publica a versão 4 com outra taxa; a lista de áreas é consultada de novo (ex.: depois de um cancelamento).
    fake.mocks.amenities.mockImplementation(async () => [{ ...salao, policyVersion: 4, feeCents: 20000 }, quadra]);
    await act(async () => { await fake.queries.invalidateQueries(); });
    expect(await screen.findByText(/As regras desta área mudaram \(agora versão 4\)/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Solicitar reserva' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 4)' })).not.toBeChecked();
    expect(fake.state.creates).toHaveLength(0);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 4)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/Pedido enviado/)).toBeTruthy();
    expect((fake.state.creates[0]!.body as { acceptedPolicyVersion: number }).acceptedPolicyVersion).toBe(4);
  });

  it('repetição depois de resultado incerto reenvia o corpo original, mesmo que as regras mudem', async () => {
    const fake = await mount();
    await pickSlot('10:00–11:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/o mesmo pedido não é gravado duas vezes/)).toBeTruthy();
    fake.mocks.amenities.mockImplementation(async () => [{ ...salao, policyVersion: 4 }, quadra]);
    await act(async () => { await fake.queries.invalidateQueries(); });
    await screen.findByRole('checkbox', { name: 'Li e aceito as regras da área (versão 4)' });
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }));
    expect(await screen.findByText(/Pedido enviado/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]).toEqual(fake.state.creates[0]);
  });

  it('lista cortada pelo servidor continua a partir da última reserva mostrada, até a omitida', async () => {
    const fake = fakeApi();
    const booking = (n: number, day: string): Reservation => ({ reservationId: `41000000-0000-4000-8000-00000000010${n}`, unitId: UNIT, amenityId: SALAO,
      status: n < 3 ? 'CANCELLED' : 'CONFIRMED', startsAt: `${day}T13:00:00.000Z`, endsAt: `${day}T14:00:00.000Z`, occupiedFrom: `${day}T13:00:00.000Z`,
      occupiedUntil: `${day}T14:00:00.000Z`, acceptedPolicyVersion: 3, feeCents: 0, version: 1, decisionReason: null });
    fake.state.reservations = [booking(1, '2030-01-08'), booking(2, '2030-01-09'), booking(3, '2030-01-10')];
    fake.state.limit = 2;
    const queries = newQueryClient(Infinity); clients.push(queries);
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => 'test:key-cursor-000001'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    expect(await screen.findByText(/Continue para ver as seguintes/)).toBeTruthy();
    expect(within(screen.getByLabelText('Reservas da unidade')).queryByText('Confirmada')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Ver as reservas seguintes' }));
    const list = await screen.findByLabelText('Reservas da unidade');
    await waitFor(() => expect(within(screen.getByLabelText('Reservas da unidade')).getByText('Confirmada')).toBeTruthy());
    expect(list).toBeTruthy();
    expect(fake.mocks.reservations.mock.calls.at(-1)![2]).toMatchObject({ from: '2030-01-09T13:00:00.000Z' });
    await fireEvent.press(screen.getByRole('button', { name: /^Cancelar reserva/ }));
    expect(screen.getByRole('button', { name: 'Confirmar cancelamento' })).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Voltar ao início do período' }));
    expect(await screen.findByText(/Continue para ver as seguintes/)).toBeTruthy();
  });

  it('corte sem avanço possível não oferece botão que repete a mesma página', async () => {
    const fake = fakeApi();
    const same = (n: number): Reservation => ({ reservationId: `41000000-0000-4000-8000-00000000020${n}`, unitId: UNIT, amenityId: SALAO, status: 'CANCELLED',
      startsAt: '2030-01-07T00:00:00.000Z', endsAt: '2030-01-07T13:00:00.000Z', occupiedFrom: '2030-01-07T00:00:00.000Z',
      occupiedUntil: '2030-01-07T13:00:00.000Z', acceptedPolicyVersion: 3, feeCents: 0, version: 1, decisionReason: null });
    fake.state.reservations = [same(1), same(2), same(3)];
    fake.state.limit = 2;
    const queries = newQueryClient(Infinity); clients.push(queries);
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={() => new Date('2030-01-07T03:00:00Z')} newKey={() => 'test:key-cursor-000002'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    expect(await screen.findByText(/não foi possível avançar daqui/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Ver as reservas seguintes' })).toBeNull();
  });

  it('pedido incerto sobrevive à troca de vínculo e volta travado na mesma chave e no mesmo corpo', async () => {
    const fake = fakeApi();
    const pending: PendingStore = new Map();
    const membership = { membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
      timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' as const };
    const show = async (unitId: string) => {
      const queries = newQueryClient(Infinity); clients.push(queries);
      await render(<QueryClientProvider client={queries}>
        <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => `test:key-${String(++counter).padStart(12, '0')}`}
          membership={{ ...membership, unitId }} pending={pending} />
      </QueryClientProvider>);
    };
    await show(UNIT);
    await pickSlot('10:00–11:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/o mesmo pedido não é gravado duas vezes/)).toBeTruthy();
    // Troca para outra unidade: nada pendente lá.
    await cleanup();
    await show('20000000-0000-4000-8000-000000000002');
    expect(await screen.findByRole('button', { name: 'Ver horários de Salão fictício' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Tentar confirmar o mesmo pedido' })).toBeNull();
    // Volta: a confirmação do mesmo pedido reaparece travada.
    await cleanup();
    await show(UNIT);
    const retry = await screen.findByRole('button', { name: 'Tentar confirmar o mesmo pedido' });
    expect(screen.getByRole('button', { name: 'Voltar às áreas' })).toBeDisabled();
    await fireEvent.press(retry);
    expect(await screen.findByText(/Pedido enviado/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]).toEqual(fake.state.creates[0]);
    expect(pending.size).toBe(0);
  });

  it('área desativada depois de resultado incerto mantém a confirmação do mesmo pedido e bloqueia outro', async () => {
    const fake = await mount();
    await pickSlot('10:00–11:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    expect(await screen.findByText(/o mesmo pedido não é gravado duas vezes/)).toBeTruthy();
    // A gestão desativa o salão: a lista de áreas deixa de trazê-lo.
    fake.mocks.amenities.mockImplementation(async () => [quadra]);
    await act(async () => { await fake.queries.invalidateQueries(); });
    expect(await screen.findByText(/a área não aparece mais na lista/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Ver horários/ })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }));
    expect(await screen.findByText(/Pedido confirmado pelo servidor \(Aguardando aprovação\)/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]).toEqual(fake.state.creates[0]);
    expect(screen.queryByText(/a área não aparece mais na lista/)).toBeNull();
  });

  it('recusa definitiva da confirmação sem área libera novos pedidos e avisa que nada foi gravado', async () => {
    const fake = await mount();
    await pickSlot('10:00–11:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    await screen.findByText(/o mesmo pedido não é gravado duas vezes/);
    fake.mocks.amenities.mockImplementation(async () => [quadra, { ...salao, amenityId: '30000000-0000-4000-8000-000000000003', name: 'Churrasqueira fictícia' }]);
    await act(async () => { await fake.queries.invalidateQueries(); });
    await screen.findByText(/a área não aparece mais na lista/);
    fake.state.createError = new ApiError(404, 'RESERVATION_RESOURCE_UNAVAILABLE', 'A reserva ou a área não está disponível para esta operação.');
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }));
    expect(await screen.findByText(/O pedido anterior não foi gravado/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Ver horários de Churrasqueira fictícia' })).toBeEnabled();
  });

  it('chave vencida ao repetir não diz que nada foi gravado e leva a conferir a lista', async () => {
    const fake = await mount();
    await pickSlot('10:00–11:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras da área (versão 3)' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Solicitar reserva' }));
    await screen.findByText(/o mesmo pedido não é gravado duas vezes/);
    fake.state.createError = new ApiError(409, 'IDEMPOTENCY_KEY_EXPIRED', 'O prazo para confirmar este envio terminou e não dá para saber se ele foi gravado.');
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar confirmar o mesmo pedido' }));
    expect(await screen.findByText(/não dá para saber se ele foi gravado\. Confira em Minhas reservas antes de fazer outro\./)).toBeTruthy();
    expect(screen.queryByText(/não foi gravado/)).toBeNull();
    expect(screen.getByRole('button', { name: 'Ver horários de Salão fictício' })).toBeEnabled();
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

  it('reservas além dos próximos 30 dias aparecem navegando por período e podem ser canceladas', async () => {
    const fake = fakeApi();
    fake.mocks.amenities.mockImplementation(async () => [{ ...salao, policy: { ...policy, maximumAdvanceDays: 90 } }, quadra]);
    fake.state.reservations = [{ reservationId: '41000000-0000-4000-8000-000000000020', unitId: UNIT, amenityId: SALAO, status: 'PENDING_APPROVAL',
      startsAt: '2030-02-20T13:00:00.000Z', endsAt: '2030-02-20T14:00:00.000Z', occupiedFrom: '2030-02-20T13:00:00.000Z',
      occupiedUntil: '2030-02-20T14:00:00.000Z', acceptedPolicyVersion: 3, feeCents: 15000, version: 0, decisionReason: null }];
    const queries = newQueryClient(Infinity); clients.push(queries);
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => 'test:key-period-000001'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    expect(await screen.findByText('Nenhuma reserva da unidade neste período.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Período anterior' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Próximo período' }));
    const list = await screen.findByLabelText('Reservas da unidade');
    expect(within(list).getByText('Aguardando aprovação')).toBeTruthy();
    await fireEvent.press(within(list).getByRole('button', { name: /^Retirar pedido/ }));
    await fireEvent.press(within(list).getByRole('button', { name: 'Confirmar retirada' }));
    await waitFor(() => expect(within(screen.getByLabelText('Reservas da unidade')).getByText('Cancelada')).toBeTruthy());
    // A navegação vai até o limite do servidor (366 dias = 12 períodos à frente), mesmo que a regra atual seja menor.
    for (let index = 1; index < 12; index += 1) await fireEvent.press(screen.getByRole('button', { name: 'Próximo período' }));
    expect(screen.getByRole('button', { name: 'Próximo período' })).toBeDisabled();
  });

  it('reserva antiga além da antecedência atual continua alcançável depois de a gestão reduzir a regra', async () => {
    const fake = fakeApi();
    fake.mocks.amenities.mockImplementation(async () => [{ ...salao, policy: { ...policy, maximumAdvanceDays: 10 } }, quadra]);
    fake.state.reservations = [{ reservationId: '41000000-0000-4000-8000-000000000031', unitId: UNIT, amenityId: SALAO, status: 'CONFIRMED',
      startsAt: '2030-03-08T13:00:00.000Z', endsAt: '2030-03-08T14:00:00.000Z', occupiedFrom: '2030-03-08T13:00:00.000Z',
      occupiedUntil: '2030-03-08T14:00:00.000Z', acceptedPolicyVersion: 2, feeCents: 0, version: 0, decisionReason: null }];
    const queries = newQueryClient(Infinity); clients.push(queries);
    await render(<QueryClientProvider client={queries}>
      <Reservations api={fake.api} accountId={ACCOUNT} now={now} newKey={() => 'test:key-period-000002'}
        membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
          timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
    </QueryClientProvider>);
    expect(await screen.findByText('Nenhuma reserva da unidade neste período.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Próximo período' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Próximo período' }));
    const list = await screen.findByLabelText('Reservas da unidade');
    expect(within(list).getByText('Confirmada')).toBeTruthy();
    expect(within(list).getByRole('button', { name: /^Cancelar reserva/ })).toBeTruthy();
  });
});
