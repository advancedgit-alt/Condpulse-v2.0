import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { TotpEnrollment } from '../api/types';
import { useCooldown } from './cooldown';
import { ErrorState, Icon, Loading, Notice, RateLimitNotice, errorMessage } from './primitives';

export const mfaStatusKey = (accountId: string) => ['private', accountId, 'mfa-status'] as const;

/** Agrupa o segredo base32 para leitura e digitação manual; não altera o valor. */
export function groupSecret(secret: string): string {
  return secret.replace(/=+$/, '').match(/.{1,4}/g)?.join(' ') ?? '';
}

/**
 * Segundo fator exigido para ações de equipe (CP-013). O segredo de cadastro existe somente no estado deste
 * componente: não entra em cache de consultas, armazenamento do navegador ou registros.
 */
export function MfaPanel({ accountId, onVerified }: { accountId: string; onVerified(): void }) {
  const status = useQuery({ queryKey: mfaStatusKey(accountId), queryFn: ({ signal }) => api.mfaStatus(signal) });
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const remaining = useCooldown(failure);
  if (status.isPending) return <div className="panel"><Loading label="Conferindo o segundo fator…" /></div>;
  if (status.isError) return <ErrorState error={status.error} retry={() => void status.refetch()} />;
  const verified = status.data.factors.find((factor) => factor.status === 'VERIFIED');
  const factorId = enrollment?.factorId ?? verified?.factorId ?? null;

  async function enroll() {
    if (busy) return;
    setBusy(true); setFailure(null);
    try { setEnrollment(await api.enrollTotp()); }
    catch (error) { setFailure(error); }
    finally { setBusy(false); }
  }
  async function verify(event: FormEvent) {
    event.preventDefault();
    if (busy || !factorId || remaining > 0) return;
    setBusy(true); setFailure(null);
    try {
      await api.verifyTotp(factorId, code.trim());
      setEnrollment(null); setCode('');
      onVerified();
    } catch (error) {
      setFailure(error);
      if (error instanceof ApiError && error.code === 'MFA_FACTOR_NOT_FOUND') { setEnrollment(null); void status.refetch(); }
    } finally { setBusy(false); }
  }

  const rateLimited = failure instanceof ApiError && failure.status === 429;
  return <section className="panel mfa-panel" aria-labelledby="mfa-title">
    <span className="section-index">SEGUNDO FATOR</span>
    <h2 id="mfa-title">Confirme sua identidade para usar a gestão.</h2>
    <p>Ações de equipe exigem um código do aplicativo autenticador. São permitidas até 5 verificações e 5 cadastros a cada 15 minutos por conta.</p>
    {!factorId ? <>
      <Notice>Nenhum autenticador confirmado nesta conta. Cadastre um aplicativo autenticador (TOTP) para continuar.
        O primeiro cadastro deve ser feito no atendimento combinado com a operação do condomínio.</Notice>
      <button className="button" type="button" disabled={busy} onClick={() => void enroll()}><Icon name="shield" />{busy ? 'Preparando…' : 'Cadastrar autenticador'}</button>
    </> : null}
    {enrollment ? <div className="mfa-enrollment">
      <p>Leia o código no aplicativo autenticador ou digite a chave. Ela aparece somente agora e não será exibida de novo.</p>
      {enrollment.qrCodeDataUri ? <img className="mfa-qr" src={enrollment.qrCodeDataUri} alt="Código QR para cadastrar o CondPulse no aplicativo autenticador" /> : null}
      <p className="mfa-secret"><span>Chave</span><code>{groupSecret(enrollment.secret)}</code></p>
    </div> : null}
    {factorId ? <form className="stack-form mfa-form" onSubmit={(event) => void verify(event)}>
      <label htmlFor="mfa-code">Código de 6 dígitos</label>
      <input id="mfa-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required
        value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} disabled={busy} />
      <button className="button" type="submit" disabled={busy || code.length !== 6 || remaining > 0}><Icon name="check" />{busy ? 'Verificando…' : 'Verificar código'}</button>
    </form> : null}
    {failure ? rateLimited ? <RateLimitNotice error={failure as ApiError} remaining={remaining} />
      : <Notice tone="error">{errorMessage(failure)}</Notice> : null}
    <p className="field-help">Perdeu o aparelho? Procure a operação do condomínio: o fator só é removido após confirmação de identidade, nunca por mensagem.</p>
  </section>;
}
