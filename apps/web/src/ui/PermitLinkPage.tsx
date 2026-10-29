import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, PERMIT_LINK, type PublicPermit } from '../api/client';
import { SessionPage, useAuth } from './Auth';
import { Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';

const KEY = 'cp.visit-link';
function storage(): Storage | null { try { return window.sessionStorage; } catch { return null; } }

/**
 * Lê o token somente do fragmento (#), que o navegador nunca envia ao servidor, guarda nesta aba (para recarregar) e
 * limpa a barra de endereço: o token não fica no histórico nem é repassado como Referer.
 */
export function captureVisitLink(): string | null {
  const match = /^#([A-Za-z0-9_-]{43})$/.exec(window.location.hash);
  if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname);
  if (match) {
    try { storage()?.setItem(KEY, match[1]!); } catch { /* Uso só nesta visita. */ }
    return match[1]!;
  }
  try {
    const saved = storage()?.getItem(KEY) ?? null;
    return saved && PERMIT_LINK.test(saved) ? saved : null;
  } catch { return null; }
}
function forget(): void { try { storage()?.removeItem(KEY); } catch { /* Sem armazenamento. */ } }

/** Página de quem recebeu o link de uma visita (CP-037): sem conta, sem unidade, só o essencial do convite. */
export function PermitLinkPage() {
  const [token, setToken] = useState(captureVisitLink);
  // Outro link aberto na mesma aba (só o fragmento muda): ler de novo e limpar a barra.
  useEffect(() => {
    const changed = () => {
      // Âncora da própria página (ex.: "Ir para o conteúdo") não é outro link de visita.
      const anchor = window.location.hash.slice(1);
      if (anchor && document.getElementById(decodeURIComponent(anchor))) return;
      const next = captureVisitLink();
      setToken((current) => {
        if (current !== next) { setPermit(null); setFailure(null); }
        return next;
      });
    };
    window.addEventListener('hashchange', changed);
    return () => window.removeEventListener('hashchange', changed);
  }, []);
  const [permit, setPermit] = useState<PublicPermit | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [loading, setLoading] = useState(Boolean(token));
  // Sem AbortSignal: a consulta é um POST e cancelá-lo viraria "resultado incerto"; resposta tardia é só ignorada.
  const load = useCallback(async (current: { active: boolean } = { active: true }) => {
    if (!token) { setLoading(false); return; }
    setLoading(true); setFailure(null);
    try { const value = await api.viewPermitLink(token); if (current.active) setPermit(value); }
    catch (error) {
      if (!current.active) return;
      if (error instanceof ApiError && error.code === 'PERMIT_LINK_UNAVAILABLE') forget();
      setFailure(error);
    } finally { if (current.active) setLoading(false); }
  }, [token]);
  // Espera a verificação de sessão da aplicação terminar: ela pode reiniciar o cliente e tornaria este POST "incerto".
  const auth = useAuth();
  const settled = auth.status !== 'loading' && auth.status !== 'logoutPending';
  useEffect(() => {
    if (!settled) return undefined;
    const current = { active: true };
    void load(current);
    return () => { current.active = false; };
  }, [load, settled]);

  if (!token) return <SessionPage><span className="eyebrow">VISITA</span><h1>Link de visita indisponível.</h1>
    <p>Abra o link completo que você recebeu de quem convidou. Se ele não funcionar, peça um novo link.</p></SessionPage>;
  if (loading) return <SessionPage><Loading label="Abrindo o convite…" /></SessionPage>;
  if (failure || !permit) {
    const gone = failure instanceof ApiError && failure.code === 'PERMIT_LINK_UNAVAILABLE';
    return <SessionPage><span className="eyebrow">VISITA</span><h1>{gone ? 'Este convite não está mais disponível.' : 'Não foi possível abrir o convite.'}</h1>
      <Notice tone={gone ? 'info' : 'error'}>{errorMessage(failure)}</Notice>
      {gone ? null : <div className="button-row"><button className="button" type="button" onClick={() => void load()}>Tentar novamente</button></div>}
    </SessionPage>;
  }
  return <SessionPage><span className="eyebrow">VISITA PREVISTA</span>
    <h1>Olá, <bdi>{permit.visitorFirstName}</bdi>.</h1>
    <p>Você tem uma visita prevista em <strong><bdi>{permit.tenantName}</bdi></strong>.</p>
    <dl className="visit-link-details">
      <dt>Período</dt><dd>De {formatWhen(permit.validFrom, permit.timeZone)} até {formatWhen(permit.validUntil, permit.timeZone)}</dd>
      {permit.purpose ? <><dt>Finalidade</dt><dd><bdi>{permit.purpose}</bdi></dd></> : null}
      <dt>Situação</dt><dd>{permit.state === 'SCHEDULED' ? 'Agendada: ainda não começou' : 'Em vigor agora'}</dd>
    </dl>
    <Notice tone="info">Apresente-se na portaria com um documento. Este convite não substitui a conferência da portaria nem abre portas.</Notice>
  </SessionPage>;
}
