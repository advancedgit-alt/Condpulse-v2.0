import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { parcels, type Delegation, type Parcel } from '../api/parcels';
import { collapseText, displayText } from '../api/text';
import { ErrorState, Loading, Notice, errorMessage } from './primitives';
import { formatWhen, toInstant, wallTimeInZone } from './dateTime';
import { repeatFailure, settleFailure, uncertain, useIntent } from './operationIntent';

/**
 * Retirada por terceiro (CP-041): quem pode retirar pela unidade autoriza uma pessoa para esta encomenda. O código de 8
 * dígitos aparece só agora, nesta tela; a portaria confere o código e o documento. Nova autorização substitui a anterior.
 */
const stateText: Record<Delegation['state'], string> = {
  ACTIVE: 'Autorização em vigor', USED: 'Retirada feita com o código', REVOKED: 'Autorização cancelada', LOCKED: 'Bloqueada por tentativas erradas',
  CLOSED: 'Autorização encerrada', EXPIRED: 'Autorização vencida',
};

export function ParcelDelegation({ baseKey, tenantId, parcel, timeZone }: { baseKey: readonly unknown[]; tenantId: string; parcel: Parcel; timeZone: string }) {
  const queries = useQueryClient();
  const key = [...baseKey, 'delegation', parcel.parcelId];
  const current = useQuery({ queryKey: key, queryFn: ({ signal }) => parcels.delegation(tenantId, parcel.parcelId, signal) });
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [code, setCode] = useState<{ value: string; name: string } | null>(null);
  const [notice, setNotice] = useState<{ tone: 'success' | 'warning' | 'info' | 'error'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const refresh = () => queries.invalidateQueries({ queryKey: key });
  const delegation = current.data ?? null;
  const active = delegation?.state === 'ACTIVE';
  async function revoke() {
    if (!delegation || busy) return;
    setBusy(true); setNotice(null);
    try {
      await parcels.revokeDelegation(tenantId, parcel.parcelId, { delegationId: delegation.delegationId, expectedVersion: delegation.version });
      setCode(null); setNotice({ tone: 'success', text: `Autorização de ${delegation.delegateName} cancelada. O código deixou de valer.` });
    } catch (error) { setNotice({ tone: 'error', text: errorMessage(error) }); }
    finally { setBusy(false); setRevoking(false); await refresh(); }
  }
  return <div className="share-link" aria-label={`Retirada por terceiro da encomenda recebida em ${formatWhen(parcel.receivedAt, timeZone)}`}>
    {current.isPending ? <Loading label="Consultando autorização…" />
      : current.isError ? <ErrorState error={current.error} retry={() => void current.refetch()} />
        : delegation ? <span className="field-help">{stateText[delegation.state]}: <bdi>{delegation.delegateName}</bdi>
          {delegation.documentLast4 ? ` · documento final ${delegation.documentLast4}` : ''}{active ? ` · até ${formatWhen(delegation.validUntil, timeZone)} · ${delegation.attemptsLeft} tentativas restantes` : ''}</span> : null}
    {code ? <Notice tone="info"><strong>Código para {code.name}: <span className="delegation-code">{code.value}</span></strong> Envie à pessoa autorizada.
      Ele aparece só agora; na portaria ela informa o código e mostra o documento.</Notice> : null}
    {notice ? <Notice tone={notice.tone}>{notice.text}</Notice> : null}
    {creating ? <DelegationForm tenantId={tenantId} parcel={parcel} timeZone={timeZone} replacing={active}
      close={(result) => { setCreating(false); if (result) { setCode(result.code ? { value: result.code, name: result.name } : null); setNotice(result.notice); } void refresh(); }} />
      : revoking ? <div className="cancel-confirmation" role="group" aria-label="Confirmação de cancelamento da autorização">
        <p>Cancelar a autorização? O código deixa de valer imediatamente.</p>
        <div className="button-row"><button className="button button-danger button-compact" type="button" disabled={busy} onClick={() => void revoke()}>Confirmar cancelamento</button>
          <button className="button button-secondary button-compact" type="button" disabled={busy} onClick={() => setRevoking(false)}>Manter</button></div></div>
        : <div className="button-row">
          <button className="button button-secondary button-compact" type="button" disabled={current.isPending} onClick={() => { setNotice(null); setCreating(true); }}>
            {active ? 'Gerar nova autorização' : 'Autorizar retirada por terceiro'}</button>
          {active ? <button className="button button-secondary button-compact" type="button" onClick={() => { setNotice(null); setRevoking(true); }}>Cancelar autorização</button> : null}
        </div>}
  </div>;
}

function DelegationForm({ tenantId, parcel, timeZone, replacing, close }: { tenantId: string; parcel: Parcel; timeZone: string; replacing: boolean;
  close(result: { code: string | null; name: string; notice: { tone: 'success' | 'warning' | 'info'; text: string } } | null): void }) {
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [until, setUntil] = useState(() => wallTimeInZone(timeZone, new Date(Date.now() + 48 * 3_600_000)));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [locked, setLocked] = useState(false);
  // Corpo do envio com resultado incerto: a repetição manda exatamente o mesmo, sem revalidar o prazo pelo relógio atual.
  const [sent, setSent] = useState<{ delegateName: string; documentLast4: string | null; validUntil: string | null } | null>(null);
  const intent = useIntent();
  const person = displayText(name, 160);
  const documentValue = collapseText(document).toUpperCase();
  const documentValid = documentValue === '' || /^[0-9A-Z]{2,4}$/.test(documentValue);
  let validUntil: string | null = null; let periodError: string | null = null;
  try {
    validUntil = toInstant(until, timeZone);
    const span = Date.parse(validUntil) - Date.now();
    if (span < 15 * 60_000) periodError = 'A autorização vale por pelo menos 15 minutos.';
    else if (span > 7 * 86_400_000) periodError = 'A autorização vale por até 7 dias.';
  } catch (error) { periodError = error instanceof Error ? error.message : 'Informe a validade.'; }
  const valid = person !== null && [...person].length >= 2 && documentValid && periodError === null && validUntil !== null;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || (!locked && !valid)) return;
    const body = locked && sent ? sent : { delegateName: person!, documentLast4: documentValue || null, validUntil };
    setSent(body);
    const key = intent.keyFor(JSON.stringify(body));
    setBusy(true); setFailure(null);
    try {
      const result = await parcels.delegate(tenantId, parcel.parcelId, key, body);
      close(result.code ? { code: result.code, name: result.delegation.delegateName, notice: { tone: 'success', text: `Autorização criada para ${result.delegation.delegateName} até ${formatWhen(result.delegation.validUntil, timeZone)}.` } }
        : { code: null, name: result.delegation.delegateName, notice: { tone: 'warning', text: 'Esta autorização já estava criada, mas o código só aparece na primeira resposta. Gere uma nova autorização para ter outro código.' } });
    } catch (error) {
      const shown = repeatFailure(error);
      setFailure(shown);
      settleFailure(shown, locked, intent, setLocked);
    } finally { setBusy(false); }
  }
  const id = `delegate-${parcel.parcelId}`;
  const disabled = busy || locked;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Autorizar retirada por terceiro">
    {replacing ? <Notice tone="warning">A nova autorização substitui a atual: o código anterior deixa de valer.</Notice> : null}
    <label htmlFor={`${id}-name`}>Nome de quem vai retirar</label>
    <input id={`${id}-name`} required maxLength={160} value={name} onChange={(event) => setName(event.target.value)} disabled={disabled} />
    <label htmlFor={`${id}-document`}>Final do documento (opcional)</label>
    <input id={`${id}-document`} maxLength={4} value={document} onChange={(event) => setDocument(event.target.value)} disabled={disabled} aria-invalid={!documentValid} />
    <label htmlFor={`${id}-until`}>Válida até</label>
    <input id={`${id}-until`} type="datetime-local" required value={until} onChange={(event) => setUntil(event.target.value)} disabled={disabled} />
    {periodError && !locked ? <span className="field-help" role="status">{periodError}</span> : <span className="field-help">Horário em {timeZone}.</span>}
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    {locked ? <Notice tone="warning">Não foi possível confirmar a autorização. A próxima tentativa usa o mesmo envio; se ela já tiver sido criada, gere outra para ver um código.</Notice> : null}
    <div className="button-row">
      <button className="button button-compact" type="submit" disabled={busy || (!locked && !valid)}>{busy ? 'Gerando…' : locked ? 'Tentar confirmar a mesma autorização' : 'Gerar código'}</button>
      <button className="button button-secondary button-compact" type="button" disabled={busy} onClick={() => close(null)}>Voltar</button>
    </div>
  </form>;
}
