import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import { inboxNotification, inboxPage, type InboxNotification } from '../src/api/notifications';
import { ContractError } from '../src/api/reservations';
import { Inbox } from '../src/ui/Inbox';
import { topicKeys } from '../src/ui/UnitEvents';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const OTHER_UNIT = '20000000-0000-4000-8000-000000000002';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const N1 = '55000000-0000-4000-8000-000000000001';
const N2 = '55000000-0000-4000-8000-000000000002';
const item = (overrides: Partial<InboxNotification> = {}): InboxNotification => ({ notificationId: N1, unitId: UNIT, kind: 'ARRIVAL_WAITING',
  resourceId: '56000000-0000-4000-8000-000000000001', resourceState: 'WAITING', state: 'AVAILABLE', createdAt: '2030-01-07T11:00:00Z', readAt: null, ...overrides });

function fakeApi(items = [item(), item({ notificationId: N2, kind: 'PARCEL_RECEIVED', resourceState: 'DELIVERED', state: 'READ_IN_APP', readAt: '2030-01-06T12:00:00Z' })]) {
  const state = { items, opens: [] as string[], listError: null as ApiError | null, openError: null as ApiError | null };
  const api = {
    unitInbox: jest.fn(async () => {
      if (state.listError) throw state.listError;
      return { items: state.items, unread: state.items.filter((entry) => entry.state === 'AVAILABLE').length };
    }),
    openNotification: jest.fn(async (_t: string, id: string) => {
      state.opens.push(id);
      if (state.openError) { const error = state.openError; state.openError = null; throw error; }
      state.items = state.items.map((entry) => entry.notificationId === id && entry.state === 'AVAILABLE' ? { ...entry, state: 'READ_IN_APP' as const, readAt: '2030-01-07T11:10:00Z' } : entry);
      return state.items.find((entry) => entry.notificationId === id)!;
    }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
async function mount(fake = fakeApi(), queries = newQueryClient(Infinity)) {
  clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Inbox api={fake.api} accountId={ACCOUNT} tenantId={TENANT} unitId={UNIT} timeZone="America/Sao_Paulo" />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string | RegExp) => fireEvent.press(await screen.findByRole('button', { name }));

describe('caixa da unidade no celular', () => {
  it('contrato: unidade conferida, leitura coerente, contagem nunca menor que os não abertos', async () => {
    expect(inboxNotification(item(), UNIT).state).toBe('AVAILABLE');
    expect(() => inboxNotification(item(), OTHER_UNIT)).toThrow(ContractError);
    expect(() => inboxNotification({ ...item(), readAt: '2030-01-07T11:10:00Z' })).toThrow(ContractError);
    expect(() => inboxNotification({ ...item(), resourceState: 'DELIVERED' })).toThrow(ContractError);
    expect(() => inboxPage({ items: [item()], unread: 0 }, UNIT)).toThrow(ContractError);
    expect(inboxPage({ items: [item()], unread: 3 }, UNIT).unread).toBe(3);
    expect(inboxNotification(item({ kind: 'WAITLIST_OFFERED', resourceState: 'OFFERED' }), UNIT).kind).toBe('WAITLIST_OFFERED');
    expect(() => inboxNotification(item({ kind: 'WAITLIST_OFFERED', resourceState: 'RECEIVED' }), UNIT)).toThrow(ContractError);
    expect(inboxNotification(item({ kind: 'REQUEST_UPDATED', resourceState: 'WAITING_RESIDENT' }), UNIT).kind).toBe('REQUEST_UPDATED');
    expect(() => inboxNotification(item({ kind: 'MOVE_DECIDED', resourceState: 'IN_PROGRESS' }), UNIT)).toThrow(ContractError);
    const calls: { url: string; init: RequestInit }[] = [];
    let reply = new Response(JSON.stringify({ ...item(), state: 'READ_IN_APP', readAt: '2030-01-07T11:10:00Z' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never,
      fetch: async (url, init) => { calls.push({ url, init }); return reply; } });
    expect((await api.openNotification(TENANT, N1)).state).toBe('READ_IN_APP');
    expect(calls[0]!.url).toBe(`https://api.example.invalid/v1/tenants/${TENANT}/notifications/${N1}/open`);
    expect(calls[0]!.init.method).toBe('POST');
    reply = new Response(JSON.stringify(item()), { status: 200, headers: { 'Content-Type': 'application/json' } });
    expect(await api.openNotification(TENANT, N1).then(() => null, (error: ApiError) => error.uncertain)).toBe(true);
    reply = new Response(JSON.stringify({ code: 'NOTIFICATION_NOT_FOUND' }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } });
    expect(await api.openNotification(TENANT, N1).then(() => null, (error: ApiError) => error.code)).toBe('NOTIFICATION_NOT_FOUND');
  });

  it('mostra não abertos, abre e registra a leitura, aponta a seção da unidade e atualiza o detalhe', async () => {
    const queries = newQueryClient(Infinity);
    const visitsKey = ['private', ACCOUNT, 'tenant', TENANT, 'unit', UNIT, 'visits', 'arrivals'];
    queries.setQueryData(visitsKey, { arrivals: [] });
    const fake = await mount(fakeApi(), queries);
    expect(await screen.findByText('Caixa da unidade · 1 novo')).toBeTruthy();
    expect(screen.getByText('Chegada aguardando resposta da unidade · Não aberto')).toBeTruthy();
    expect(screen.getByText('Situação atual: aguardando resposta.')).toBeTruthy();
    expect(screen.getByText('Encomenda recebida na portaria · Aberto')).toBeTruthy();
    expect(screen.getByText(/aberto por você em/)).toBeTruthy();
    expect(screen.getByText('Situação atual: retirada registrada.')).toBeTruthy();
    await press(/^Abrir: Chegada aguardando resposta da unidade/);
    expect(await screen.findByText('Aviso aberto. A chegada aparece em “Visitas”, nesta tela, para responder.')).toBeTruthy();
    expect(fake.state.opens).toEqual([N1]);
    expect(queries.getQueryState(visitsKey)?.isInvalidated).toBe(true);
    expect(await screen.findByText('Chegada aguardando resposta da unidade · Aberto')).toBeTruthy();
    expect(screen.getByText('Caixa da unidade')).toBeTruthy();
    await press(/^Abrir de novo: Encomenda recebida na portaria/);
    expect(await screen.findByText('Aviso aberto. A encomenda aparece em “Encomendas”, nesta tela.')).toBeTruthy();
    expect(fake.state.opens).toEqual([N1, N2]);
    const before = (fake.api.unitInbox as jest.Mock).mock.calls.length;
    await press('Atualizar caixa');
    await waitFor(() => expect((fake.api.unitInbox as jest.Mock).mock.calls.length).toBeGreaterThan(before));
  }, 20000);

  it('vazia, registro indisponível, falhas com nova tentativa e vínculo sem acesso escondendo a caixa', async () => {
    const empty = await mount(fakeApi([]));
    expect(await screen.findByText(/^Nenhum aviso\./)).toBeTruthy();
    expect(empty.state.opens).toEqual([]);
    await cleanup();
    const fake = fakeApi([item({ resourceState: null })]);
    fake.state.listError = new ApiError(503, 'NOTIFICATION_DEPENDENCY_UNAVAILABLE', 'Não foi possível concluir a operação. Atualize e confira antes de repetir.', false);
    await mount(fake);
    expect(await screen.findByText('Não foi possível concluir a operação. Atualize e confira antes de repetir.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.listError = null;
    await press('Tentar consultar a caixa de novo');
    expect(await screen.findByText('Este registro não está mais disponível para o seu vínculo.')).toBeTruthy();
    fake.state.openError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
    await press(/^Abrir: Chegada/);
    expect(await screen.findByText('Não foi possível confirmar o resultado. Atualize e confira antes de repetir.')).toBeTruthy();
    await press(/^Abrir: Chegada/);
    expect(await screen.findByText(/^Aviso aberto\./)).toBeTruthy();
    await cleanup();
    const denied = fakeApi();
    denied.state.listError = new ApiError(404, 'UNIT_ACCESS_DENIED', 'Unidade não disponível para esta operação.', false);
    await mount(denied);
    await waitFor(() => expect(denied.api.unitInbox).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText(/^Caixa da unidade/)).toBeNull());
  }, 20000);

  it('canal da unidade atualiza caixa, visitas, avisos e pedidos no celular', () => {
    expect(topicKeys.notifications).toEqual(['inbox']);
    expect(topicKeys.arrivals).toEqual(['visits']);
    expect(topicKeys.announcements).toEqual(['announcements']);
    expect(topicKeys.requests).toEqual(['requests', 'request']);
  });

  it('oferta da fila de espera aponta as reservas e atualiza a fila', async () => {
    const queries = newQueryClient(Infinity);
    const waitlistKey = ['private', ACCOUNT, 'tenant', TENANT, 'unit', UNIT, 'waitlist'];
    queries.setQueryData(waitlistKey, { entries: [] });
    const fake = await mount(fakeApi([item({ kind: 'WAITLIST_OFFERED', resourceState: 'OFFERED' })]), queries);
    expect(await screen.findByText('Vaga da fila de espera · Não aberto')).toBeTruthy();
    expect(screen.getByText('Situação atual: oferta aguardando o seu aceite, com prazo.')).toBeTruthy();
    await press(/^Abrir: Vaga da fila de espera/);
    expect(await screen.findByText('Aviso aberto. A oferta aparece em “Fila de espera”, nas reservas desta tela, para aceitar no prazo.')).toBeTruthy();
    expect(fake.state.opens).toEqual([N1]);
    expect(queries.getQueryState(waitlistKey)?.isInvalidated).toBe(true);
  }, 20000);

  it('resposta do pedido e decisão da mudança apontam a seção e atualizam a consulta', async () => {
    const queries = newQueryClient(Infinity);
    const requestsKey = ['private', ACCOUNT, 'tenant', TENANT, 'unit', UNIT, 'requests'];
    const movesKey = ['private', ACCOUNT, 'tenant', TENANT, 'unit', UNIT, 'moves'];
    queries.setQueryData(requestsKey, { requests: [] }); queries.setQueryData(movesKey, { moves: [] });
    const detailKey = ['private', ACCOUNT, 'tenant', TENANT, 'unit', UNIT, 'request', item().resourceId];
    queries.setQueryData(detailKey, { messages: [] });
    const fake = await mount(fakeApi([item({ kind: 'REQUEST_UPDATED', resourceState: 'WAITING_RESIDENT' }),
      item({ notificationId: N2, kind: 'MOVE_DECIDED', resourceState: 'REJECTED' })]), queries);
    expect(await screen.findByText('Atualização no pedido da unidade · Não aberto')).toBeTruthy();
    expect(screen.getByText('Situação atual: aguardando a resposta da unidade.')).toBeTruthy();
    expect(screen.getByText('Situação atual: mudança recusada.')).toBeTruthy();
    await press(/^Abrir: Atualização no pedido da unidade/);
    expect(await screen.findByText('Aviso aberto. O pedido está em “Pedidos”, nesta tela.')).toBeTruthy();
    expect(queries.getQueryState(requestsKey)?.isInvalidated).toBe(true);
    expect(queries.getQueryState(detailKey)?.isInvalidated).toBe(true);
    await press(/^Abrir: Decisão sobre a mudança/);
    expect(await screen.findByText('Aviso aberto. A mudança está em “Mudança e entrega de móveis”, nesta tela.')).toBeTruthy();
    expect(queries.getQueryState(movesKey)?.isInvalidated).toBe(true);
    expect(fake.state.opens).toEqual([N1, N2]);
  }, 20000);
});
