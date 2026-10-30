import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import { ContractError } from '../src/api/reservations';
import { arrival, normalizeDocument, permit, permitLink, type Arrival, type Permit } from '../src/api/visits';
import { Visits, type PermitPendingStore } from '../src/ui/Visits';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const ARRIVAL = '47000000-0000-4000-8000-000000000001';
const PERMIT = '48000000-0000-4000-8000-000000000001';
const TOKEN = 'A'.repeat(43);
const now = () => new Date('2030-01-07T11:10:00Z'); // 08:10 em São Paulo
const waiting = (overrides: Partial<Arrival> = {}): Arrival => ({ arrivalId: ARRIVAL, unitId: UNIT, visitorName: 'Pessoa Visitante', documentLast4: '12AB',
  permitId: null, state: 'WAITING', expiresAt: '2030-01-07T11:20:00Z', decidedAt: null, decisionSource: null, decisionNote: null, decidedByMe: false,
  version: 0, createdAt: '2030-01-07T11:05:00Z', ...overrides });
const invite = (overrides: Partial<Permit> = {}): Permit => ({ permitId: PERMIT, unitId: UNIT, visitorName: 'Visita Prevista', documentLast4: null,
  purpose: 'Jantar', validFrom: '2030-01-07T20:00:00Z', validUntil: '2030-01-07T23:00:00Z', state: 'SCHEDULED', createdByMe: true, revokedAt: null,
  revokeReason: null, version: 0, shareLinkActive: false, reservationId: null, ruleId: null, ...overrides });

function fakeApi() {
  const state = { arrivals: [waiting()] as Arrival[], permits: [invite()] as Permit[], decisions: [] as string[], creates: [] as { body: Record<string, unknown>; key: string }[],
    revokes: [] as unknown[], links: 0, linkRevokes: 0, createError: null as ApiError | null, decideError: null as ApiError | null,
    arrivalsError: null as ApiError | null, permitsError: null as ApiError | null, keys: new Map<string, Permit>() };
  const api = {
    unitArrivals: jest.fn(async () => { if (state.arrivalsError) throw state.arrivalsError; return { arrivals: state.arrivals, truncated: false }; }),
    unitPermits: jest.fn(async () => { if (state.permitsError) throw state.permitsError; return { permits: state.permits, truncated: false }; }),
    unitRules: jest.fn(async () => ({ rules: [], truncated: false })),
    decideArrival: jest.fn(async (_t: string, _u: string, id: string, decision: 'APPROVE' | 'DENY') => {
      state.decisions.push(decision);
      if (state.decideError) throw state.decideError;
      const decided = waiting({ arrivalId: id, state: decision === 'APPROVE' ? 'APPROVED' : 'DENIED', decidedAt: '2030-01-07T11:11:00Z',
        decisionSource: 'APP', decidedByMe: true, version: 1 });
      state.arrivals = [decided];
      return decided;
    }),
    createPermit: jest.fn(async (_t: string, unitId: string, body: Record<string, unknown>, key: string) => {
      state.creates.push({ body, key });
      const previous = state.keys.get(key);
      if (previous) return { permit: previous, replayed: true };
      const created = invite({ permitId: '48000000-0000-4000-8000-000000000002', unitId, visitorName: body.visitorName as string,
        documentLast4: body.documentLast4 as string | null, purpose: body.purpose as string | null, validFrom: body.validFrom as string, validUntil: body.validUntil as string });
      state.keys.set(key, created); state.permits = [...state.permits, created];
      if (state.createError) { const error = state.createError; state.createError = null; throw error; }
      return { permit: created, replayed: false };
    }),
    revokePermit: jest.fn(async (_t: string, _u: string, id: string, body: { reason: string; expectedVersion: number }) => {
      state.revokes.push(body);
      const revoked = invite({ permitId: id, state: 'REVOKED', revokedAt: '2030-01-07T11:12:00Z', revokeReason: body.reason, version: 1 });
      state.permits = state.permits.map((item) => item.permitId === id ? revoked : item);
      return revoked;
    }),
    shareLink: jest.fn(async () => { state.links++; state.permits = state.permits.map((item) => ({ ...item, shareLinkActive: true }));
      return { token: TOKEN, validUntil: '2030-01-07T23:00:00Z' }; }),
    revokeLink: jest.fn(async (_t: string, _u: string, id: string) => { state.linkRevokes++;
      state.permits = state.permits.map((item) => ({ ...item, shareLinkActive: false })); return invite({ permitId: id }); }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(fake = fakeApi(), options: { pending?: PermitPendingStore; share?: (message: string) => Promise<void>; webOrigin?: string | null } = {}) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Visits api={fake.api} accountId={ACCOUNT} now={now} newKey={() => `test:visit-${String(++counter).padStart(12, '0')}`} pending={options.pending}
      webOrigin={options.webOrigin === undefined ? 'https://condpulse.example.invalid' : options.webOrigin} share={options.share}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role: 'RESIDENT' }} />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));

