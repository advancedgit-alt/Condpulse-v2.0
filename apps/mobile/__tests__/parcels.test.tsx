import { cleanup, fireEvent, render, screen, within } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, type CondPulseApi } from '../src/api/client';
import { delegation, issued, normalizeDocument, parcel, type Delegation, type Parcel } from '../src/api/parcels';
import { ContractError } from '../src/api/reservations';
import { Parcels, type DelegationPendingStore } from '../src/ui/Parcels';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const PARCEL = '41000000-0000-4000-8000-000000000001';
const now = () => new Date('2030-01-07T11:10:00Z'); // 08:10 em São Paulo
const waitingParcel: Parcel & { voidReason: null; voidedAt: null } = { parcelId: PARCEL, unitId: UNIT, quantity: 2, description: 'Caixa fictícia',
  storageLocation: 'Prateleira A', status: 'RECEIVED', receivedAt: '2030-01-07T10:00:00Z', version: 0, handoff: null, voidReason: null, voidedAt: null };
const deliveredParcel = { ...waitingParcel, parcelId: '41000000-0000-4000-8000-000000000002', quantity: 1, status: 'DELIVERED' as const, version: 1,
  handoff: { recipientName: 'Pessoa Fictícia', deliveredAt: '2030-01-06T20:00:00Z', evidenceKind: 'DELEGATION' as const } };
const activeDelegation: Delegation = { delegationId: '42000000-0000-4000-8000-000000000001', parcelId: PARCEL, unitId: UNIT, delegateName: 'Terceiro Fictício',
  documentLast4: '12AB', validUntil: '2030-01-09T11:10:00Z', state: 'ACTIVE', attempts: 1, attemptsLeft: 4, createdByMe: true,
  createdAt: '2030-01-07T11:00:00Z', usedAt: null, closedAt: null, version: 1 };

function fakeApi() {
  const state = { waiting: [waitingParcel] as Parcel[], delivered: [deliveredParcel] as Parcel[], current: null as Delegation | null,
    creates: [] as { body: { delegateName: string; documentLast4: string | null; validUntil: string | null }; key: string }[], revokes: [] as unknown[],
    errors: { list: [] as ApiError[], create: [] as ApiError[], revoke: [] as ApiError[], delegation: [] as ApiError[] } };
  const api = {
    unitParcels: jest.fn(async (_t: string, _u: string, status: 'RECEIVED' | 'DELIVERED') => {
      const error = state.errors.list.shift(); if (error) throw error;
      return { parcels: status === 'RECEIVED' ? state.waiting : state.delivered, truncated: false };
    }),
    parcelDelegation: jest.fn(async () => {
      const error = state.errors.delegation.shift(); if (error) throw error;
      return state.current;
    }),
    delegate: jest.fn(async (_t: string, _p: string, body: { delegateName: string; documentLast4: string | null; validUntil: string | null }, key: string) => {
      state.creates.push({ body, key });
      const error = state.errors.create.shift(); if (error) throw error;
      const replayed = state.creates.filter((item) => item.key === key).length > 1;
      const created = { ...activeDelegation, delegationId: `42000000-0000-4000-8000-00000000000${state.creates.length + 1}`, delegateName: body.delegateName,
        documentLast4: body.documentLast4, validUntil: body.validUntil!, attempts: 0, attemptsLeft: 5, version: 0 };
      state.current = created;
      return { delegation: created, code: replayed ? null : '12345678', replayed };
    }),
    revokeDelegation: jest.fn(async (_t: string, _p: string, body: { delegationId: string; expectedVersion: number }) => {
      state.revokes.push(body);
      const error = state.errors.revoke.shift(); if (error) throw error;
      state.current = { ...state.current!, state: 'REVOKED', closedAt: now().toISOString(), version: state.current!.version + 1 };
      return state.current;
    }),
  };
  return { api: api as unknown as CondPulseApi, state, mocks: api };
}

