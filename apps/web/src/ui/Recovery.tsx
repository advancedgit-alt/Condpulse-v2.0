import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { api, ApiError, passwordProblem, RECOVERY_LINK } from '../api/client';
import { SessionPage, useAuth } from './Auth';
import { Icon, Notice, RateLimitNotice, errorMessage } from './primitives';
import { useCooldown } from './cooldown';

const LINK_KEY = 'condpulse:recovery-link';
const STAGE_KEY = 'condpulse:recovery-stage';
/** Respostas que encerram a tentativa: o link ou o cookie de recuperação não servem mais. */
const TERMINAL = new Set(['RECOVERY_LINK_INVALID', 'RECOVERY_SESSION_EXPIRED', 'RECOVERY_NOT_ALLOWED', 'RECOVERY_OUTCOME_UNKNOWN',
  'COMMAND_OUTCOME_UNKNOWN', 'ACCOUNT_INACTIVE', 'MFA_FACTOR_NOT_FOUND', 'MFA_OPERATION_REJECTED', 'AUTH_PROVIDER_UNAVAILABLE']);

interface PasswordStage { mfaRequired: boolean; expiresAt: string }
type Stage = { kind: 'request' } | { kind: 'requested' } | { kind: 'link'; token: string } | ({ kind: 'password' } & PasswordStage)
  | { kind: 'done'; otherSessionsEnded: boolean } | { kind: 'ended'; failure: unknown };

function storage(): Storage | null { try { return window.sessionStorage; } catch { return null; } }
function forgetRecovery(): void { try { storage()?.removeItem(LINK_KEY); storage()?.removeItem(STAGE_KEY); } catch { /* Sem armazenamento. */ } }

/**
 * Lê o hash do link somente do fragmento (nunca enviado ao servidor), guarda nesta aba e limpa a barra de endereço.
 * Depois da troca do link, só o estágio (sem segredo) é guardado: o cookie de recuperação fica com o servidor.
 */
export function initialRecoveryStage(): Stage {
  const match = /^#token_hash=([A-Za-z0-9_-]{16,512})$/.exec(window.location.hash);
  if (match) {
    try { storage()?.setItem(LINK_KEY, match[1]!); storage()?.removeItem(STAGE_KEY); } catch { /* Uso nesta visita. */ }
    window.history.replaceState(window.history.state, '', window.location.pathname);
    return { kind: 'link', token: match[1]! };
  }
  if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname);
  try {
    const raw = storage()?.getItem(STAGE_KEY);
    if (raw) {
      const saved = JSON.parse(raw) as Partial<PasswordStage>;
      if (typeof saved.mfaRequired === 'boolean' && typeof saved.expiresAt === 'string' && Date.parse(saved.expiresAt) > Date.now())
        return { kind: 'password', mfaRequired: saved.mfaRequired, expiresAt: saved.expiresAt };
    }
    const token = storage()?.getItem(LINK_KEY);
    if (token && RECOVERY_LINK.test(token)) return { kind: 'link', token };
  } catch { /* Estado ilegível: recomeçar pelo pedido. */ }
  forgetRecovery();
  return { kind: 'request' };
}

export function RecoveryPage() {
  const [stage, setStage] = useState<Stage>(initialRecoveryStage);
  function end(failure: unknown) { forgetRecovery(); setStage({ kind: 'ended', failure }); }
  function restart() { forgetRecovery(); setStage({ kind: 'request' }); }
  if (stage.kind === 'request') return <RequestForm onRequested={() => setStage({ kind: 'requested' })} />;
  if (stage.kind === 'requested') return <SessionPage><span className="eyebrow">RECUPERAÇÃO DE ACESSO</span><h1>Confira seu e-mail.</h1>
    <p>Se o e-mail estiver cadastrado, você receberá em instantes um link para definir uma nova senha. O link vale por pouco tempo e uma única vez.</p>
    <p className="muted">Não chegou? Confira a caixa de spam ou procure a gestão do seu condomínio.</p>
    <div className="button-row"><Link className="button" to="/entrar">Voltar para o acesso<Icon name="arrow" /></Link>
      <button className="button button-secondary" type="button" onClick={restart}>Usar outro e-mail</button></div></SessionPage>;
  if (stage.kind === 'link') return <LinkStep token={stage.token} onDiscard={restart} onVerified={(next) => {
    const saved: PasswordStage = { mfaRequired: next.mfaRequired, expiresAt: next.expiresAt };
    try { storage()?.removeItem(LINK_KEY); storage()?.setItem(STAGE_KEY, JSON.stringify(saved)); } catch { /* Sem armazenamento. */ }
    setStage({ kind: 'password', ...saved });
  }} onEnded={end} />;
  if (stage.kind === 'password') return <PasswordStep mfaRequired={stage.mfaRequired} expiresAt={stage.expiresAt}
    onDone={(otherSessionsEnded) => { forgetRecovery(); setStage({ kind: 'done', otherSessionsEnded }); }} onEnded={end} />;
  if (stage.kind === 'done') return <Done otherSessionsEnded={stage.otherSessionsEnded} />;
  return <SessionPage><span className="eyebrow">RECUPERAÇÃO DE ACESSO</span><h1>Recuperação não concluída.</h1>
    <Notice tone="error">{errorMessage(stage.failure)}</Notice>
    <div className="button-row"><button className="button" type="button" onClick={restart}>Solicitar novo link<Icon name="arrow" /></button>
      <Link className="button button-secondary" to="/entrar">Voltar para o acesso</Link></div></SessionPage>;
}

