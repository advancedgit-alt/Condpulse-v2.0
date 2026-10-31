import { createContext, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate } from 'react-router-dom';
import { api, ApiError, type InvitationKind } from '../api/client';
import type { CurrentIdentity, InvitationFirstAccess } from '../api/types';
import { Brand, ErrorState, Icon, Loading, Notice, RateLimitNotice, errorMessage } from './primitives';
import { useCooldown } from './cooldown';
import { forgetInvitation, storedInvitation } from './invitationStorage';
import { pendingOperation } from './operationIntent';

type AuthStatus = 'loading' | 'anonymous' | 'authenticated' | 'blocked' | 'unavailable' | 'logoutPending' | 'logoutUnconfirmed';
interface AuthValue {
  identity: CurrentIdentity | null;
  status: AuthStatus;
  error: unknown;
  login(email: string, password: string): Promise<void>;
  /** Primeiro acesso por convite: entra e aceita o convite na mesma operação (conta local ainda inexistente). */
  acceptInvitation(email: string, password: string, token: string, kind?: InvitationKind): Promise<InvitationFirstAccess>;
  logout(): Promise<void>;
  recheck(): void;
}
const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queries = useQueryClient();
  const [mode, setMode] = useState<'ready' | 'anonymous' | 'loginPending' | 'blocked' | 'unavailable' | 'logoutPending' | 'logoutUnconfirmed'>('ready');
  const [modeError, setModeError] = useState<unknown>(null);
  const authEpoch = useRef(0);
  const identity = useQuery({ queryKey: ['identity'], queryFn: ({ signal }) => api.currentIdentity(signal), enabled: mode === 'ready' });
  useEffect(() => api.onSessionExpired(failure => {
    authEpoch.current += 1;
    setModeError(failure);
    setMode(failure instanceof ApiError && failure.status === 401 ? 'anonymous'
      : failure instanceof ApiError && failure.code === 'ACCOUNT_INACTIVE' ? 'blocked' : 'unavailable');
    void queries.cancelQueries(); queries.clear();
    // Envio pendente pertence à sessão encerrada: a próxima entrada nesta aba não o herda.
    pendingOperation.clearAll();
  }), [queries]);
  const needsLogin = mode === 'anonymous' || mode === 'ready' && !identity.isFetching
    && identity.error instanceof ApiError && identity.error.status === 401;
  const loginAvailability = useQuery({
    queryKey: ['login-availability', authEpoch.current],
    queryFn: async ({ signal }) => { await api.verifyLoginAvailable(signal); return true; },
    enabled: needsLogin,
    refetchOnWindowFocus: false,
  });
  const error = (needsLogin ? loginAvailability.error : modeError) ?? identity.error;
  const status: AuthStatus = mode === 'loginPending' ? 'loading'
    : mode === 'logoutPending' || mode === 'logoutUnconfirmed' || mode === 'blocked' || mode === 'unavailable' ? mode
    : needsLogin ? loginAvailability.isPending || loginAvailability.isFetching ? 'loading'
      : loginAvailability.isError ? 'unavailable' : 'anonymous'
    : identity.isPending ? 'loading' : identity.isError
      ? identity.error instanceof ApiError && identity.error.status === 403 ? 'blocked' : 'unavailable'
      : 'authenticated';
  // Pendências de envio pertencem à conta autenticada: outra conta nesta aba encontra a lista vazia.
  const authenticatedAccount = status === 'authenticated' ? identity.data?.accountId ?? null : null;
  useEffect(() => { if (authenticatedAccount) pendingOperation.bindAccount(authenticatedAccount); }, [authenticatedAccount]);
  useEffect(() => {
    if (status === 'blocked' || status === 'unavailable') {
      void queries.cancelQueries({ queryKey: ['private'] });
      queries.removeQueries({ queryKey: ['private'] });
    }
  }, [queries, status]);

  async function login(email: string, password: string) {
    if (status !== 'anonymous') throw new ApiError(503, 'AUTH_PROVIDER_UNAVAILABLE', 'O serviço de acesso está temporariamente indisponível.');
    const epoch = ++authEpoch.current;
    setMode('loginPending'); setModeError(null);
    pendingOperation.clearAll();
    let accepted = false;
    try {
      await queries.cancelQueries(); queries.clear(); api.clearSession();
      await api.login({ email, password }); accepted = true;
      if (epoch !== authEpoch.current) throw new DOMException('Acesso substituído.', 'AbortError');
      const account = await api.currentIdentity();
      if (epoch !== authEpoch.current) throw new DOMException('Acesso substituído.', 'AbortError');
      queries.setQueryData(['identity'], account);
      setModeError(null); setMode('ready');
    } catch (failure) {
      if (epoch === authEpoch.current) {
        setModeError(failure);
        setMode(failure instanceof ApiError && (failure.code === 'ACCOUNT_INACTIVE' || accepted && failure.status === 403) ? 'blocked'
          : !accepted && failure instanceof ApiError && [400, 401, 429].includes(failure.status) ? 'anonymous' : 'unavailable');
      }
      throw failure;
    }
  }
  async function acceptInvitation(email: string, password: string, token: string, kind: InvitationKind = 'membership'): Promise<InvitationFirstAccess> {
    // Conta sem espelho local termina o login comum em "bloqueado" (ACCOUNT_INACTIVE): o primeiro acesso é a saída.
    if (status !== 'anonymous' && status !== 'blocked') throw new ApiError(503, 'AUTH_PROVIDER_UNAVAILABLE', 'O serviço de acesso está temporariamente indisponível.');
    const epoch = ++authEpoch.current;
    setMode('loginPending'); setModeError(null);
    pendingOperation.clearAll();
    let accepted = false;
    try {
      await queries.cancelQueries(); queries.clear(); api.clearSession();
      const result = await api.acceptInvitationWithLogin({ email, password, token, kind }); accepted = true;
      // Aceite gravado: o resultado volta à tela mesmo se a identidade não puder ser lida agora (nova consulta depois).
      if (epoch !== authEpoch.current) return result;
      if (!result.sessionStarted) { setMode('anonymous'); return result; }
      try {
        const account = await api.currentIdentity();
        if (epoch === authEpoch.current) queries.setQueryData(['identity'], account);
      } catch { /* a consulta de identidade é refeita com o modo pronto */ }
      if (epoch === authEpoch.current) { setModeError(null); setMode('ready'); }
      return result;
    } catch (failure) {
      if (epoch === authEpoch.current) {
        setModeError(failure);
        setMode(failure instanceof ApiError && failure.code === 'ACCOUNT_INACTIVE' ? 'blocked'
          : !accepted && failure instanceof ApiError && [400, 401, 404, 409, 429].includes(failure.status) ? 'anonymous' : 'unavailable');
      }
      throw failure;
    }
  }
  async function logout() {
    const epoch = ++authEpoch.current;
    setMode('logoutPending'); setModeError(null);
    // Convite pendente pertence a quem abriu o link: a próxima conta desta aba não o herda.
    forgetInvitation();
    pendingOperation.clearAll();
    await queries.cancelQueries(); queries.clear(); api.clearSession();
    try { await api.logout(); if (epoch === authEpoch.current) setMode('ready'); }
    catch (failure) { if (epoch === authEpoch.current) { setModeError(failure); setMode('logoutUnconfirmed'); } }
  }
  function recheck() {
    authEpoch.current += 1;
    setModeError(null); setMode('ready');
    void queries.invalidateQueries({ queryKey: ['identity'] });
  }
  return <AuthContext.Provider value={{ identity: status === 'authenticated' ? identity.data ?? null : null, status, error, login, acceptInvitation, logout, recheck }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('Contexto de autenticação ausente.');
  return context;
}

