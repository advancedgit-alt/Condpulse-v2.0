import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, newOperationKey } from '../api/client';
import { visits, type VisitPermit } from '../api/visits';
import type { Reservation } from '../api/types';
import { scopeKey, type Scope } from './Operations';
import { Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';

const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
const stateLabels: Record<VisitPermit['state'], string> = { SCHEDULED: 'Agendado', ACTIVE: 'Em vigor', REVOKED: 'Revogado', EXPIRED: 'Encerrado' };

/**
 * Convidados da reserva (CP-038). Cada convidado vira um convite da unidade com a janela do evento (até 1 hora antes do
 * início até o fim do uso), conferido pela portaria; cancelar a reserva revoga todos de uma vez.
 */
export function ReservationGuests({ scope, reservation, capacity, timeZone, locked }:
  { scope: Scope; reservation: Reservation; capacity: number | null; timeZone: string; locked: boolean }) {
  const queries = useQueryClient();
  const [open, setOpen] = useState(false);
  const key = [...scopeKey(scope), 'guests', reservation.reservationId] as const;
  const guests = useQuery({ queryKey: key, enabled: open, queryFn: ({ signal }) => visits.reservationGuests(scope.tenantId, reservation.reservationId, signal) });
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // Chave presa aos dados: repetir depois de resultado incerto confirma o mesmo convidado, sem duplicar.
  const [intent, setIntent] = useState<{ body: string; key: string } | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const active = (guests.data?.permits ?? []).filter((item) => item.state === 'ACTIVE' || item.state === 'SCHEDULED');
  const full = capacity !== null && active.length >= capacity;
  const pendingUncertain = intent !== null && uncertain(failure);

  async function add(event: FormEvent) {
    event.preventDefault();
    if (busy || locked) return;
    const body = { visitorName: name.trim(), documentLast4: document.trim() ? document.trim().toUpperCase() : null };
    const serialized = JSON.stringify(body);
    const operation = intent?.body === serialized ? intent.key : newOperationKey();
    setIntent({ body: serialized, key: operation }); setBusy(true); setFailure(null); setOutcome(null);
    try {
      const result = await visits.addGuest(scope.tenantId, reservation.reservationId, operation, body);
      setIntent(null); setName(''); setDocument('');
      setOutcome(`${result.permit.visitorName} foi convidado(a). A portaria vê o nome de ${formatWhen(result.permit.validFrom, timeZone)} até o fim do uso.`);
    } catch (error) {
      setFailure(error);
      if (!uncertain(error)) setIntent(null);
    } finally {
      setBusy(false);
      await queries.invalidateQueries({ queryKey: key });
    }
  }
  async function remove(guest: VisitPermit) {
    if (busy || locked) return;
    setBusy(true); setFailure(null); setOutcome(null);
    try {
      await visits.revokePermit(scope.tenantId, guest.permitId, { reason: 'Convidado removido pela unidade', expectedVersion: guest.version });
      setOutcome(`Convite de ${guest.visitorName} revogado.`);
    } catch (error) { setFailure(error); }
    finally { setBusy(false); setRemoving(null); await queries.invalidateQueries({ queryKey: key }); }
  }

  return <section className="reservation-guests" aria-label={`Convidados da reserva de ${formatWhen(reservation.startsAt, timeZone)}`}>
    <button className="text-button" type="button" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? 'Ocultar convidados' : 'Convidados'}</button>
    {open ? <>
      <p className="field-help">Cada convidado entra na lista da portaria de até 1 hora antes do início até o fim do uso. Cancelar a reserva revoga todos.</p>
      {guests.isPending ? <p>Carregando convidados…</p>
        : guests.isError ? <Notice tone="error">{errorMessage(guests.error)}</Notice>
          : guests.data.permits.length === 0 ? <p>Nenhum convidado.</p>
            : <ul className="guest-list" aria-label="Convidados">{guests.data.permits.map((guest) => <li key={guest.permitId}>
              <span><bdi>{guest.visitorName}</bdi>{guest.documentLast4 ? ` · documento final ${guest.documentLast4}` : ''} · {stateLabels[guest.state]}</span>
              {(guest.state === 'ACTIVE' || guest.state === 'SCHEDULED') ? removing === guest.permitId
                ? <span className="button-row"><button className="button button-danger button-compact" type="button" disabled={busy || locked}
                    onClick={() => void remove(guest)}>Confirmar remoção</button>
                  <button className="button button-secondary button-compact" type="button" disabled={busy} onClick={() => setRemoving(null)}>Manter</button></span>
                : <button className="text-button danger-text" type="button" disabled={busy || locked} onClick={() => setRemoving(guest.permitId)}
                    aria-label={`Remover ${guest.visitorName}`}>Remover</button> : null}
            </li>)}</ul>}
      {guests.data?.truncated ? <Notice tone="warning">A lista mostra os convidados ativos primeiro e, depois, os 500 mais recentes.</Notice> : null}
      {outcome ? <Notice tone="success">{outcome}</Notice> : null}
      {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
      {full && !pendingUncertain ? <Notice tone="info">{`A área permite até ${capacity} convidados nesta reserva.`}</Notice>
        : <form className="guest-form" onSubmit={(event) => void add(event)} aria-label="Adicionar convidado">
          <label htmlFor={`guest-name-${reservation.reservationId}`}>Nome do convidado</label>
          <input id={`guest-name-${reservation.reservationId}`} value={name} maxLength={160} required disabled={busy || pendingUncertain}
            onChange={(event) => setName(event.target.value)} />
          <label htmlFor={`guest-doc-${reservation.reservationId}`}>Final do documento (opcional)</label>
          <input id={`guest-doc-${reservation.reservationId}`} value={document} maxLength={4} disabled={busy || pendingUncertain}
            onChange={(event) => setDocument(event.target.value)} />
          <button className="button button-compact" type="submit" disabled={busy || locked || name.trim().length < 2}>
            {busy ? 'Enviando…' : pendingUncertain ? 'Tentar confirmar o mesmo convidado' : 'Adicionar convidado'}</button>
        </form>}
    </> : null}
  </section>;
}
