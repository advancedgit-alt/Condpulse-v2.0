import { useRef, useState, type FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { InvitationFirstAccess } from '../api/types';
import { SessionPage, useAuth } from './Auth';
import { Icon, Loading, Notice, RateLimitNotice, errorMessage } from './primitives';
import { useCooldown } from './cooldown';
import { captureInvitationFromLocation, forgetInvitation as forget } from './invitationStorage';

/** Recusas definitivas: o convite desta aba não serve mais e é descartado. */
const SPENT = ['INVITATION_NOT_AVAILABLE', 'MEMBERSHIP_ALREADY_EXISTS'];

export function InvitationPage() {
  const auth = useAuth();
  const queries = useQueryClient();
  const [token, setToken] = useState(captureInvitationFromLocation);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [accepted, setAccepted] = useState<null | { sessionStarted: boolean }>(null);
  function settle(error: unknown) {
    setFailure(error);
    if (error instanceof ApiError && SPENT.includes(error.code)) { forget(); setToken(null); }
  }
  async function accept() {
    if (!token || pending) return;
    setPending(true); setFailure(null);
    try {
      await api.acceptInvitation(token);
      forget(); setToken(null); setAccepted({ sessionStarted: true });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    } catch (error) { settle(error); } finally { setPending(false); }
  }
  if (accepted) return <SessionPage><span className="eyebrow">CONVITE ACEITO</span><h1>Pedido de vínculo enviado.</h1>
    <p>A gestão do condomínio precisa aprovar o seu acesso. Depois da aprovação, o condomínio aparecerá na escolha de acesso.</p>
    {accepted.sessionStarted ? <Link className="button" to="/contextos">Ir para meus acessos<Icon name="arrow" /></Link>
      : <><Notice tone="warning">O pedido foi registrado, mas a sessão não pôde ser iniciada agora. Entre com o seu e-mail e a sua senha para acompanhar.</Notice>
        <Link className="button" to="/entrar">Entrar<Icon name="arrow" /></Link></>}</SessionPage>;
  if (!token) return <SessionPage><span className="eyebrow">CONVITE</span><h1>Convite indisponível.</h1>
    {failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : <p>Abra o link completo recebido da gestão. Ele vale uma única vez, para o e-mail convidado e dentro do prazo.</p>}
    <Link className="button button-secondary" to="/entrar">Ir para o acesso</Link></SessionPage>;
  if (auth.status === 'loading' || auth.status === 'logoutPending') return <SessionPage><Loading label="Verificando sua sessão…" /></SessionPage>;
  if (auth.status === 'anonymous' || auth.status === 'blocked') return <FirstAccess token={token}
    onAccepted={(result) => { forget(); setToken(null); setAccepted({ sessionStarted: result.sessionStarted }); }}
    onSpent={(error) => settle(error)} discard={() => { forget(); setToken(null); }} />;
  if (auth.status !== 'authenticated') return <SessionPage><span className="eyebrow">CONVITE</span><h1>Não foi possível verificar seu acesso.</h1>
    <p>Tente novamente em instantes. O convite continua guardado nesta janela.</p>
    <button className="button" type="button" onClick={auth.recheck}>Verificar acesso</button></SessionPage>;
  return <SessionPage><span className="eyebrow">CONVITE</span><h1>Aceitar convite do condomínio?</h1>
    <p>O aceite cria um pedido de vínculo para a sua conta. O acesso só é liberado depois da aprovação da gestão.</p>
    {failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : null}
    <div className="button-row"><button className="button" type="button" disabled={pending} onClick={() => void accept()}>{pending ? 'Enviando…' : 'Aceitar convite'}<Icon name="check" /></button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={() => { forget(); setToken(null); }}>Descartar convite</button></div>
  </SessionPage>;
}

/**
 * Primeiro acesso: o convidado ainda sem conta local entra com o e-mail convidado e a senha recebida no cadastro, e o
 * convite é aceito na mesma operação. Quem já tem acesso pode entrar pelo login comum e voltar ao aviso do convite.
 */
function FirstAccess({ token, onAccepted, onSpent, discard }: { token: string; onAccepted(result: InvitationFirstAccess): void;
  onSpent(error: unknown): void; discard(): void }) {
  const auth = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const inFlight = useRef(false);
  const remaining = useCooldown(failure ?? undefined);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (inFlight.current || remaining > 0 || !email.trim() || !password) return;
    inFlight.current = true; setPending(true); setFailure(null);
    try {
      const result = await auth.acceptInvitation(email.trim(), password, token);
      setPassword('');
      onAccepted(result);
    } catch (error) {
      setPassword('');
      if (error instanceof ApiError && SPENT.includes(error.code)) { onSpent(error); return; }
      setFailure(error);
    } finally { inFlight.current = false; setPending(false); }
  }
  return <SessionPage><span className="eyebrow">CONVITE · PRIMEIRO ACESSO</span><h1>Aceite o convite e entre.</h1>
    <p>Use o e-mail que recebeu o convite e a senha do seu cadastro. O aceite cria um pedido de vínculo; o acesso ao condomínio só é liberado depois da aprovação da gestão.</p>
    <form className="stack-form" onSubmit={event => void submit(event)}>
      <label htmlFor="invitation-email">E-mail convidado</label>
      <input id="invitation-email" type="email" autoComplete="username" required maxLength={254} value={email}
        onChange={event => setEmail(event.target.value)} disabled={pending} />
      <label htmlFor="invitation-password">Senha</label>
      <input id="invitation-password" type="password" autoComplete="current-password" required maxLength={1024} value={password}
        onChange={event => setPassword(event.target.value)} disabled={pending} />
      {failure instanceof ApiError && failure.status === 429 ? <RateLimitNotice error={failure} remaining={remaining} />
        : failure ? <Notice tone={failure instanceof ApiError && failure.uncertain ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
      <button className="button button-wide" type="submit" disabled={pending || remaining > 0}>{pending ? 'Enviando…' : 'Aceitar convite e entrar'}<Icon name="check" /></button>
    </form>
    <div className="button-row"><Link className="text-button" to="/entrar">Já tenho acesso: entrar</Link>
      <button className="text-button" type="button" disabled={pending} onClick={discard}>Descartar convite</button></div>
  </SessionPage>;
}