export function AuthBoundary({ children }: { children: ReactNode }) {
  const auth = useAuth();
  if (auth.status === 'loading') return <SessionPage><Loading label="Verificando sua sessão…" /></SessionPage>;
  if (auth.status === 'logoutPending') return <SessionPage><Loading label="Confirmando sua saída…" /></SessionPage>;
  if (auth.status === 'logoutUnconfirmed') return <SessionPage><h1>Saída não confirmada</h1>
    <Notice tone="warning">Os dados desta tela foram removidos, mas não conseguimos confirmar o encerramento da sessão no servidor.</Notice>
    <button className="button" onClick={() => void auth.logout()}>Tentar sair novamente</button></SessionPage>;
  if (auth.status === 'blocked') return <SessionPage><span className="eyebrow">ACESSO EM VALIDAÇÃO</span><h1>Seu cadastro precisa ser validado.</h1>
    <p>Procure a gestão do condomínio para confirmar seu acesso e seus vínculos.</p>
    {storedInvitation() ? <Notice>Há um convite aberto nesta janela. <Link to="/convite">Concluir o primeiro acesso pelo convite</Link></Notice> : null}
    {storedInvitation('manager') ? <Notice>Há um convite de gestor aberto nesta janela. <Link to="/convite-gestor">Concluir o primeiro acesso como gestor</Link></Notice> : null}
    <div className="button-row"><button className="button" onClick={auth.recheck}>Verificar acesso</button><button className="button button-secondary" onClick={() => void auth.logout()}>Sair</button></div></SessionPage>;
  if (auth.status === 'unavailable') return <SessionPage><h1>Não foi possível verificar seu acesso.</h1><ErrorState error={auth.error} retry={auth.recheck} /><button className="button button-secondary" onClick={() => void auth.logout()}>Sair</button></SessionPage>;
  if (auth.status === 'anonymous') return <Navigate to="/entrar" replace />;
  return children;
}

