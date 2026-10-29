import { CHUNK, createVault, safeKey } from '../src/session/vault';
import { ensureFreshInstall } from '../src/session/install';
import { memoryStore } from '../test-support/fakes';


describe('cofre da sessão', () => {
  it('divide valores grandes abaixo do limite do armazenamento seguro e devolve o mesmo valor', async () => {
    const store = memoryStore();
    const vault = createVault(store);
    const session = JSON.stringify({ access_token: 'á'.repeat(CHUNK * 3 + 7), refresh_token: 'r'.repeat(100) });
    await vault.setItem('cp-auth', session);
    expect(await vault.getItem('cp-auth')).toBe(session);
    await vault.setItem('cp-auth', 'curto');
    expect(await vault.getItem('cp-auth')).toBe('curto');
    expect([...store.items.keys()].filter((key) => key.startsWith(safeKey('cp-auth')) && /\.\d+$/.test(key))).toHaveLength(1);
  });
  it('parte faltando não devolve sessão pela metade', async () => {
    const store = memoryStore();
    const vault = createVault(store);
    await vault.setItem('cp-auth', 'x'.repeat(CHUNK * 2));
    store.items.delete(`${safeKey('cp-auth')}.1`);
    expect(await vault.getItem('cp-auth')).toBeNull();
  });
  it('apagar tudo remove todas as chaves gravadas, inclusive as de nome desconhecido', async () => {
    const store = memoryStore();
    const vault = createVault(store);
    await vault.setItem('cp-auth', 'sessao');
    await vault.setItem('cp-auth-code-verifier', 'verificador');
    await vault.setItem('cp-auth-user', '{"id":"x"}');
    await vault.removeItem('cp-auth-user');
    expect(await vault.getItem('cp-auth-user')).toBeNull();
    await vault.wipe();
    expect(store.items.size).toBe(0);
  });
  it('instalação nova apaga sessão que o Keychain guardou da instalação anterior', async () => {
    const store = memoryStore();
    const vault = createVault(store);
    await vault.setItem('cp-auth', 'sessao-de-outra-instalacao');
    let marked = false;
    const marker = { exists: () => marked, create: () => { marked = true; } };
    expect(await ensureFreshInstall(marker, vault)).toBe('fresh');
    expect(store.items.size).toBe(0);
    await vault.setItem('cp-auth', 'sessao-atual');
    expect(await ensureFreshInstall(marker, vault)).toBe('existing');
    expect(await vault.getItem('cp-auth')).toBe('sessao-atual');
  });
  it('gravação interrompida antes do contador não deixa fragmento depois de apagar tudo', async () => {
    const store = memoryStore();
    const vault = createVault(store);
    await vault.setItem('cp-auth', 'x'.repeat(CHUNK * 3));
    const original = store.setItemAsync;
    store.setItemAsync = async (key, value) => { if (key.endsWith('.n')) throw new Error('processo encerrado'); return original(key, value); };
    await expect(vault.setItem('cp-auth', 'refresh-token-ficticio-' + 'y'.repeat(CHUNK * 4))).rejects.toThrow('processo encerrado');
    store.setItemAsync = original;
    expect([...store.items.values()].some((value) => value.includes('refresh-token-ficticio'))).toBe(true);
    await vault.wipe();
    expect(store.items.size).toBe(0);
  });
});
