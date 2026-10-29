import type { SecureKeyValue } from '../src/session/vault';
import type { AuthPort, AuthSession } from '../src/session/session';

/** Armazenamento seguro em memória com as mesmas restrições de chave e tamanho do SecureStore. */
export function memoryStore(): SecureKeyValue & { items: Map<string, string> } {
  const items = new Map<string, string>();
  return {
    items,
    getItemAsync: async (key) => items.get(key) ?? null,
    setItemAsync: async (key, value) => {
      if (!/^[A-Za-z0-9._-]+$/.test(key)) throw new Error(`chave inválida: ${key}`);
      if (new TextEncoder().encode(value).length > 2048) throw new Error('valor acima do limite do SecureStore');
      items.set(key, value);
    },
    deleteItemAsync: async (key) => { items.delete(key); },
  };
}

type Listener = (event: string, session: AuthSession | null) => void;
/** Auth do Supabase em memória: contas fictícias, segundo fator opcional e renovação que pode ser recusada. */
export function fakeAuth(accounts: Record<string, { id: string; password: string; totp?: string }>) {
  const listeners = new Set<Listener>();
  const state = { session: null as AuthSession | null, level: 'aal1' as 'aal1' | 'aal2', factor: null as string | null,
    refreshRejected: false, getSessionError: false, signOuts: 0, issued: 0, revoked: [] as string[],
    revokeImpl: null as null | ((token: string, signal: AbortSignal) => Promise<void>) };
  const issue = (id: string) => { state.issued += 1; state.session = { access_token: `synthetic-access-${id}-${state.issued}`, user: { id } }; return state.session; };
  const auth: AuthPort = {
    getSession: async () => state.getSessionError ? { data: { session: null }, error: { status: 400, code: 'refresh_token_not_found' } } : { data: { session: state.session }, error: null },
    signInWithPassword: async ({ email, password }) => {
      const account = accounts[email];
      if (!account || account.password !== password) return { data: { session: null }, error: { status: 400, code: 'invalid_credentials' } };
      state.level = 'aal1'; state.factor = account.totp ?? null;
      return { data: { session: issue(account.id) }, error: null };
    },
    refreshSession: async () => {
      if (state.refreshRejected || !state.session) {
        state.session = null;
        for (const listener of listeners) listener('SIGNED_OUT', null);
        return { data: { session: null }, error: { status: 400, code: 'refresh_token_not_found' } };
      }
      return { data: { session: issue(state.session.user.id) }, error: null };
    },
    onAuthStateChange: (callback) => { listeners.add(callback); return { data: { subscription: { unsubscribe: () => { listeners.delete(callback); } } } }; },
    mfa: {
      getAuthenticatorAssuranceLevel: async () => ({ data: { currentLevel: state.level, nextLevel: state.factor ? 'aal2' : state.level }, error: null }),
      listFactors: async () => ({ data: { totp: state.factor ? [{ id: '61000000-0000-4000-8000-000000000001', status: 'verified' }] : [] }, error: null }),
      challengeAndVerify: async ({ code }) => {
        if (!state.factor || code !== state.factor) return { data: null, error: { status: 422, code: 'mfa_verification_failed' } };
        state.level = 'aal2';
        if (state.session) issue(state.session.user.id);
        return { data: {}, error: null };
      },
    },
  };
  /** Revogação remota fictícia: registra o token encerrado; o teste pode trocar por uma lenta ou sem resposta. */
  const revoke = async (token: string, signal: AbortSignal) => {
    state.signOuts += 1;
    if (state.revokeImpl) return state.revokeImpl(token, signal);
    state.revoked.push(token);
  };
  return { auth, state, revoke, emit: (event: string) => { for (const listener of listeners) listener(event, state.session); } };
}
