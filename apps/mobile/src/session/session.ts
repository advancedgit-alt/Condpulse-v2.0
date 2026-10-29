import type { Vault } from './vault';

/**
 * Sessão nativa (CP-012). O Supabase Auth é chamado direto do aparelho; o refresh token fica só no cofre seguro e o
 * access token só em memória da biblioteca. Toda saída — pelo botão, por token expirado/recusado, por troca de conta
 * ou por reinstalação — apaga o cofre e o cache de dados em memória antes de mostrar a próxima tela.
 * Nunca registra token, e-mail nem senha.
 */
export interface AuthSession { access_token: string; user: { id: string } }
interface Result<T> { data: T; error: { status?: number; code?: string; name?: string } | null }
export interface AuthPort {
  getSession(): Promise<Result<{ session: AuthSession | null }>>;
  signInWithPassword(credentials: { email: string; password: string }): Promise<Result<{ session: AuthSession | null }>>;
  refreshSession(): Promise<Result<{ session: AuthSession | null }>>;
  onAuthStateChange(callback: (event: string, session: AuthSession | null) => void): { data: { subscription: { unsubscribe(): void } } };
  mfa: {
    getAuthenticatorAssuranceLevel(): Promise<Result<{ currentLevel: string | null; nextLevel: string | null } | null>>;
    listFactors(): Promise<Result<{ totp: { id: string; status: string }[] } | null>>;
    challengeAndVerify(params: { factorId: string; code: string }): Promise<Result<unknown>>;
  };
}

export type SessionState =
  | { status: 'loading' }
  | { status: 'signedOut'; reason: 'expired' | 'signedOut' | null }
  | { status: 'mfaRequired'; accountId: string }
  | { status: 'signedIn'; accountId: string; assurance: 'AAL1' | 'AAL2' };

export class SessionError extends Error {
  constructor(readonly code: 'INVALID_CREDENTIALS' | 'MFA_INVALID' | 'RATE_LIMITED' | 'AUTH_UNAVAILABLE' | 'NO_SESSION', message: string) {
    super(message);
    this.name = 'SessionError';
  }
}
const messages = {
  INVALID_CREDENTIALS: 'Não foi possível validar seu e-mail e sua senha.',
  MFA_INVALID: 'Código inválido ou expirado. Confira o aplicativo autenticador.',
  RATE_LIMITED: 'Muitas tentativas. Aguarde um pouco antes de tentar novamente.',
  AUTH_UNAVAILABLE: 'O acesso está temporariamente indisponível. Confira a conexão e tente novamente.',
  NO_SESSION: 'Entre novamente para continuar.',
} as const;
function failure(error: { status?: number; code?: string } | null, fallback: 'INVALID_CREDENTIALS' | 'MFA_INVALID'): SessionError {
  const code = error?.status === 429 || error?.code === 'over_request_rate_limit' ? 'RATE_LIMITED'
    : error?.status === undefined || error.status === 0 || error.status >= 500 ? 'AUTH_UNAVAILABLE' : fallback;
  return new SessionError(code, messages[code]);
}
export const SIGN_OUT_TIMEOUT = 5_000;
/**
 * Revogação remota da sessão encerrada, pelo access token dela (POST /auth/v1/logout?scope=local), cancelável. Não usa
 * o cliente do Auth: uma resposta tardia nunca toca no armazenamento nem numa sessão nova aberta depois.
 */
export type Revoke = (accessToken: string, signal: AbortSignal) => Promise<void>;
const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;

export class SessionManager {
  private state: SessionState = { status: 'loading' };
  private readonly listeners = new Set<(state: SessionState) => void>();
  private lastAccount: string | null = null;
  private ending: Promise<void> | null = null;
  /** Último access token entregue pelo Auth, só para a revogação remota na saída. */
  private lastToken: string | null = null;
  /** Última revogação remota em andamento (só para acompanhamento e testes). */
  revoking: Promise<void> = Promise.resolve();
  private subscription: { unsubscribe(): void } | null = null;

  constructor(private readonly auth: AuthPort, private readonly vault: Vault, private readonly clearData: () => void,
    private readonly revoke: Revoke) {}

  getState(): SessionState { return this.state; }
  subscribe(listener: (state: SessionState) => void): () => void {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  }
  private set(state: SessionState): void {
    this.state = state;
    for (const listener of this.listeners) listener(state);
  }

  async start(): Promise<void> {
    this.subscription ??= this.auth.onAuthStateChange((event) => {
      // A biblioteca avisa SIGNED_OUT quando a renovação é recusada: a sessão acabou de fato. Fora do callback para
      // não chamar o Auth de dentro do próprio aviso.
      if (event === 'SIGNED_OUT' && this.ending === null && (this.state.status === 'signedIn' || this.state.status === 'mfaRequired'))
        setTimeout(() => { void this.expire(); }, 0);
    }).data.subscription;
    let session: AuthSession | null = null;
    try {
      const result = await this.auth.getSession();
      session = result.error ? null : result.data.session;
      if (result.error) await this.vault.wipe();
    } catch { session = null; }
    if (!session) { this.set({ status: 'signedOut', reason: null }); return; }
    await this.evaluate(session);
  }
  stop(): void { this.subscription?.unsubscribe(); this.subscription = null; }

