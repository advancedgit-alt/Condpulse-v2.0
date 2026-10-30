import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import type { Move, MoveRules } from '../src/api/moves';
import { move, normalizePlate } from '../src/api/moves';
import { Moves } from '../src/ui/Moves';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const MOVE = '9a000000-0000-4000-8000-000000000001';
const now = () => new Date('2030-01-07T11:10:00Z'); // segunda, 08:10 em São Paulo
const rules: MoveRules = { rulesText: 'Use o elevador de serviço com proteção.', weekDays: [1, 2, 3, 4, 5, 6, 7], startTime: '08:00', endTime: '18:00',
  minNoticeHours: 24, maxDurationMinutes: 240, version: 1 };

function fakeApi(initialRules: MoveRules | null = rules) {
  const state = { rules: initialRules, moves: [] as Move[], creates: [] as { body: Record<string, unknown>; key: string }[], cancels: [] as unknown[],
    createError: null as ApiError | null, keys: new Map<string, Move>(), listError: null as ApiError | null };
  const api = {
    moveRules: jest.fn(async () => state.rules),
    unitMoves: jest.fn(async () => { if (state.listError) throw state.listError; return { moves: state.moves, truncated: false }; }),
    createMove: jest.fn(async (_tenant: string, unitId: string, body: Record<string, unknown>, key: string) => {
      state.creates.push({ body, key });
      const previous = state.keys.get(key);
      if (previous) return { move: previous, replayed: true };
      const created: Move = { moveId: MOVE, unitId, kind: body.kind as Move['kind'], startsAt: body.startsAt as string, endsAt: body.endsAt as string,
        providerName: body.providerName as string, vehiclePlate: body.vehiclePlate as string | null, items: body.items as string | null,
        rulesVersion: body.acceptedRulesVersion as number, state: 'REQUESTED', decisionNote: null, version: 0 };
      state.keys.set(key, created); state.moves = [created];
      if (state.createError) { const error = state.createError; state.createError = null; throw error; }
      return { move: created, replayed: false };
    }),
    cancelMove: jest.fn(async (_tenant: string, moveId: string, expectedVersion: number) => {
      state.cancels.push({ moveId, expectedVersion });
      const current = state.moves.find((item) => item.moveId === moveId)!;
      const cancelled: Move = { ...current, state: 'CANCELLED', version: current.version + 1 };
      state.moves = [cancelled];
      return cancelled;
    }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(fake = fakeApi()) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Moves api={fake.api} accountId={ACCOUNT} now={now} newKey={() => `test:move-${String(++counter).padStart(12, '0')}`}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));

test('regras exibidas, pedido com aceite e chave mantida depois de resultado incerto, retirada com confirmação', async () => {
  const fake = await mount();
  expect(await screen.findByText('Regras de mudança (versão 1)')).toBeTruthy();
  expect(screen.getByText('todos os dias, das 08:00 às 18:00; antecedência mínima de 24 h; até 240 min por pedido.')).toBeTruthy();
  expect(screen.getByText('Nenhum pedido de mudança.')).toBeTruthy();
  await press('Pedir mudança');
  await press('Entrega de móveis');
  await fireEvent.changeText(screen.getByLabelText('Data (AAAA-MM-DD)'), '2030-01-10');
  await fireEvent.changeText(screen.getByLabelText('Início (HH:MM)'), '09:00');
  await fireEvent.changeText(screen.getByLabelText('Fim (HH:MM)'), '08:00');
  expect(screen.getByText('O fim precisa ser depois do início.')).toBeTruthy();
  await fireEvent.changeText(screen.getByLabelText('Fim (HH:MM)'), '15:00');
  expect(screen.getByText('As regras permitem até 240 minutos por pedido.')).toBeTruthy();
  await fireEvent.changeText(screen.getByLabelText('Fim (HH:MM)'), '12:00');
  await fireEvent.changeText(screen.getByLabelText('Responsável ou transportadora'), '  Transportadora   Fictícia ');
  await fireEvent.changeText(screen.getByLabelText('Placa do veículo (opcional)'), 'AB');
  expect(screen.getByText('Informe a placa com 5 a 8 letras e números, ou deixe em branco.')).toBeTruthy();
  await fireEvent.changeText(screen.getByLabelText('Placa do veículo (opcional)'), 'abc-1d23');
  await fireEvent.changeText(screen.getByLabelText('Itens (opcional)'), 'Sofá');
  expect(screen.getByRole('button', { name: 'Enviar pedido de mudança' })).toBeDisabled();
  await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras de mudança (versão 1)' }));
  fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
  await press('Enviar pedido de mudança');
  expect(await screen.findByText('O resultado não foi confirmado e o pedido ficou travado. Use “Tentar confirmar o mesmo pedido”: se ele já foi enviado, nada novo é gravado.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Voltar' })).toBeNull();
  await press('Tentar confirmar o mesmo pedido');
  expect(await screen.findByText('Este pedido já estava enviado (aguardando a gestão).')).toBeTruthy();
  const body = { kind: 'FURNITURE_DELIVERY', startsAt: '2030-01-10T12:00:00.000Z', endsAt: '2030-01-10T15:00:00.000Z',
    providerName: 'Transportadora Fictícia', vehiclePlate: 'ABC1D23', items: 'Sofá', acceptedRulesVersion: 1 };
  expect(fake.state.creates.map((item) => item.body)).toEqual([body, body]);
  expect(fake.state.creates[1]!.key).toBe(fake.state.creates[0]!.key);
  const list = await screen.findByLabelText('Pedidos de mudança da unidade');
  expect(within(list).getByText('Entrega de móveis · Aguardando a gestão')).toBeTruthy();
  expect(within(list).getByText('Responsável: Transportadora Fictícia · placa ABC1D23')).toBeTruthy();
  await press('Retirar pedido');
  await press('Voltar');
  expect(fake.state.cancels).toEqual([]);
  await press('Retirar pedido');
  await press('Confirmar retirada');
  expect(await screen.findByText('Pedido de mudança retirado.')).toBeTruthy();
  expect(await within(list).findByText('Entrega de móveis · Cancelada')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Retirar pedido' })).toBeNull();
  expect(fake.state.cancels).toEqual([{ moveId: MOVE, expectedVersion: 0 }]);
});

test('sem regras não há pedido; regras alteradas pedem novo aceite com nova chave', async () => {
  const empty = await mount(fakeApi(null));
  expect(await screen.findByText('A gestão ainda não publicou as regras de mudança. Quando publicar, o pedido fica disponível aqui.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Pedir mudança' })).toBeNull();
  empty.state.rules = rules;
  await press('Atualizar mudanças');
  await press('Pedir mudança');
  await fireEvent.changeText(screen.getByLabelText('Data (AAAA-MM-DD)'), '2030-01-10');
  await fireEvent.changeText(screen.getByLabelText('Início (HH:MM)'), '10:00');
  await fireEvent.changeText(screen.getByLabelText('Fim (HH:MM)'), '11:00');
  await fireEvent.changeText(screen.getByLabelText('Responsável ou transportadora'), 'Pessoa Fictícia');
  await fireEvent.press(screen.getByRole('checkbox', { name: 'Li e aceito as regras de mudança (versão 1)' }));
  empty.state.createError = new ApiError(409, 'MOVE_RULES_CHANGED', 'As regras de mudança foram alteradas. Leia a versão atual antes de continuar.');
  empty.state.rules = { ...rules, version: 2, rulesText: 'Regras novas: proteja o piso.' };
  await press('Enviar pedido de mudança');
  expect(await screen.findByText('As regras de mudança foram alteradas. Leia a versão atual antes de continuar.')).toBeTruthy();
  expect(await screen.findByText('Regras novas: proteja o piso.')).toBeTruthy();
  const accept = await screen.findByRole('checkbox', { name: 'Li e aceito as regras de mudança (versão 2)' });
  expect(accept).not.toBeChecked();
  expect(screen.getByRole('button', { name: 'Enviar pedido de mudança' })).toBeDisabled();
  await fireEvent.press(accept);
  empty.state.keys.clear(); empty.state.moves = [];
  await press('Enviar pedido de mudança');
  expect(await screen.findByText(/^Pedido de mudança de entrada enviado para/)).toBeTruthy();
  expect(empty.state.creates.map((item) => item.body.acceptedRulesVersion)).toEqual([1, 2]);
  expect(empty.state.creates[1]!.key).not.toBe(empty.state.creates[0]!.key);
});