function RequestForm({ onRequested }: { onRequested(): void }) {
  const [email, setEmail] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const inFlight = useRef(false);
  const remaining = useCooldown(failure ?? undefined);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (inFlight.current || remaining > 0) return;
    inFlight.current = true; setPending(true); setFailure(null);
    try { await api.requestRecovery(email.trim()); onRequested(); }
    catch (error) { setFailure(error); }
    finally { inFlight.current = false; setPending(false); }
  }
  return <SessionPage><span className="eyebrow">RECUPERAÇÃO DE ACESSO</span><h1>Esqueceu sua senha?</h1>
    <p>Informe o e-mail do seu cadastro. Por segurança, a resposta é a mesma exista ou não uma conta com esse e-mail.</p>
    <form className="stack-form" onSubmit={event => void submit(event)}>
      <label htmlFor="recovery-email">E-mail</label>
      <input id="recovery-email" type="email" autoComplete="username" required maxLength={254} value={email}
        onChange={event => setEmail(event.target.value)} disabled={pending} />
      {failure instanceof ApiError && failure.status === 429 ? <RateLimitNotice error={failure} remaining={remaining} />
        : failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : null}
      <button className="button button-wide" type="submit" disabled={pending || remaining > 0}>{pending ? 'Enviando…' : 'Enviar link de recuperação'}<Icon name="arrow" /></button>
    </form>
    <Link className="text-button" to="/entrar">Voltar para o acesso</Link></SessionPage>;
}

/** O link só é trocado com um clique: pré-visualizadores de e-mail não consomem o link de uso único. */
function LinkStep({ token, onVerified, onEnded, onDiscard }: { token: string; onVerified(next: PasswordStage): void;
  onEnded(failure: unknown): void; onDiscard(): void }) {
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function verify() {
    if (pending) return;
    setPending(true); setFailure(null);
    try { onVerified(await api.verifyRecovery(token)); }
    catch (error) {
      if (error instanceof ApiError && TERMINAL.has(error.code)) { onEnded(error); return; }
      setFailure(error);
    } finally { setPending(false); }
  }
  return <SessionPage><span className="eyebrow">RECUPERAÇÃO DE ACESSO</span><h1>Definir uma nova senha.</h1>
    <p>Continue para criar a nova senha. Depois da troca, todas as sessões abertas da sua conta serão encerradas.</p>
    {failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : null}
    <div className="button-row"><button className="button" type="button" disabled={pending} onClick={() => void verify()}>{pending ? 'Validando link…' : 'Continuar'}<Icon name="arrow" /></button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={onDiscard}>Descartar link</button></div>
  </SessionPage>;
}

