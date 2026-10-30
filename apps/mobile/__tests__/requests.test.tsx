import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import { multiline, requestMessage, singleLine, unitRequest, unitRequestSummary, type OpenRequest, type UnitRequest } from '../src/api/requests';
import { ContractError } from '../src/api/reservations';
import { Requests, type RequestPendingStore } from '../src/ui/Requests';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const OTHER_UNIT = '20000000-0000-4000-8000-000000000002';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const R1 = '53000000-0000-4000-8000-000000000001';
const R2 = '53000000-0000-4000-8000-000000000002';
const staffMessage = { messageId: '54000000-0000-4000-8000-000000000001', authorRole: 'STAFF', authorName: null, mine: false, visibility: 'RESIDENT',
  body: 'Vamos verificar amanhã.', createdAt: '2030-01-07T12:00:00Z' };
const detail = (overrides: Partial<UnitRequest> = {}): UnitRequest => ({ requestId: R1, unitId: UNIT, category: 'MAINTENANCE', title: 'Vazamento na pia',
  state: 'IN_PROGRESS', assigned: true, dueOn: '2030-01-09', createdAt: '2030-01-07T11:00:00Z', updatedAt: '2030-01-07T12:00:00Z',
  description: 'Água no armário da cozinha.', resolution: null, requesterName: 'Morador Fictício', openedByMe: true, resolvedAt: null, closedAt: null,
  messages: [{ messageId: staffMessage.messageId, authorRole: 'STAFF', authorName: null, mine: false, body: staffMessage.body, createdAt: staffMessage.createdAt }],
  messagesTruncated: false, ...overrides });
const summary = (value: UnitRequest) => ({ requestId: value.requestId, unitId: value.unitId, category: value.category, title: value.title, state: value.state,
  assigned: value.assigned, dueOn: value.dueOn, createdAt: value.createdAt, updatedAt: value.updatedAt });