export function SessionPage({ children }: { children: ReactNode }) {
  return <main id="main-content" className="session-page"><div className="session-card"><Brand /><div className="session-content">{children}</div></div></main>;
}

export function LoginPage() {
  const auth = useAuth();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const inFlight = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const remaining = useCooldown(failure ?? (auth.status === 'unavailable' ? auth.error : undefined));
  if (auth.status === 'authenticated') return <Navigate to="/entrada" replace />;
  if (auth.status !== 'anonymous' && auth.status !== 'unavailable') return <AuthBoundary><Navigate to="/entrada" replace /></AuthBoundary>;
  const unavailable = auth.status === 'unavailable';
  async function submit(event: FormEvent) {
    event.preventDefault(); if (inFlight.current || remaining > 0 || unavailable) return;
    inFlight.current = true;
    setPending(true); setFailure(null);
    try { await auth.login(email.trim(), password); }
    catch (error) { if (alive.current && !(error instanceof Error && error.name === 'AbortError')) setFailure(error); }
    finally { inFlight.current = false; if (alive.current) { setPassword(''); setPending(false); } }
  }
  return <main id="main-content" className="login-page">
    <section className="login-story" aria-label="CondPulse"><Brand light />
      <div className="login-story-copy"><span className="eyebrow">A VIDA NO SEU CONDOMÍNIO</span><h1>Mais simples.<br />Mais perto.</h1><p>Um lugar para cuidar da sua rotina e dos espaços que você compartilha.</p>
        <div className="story-rule" /><div className="story-feature"><Icon name="calendar" /><span>Seus espaços, com regras claras.</span></div><div className="story-feature"><Icon name="shield" /><span>Acesso ligado ao seu condomínio.</span></div></div>
      <p className="story-bottom">CondPulse · Vida em comunidade</p>
    </section>
    <section className="login-form-side"><div className="mobile-brand"><Brand /></div><div className="login-form-wrap"><span className="eyebrow">BEM-VINDO AO CONDPULSE</span><h2>Entre para continuar.</h2><p className="muted">Use o e-mail e a senha do seu cadastro.</p>
      {unavailable ? <Notice tone="warning"><p>Serviço de acesso indisponível. Não foi possível verificar a conexão com o sistema.</p><button className="button button-secondary" disabled={remaining > 0} onClick={() => { if (remaining === 0) auth.recheck(); }}>Verificar conexão</button><button className="text-button" onClick={() => void auth.logout()}>Sair</button></Notice> : null}
      <form onSubmit={event => void submit(event)} className="stack-form">
        <label htmlFor="email">E-mail</label><input id="email" name="email" type="email" autoComplete="username" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} disabled={pending || unavailable} />
        <label htmlFor="password">Senha</label><input id="password" name="password" type="password" autoComplete="current-password" required maxLength={1024} value={password} onChange={event => setPassword(event.target.value)} disabled={pending || unavailable} />
        {failure instanceof ApiError && failure.status === 429 ? <RateLimitNotice error={failure} remaining={remaining} />
          : failure ? <Notice tone="error">{errorMessage(failure)}{failure instanceof ApiError && failure.code === 'ACCOUNT_INACTIVE'
            ? storedInvitation() ? <> Primeiro acesso por convite? <Link to="/convite">Conclua pelo convite aberto nesta janela</Link>.</> : ' Solicite a validação do cadastro à gestão.' : ''}</Notice> : null}
        {!failure && unavailable && auth.error instanceof ApiError && auth.error.status === 429 ? <RateLimitNotice error={auth.error} remaining={remaining} /> : null}
        <button className="button button-wide" type="submit" disabled={pending || unavailable || remaining > 0}>{pending ? 'Entrando…' : remaining > 0 ? 'Aguarde para entrar' : 'Entrar'}<Icon name="arrow" /></button>
      </form>
      <Link className="text-button" to="/recuperar-acesso">Esqueci minha senha</Link>
      <p className="login-help">Precisa de acesso ao condomínio?<br />Fale com a gestão do seu condomínio.</p>
      <div className="security-caption"><Icon name="shield" /><span>Seu acesso é pessoal. Não compartilhe sua senha.</span></div>
    </div><p className="login-footer">CondPulse · Portal do morador</p></section>
  </main>;
}