function PasswordStep({ mfaRequired, expiresAt, onDone, onEnded }: PasswordStage & { onDone(otherSessionsEnded: boolean): void; onEnded(failure: unknown): void }) {
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [code, setCode] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const inFlight = useRef(false);
  const remaining = useCooldown(failure ?? undefined);
  const ended = useRef(onEnded);
  ended.current = onEnded;
  useEffect(() => {
    // O prazo do servidor manda; o aviso local só evita digitar uma senha que já não pode ser usada.
    const wait = Date.parse(expiresAt) - Date.now();
    const timer = window.setTimeout(() => ended.current(new ApiError(401, 'RECOVERY_SESSION_EXPIRED',
      'O prazo para definir a nova senha terminou. Solicite um novo link.')), Math.max(0, Math.min(wait, 2_147_000_000)));
    return () => window.clearTimeout(timer);
  }, [expiresAt]);
  const problem = password ? passwordProblem(password) : null;
  const mismatch = confirmation.length > 0 && confirmation !== password;
  const codeReady = !mfaRequired || /^[0-9]{6}$/.test(code);
  const ready = password.length > 0 && !problem && confirmation === password && codeReady;
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!ready || inFlight.current || remaining > 0) return;
    inFlight.current = true; setPending(true); setFailure(null);
    try {
      const result = await api.completeRecovery(password, mfaRequired ? code : null);
      setPassword(''); setConfirmation(''); setCode('');
      onDone(result.otherSessionsEnded);
    } catch (error) {
      setCode('');
      if (error instanceof ApiError && TERMINAL.has(error.code)) { setPassword(''); setConfirmation(''); onEnded(error); return; }
      setFailure(error);
    } finally { inFlight.current = false; setPending(false); }
  }
  return <SessionPage><span className="eyebrow">RECUPERAÇÃO DE ACESSO</span><h1>Crie sua nova senha.</h1>
    <p>Use de 12 a 72 caracteres, com pelo menos 4 caracteres diferentes. Prefira uma frase que só você conheça.</p>
    <form className="stack-form" onSubmit={event => void submit(event)}>
      <label htmlFor="recovery-password">Nova senha</label>
      <input id="recovery-password" type="password" autoComplete="new-password" required maxLength={72} value={password}
        aria-invalid={problem ? true : undefined} aria-describedby={problem ? 'recovery-password-problem' : undefined}
        onChange={event => setPassword(event.target.value)} disabled={pending} />
      {problem ? <p id="recovery-password-problem" className="field-help">{problem}</p> : null}
      <label htmlFor="recovery-confirmation">Confirme a nova senha</label>
      <input id="recovery-confirmation" type="password" autoComplete="new-password" required maxLength={72} value={confirmation}
        aria-invalid={mismatch ? true : undefined} aria-describedby={mismatch ? 'recovery-confirmation-problem' : undefined}
        onChange={event => setConfirmation(event.target.value)} disabled={pending} />
      {mismatch ? <p id="recovery-confirmation-problem" className="field-help">As senhas não são iguais.</p> : null}
      {mfaRequired ? <><label htmlFor="recovery-code">Código de 6 dígitos do autenticador</label>
        <input id="recovery-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={code}
          onChange={event => setCode(event.target.value.replace(/[^0-9]/g, ''))} disabled={pending} />
        <p className="field-help">Sua conta usa segundo fator: a troca de senha também exige o código atual do aplicativo.</p></> : null}
      {failure instanceof ApiError && failure.status === 429 ? <RateLimitNotice error={failure} remaining={remaining} />
        : failure ? <Notice tone="error">{errorMessage(failure)}</Notice> : null}
      <button className="button button-wide" type="submit" disabled={!ready || pending || remaining > 0}>{pending ? 'Salvando…' : 'Salvar nova senha'}<Icon name="check" /></button>
    </form></SessionPage>;
}

function Done({ otherSessionsEnded }: { otherSessionsEnded: boolean }) {
  const auth = useAuth();
  const queries = useQueryClient();
  const navigate = useNavigate();
  const settled = useRef(false);
  useEffect(() => {
    // O servidor apagou sessão e CSRF deste navegador: nada da sessão anterior pode continuar na tela.
    if (settled.current) return;
    settled.current = true;
    void queries.cancelQueries(); queries.clear(); auth.recheck();
  }, [auth, queries]);
  return <SessionPage><span className="eyebrow">RECUPERAÇÃO DE ACESSO</span><h1>Senha alterada.</h1>
    <p>Entre novamente com a nova senha.</p>
    {otherSessionsEnded ? <Notice tone="success">As sessões abertas da sua conta foram encerradas.</Notice>
      : <Notice tone="warning">Não foi possível confirmar o encerramento de todas as sessões abertas. Elas expiram sozinhas; se notar acesso estranho, procure a gestão.</Notice>}
    <button className="button" type="button" onClick={() => navigate('/entrar', { replace: true })}>Entrar com a nova senha<Icon name="arrow" /></button>
  </SessionPage>;
}