function fakeApi(initial: UnitRequest[] = []) {
  const state = { requests: [...initial], opens: [] as { body: OpenRequest; key: string }[], messages: [] as { body: string; key: string }[],
    openError: null as ApiError | null, messageError: null as ApiError | null, listError: null as ApiError | null, detailError: null as ApiError | null,
    openKeys: new Map<string, UnitRequest>(), messageKeys: new Set<string>() };
  const api = {
    unitRequests: jest.fn(async () => { if (state.listError) throw state.listError; return { requests: state.requests.map(summary), truncated: false }; }),
    openRequest: jest.fn(async (_t: string, unitId: string, body: OpenRequest, key: string) => {
      state.opens.push({ body, key });
      const previous = state.openKeys.get(key);
      if (previous) return { request: previous, replayed: true };
      const created = detail({ requestId: R2, unitId, category: body.category, title: body.title, description: body.description, state: 'OPEN',
        assigned: false, dueOn: null, messages: [] });
      state.openKeys.set(key, created); state.requests = [created, ...state.requests];
      if (state.openError) { const error = state.openError; state.openError = null; throw error; }
      return { request: created, replayed: false };
    }),
    unitRequest: jest.fn(async (_t: string, _u: string, id: string) => {
      if (state.detailError) throw state.detailError;
      return state.requests.find((item) => item.requestId === id)!;
    }),
    requestMessage: jest.fn(async (_t: string, id: string, body: string, key: string) => {
      state.messages.push({ body, key });
      const replayed = state.messageKeys.has(key);
      if (!replayed) {
        state.messageKeys.add(key);
        state.requests = state.requests.map((item) => item.requestId === id ? { ...item, messages: [...item.messages,
          { messageId: `54000000-0000-4000-8000-00000000010${state.messageKeys.size}`, authorRole: 'UNIT' as const, authorName: 'Morador Fictício', mine: true,
            body, createdAt: '2030-01-07T12:30:00Z' }] } : item);
      }
      if (state.messageError) { const error = state.messageError; state.messageError = null; throw error; }
      return { message: {}, replayed };
    }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(fake = fakeApi(), pending?: RequestPendingStore) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Requests api={fake.api} accountId={ACCOUNT} newKey={() => `test:request-${String(++counter).padStart(12, '0')}`} pending={pending}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));
const uncertainError = () => new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
const tokens = { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never;
const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': status < 400 ? 'application/json' : 'application/problem+json', ...headers } });

describe('pedidos da unidade no celular', () => {
  it('contrato: sem comentário interno, equipe sem nome, lista sem texto e unidade conferida', async () => {
    expect(requestMessage(staffMessage).authorName).toBeNull();
    expect(() => requestMessage({ ...staffMessage, visibility: 'INTERNAL' })).toThrow(ContractError);
    expect(() => requestMessage({ ...staffMessage, authorName: 'Pessoa da Equipe' })).toThrow(ContractError);
    expect(() => requestMessage({ ...staffMessage, authorRole: 'UNIT', authorName: null })).toThrow(ContractError);
    const wire = { ...detail(), messages: [staffMessage] };
    expect(unitRequest(wire, UNIT).messages).toHaveLength(1);
    expect(() => unitRequest(wire, OTHER_UNIT)).toThrow(ContractError);
    expect(() => unitRequest({ ...wire, state: 'CLOSED' }, UNIT)).toThrow(ContractError);
    expect(() => unitRequest({ ...wire, resolution: 'Feito.' }, UNIT)).toThrow(ContractError);
    expect(() => unitRequestSummary({ ...summary(detail()), description: 'texto' }, UNIT)).toThrow(ContractError);
    expect(singleLine('  Vazamento   na pia ', 3, 120)).toBe('Vazamento na pia');
    expect(singleLine('ab', 3, 120)).toBeNull();
    expect(multiline('\n linha  um \n\n\n linha dois \n', 2000)).toBe('linha um\n\nlinha dois');
    expect(multiline('   ', 2000)).toBeNull();

    const calls: { url: string; init: RequestInit }[] = [];
    let reply: Response = json({ requests: [summary(detail())], truncated: false });
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens, fetch: async (url, init) => { calls.push({ url, init }); return reply; } });
    expect((await api.unitRequests(TENANT, UNIT)).requests).toHaveLength(1);
    reply = json({ requests: [summary(detail({ unitId: OTHER_UNIT }))], truncated: false });
    expect(await api.unitRequests(TENANT, UNIT).then(() => null, (error: ApiError) => error.code)).toBe('INVALID_API_RESPONSE');
    const body: OpenRequest = { category: 'NOISE', title: 'Barulho à noite', description: 'Som alto depois das 22h.' };
    reply = json({ ...detail({ category: 'NOISE', title: body.title, description: body.description, state: 'OPEN', assigned: false, dueOn: null }), messages: [] }, 201);
    expect((await api.openRequest(TENANT, UNIT, body, 'test:open-000000000001')).replayed).toBe(false);
    expect(calls.at(-1)!.url).toBe(`https://api.example.invalid/v1/tenants/${TENANT}/units/${UNIT}/requests`);
    expect((calls.at(-1)!.init.headers as Record<string, string>)['Idempotency-Key']).toBe('test:open-000000000001');
    reply = json({ ...detail({ title: 'Outro título', state: 'OPEN', assigned: false, dueOn: null }), messages: [] }, 201);
    const mismatch = await api.openRequest(TENANT, UNIT, body, 'test:open-000000000002').then(() => null, (error: ApiError) => error);
    expect(mismatch!.uncertain).toBe(true);
    reply = json({ code: 'SERVICE_REQUEST_CLOSED', status: 409 }, 409);
    const closed = await api.requestMessage(TENANT, R1, 'Oi', 'test:message-0000000001').then(() => null, (error: ApiError) => error);
    expect(closed!.code).toBe('SERVICE_REQUEST_CLOSED');
    reply = json({ ...staffMessage, authorRole: 'UNIT', authorName: 'Morador Fictício', mine: true, body: 'Oi' }, 201, { 'Idempotency-Replayed': 'true' });
    expect((await api.requestMessage(TENANT, R1, 'Oi', 'test:message-0000000002')).replayed).toBe(true);
    reply = json({ ...staffMessage, authorRole: 'UNIT', authorName: 'Morador Fictício', mine: true, body: 'Outro' }, 201);
    expect(await api.requestMessage(TENANT, R1, 'Oi', 'test:message-0000000003').then(() => null, (error: ApiError) => error.uncertain)).toBe(true);
  });

  it('abre pedido com chave mantida depois de resultado incerto e já mostra a conversa', async () => {
    const fake = await mount();
    expect(await screen.findByText(/Nenhum pedido/)).toBeTruthy();
    await press('Abrir pedido');
    await press('Voltar');
    expect(fake.state.opens).toHaveLength(0);
    await press('Abrir pedido');
    await press('Barulho');
    expect(screen.getByRole('button', { name: 'Enviar pedido' })).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Título'), 'ab');
    expect(screen.getByText('O título tem de 3 a 120 caracteres.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Título'), '  Barulho   à noite ');
    await fireEvent.changeText(screen.getByLabelText('Descrição'), 'Som alto depois das 22h.');
    fake.state.openError = uncertainError();
    await press('Enviar pedido');
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo pedido' })).toBeTruthy();
    expect(screen.getByLabelText('Título').props.editable).toBe(false);
    await press('Tentar confirmar o mesmo pedido');
    expect(await screen.findByText('Este pedido já estava aberto: Barulho à noite (enviado).')).toBeTruthy();
    expect(fake.state.opens).toHaveLength(2);
    expect(fake.state.opens[1]!.key).toBe(fake.state.opens[0]!.key);
    expect(fake.state.opens[0]!.body).toEqual({ category: 'NOISE', title: 'Barulho à noite', description: 'Som alto depois das 22h.' });
    expect(await screen.findByText('Barulho à noite · Enviado')).toBeTruthy();
    expect(await screen.findByText('Som alto depois das 22h.')).toBeTruthy();
    expect(screen.getByText('Nenhuma mensagem ainda.')).toBeTruthy();
    expect(screen.getByText('Pedidos · 1 em andamento')).toBeTruthy();
    await press('Fechar conversa');
    expect(await screen.findByRole('button', { name: 'Ver conversa: Barulho à noite' })).toBeTruthy();
  }, 20000);

  it('conversa: equipe sem nome, resposta incerta repete a mesma chave e pedido encerrado sem resposta', async () => {
    const fake = await mount(fakeApi([detail(), detail({ requestId: R2, title: 'Lâmpada queimada', state: 'CLOSED', assigned: false, dueOn: null, closedAt: '2030-01-06T12:00:00Z', messages: [] })]));
    expect(await screen.findByText('Vazamento na pia · Em execução')).toBeTruthy();
    expect(screen.getByText('Responsável da equipe definido · prazo comunicado: 09/01/2030')).toBeTruthy();
    await press('Ver conversa: Vazamento na pia');
    expect(await screen.findByText(/^Equipe do condomínio · /)).toBeTruthy();
    expect(screen.getByText('Vamos verificar amanhã.')).toBeTruthy();
    expect(screen.getByText('Aberto por você')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Enviar mensagem' })).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Mensagem para a gestão'), '  Obrigado,  aguardo. ');
    fake.state.messageError = uncertainError();
    await press('Enviar mensagem');
    expect(await screen.findByRole('button', { name: 'Tentar confirmar a mesma mensagem' })).toBeTruthy();
    expect(screen.getByLabelText('Mensagem para a gestão').props.editable).toBe(false);
    await press('Tentar confirmar a mesma mensagem');
    expect(await screen.findByText('Mensagem enviada.')).toBeTruthy();
    expect(fake.state.messages).toHaveLength(2);
    expect(fake.state.messages[1]!.key).toBe(fake.state.messages[0]!.key);
    expect(fake.state.messages[0]!.body).toBe('Obrigado, aguardo.');
    expect(await screen.findByText('Obrigado, aguardo.')).toBeTruthy();
    expect(screen.getByText(/^Você · /)).toBeTruthy();
    await press('Fechar conversa');
    await press('Ver conversa: Lâmpada queimada');
    expect(await screen.findByText('Pedido encerrado. A conversa fica guardada; se precisar, abra um novo pedido.')).toBeTruthy();
    expect(screen.queryByLabelText('Mensagem para a gestão')).toBeNull();
  }, 20000);

  it('envio incerto sobrevive à troca de vínculo; chave vencida exige conferência antes do descarte', async () => {
    const pending: RequestPendingStore = new Map();
    const fake = fakeApi();
    fake.state.openError = uncertainError();
    await mount(fake, pending);
    await press('Abrir pedido');
    await fireEvent.changeText(screen.getByLabelText('Título'), 'Portão emperrado');
    await fireEvent.changeText(screen.getByLabelText('Descrição'), 'O portão da garagem não fecha.');
    await press('Enviar pedido');
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo pedido' })).toBeTruthy();
    await cleanup();
    await mount(fake, pending);
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo pedido' })).toBeTruthy();
    expect(screen.getByLabelText('Título').props.value).toBe('Portão emperrado');
    fake.api.openRequest = jest.fn(async () => { throw new ApiError(409, 'IDEMPOTENCY_KEY_EXPIRED', 'O prazo para confirmar este envio terminou.', false); }) as never;
    await press('Tentar confirmar o mesmo pedido');
    expect(await screen.findByText('O prazo para confirmar este envio terminou.')).toBeTruthy();
    expect(pending.size).toBe(1);
    expect(screen.getByLabelText('Título').props.editable).toBe(false);
    await press('Conferi a lista: descartar este envio');
    expect(pending.size).toBe(0);
    expect(await screen.findByRole('button', { name: 'Abrir pedido' })).toBeTruthy();
  }, 20000);

  it('falhas: lista e conversa com nova tentativa; recusa definitiva libera o formulário; vínculo sem acesso esconde a seção', async () => {
    const fake = fakeApi([detail()]);
    fake.state.listError = new ApiError(503, 'HTTP_ERROR', 'Pedidos indisponíveis agora.', false);
    await mount(fake);
    expect(await screen.findByText('Pedidos indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.listError = null;
    await press('Tentar de novo');
    expect(await screen.findByText('Vazamento na pia · Em execução')).toBeTruthy();
    const before = (fake.api.unitRequests as jest.Mock).mock.calls.length;
    await press('Atualizar pedidos');
    await waitFor(() => expect(fake.api.unitRequests).toHaveBeenCalledTimes(before + 1));
    fake.state.detailError = new ApiError(404, 'SERVICE_REQUEST_NOT_FOUND', 'Pedido não encontrado. Ele pode não ser desta unidade.', false);
    await press('Ver conversa: Vazamento na pia');
    expect(await screen.findByText('Pedido não encontrado. Ele pode não ser desta unidade.')).toBeTruthy();
    fake.state.detailError = null;
    await press('Tentar abrir de novo');
    expect(await screen.findByText('Vamos verificar amanhã.')).toBeTruthy();
    fake.state.messageError = new ApiError(409, 'SERVICE_REQUEST_CLOSED', 'O pedido foi encerrado; abra um novo pedido se precisar.', false);
    await fireEvent.changeText(screen.getByLabelText('Mensagem para a gestão'), 'Ainda está pingando.');
    await press('Enviar mensagem');
    expect(await screen.findByText('O pedido foi encerrado; abra um novo pedido se precisar.')).toBeTruthy();
    expect(screen.getByLabelText('Mensagem para a gestão').props.editable).toBe(true);
    await cleanup();
    const denied = fakeApi();
    denied.state.listError = new ApiError(404, 'UNIT_ACCESS_DENIED', 'Unidade não disponível para esta operação.', false);
    await mount(denied);
    await waitFor(() => expect(denied.api.unitRequests).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText(/^Pedidos/)).toBeNull());
    expect(screen.queryByRole('button', { name: 'Abrir pedido' })).toBeNull();
  }, 20000);
});