test('dependente ou recurso desligado: a seção some sem erro', async () => {
  const fake = fakeApi();
  fake.state.listError = new ApiError(404, 'UNIT_ACCESS_DENIED', 'Unidade não disponível para esta operação.');
  await mount(fake);
  await waitFor(() => expect(screen.queryByText('Mudança e entrega de móveis')).toBeNull());
  expect(screen.queryByText('Unidade não disponível para esta operação.')).toBeNull();
});

test('contrato: decisão coerente, placa no formato gravado e pedido novo conferido', async () => {
  const requested = { moveId: MOVE, unitId: UNIT, unitLabel: '101', blockName: null, kind: 'MOVE_IN', startsAt: '2030-01-10T12:00:00Z',
    endsAt: '2030-01-10T15:00:00Z', providerName: 'Fulano', vehiclePlate: null, items: null, rulesVersion: 1, state: 'REQUESTED',
    decisionNote: null, decidedAt: null, createdByMe: true, createdAt: '2030-01-07T11:00:00Z', version: 0 };
  expect(move(requested).state).toBe('REQUESTED');
  expect(() => move({ ...requested, state: 'REJECTED' })).toThrow();
  expect(() => move({ ...requested, vehiclePlate: 'abc1d23' })).toThrow();
  expect(normalizePlate(' abc-1d23 ')).toBe('ABC1D23');
  expect(normalizePlate('')).toBeNull();
  expect(normalizePlate('AB')).toBe('');
  const fetcher = jest.fn(async () => new Response(JSON.stringify({ ...requested, rulesVersion: 2 }), { status: 201,
    headers: { 'Content-Type': 'application/json', 'Idempotency-Replayed': 'false' } }));
  const api = createApi({ baseUrl: 'https://api.example.org', fetch: fetcher,
    tokens: { accessToken: async () => 'token-ficticio', refresh: async () => null, expire: async () => {} } });
  await expect(api.createMove(TENANT, UNIT, { kind: 'MOVE_IN', startsAt: requested.startsAt, endsAt: requested.endsAt, providerName: 'Fulano',
    vehiclePlate: null, items: null, acceptedRulesVersion: 1 }, 'test:move-key-000000001')).rejects.toMatchObject({ uncertain: true });
  expect(String((fetcher.mock.calls[0] as unknown[])[0])).toBe(`https://api.example.org/v1/tenants/${TENANT}/units/${UNIT}/moves`);
});