describe('contrato de visitas no celular', () => {
  it('recusa convite e chegada de outra unidade ou incoerentes, e link fora do formato', () => {
    expect(permit(invite(), UNIT).state).toBe('SCHEDULED');
    expect(() => permit(invite(), '20000000-0000-4000-8000-000000000009')).toThrow(ContractError);
    expect(() => permit(invite({ state: 'REVOKED' }), UNIT)).toThrow(ContractError);
    expect(() => permit(invite({ validUntil: '2030-01-07T19:00:00Z' }), UNIT)).toThrow(ContractError);
    expect(arrival(waiting(), UNIT).state).toBe('WAITING');
    expect(() => arrival(waiting({ state: 'APPROVED' }), UNIT)).toThrow(ContractError);
    expect(() => arrival(waiting({ decidedByMe: true }), UNIT)).toThrow(ContractError);
    expect(() => arrival(waiting({ state: 'APPROVED', decidedAt: '2030-01-07T11:11:00Z', decisionSource: 'INTERCOM', version: 1 }), UNIT)).toThrow(ContractError);
    expect(permitLink({ token: TOKEN, validUntil: '2030-01-07T23:00:00Z' }).token).toBe(TOKEN);
    expect(() => permitLink({ token: 'curto', validUntil: '2030-01-07T23:00:00Z' })).toThrow(ContractError);
    expect(() => permitLink({ token: TOKEN, validUntil: '2030-01-07T23:00:00Z', unitId: UNIT })).toThrow(ContractError);
    expect(normalizeDocument(' 12ab ')).toBe('12AB');
    expect(normalizeDocument('')).toBeNull();
    expect(normalizeDocument('12345')).toBe('');
  });
  it('rotas da unidade e da decisão', async () => {
    const calls: string[] = [];
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never,
      fetch: async (url, init) => { calls.push(`${init?.method ?? 'GET'} ${String(url)}`);
        const body = String(url).endsWith('/decision') ? waiting({ state: 'DENIED', decidedAt: '2030-01-07T11:11:00Z', decisionSource: 'APP', decidedByMe: true, version: 1 })
          : String(url).endsWith('/arrivals') ? { arrivals: [], truncated: false } : { permits: [], truncated: false };
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }); } });
    await api.unitPermits(TENANT, UNIT);
    await api.unitArrivals(TENANT, UNIT);
    expect((await api.decideArrival(TENANT, UNIT, ARRIVAL, 'DENY')).state).toBe('DENIED');
    expect(calls).toEqual([`GET https://api.example.invalid/v1/tenants/${TENANT}/units/${UNIT}/permits`,
      `GET https://api.example.invalid/v1/tenants/${TENANT}/units/${UNIT}/arrivals`,
      `POST https://api.example.invalid/v1/tenants/${TENANT}/arrivals/${ARRIVAL}/decision`]);
  });
});

