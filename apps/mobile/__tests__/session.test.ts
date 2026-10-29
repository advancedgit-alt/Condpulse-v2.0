import { createVault } from '../src/session/vault';
import { SessionManager } from '../src/session/session';
import { fakeAuth, memoryStore } from '../test-support/fakes';

const A = '10000000-0000-4000-8000-000000000001';
const B = '10000000-0000-4000-8000-000000000002';
const flush = () => new Promise((resolve) => setTimeout(resolve, 5));

function setup() {
  const store = memoryStore();
  const vault = createVault(store);
  const fake = fakeAuth({
    'morador@example.invalid': { id: A, password: 'SYNTHETIC-PASSWORD-A' },
    'gestao@example.invalid': { id: B, password: 'SYNTHETIC-PASSWORD-B', totp: '123456' },
  });
  const clears = { count: 0 };
  const manager = new SessionManager(fake.auth, vault, () => { clears.count += 1; });
  return { store, vault, fake, clears, manager };
}

describe('sessão nativa', () => {
  it('entra, entrega token só para sessão liberada e sai apagando cofre e dados', async () => {
    const { store, vault, fake, clears, manager } = setup();
    await manager.start();
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: null });
    await vault.setItem('cp-auth', 'sessao-gravada-pela-biblioteca');
    await manager.signIn('  Morador@Example.invalid ', 'SYNTHETIC-PASSWORD-A');
    expect(manager.getState()).toEqual({ status: 'signedIn', accountId: A, assurance: 'AAL1' });
    expect(await manager.accessToken()).toMatch(/^synthetic-access-/);
    const before = clears.count;
    await manager.signOut();
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: 'signedOut' });
    expect(store.items.size).toBe(0);
    expect(clears.count).toBeGreaterThan(before);
    expect(fake.state.signOuts).toBe(1);
    expect(await manager.accessToken()).toBeNull();
  });

  it('credencial errada, e-mail inválido e Auth fora do ar viram mensagens conhecidas', async () => {
    const { manager } = setup();
    await manager.start();
    await expect(manager.signIn('morador@example.invalid', 'errada')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    await expect(manager.signIn('nao-e-email', 'x')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    const down = setup();
    down.fake.auth.signInWithPassword = async () => { throw new TypeError('rede'); };
    await down.manager.start();
    await expect(down.manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A')).rejects.toMatchObject({ code: 'AUTH_UNAVAILABLE' });
    expect(manager.getState().status).toBe('signedOut');
  });

  it('conta com segundo fator só recebe token depois do código certo', async () => {
    const { manager } = setup();
    await manager.start();
    await manager.signIn('gestao@example.invalid', 'SYNTHETIC-PASSWORD-B');
    expect(manager.getState()).toEqual({ status: 'mfaRequired', accountId: B });
    expect(await manager.accessToken()).toBeNull();
    await expect(manager.verifyMfa('000000')).rejects.toMatchObject({ code: 'MFA_INVALID' });
    await expect(manager.verifyMfa('12a456')).rejects.toMatchObject({ code: 'MFA_INVALID' });
    await manager.verifyMfa('123456');
    expect(manager.getState()).toEqual({ status: 'signedIn', accountId: B, assurance: 'AAL2' });
  });

  it('troca de conta limpa os dados da conta anterior antes de liberar a nova', async () => {
    const { clears, manager } = setup();
    await manager.start();
    await manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A');
    const afterFirst = clears.count;
    // Nova entrada com outra conta sem sair (ex.: sessão substituída): o cache é limpo de novo.
    await manager.signIn('gestao@example.invalid', 'SYNTHETIC-PASSWORD-B');
    expect(clears.count).toBeGreaterThanOrEqual(afterFirst + 1);
    expect(manager.getState()).toMatchObject({ status: 'mfaRequired', accountId: B });
  });

  it('renovação recusada (token expirado ou revogado) encerra a sessão e apaga tudo', async () => {
    const { store, vault, fake, manager } = setup();
    await manager.start();
    await manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A');
    await vault.setItem('cp-auth', 'sessao');
    fake.state.refreshRejected = true;
    expect(await manager.refresh()).toBeNull();
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: 'expired' });
    expect(store.items.size).toBe(0);
  });

  it('aviso de saída vindo da biblioteca (renovação automática recusada) também encerra', async () => {
    const { store, vault, fake, manager } = setup();
    await manager.start();
    await manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A');
    await vault.setItem('cp-auth', 'sessao');
    fake.emit('SIGNED_OUT');
    await flush();
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: 'expired' });
    expect(store.items.size).toBe(0);
  });

  it('sessão gravada que o Auth não aceita mais na abertura do app não é usada e é apagada', async () => {
    const { store, vault, fake, manager } = setup();
    await vault.setItem('cp-auth', 'sessao-antiga');
    fake.state.getSessionError = true;
    await manager.start();
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: null });
    expect(store.items.size).toBe(0);
  });

  it('sair sem rede ainda apaga o aparelho', async () => {
    const { store, vault, fake, manager } = setup();
    await manager.start();
    await manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A');
    await vault.setItem('cp-auth', 'sessao');
    fake.auth.signOut = async () => { throw new TypeError('rede'); };
    await manager.signOut();
    expect(store.items.size).toBe(0);
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: 'signedOut' });
  });

  it('saída bloqueia a sessão na hora, mesmo com a revogação remota lenta, e o novo login espera a limpeza', async () => {
    const { store, vault, fake, manager } = setup();
    await manager.start();
    await manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A');
    await vault.setItem('cp-auth', 'sessao');
    let release!: () => void;
    fake.auth.signOut = () => new Promise((resolve) => { release = () => resolve({ error: null }); });
    const leaving = manager.signOut();
    expect(manager.getState()).toEqual({ status: 'signedOut', reason: 'signedOut' });
    expect(await manager.accessToken()).toBeNull();
    expect(await manager.refresh()).toBeNull();
    const next = manager.signIn('gestao@example.invalid', 'SYNTHETIC-PASSWORD-B');
    await flush();
    expect(manager.getState().status).toBe('signedOut');
    release();
    await leaving;
    expect(store.items.size).toBe(0);
    await next;
    expect(manager.getState()).toMatchObject({ status: 'mfaRequired', accountId: B });
  });

  it('revogação remota que não responde não impede apagar o aparelho', async () => {
    jest.useFakeTimers();
    try {
      const { store, vault, fake, manager } = setup();
      await manager.start();
      await manager.signIn('morador@example.invalid', 'SYNTHETIC-PASSWORD-A');
      await vault.setItem('cp-auth', 'sessao');
      fake.auth.signOut = () => new Promise(() => undefined);
      const leaving = manager.signOut();
      await jest.advanceTimersByTimeAsync(5_000);
      await leaving;
      expect(store.items.size).toBe(0);
    } finally { jest.useRealTimers(); }
  });
});