const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(setup?: (fake: ReturnType<typeof fakeApi>) => void, pending?: DelegationPendingStore, existing?: ReturnType<typeof fakeApi>) {
  const fake = existing ?? fakeApi();
  setup?.(fake);
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Parcels api={fake.api} accountId={ACCOUNT} now={now} pending={pending} newKey={() => `test:parc-${String(++counter).padStart(12, '0')}`}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
  </QueryClientProvider>);
  return fake;
}
const uncertain = () => new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.', true);

describe('contrato de encomendas e autorização no celular', () => {
  it('falha fechada com entrega sem retirada, autorização incoerente e código fora da regra', () => {
    expect(parcel(waitingParcel).status).toBe('RECEIVED');
    expect(() => parcel({ ...waitingParcel, status: 'DELIVERED', version: 1 })).toThrow(ContractError);
    expect(() => parcel({ ...waitingParcel, status: 'VOIDED', version: 1 })).toThrow(ContractError);
    expect(delegation(activeDelegation).attemptsLeft).toBe(4);
    expect(() => delegation({ ...activeDelegation, attemptsLeft: 5 })).toThrow(ContractError);
    expect(() => delegation({ ...activeDelegation, state: 'USED' })).toThrow(ContractError);
    expect(issued({ delegation: activeDelegation, code: '12345678' }, PARCEL, false).code).toBe('12345678');
    expect(() => issued({ delegation: activeDelegation, code: null }, PARCEL, false)).toThrow(ContractError);
    expect(() => issued({ delegation: activeDelegation, code: '12345678' }, PARCEL, true)).toThrow(ContractError);
    expect(() => issued({ delegation: activeDelegation, code: '1234' }, PARCEL, false)).toThrow(ContractError);
    expect(() => issued({ delegation: activeDelegation, code: '12345678' }, '41000000-0000-4000-8000-000000000009', false)).toThrow(ContractError);
    expect(normalizeDocument(' 12ab ')).toBe('12AB');
    expect(normalizeDocument('')).toBeNull();
    expect(normalizeDocument('12345')).toBe('');
  });
});