  private async evaluate(session: AuthSession): Promise<void> {
    const accountId = session.user.id;
    this.lastToken = session.access_token;
    // Outra conta no mesmo aparelho: nada do cache anterior pode aparecer.
    if (this.lastAccount !== accountId) this.clearData();
    this.lastAccount = accountId;
    const level = await this.auth.mfa.getAuthenticatorAssuranceLevel();
    if (level.error || !level.data) { await this.expire(); throw new SessionError('AUTH_UNAVAILABLE', messages.AUTH_UNAVAILABLE); }
    if (level.data.currentLevel === 'aal1' && level.data.nextLevel === 'aal2') { this.set({ status: 'mfaRequired', accountId }); return; }
    this.set({ status: 'signedIn', accountId, assurance: level.data.currentLevel === 'aal2' ? 'AAL2' : 'AAL1' });
  }

  async signIn(email: string, password: string): Promise<void> {
    const normalized = email.trim().toLowerCase();
    if (!EMAIL.test(normalized) || password.length < 1 || password.length > 128)
      throw new SessionError('INVALID_CREDENTIALS', 'Informe seu e-mail e sua senha.');
    // Uma limpeza local em andamento termina antes (senão ela apagaria a sessão nova); a revogação remota não é esperada.
    if (this.ending) await this.ending;
    // Qualquer resto de outra sessão sai antes do novo login.
    this.clearData();
    let result: Awaited<ReturnType<AuthPort['signInWithPassword']>>;
    try { result = await this.auth.signInWithPassword({ email: normalized, password }); }
    catch { throw new SessionError('AUTH_UNAVAILABLE', messages.AUTH_UNAVAILABLE); }
    if (result.error || !result.data.session) throw failure(result.error, 'INVALID_CREDENTIALS');
    await this.evaluate(result.data.session);
  }

  async verifyMfa(code: string): Promise<void> {
    if (this.state.status !== 'mfaRequired') throw new SessionError('NO_SESSION', messages.NO_SESSION);
    if (!/^\d{6}$/.test(code)) throw new SessionError('MFA_INVALID', 'Informe o código de 6 dígitos do aplicativo autenticador.');
    const factors = await this.auth.mfa.listFactors();
    const factor = factors.data?.totp.find((item) => item.status === 'verified');
    if (factors.error || !factor) throw new SessionError('AUTH_UNAVAILABLE', messages.AUTH_UNAVAILABLE);
    const verified = await this.auth.mfa.challengeAndVerify({ factorId: factor.id, code });
    if (verified.error) throw failure(verified.error, 'MFA_INVALID');
    const current = await this.auth.getSession();
    if (current.error || !current.data.session) { await this.expire(); throw new SessionError('NO_SESSION', messages.NO_SESSION); }
    await this.evaluate(current.data.session);
  }

  /** Token atual só para a sessão liberada; nunca durante a etapa de segundo fator. */
  async accessToken(): Promise<string | null> {
    if (this.state.status !== 'signedIn' || this.ending) return null;
    const result = await this.auth.getSession();
    // A saída pode ter começado durante a leitura.
    if (this.state.status !== 'signedIn' || this.ending) return null;
    const token = result.error ? null : result.data.session?.access_token ?? null;
    if (token) this.lastToken = token;
    return token;
  }
  /** Renovação forçada depois de um 401; recusa encerra a sessão. */
  async refresh(): Promise<string | null> {
    if (this.state.status !== 'signedIn' || this.ending) return null;
    try {
      const result = await this.auth.refreshSession();
      if (this.state.status !== 'signedIn' || this.ending) return null;
      if (!result.error && result.data.session) { this.lastToken = result.data.session.access_token; return this.lastToken; }
    } catch { /* tratado abaixo */ }
    await this.expire();
    return null;
  }

  async signOut(): Promise<void> { await this.end('signedOut'); }
  async expire(): Promise<void> { await this.end('expired'); }

  private end(reason: 'expired' | 'signedOut'): Promise<void> {
    if (this.ending) return this.ending;
    // Bloqueio local imediato: a tela privada sai e nenhum token é entregue antes de qualquer chamada de rede.
    this.lastAccount = null;
    this.clearData();
    this.set({ status: 'signedOut', reason });
    this.ending = (async () => {
      try {
        // Token já conhecido em memória: nenhuma leitura que possa renovar pela rede antes de apagar o cofre.
        const token = this.lastToken;
        this.lastToken = null;
        // Primeiro o aparelho: o cliente do Auth lê a sessão do cofre, então apagar o cofre encerra a sessão local.
        await this.vault.wipe();
        // Depois a revogação remota, em segundo plano e com prazo real (a requisição é cancelada, não só ignorada).
        // Ela só usa o token antigo; um novo login não espera por ela nem pode ser afetado por uma resposta tardia.
        if (token) this.revoking = this.revokeWithDeadline(token);
      } finally { this.ending = null; }
    })();
    return this.ending;
  }

  private async revokeWithDeadline(token: string): Promise<void> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), SIGN_OUT_TIMEOUT);
    try { await this.revoke(token, controller.signal); } catch { /* sem rede ou prazo: a sessão local já acabou */ }
    finally { clearTimeout(timer); }
  }
}