describe('acessos da unidade no celular', () => {
  it('autoriza a chegada uma vez e mostra quem decidiu; prazo vencido não registra a resposta', async () => {
    const fake = await mount();
    expect(await screen.findByText('Pessoa Visitante · Aguardando resposta')).toBeTruthy();
    expect(screen.getByText(/Sem resposta no prazo nada é liberado/)).toBeTruthy();
    await press('Autorizar entrada de Pessoa Visitante');
    expect(await screen.findByText('Entrada de Pessoa Visitante autorizada. A portaria confere a pessoa antes de liberar.')).toBeTruthy();
    expect(fake.state.decisions).toEqual(['APPROVE']);
    expect(await screen.findByText('Pessoa Visitante · Entrada autorizada')).toBeTruthy();
    expect(screen.getByText(/Respondida pelo aplicativo por você/)).toBeTruthy();
    fake.state.arrivals = [waiting({ arrivalId: '47000000-0000-4000-8000-000000000002', visitorName: 'Outra Pessoa' })];
    fake.state.decideError = new ApiError(409, 'ARRIVAL_EXPIRED', 'O prazo de resposta terminou.', false);
    await press('Atualizar acessos');
    await press('Recusar entrada de Outra Pessoa');
    expect(await screen.findByText('O prazo de resposta terminou. A sua resposta não foi registrada.')).toBeTruthy();
  }, 15000);

  it('convite valida os campos, grava com chave e trava na mesma chave depois de resultado incerto', async () => {
    const fake = fakeApi();
    fake.state.permits = [];
    await mount(fake);
    await press('Convidar visitante');
    const register = () => screen.getByRole('button', { name: 'Registrar convite' });
    await fireEvent.changeText(screen.getByLabelText('Nome de quem vai chegar'), 'A');
    expect(register()).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Nome de quem vai chegar'), '  Visita   Nova ');
    await fireEvent.changeText(screen.getByLabelText('Final do documento (opcional, 2 a 4 caracteres)'), '12345');
    expect(screen.getByText('Informe só os 2 a 4 últimos caracteres do documento, ou deixe em branco.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Final do documento (opcional, 2 a 4 caracteres)'), '9z');
    await fireEvent.changeText(screen.getByLabelText('Início (AAAA-MM-DD HH:MM)'), '2030-01-07 19:00');
    await fireEvent.changeText(screen.getByLabelText('Fim (AAAA-MM-DD HH:MM)'), '2030-01-07 18:00');
    expect(screen.getByText('O fim precisa ser depois do início.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Fim (AAAA-MM-DD HH:MM)'), '2030-01-16 18:00');
    expect(screen.getByText('O convite vale por até 7 dias.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Fim (AAAA-MM-DD HH:MM)'), '2030-01-07 22:00');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
    await fireEvent.press(register());
    expect(await screen.findByRole('button', { name: 'Tentar confirmar o mesmo convite' })).toBeTruthy();
    expect(screen.getByLabelText('Nome de quem vai chegar').props.editable).toBe(false);
    await press('Tentar confirmar o mesmo convite');
    expect(await screen.findByText('Este convite já estava registrado (agendado).')).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]!.key).toBe(fake.state.creates[0]!.key);
    expect(fake.state.creates[0]!.body).toEqual({ visitorName: 'Visita Nova', documentLast4: '9Z', purpose: null,
      validFrom: '2030-01-07T22:00:00.000Z', validUntil: '2030-01-08T01:00:00.000Z' });
    expect(await screen.findByText('Visita Nova · Agendado')).toBeTruthy();
  }, 15000);

  it('link aparece uma vez, vai pelo compartilhamento do aparelho e pode ser revogado sem revogar o convite', async () => {
    const shared: string[] = [];
    const fake = await mount(fakeApi(), { share: async (message) => { shared.push(message); } });
    await press('Compartilhar link');
    expect(await screen.findByText(`https://condpulse.example.invalid/visita#${TOKEN}`)).toBeTruthy();
    expect(screen.getByText(/Este link aparece só agora/)).toBeTruthy();
    await press('Enviar link');
    expect(shared).toEqual([`Convite para Visita Prevista: https://condpulse.example.invalid/visita#${TOKEN}`]);
    await press('Revogar link');
    expect(await screen.findByText('Link do convite de Visita Prevista revogado. O convite continua valendo.')).toBeTruthy();
    expect(screen.queryByText(`https://condpulse.example.invalid/visita#${TOKEN}`)).toBeNull();
    expect(fake.state.links).toBe(1);
    expect(fake.state.linkRevokes).toBe(1);
  }, 15000);

  it('falha do compartilhamento deixa o link para copiar; sem origem da web não oferece link', async () => {
    await mount(fakeApi(), { share: async () => { throw new Error('indisponível'); } });
    await press('Compartilhar link');
    await press('Enviar link');
    expect(await screen.findByText('Não foi possível abrir o compartilhamento. Selecione o link e copie manualmente.')).toBeTruthy();
    await cleanup();
    await mount(fakeApi(), { webOrigin: null });
    expect(await screen.findByText('Visita Prevista · Agendado')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Compartilhar link' })).toBeNull();
  }, 15000);

  it('revogar exige motivo e vale para a versão exibida', async () => {
    const fake = await mount();
    await press('Revogar convite');
    expect(screen.getByRole('button', { name: 'Confirmar revogação' })).toBeDisabled();
    await press('Voltar');
    expect(fake.state.revokes).toEqual([]);
    await press('Revogar convite');
    await fireEvent.changeText(screen.getByLabelText('Motivo da revogação'), '  Mudança de planos ');
    await press('Confirmar revogação');
    expect(await screen.findByText('Convite de Visita Prevista revogado. A portaria deixa de vê-lo como autorização.')).toBeTruthy();
    expect(fake.state.revokes).toEqual([{ reason: 'Mudança de planos', expectedVersion: 0 }]);
    expect(await screen.findByText('Visita Prevista · Revogado')).toBeTruthy();
    expect(screen.getByText('Nenhum convite em aberto. A portaria vê o nome e o período e confere a pessoa na chegada.')).toBeTruthy();
  }, 15000);

  it('falha na primeira consulta oferece nova tentativa; vínculo sem permissão ou função desligada esconde', async () => {
    const fake = fakeApi();
    fake.state.permitsError = new ApiError(503, 'VISIT_DEPENDENCY_UNAVAILABLE', 'Convites indisponíveis agora.', false);
    await mount(fake);
    expect(await screen.findByText('Convites indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.permitsError = null;
    await press('Tentar consultar convites de novo');
    expect(await screen.findByText('Visita Prevista · Agendado')).toBeTruthy();
    await cleanup();
    const off = fakeApi();
    off.state.arrivalsError = new ApiError(404, 'API_NOT_AVAILABLE', 'Indisponível.', false);
    off.state.permitsError = new ApiError(404, 'API_NOT_AVAILABLE', 'Indisponível.', false);
    await mount(off);
    await waitFor(() => expect(screen.queryByText('Acessos da unidade')).toBeNull());
  }, 15000);
});