describe('encomendas no celular', () => {
  it('lista a retirar e retiradas; vazio e atualizar', async () => {
    const fake = await mount();
    const waiting = await screen.findByLabelText('Encomendas a retirar');
    expect(within(waiting).getByText('2 volumes · a retirar')).toBeTruthy();
    expect(within(waiting).getByText(/guardada em Prateleira A/)).toBeTruthy();
    const delivered = screen.getByLabelText('Encomendas retiradas');
    expect(within(delivered).getByText(/Retirada por Pessoa Fictícia \(pessoa autorizada pela unidade, com código\)/)).toBeTruthy();
    fake.state.waiting = []; fake.state.delivered = [];
    await fireEvent.press(screen.getByRole('button', { name: 'Atualizar encomendas' }));
    expect(await screen.findByText('Nada a retirar.')).toBeTruthy();
    expect(screen.getByText('Nenhuma retirada registrada recentemente.')).toBeTruthy();
  });

  it('erro na consulta mostra nova tentativa; vínculo sem acesso esconde a seção', async () => {
    const failure = () => new ApiError(0, 'NETWORK_UNAVAILABLE', 'Não foi possível conectar ao sistema. Confira sua conexão e tente novamente.');
    const fake = await mount((f) => { f.state.errors.list.push(failure(), failure(), failure(), failure()); });
    expect(await screen.findByText(/Não foi possível conectar ao sistema/, {}, { timeout: 4000 })).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Tentar consultar de novo' }));
    expect(await screen.findByLabelText('Encomendas a retirar')).toBeTruthy();
    expect(fake.mocks.unitParcels.mock.calls.length).toBeGreaterThanOrEqual(4);
    await cleanup();
    await mount((f) => { f.state.errors.list.push(new ApiError(404, 'UNIT_ACCESS_DENIED', 'Unidade não disponível para esta operação.')); });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByText('Encomendas')).toBeNull();
  }, 10000);

  it('autoriza terceiro: valida campos, mostra o código uma vez e envia o corpo normalizado', async () => {
    const fake = await mount();
    await fireEvent.press(await screen.findByRole('button', { name: 'Autorizar retirada por terceiro' }));
    const generate = screen.getByRole('button', { name: 'Gerar código' });
    expect(generate).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Nome de quem vai retirar'), '  Terceiro   Fictício ');
    await fireEvent.changeText(screen.getByLabelText('Final do documento (opcional)'), '12345');
    expect(screen.getByText(/Informe só os 2 a 4 últimos caracteres/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Gerar código' })).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Final do documento (opcional)'), '12ab');
    await fireEvent.changeText(screen.getByLabelText('Válida até: hora (HH:MM)'), '08:15');
    await fireEvent.changeText(screen.getByLabelText('Válida até: data (AAAA-MM-DD)'), '2030-01-07');
    expect(screen.getByText('A autorização vale por pelo menos 15 minutos.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Válida até: data (AAAA-MM-DD)'), '2030-01-20');
    expect(screen.getByText('A autorização vale por até 7 dias.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Válida até: data (AAAA-MM-DD)'), '2030-01-09');
    await fireEvent.press(screen.getByRole('button', { name: 'Gerar código' }));
    expect(await screen.findByText('12345678')).toBeTruthy();
    expect(screen.getByText(/Autorização criada para Terceiro Fictício/)).toBeTruthy();
    expect(fake.state.creates[0]!.body).toEqual({ delegateName: 'Terceiro Fictício', documentLast4: '12AB', validUntil: '2030-01-09T11:15:00.000Z' });
    expect(await screen.findByText(/Autorização em vigor: Terceiro Fictício · documento final 12AB/)).toBeTruthy();
  });

  it('validade padrão de 48 horas; voltar fecha o formulário sem enviar', async () => {
    const fake = await mount();
    await fireEvent.press(await screen.findByRole('button', { name: 'Autorizar retirada por terceiro' }));
    expect(screen.getByLabelText('Válida até: data (AAAA-MM-DD)').props.value).toBe('2030-01-09');
    expect(screen.getByLabelText('Válida até: hora (HH:MM)').props.value).toBe('08:10');
    await fireEvent.press(screen.getByRole('button', { name: 'Voltar' }));
    expect(screen.queryByLabelText('Nome de quem vai retirar')).toBeNull();
    expect(fake.state.creates).toHaveLength(0);
  });

  it('resultado incerto trava o envio e repete a mesma chave e corpo; repetição avisa que o código não volta', async () => {
    const fake = await mount((f) => { f.state.errors.create.push(uncertain()); });
    await fireEvent.press(await screen.findByRole('button', { name: 'Autorizar retirada por terceiro' }));
    await fireEvent.changeText(screen.getByLabelText('Nome de quem vai retirar'), 'Terceiro Fictício');
    await fireEvent.press(screen.getByRole('button', { name: 'Gerar código' }));
    const again = await screen.findByRole('button', { name: 'Tentar confirmar a mesma autorização' });
    expect(screen.getByLabelText('Nome de quem vai retirar').props.editable).toBe(false);
    await fireEvent.press(again);
    expect(await screen.findByText(/o código só aparece na primeira resposta/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]!.key).toBe(fake.state.creates[0]!.key);
    expect(fake.state.creates[1]!.body).toEqual(fake.state.creates[0]!.body);
    expect(screen.queryByText('12345678')).toBeNull();
  });

  it('sem a chave do código o servidor recusa com certeza: mostra o motivo sem travar', async () => {
    const fake = await mount((f) => { f.state.errors.create.push(new ApiError(503, 'DELEGATION_UNAVAILABLE',
      'A retirada por código ainda não está disponível neste condomínio.', true)); });
    await fireEvent.press(await screen.findByRole('button', { name: 'Autorizar retirada por terceiro' }));
    await fireEvent.changeText(screen.getByLabelText('Nome de quem vai retirar'), 'Terceiro Fictício');
    await fireEvent.press(screen.getByRole('button', { name: 'Gerar código' }));
    expect(await screen.findByText('A retirada por código ainda não está disponível neste condomínio.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Gerar código' })).not.toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Gerar código' }));
    expect(await screen.findByText('12345678')).toBeTruthy();
    expect(fake.state.creates[1]!.key).not.toBe(fake.state.creates[0]!.key);
  });

  it('autorização em vigor: nova autorização avisa a substituição; cancelar confirma a autorização exibida', async () => {
    const fake = await mount((f) => { f.state.current = activeDelegation; });
    expect(await screen.findByText(/Autorização em vigor: Terceiro Fictício · documento final 12AB · até .* · 4 tentativas restantes/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Gerar nova autorização' }));
    expect(screen.getByText(/A nova autorização substitui a atual/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Voltar' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Cancelar autorização' }));
    expect(screen.getByText(/Cancelar a autorização de Terceiro Fictício\?/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Manter' }));
    expect(fake.state.revokes).toHaveLength(0);
    await fireEvent.press(screen.getByRole('button', { name: 'Cancelar autorização' }));
    // A consulta muda enquanto a confirmação está aberta: o cancelamento continua valendo para a autorização exibida.
    fake.state.current = { ...activeDelegation, delegationId: '42000000-0000-4000-8000-000000000009', version: 7 };
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar cancelamento' }));
    expect(await screen.findByText('Autorização de Terceiro Fictício cancelada. O código deixou de valer.')).toBeTruthy();
    expect(fake.state.revokes).toEqual([{ delegationId: activeDelegation.delegationId, expectedVersion: 1 }]);
  });

  it('cancelamento recusado mostra o motivo; falha ao consultar a autorização oferece nova consulta', async () => {
    const changed = new ApiError(409, 'DELEGATION_CHANGED', 'A autorização foi alterada. Atualize a encomenda.');
    const fake = await mount((f) => { f.state.current = activeDelegation; f.state.errors.revoke.push(changed); });
    await fireEvent.press(await screen.findByRole('button', { name: 'Cancelar autorização' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar cancelamento' }));
    expect(await screen.findByText('A autorização foi alterada. Atualize a encomenda.')).toBeTruthy();
    await cleanup();
    const failure = () => new ApiError(0, 'NETWORK_UNAVAILABLE', 'Não foi possível conectar ao sistema. Confira sua conexão e tente novamente.');
    const other = await mount((f) => { f.state.errors.delegation.push(failure(), failure()); });
    const retry = await screen.findByRole('button', { name: 'Consultar autorização de novo' }, { timeout: 4000 });
    other.state.current = activeDelegation;
    await fireEvent.press(retry);
    expect(await screen.findByText(/Autorização em vigor/)).toBeTruthy();
    expect(fake.state.revokes).toHaveLength(1);
  }, 10000);

  it('autorização incerta continua travada depois de trocar de vínculo e voltar: repete a mesma chave', async () => {
    const pending: DelegationPendingStore = new Map();
    const fake = await mount((f) => { f.state.errors.create.push(uncertain()); }, pending);
    await fireEvent.press(await screen.findByRole('button', { name: 'Autorizar retirada por terceiro' }));
    await fireEvent.changeText(screen.getByLabelText('Nome de quem vai retirar'), 'Terceiro Fictício');
    await fireEvent.press(screen.getByRole('button', { name: 'Gerar código' }));
    expect(await screen.findByRole('button', { name: 'Tentar confirmar a mesma autorização' })).toBeTruthy();
    await cleanup();
    await mount(undefined, pending, fake);
    await fireEvent.press(await screen.findByRole('button', { name: 'Tentar confirmar a mesma autorização' }));
    expect(await screen.findByText(/o código só aparece na primeira resposta/)).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]!.key).toBe(fake.state.creates[0]!.key);
    expect(fake.state.creates[1]!.body).toEqual(fake.state.creates[0]!.body);
    expect(pending.size).toBe(0);
  });
});
