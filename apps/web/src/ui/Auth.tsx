import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { CurrentIdentity } from '../api/types';
import { Brand, ErrorState, Icon, Loading, Notice, errorMessage } from './primitives';

type AuthStatus = 'loading' | 'anonymous' | 'authenticated' | 'blocked' | 'unavailable' | 'logoutPending' | 'logoutUnconfirmed';
interface AuthValue {
  identity: CurrentIdentity | null;
  status: AuthStatus;
  error: unknown;
  login(email: string, password: string): Promise<void>;
  logout(): Promise<void>;
  recheck(): void;
}
const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queries = useQueryClient();
  const [mode, setMode] = useState<'ready' | 'anonymous' | 'logoutPending' | 'logoutUnconfirmed'>('ready');
  const [logoutError, setLogoutError] = useState<unknown>(null);
  const identity = useQuery({ queryKey: ['identity'], queryFn: ({ signal }) => api.currentIdentity(signal), enabled: mode === 'ready' });
  useEffect(() => api.onSessionExpired(() => {
    setMode('anonymous');
    void queries.cancelQueries(); queries.clear();
  }), [queries]);
  const error = logoutError ?? identity.error;
  const status: AuthStatus = mode !== 'ready' ? mode : identity.isPending ? 'loading' : identity.isError
    ? identity.error instanceof ApiError && identity.error.status === 401 ? 'anonymous'
      : identity.error instanceof ApiError && identity.error.status === 403 ? 'blocked' : 'unavailable'
    : 'authenticated';
  useEffect(() => {
    if (status === 'blocked' || status === 'unavailable') {
      void queries.cancelQueries({ queryKey: ['private'] });
      queries.removeQueries({ queryKey: ['private'] });
    }
  }, [queries, status]);

  async function login(email: string, password: string) {
    await queries.cancelQueries(); queries.clear(); api.clearSession();
    await api.login({ email, password });
    const account = await api.currentIdentity();
    queries.setQueryData(['identity'], account);
    setLogoutError(null); setMode('ready');
  }
  async function logout() {
    setMode('logoutPending'); setLogoutError(null);
    await queries.cancelQueries(); queries.clear(); api.clearSession();
    try { await api.logout(); setMode('anonymous'); }
    catch (failure) { setLogoutError(failure); setMode('logoutUnconfirmed'); }
  }
  function recheck() {
    setLogoutError(null); setMode('ready');
    void queries.invalidateQueries({ queryKey: ['identity'] });
  }
  return <AuthContext.Provider value={{ identity: status === 'authenticated' ? identity.data ?? null : null, status, error, login, logout, recheck }}>{children}</AuthContext.Provider>;
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
    <div className="button-row"><button className="button" onClick={auth.recheck}>Verificar acesso</button><button className="button button-secondary" onClick={() => void auth.logout()}>Sair</button></div></SessionPage>;
  if (auth.status === 'unavailable') return <SessionPage><h1>Não foi possível verificar seu acesso.</h1><ErrorState error={auth.error} retry={auth.recheck} /></SessionPage>;
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
  if (auth.status === 'authenticated') return <Navigate to="/contextos" replace />;
  if (auth.status !== 'anonymous' && auth.status !== 'unavailable') return <AuthBoundary><Navigate to="/contextos" replace /></AuthBoundary>;
  const unavailable = auth.status === 'unavailable';
  async function submit(event: FormEvent) {
    event.preventDefault(); if (pending) return;
    setPending(true); setFailure(null);
    try { await auth.login(email.trim(), password); }
    catch (error) { setFailure(error); }
    finally { setPassword(''); setPending(false); }
  }
  return <main id="main-content" className="login-page">
    <section className="login-story" aria-label="CondPulse"><Brand light />
      <div className="login-story-copy"><span className="eyebrow">A VIDA NO SEU CONDOMÍNIO</span><h1>Mais simples.<br />Mais perto.</h1><p>Um lugar para cuidar da sua rotina e dos espaços que você compartilha.</p>
        <div className="story-rule" /><div className="story-feature"><Icon name="calendar" /><span>Seus espaços, com regras claras.</span></div><div className="story-feature"><Icon name="shield" /><span>Acesso ligado ao seu condomínio.</span></div></div>
      <p className="story-bottom">CondPulse · Vida em comunidade</p>
    </section>
    <section className="login-form-side"><div className="mobile-brand"><Brand /></div><div className="login-form-wrap"><span className="eyebrow">BEM-VINDO AO CONDPULSE</span><h2>Entre para continuar.</h2><p className="muted">Use o e-mail e a senha do seu cadastro.</p>
      {unavailable ? <Notice tone="warning"><p>Não foi possível verificar a conexão com o sistema. O acesso será liberado quando a sessão puder ser conferida.</p><button className="button button-secondary" onClick={auth.recheck}>Verificar conexão</button></Notice> : null}
      <form onSubmit={event => void submit(event)} className="stack-form">
        <label htmlFor="email">E-mail</label><input id="email" name="email" type="email" autoComplete="username" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} disabled={pending || unavailable} />
        <label htmlFor="password">Senha</label><input id="password" name="password" type="password" autoComplete="current-password" required maxLength={1024} value={password} onChange={event => setPassword(event.target.value)} disabled={pending || unavailable} />
        {failure ? <Notice tone="error">{errorMessage(failure)}{failure instanceof ApiError && failure.code === 'ACCOUNT_INACTIVE' ? ' Solicite a validação do cadastro à gestão.' : ''}</Notice> : null}
        <button className="button button-wide" type="submit" disabled={pending || unavailable}>{pending ? 'Entrando…' : 'Entrar'}<Icon name="arrow" /></button>
      </form>
      <p className="login-help">Precisa de acesso ou de ajuda com a senha?<br />Fale com a gestão do seu condomínio.</p>
      <div className="security-caption"><Icon name="shield" /><span>Seu acesso é pessoal. Não compartilhe sua senha.</span></div>
    </div><p className="login-footer">CondPulse · Portal do morador</p></section>
  </main>;
}
