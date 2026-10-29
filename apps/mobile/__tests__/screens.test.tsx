import { cleanup, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { createApi } from '../src/api/client';
import { createVault } from '../src/session/vault';
import { SessionManager } from '../src/session/session';
import { Root } from '../src/ui/Root';
import { fakeAuth, memoryStore } from '../test-support/fakes';

const A = '10000000-0000-4000-8000-000000000001';
const B = '10000000-0000-4000-8000-000000000002';
const membership = (tenantName: string, role: string) => ({ membershipId: `51000000-0000-4000-8000-00000000000${tenantName.length % 10}`,
  tenantId: '00000000-0000-4000-8000-000000000001', tenantName, timeZone: 'America/Sao_Paulo', unitId: null, role });
const identities: Record<string, unknown> = {
  [A]: { accountId: A, memberships: [membership('CONDOMÍNIO DA CONTA A', 'RESIDENT')] },
  [B]: { accountId: B, memberships: [membership('CONDOMÍNIO DA CONTA B', 'MANAGER')] },
};

const clients: ReturnType<typeof newQueryClient>[] = [];
// Sem timers pendentes do cache entre testes (o processo do Jest termina sozinho).
afterEach(async () => {
  await cleanup();
  for (const client of clients.splice(0)) client.clear();
});

async function mount() {
  const store = memoryStore();
  const fake = fakeAuth({
    'a@example.invalid': { id: A, password: 'SYNTHETIC-PASSWORD-A' },
    'b@example.invalid': { id: B, password: 'SYNTHETIC-PASSWORD-B', totp: '123456' },
  });
  // Sem coleta agendada: a limpeza do cache na saída ainda é a da aplicação; o timer de coleta prenderia o Jest.
  const queries = newQueryClient(Infinity);
  clients.push(queries);
  const manager = new SessionManager(fake.auth, createVault(store), () => queries.clear());
  const calls: string[] = [];
  let reject401 = false;
  const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: manager, fetch: async (_url, init) => {
    const token = new Headers(init?.headers).get('Authorization') ?? '';
    calls.push(token);
    if (reject401) return new Response(JSON.stringify({ code: 'AUTHENTICATION_REQUIRED' }), { status: 401, headers: { 'Content-Type': 'application/json' } });
    const account = token.includes(A) ? A : B;
    return new Response(JSON.stringify(identities[account]), { status: 200, headers: { 'Content-Type': 'application/json' } });
  } });
  await manager.start();
  await render(<QueryClientProvider client={queries}><Root manager={manager} api={api} /></QueryClientProvider>);
  return { store, fake, manager, calls, queries, set401: (value: boolean) => { reject401 = value; } };
}
async function signIn(email: string, password: string) {
  await fireEvent.changeText(screen.getByLabelText('E-mail'), email);
  await fireEvent.changeText(screen.getByLabelText('Senha'), password);
  await fireEvent.press(screen.getByRole('button', { name: 'Entrar' }));
}

describe('telas da sessão', () => {
  it('troca de usuário no mesmo aparelho não mostra dados da conta anterior', async () => {
    const { store } = await mount();
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();
    await signIn('a@example.invalid', 'SYNTHETIC-PASSWORD-A');
    expect(await screen.findByText('CONDOMÍNIO DA CONTA A')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'CONDOMÍNIO DA CONTA A, Morador' }));
    expect(await screen.findByText(/Contexto: CONDOMÍNIO DA CONTA A/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Sair' }));
    expect(await screen.findByText('Entrar no CondPulse')).toBeTruthy();
    expect(screen.getByLabelText('Senha').props.value).toBe('');
    expect(store.items.size).toBe(0);
    await signIn('b@example.invalid', 'SYNTHETIC-PASSWORD-B');
    expect(await screen.findByText('Segundo fator')).toBeTruthy();
    expect(screen.queryByText('CONDOMÍNIO DA CONTA A')).toBeNull();
    await fireEvent.changeText(screen.getByLabelText('Código do autenticador'), '000000');
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar' }));
    expect(await screen.findByText('Código inválido ou expirado. Confira o aplicativo autenticador.')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Código do autenticador'), '12-34-56');
    await fireEvent.press(screen.getByRole('button', { name: 'Confirmar' }));
    expect(await screen.findByText('CONDOMÍNIO DA CONTA B')).toBeTruthy();
    expect(screen.queryByText('CONDOMÍNIO DA CONTA A')).toBeNull();
    expect(screen.queryByText(/Contexto:/)).toBeNull();
  });

  it('credencial recusada mostra erro sem sair da tela de acesso', async () => {
    await mount();
    await signIn('a@example.invalid', 'errada');
    expect(await screen.findByText('Não foi possível validar seu e-mail e sua senha.')).toBeTruthy();
    expect(screen.getByText('Entrar no CondPulse')).toBeTruthy();
  });

  it('token recusado pela API e renovação negada voltam ao acesso com aviso e sem dados', async () => {
    const { fake, set401, store } = await mount();
    set401(true);
    fake.state.refreshRejected = true;
    await signIn('a@example.invalid', 'SYNTHETIC-PASSWORD-A');
    expect(await screen.findByText('Sua sessão terminou. Entre novamente para continuar.')).toBeTruthy();
    expect(screen.queryByText('CONDOMÍNIO DA CONTA A')).toBeNull();
    expect(store.items.size).toBe(0);
  });

  it('erro da API mostra mensagem e permite tentar de novo', async () => {
    const { set401, fake } = await mount();
    await signIn('a@example.invalid', 'SYNTHETIC-PASSWORD-A');
    expect(await screen.findByText('CONDOMÍNIO DA CONTA A')).toBeTruthy();
    // Segundo fator na tela: sair também funciona dali.
    await fireEvent.press(screen.getByRole('button', { name: 'Sair' }));
    await signIn('b@example.invalid', 'SYNTHETIC-PASSWORD-B');
    expect(await screen.findByText('Segundo fator')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Sair' }));
    expect(await screen.findByText('Entrar no CondPulse')).toBeTruthy();
    expect(fake.state.signOuts).toBe(2);
    set401(false);
  });
});
