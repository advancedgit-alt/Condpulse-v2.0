import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, type CondPulseApi, type Membership } from '../src/api/client';
import { ContractError } from '../src/api/reservations';
import { visitRule, type VisitRule } from '../src/api/visits';
import { VisitRules, type RulePendingStore } from '../src/ui/VisitRules';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const RULE = '49000000-0000-4000-8000-000000000001';
const now = () => new Date('2030-01-07T11:10:00Z'); // segunda, 08:10 em São Paulo
const rule = (overrides: Partial<VisitRule> = {}): VisitRule => ({ ruleId: RULE, unitId: UNIT, visitorName: 'Diarista Fictícia', documentLast4: '77',
  purpose: 'Limpeza', weekDays: [1, 4], startTime: '08:00', endTime: '17:00', validFrom: '2030-01-07', validUntil: '2030-03-07', state: 'ACTIVE',
  createdByMe: false, revokedAt: null, revokeReason: null, version: 0, nextWindowFrom: '2030-01-10T11:00:00Z', nextWindowUntil: '2030-01-10T20:00:00Z', ...overrides });

function fakeApi() {
  const state = { rules: [rule()] as VisitRule[], creates: [] as { body: Record<string, unknown>; key: string }[], revokes: [] as unknown[],
    createError: null as ApiError | null, revokeError: null as ApiError | null, listError: null as ApiError | null, keys: new Map<string, VisitRule>() };
  const api = {
    unitRules: jest.fn(async () => { if (state.listError) throw state.listError; return { rules: state.rules, truncated: false }; }),
    createRule: jest.fn(async (_t: string, unitId: string, body: Record<string, unknown>, key: string) => {
      state.creates.push({ body, key });
      const previous = state.keys.get(key);
      if (previous) return { rule: previous, replayed: true };
      const created = rule({ ruleId: '49000000-0000-4000-8000-000000000002', unitId, visitorName: body.visitorName as string, weekDays: body.weekDays as number[],
        startTime: body.startTime as string, endTime: body.endTime as string, validFrom: body.validFrom as string, validUntil: body.validUntil as string, createdByMe: true });
      state.keys.set(key, created); state.rules = [...state.rules, created];
      if (state.createError) { const error = state.createError; state.createError = null; throw error; }
      return { rule: created, replayed: false };
    }),
    revokeRule: jest.fn(async (_t: string, _u: string, id: string, body: { reason: string; expectedVersion: number }) => {
      state.revokes.push(body);
      if (state.revokeError) throw state.revokeError;
      const revoked = rule({ ruleId: id, state: 'REVOKED', revokedAt: '2030-01-07T11:12:00Z', revokeReason: body.reason, version: 1, nextWindowFrom: null, nextWindowUntil: null });
      state.rules = state.rules.map((item) => item.ruleId === id ? revoked : item);
      return revoked;
    }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
let counter = 0;
async function mount(fake = fakeApi(), role: Membership['role'] = 'RESIDENT', pending?: RulePendingStore) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <VisitRules api={fake.api} accountId={ACCOUNT} now={now} newKey={() => `test:rule-${String(++counter).padStart(12, '0')}`} pending={pending}
      membership={{ membershipId: '51000000-0000-4000-8000-000000000001', tenantId: TENANT, tenantName: 'CONDOMÍNIO FICTÍCIO',
        timeZone: 'America/Sao_Paulo', unitId: UNIT, role }} />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));

describe('prestador recorrente no celular', () => {
  it('contrato recusa regra de outra unidade, dias repetidos e revogada com próxima janela', () => {
    expect(visitRule(rule(), UNIT).weekDays).toEqual([1, 4]);
    expect(() => visitRule(rule(), '20000000-0000-4000-8000-000000000009')).toThrow(ContractError);
    expect(() => visitRule(rule({ weekDays: [1, 1] }), UNIT)).toThrow(ContractError);
    expect(() => visitRule(rule({ startTime: '18:00' }), UNIT)).toThrow(ContractError);
    expect(() => visitRule(rule({ state: 'REVOKED', revokedAt: '2030-01-07T11:12:00Z', revokeReason: 'x' }), UNIT)).toThrow(ContractError);
  });

  it('mostra a próxima janela e cadastra com validação; resultado incerto repete a mesma chave', async () => {
    const fake = await mount();
    expect(await screen.findByText('Diarista Fictícia · Ativa')).toBeTruthy();
    expect(screen.getByText('segunda, quinta, das 08:00 às 17:00 · de 07/01/2030 até 07/03/2030 · documento final 77')).toBeTruthy();
    expect(screen.getByText(/Próxima janela:/)).toBeTruthy();
    await press('Cadastrar prestador');
    const submit = () => screen.getByRole('button', { name: 'Cadastrar prestador recorrente' });
    await fireEvent.changeText(screen.getByLabelText('Nome do prestador'), '  Cuidador   Fictício ');
    expect(submit()).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox', { name: 'terça' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'sexta' }));
    await fireEvent.changeText(screen.getByLabelText('Até (HH:MM)'), '07:00');
    expect(screen.getByText('O fim precisa ser depois do início, no mesmo dia.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Até (HH:MM)'), '12:00');
    await fireEvent.changeText(screen.getByLabelText('Primeiro dia (AAAA-MM-DD)'), '2030-01-06');
    expect(screen.getByText('A regra começa hoje ou depois.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Primeiro dia (AAAA-MM-DD)'), '2030-01-08');
    await fireEvent.changeText(screen.getByLabelText('Último dia (AAAA-MM-DD)'), '2030-12-31');
    expect(screen.getByText('A regra vale por até 180 dias.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Último dia (AAAA-MM-DD)'), '2030-02-08');
    fake.state.createError = new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', 'Não foi possível confirmar o resultado.', true);
    await fireEvent.press(submit());
    expect(await screen.findByRole('button', { name: 'Tentar confirmar a mesma regra' })).toBeTruthy();
    expect(screen.getByLabelText('Nome do prestador').props.editable).toBe(false);
    await press('Tentar confirmar a mesma regra');
    expect(await screen.findByText('Esta regra já estava cadastrada: Cuidador Fictício, terça, sexta, das 08:00 às 12:00.')).toBeTruthy();
    expect(fake.state.creates).toHaveLength(2);
    expect(fake.state.creates[1]!.key).toBe(fake.state.creates[0]!.key);
    expect(fake.state.creates[0]!.body).toEqual({ visitorName: 'Cuidador Fictício', documentLast4: null, purpose: null, weekDays: [2, 5],
      startTime: '08:00', endTime: '12:00', validFrom: '2030-01-08', validUntil: '2030-02-08' });
  }, 20000);

  it('revogar exige motivo; conflito fecha sem alterar; dependente só revoga a própria regra', async () => {
    const fake = await mount();
    await press('Revogar regra de Diarista Fictícia');
    expect(screen.getByRole('button', { name: 'Confirmar revogação da regra' })).toBeDisabled();
    await press('Manter regra');
    expect(fake.state.revokes).toEqual([]);
    await press('Revogar regra de Diarista Fictícia');
    await fireEvent.changeText(screen.getByLabelText('Motivo da revogação da regra'), ' Fim do contrato ');
    fake.state.revokeError = new ApiError(409, 'VISIT_RULE_CHANGED', 'A regra mudou desde a leitura.', false);
    await press('Confirmar revogação da regra');
    expect(await screen.findByText('A regra mudou desde a leitura. Nada foi alterado.')).toBeTruthy();
    fake.state.revokeError = null;
    await press('Revogar regra de Diarista Fictícia');
    await fireEvent.changeText(screen.getByLabelText('Motivo da revogação da regra'), ' Fim do contrato ');
    await press('Confirmar revogação da regra');
    expect(await screen.findByText('Regra de Diarista Fictícia revogada. As próximas janelas foram canceladas.')).toBeTruthy();
    expect(fake.state.revokes).toEqual([{ reason: 'Fim do contrato', expectedVersion: 0 }, { reason: 'Fim do contrato', expectedVersion: 0 }]);
    expect(await screen.findByText('Diarista Fictícia · Revogada')).toBeTruthy();
    await cleanup();
    await mount(fakeApi(), 'DEPENDENT');
    expect(await screen.findByText('Diarista Fictícia · Ativa')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Revogar regra de Diarista Fictícia' })).toBeNull();
  }, 20000);

  it('falha na primeira consulta oferece nova tentativa; vínculo sem permissão esconde', async () => {
    const fake = fakeApi();
    fake.state.listError = new ApiError(503, 'VISIT_DEPENDENCY_UNAVAILABLE', 'Prestadores indisponíveis agora.', false);
    await mount(fake);
    expect(await screen.findByText('Prestadores indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    fake.state.listError = null;
    await press('Tentar consultar prestadores de novo');
    expect(await screen.findByText('Diarista Fictícia · Ativa')).toBeTruthy();
    await cleanup();
    const denied = fakeApi();
    denied.state.listError = new ApiError(403, 'UNIT_ACCESS_DENIED', 'Sem acesso.', false);
    await mount(denied);
    await waitFor(() => expect(screen.queryByText('Prestadores recorrentes')).toBeNull());
  }, 20000);
});
