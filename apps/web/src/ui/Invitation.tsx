import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { SessionPage, useAuth } from './Auth';
import { Icon, Loading, Notice, errorMessage } from './primitives';

const STORAGE_KEY = 'condpulse:invitation';
const TOKEN = /^[A-Za-z0-9_-]{43}$/;

/** Convite pendente nesta aba: some ao aceitar, ao falhar de forma definitiva ou ao fechar a aba. */
export function storedInvitation(): string | null {
  try { const value = window.sessionStorage.getItem(STORAGE_KEY); return value && TOKEN.test(value) ? value : null; }
  catch { return null; }
}
function forget(): void { try { window.sessionStorage.removeItem(STORAGE_KEY); } catch { /* Armazenamento indisponível. */ } }

/** Lê o token do fragmento (nunca enviado ao servidor), guarda só nesta aba e limpa a barra de endereço. */
export function captureInvitationFromLocation(): string | null {
  const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(window.location.hash);
  if (!match) return storedInvitation();
  try { window.sessionStorage.setItem(STORAGE_KEY, match[1]!); } catch { /* Sem armazenamento: aceite nesta visita. */ }
  window.history.replaceState(window.history.state, '', window.location.pathname);
  return match[1]!;
}

export function InvitationPage() {
  const auth = useAuth();
  const queries = useQueryClient();
  const [token, setToken] = useState(captureInvitationFromLocation);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [accepted, setAccepted] = useState(false);
  async function accept() {
    if (!token || pending) return;
    setPending(true); setFailure(null);
    try {
      await api.acceptInvitation(token);
      forget(); setToken(null); setAccepted(true);
      void queries.invalidateQueries({ queryKey: ['identity'] });
    } catch (error) {
      setFailure(error);
      if (error instanceof ApiError && ['INVITATION_NOT_AVAILABLE', 'MEMBERSHIP_ALREADY_EXISTS'].includes(error.code)) { forget(); setToken(null); }
    } finally { setPending(false); }
  }
  if (accepted) return <SessionPage><span className="eyebrow">CONVITE ACEITO</span><h1>Pedido de vínculo enviado.</h1>
    <p>A gestão do condomínio precisa aprovar o seu acesso. Depois da aprovação, o condomínio aparecerá na escolha de acesso.</p>
    <Link className="button" to="/contextos">Ir para meus acessos<Icon name="arrow" /></Link></SessionPage>;
  if (!token) return <SessionPage><span className="eyebrow">CONVITE</span><h1>Convite indisponível.</h1>
    {failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : <p>Abra o link completo recebido da gestão. Ele vale uma única vez, para o e-mail convidado e dentro do prazo.</p>}
    <Link className="button button-secondary" to="/entrar">Ir para o acesso</Link></SessionPage>;
  if (auth.status === 'loading' || auth.status === 'logoutPending') return <SessionPage><Loading label="Verificando sua sessão…" /></SessionPage>;
  if (auth.status !== 'authenticated') return <SessionPage><span className="eyebrow">CONVITE</span><h1>Entre para aceitar o convite.</h1>
    <p>Use a conta do e-mail convidado. Depois de entrar, abra o aviso do convite na tela de acessos para concluir.</p>
    <Link className="button" to="/entrar">Entrar<Icon name="arrow" /></Link></SessionPage>;
  return <SessionPage><span className="eyebrow">CONVITE</span><h1>Aceitar convite do condomínio?</h1>
    <p>O aceite cria um pedido de vínculo para a sua conta. O acesso só é liberado depois da aprovação da gestão.</p>
    {failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : null}
    <div className="button-row"><button className="button" type="button" disabled={pending} onClick={() => void accept()}>{pending ? 'Enviando…' : 'Aceitar convite'}<Icon name="check" /></button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={() => { forget(); setToken(null); }}>Descartar convite</button></div>
  </SessionPage>;
}
