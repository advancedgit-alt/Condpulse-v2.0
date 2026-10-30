import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import type { Amenity } from '../api/types';
import { waitlist, type JoinWaitlistBody, type WaitlistEntry } from '../api/waitlist';
import { scopeKey, type Scope } from './Operations';
import { Empty, ErrorState, Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { codeOf, repeatFailure, settleFailure, uncertain, useIntent } from './operationIntent';

/**
 * Fila de espera (CP-040). A oferta é uma retenção de até 15 minutos: só o servidor diz se ela ainda vale. Aceitar usa
 * chave de operação e, com resultado incerto, trava na mesma chave até a unidade conferir a agenda.
 */
const labels: Record<WaitlistEntry['state'], [string, string]> = {
  WAITING: ['Na fila', 'status-pending'], OFFERED: ['Vaga oferecida', 'status-ready'], ACCEPTED: ['Oferta aceita', 'status-ready'],
  LEFT: ['Saiu da fila', 'status-neutral'], EXPIRED: ['Oferta vencida', 'status-neutral'], CLOSED: ['Período encerrado', 'status-neutral'],
};
const waitlistKey = (scope: Scope) => [...scopeKey(scope), 'waitlist'] as const;
const open = (entry: WaitlistEntry) => entry.state === 'WAITING' || entry.state === 'OFFERED';

export function WaitlistSection({ scope, areas, timeZone, locked }: { scope: Scope; areas: Amenity[]; timeZone: string; locked: boolean }) {
  const entries = useQuery({ queryKey: waitlistKey(scope), queryFn: ({ signal }) => waitlist.unitEntries(scope.tenantId, scope.unitId, signal),
    // Oferta tem prazo curto: enquanto houver entrada aberta, a lista é consultada de novo a cada 30 segundos.
    refetchInterval: (query) => query.state.data?.entries.some(open) ? 30_000 : false });
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'warning' | 'info'; text: string } | null>(null);
  const name = (entry: WaitlistEntry) => areas.find((area) => area.amenityId === entry.amenityId)?.name ?? 'Área da reserva';
  return <section className="reservations-section" aria-labelledby="waitlist-title">
    <div className="section-heading"><div><span className="section-index">03 / FILA DE ESPERA</span><h2 id="waitlist-title">Fila de espera</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={entries.isFetching} onClick={() => void entries.refetch()}>Atualizar fila</button></div>
    <p className="field-help">Quando um período ocupado é liberado, o primeiro da fila recebe a vaga por até 15 minutos. Sem aceite no prazo, a vez passa ao próximo.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {entries.isPending ? <div className="panel"><Loading label="Consultando a fila de espera…" /></div>
      : entries.isError && !entries.data ? codeOf(entries.error) === 'API_NOT_AVAILABLE'
        ? <p className="field-help">A fila de espera ainda não está ativa neste ambiente.</p>
        : <ErrorState error={entries.error} retry={() => void entries.refetch()} />
        : entries.data.entries.length === 0 ? <div className="panel"><Empty title="Nenhuma fila de espera">Ao escolher um período ocupado, você pode entrar na fila e receber a vaga se ela for liberada.</Empty></div>
          : <ol className="management-list" aria-label="Entradas da fila de espera">{entries.data.entries.map((entry) =>
            <WaitlistRow key={entry.waitlistId} scope={scope} entry={entry} area={name(entry)} timeZone={timeZone} locked={locked} onOutcome={setOutcome} />)}</ol>}
    {entries.data?.truncated ? <Notice tone="warning">A lista mostra as 50 entradas mais relevantes.</Notice> : null}
  </section>;
}

function WaitlistRow({ scope, entry, area, timeZone, locked, onOutcome }: { scope: Scope; entry: WaitlistEntry; area: string; timeZone: string; locked: boolean;
  onOutcome(value: { tone: 'success' | 'warning' | 'info'; text: string }): void }) {
  const queries = useQueryClient();
  const [label, tone] = labels[entry.state];
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const intent = useIntent();
  const [held, setHeld] = useState(false);
  const refresh = () => queries.invalidateQueries({ queryKey: scopeKey(scope) });
  async function accept() {
    if (busy || locked) return;
    setBusy(true); setFailure(null);
    const key = intent.keyFor(`${entry.waitlistId}:${entry.version}`);
    try {
      const result = await waitlist.accept(scope.tenantId, entry.waitlistId, key, { unitId: scope.unitId, expectedVersion: entry.version });
      setHeld(false);
      onOutcome(result.reservation.status === 'PENDING_APPROVAL'
        ? { tone: 'info', text: `Oferta aceita: o pedido de ${area} foi enviado para aprovação da gestão.` }
        : result.reservation.status === 'CONFIRMED' ? { tone: 'success', text: `Reserva de ${area} confirmada pela fila de espera.` }
          : { tone: 'warning', text: 'A resposta não confirmou a reserva. Atualize e confira a agenda.' });
    } catch (error) {
      const shown = repeatFailure(error);
      setFailure(shown);
      settleFailure(shown, held, intent, setHeld);
    } finally { setBusy(false); await refresh(); }
  }
  async function leave() {
    if (busy || locked) return;
    setBusy(true); setFailure(null);
    try {
      await waitlist.leave(scope.tenantId, entry.waitlistId, { unitId: scope.unitId, expectedVersion: entry.version });
      onOutcome({ tone: 'success', text: entry.state === 'OFFERED' ? `Vaga de ${area} recusada. A vez passa ao próximo da fila.` : `Você saiu da fila de ${area}.` });
    } catch (error) { setFailure(error); }
    finally { setBusy(false); setConfirming(false); await refresh(); }
  }
  const offered = entry.state === 'OFFERED';
  const expired = offered && entry.offerExpiresAt !== null && Date.parse(entry.offerExpiresAt) <= Date.now();
  return <li className="management-row">
    <div><strong>{area}</strong> <span className={`status-pill ${tone}`}>{label}</span>
      <span>{formatWhen(entry.startsAt, timeZone)} até {formatWhen(entry.endsAt, timeZone)} · regulamento v{entry.acceptedPolicyVersion}</span>
      {entry.state === 'WAITING' && entry.position !== null ? <span className="field-help">Posição na fila: {entry.position}.</span> : null}
      {offered && entry.offerExpiresAt ? <span className="field-help">{expired ? 'O prazo desta oferta terminou. Atualize a fila.'
        : `Aceite até ${formatWhen(entry.offerExpiresAt, timeZone)}. ${entry.approvalRequired ? 'Ao aceitar, vira pedido sujeito à aprovação da gestão.' : 'Ao aceitar, a reserva é confirmada.'}`}</span> : null}
      {!entry.createdByMe && open(entry) ? <span className="field-help">Entrada de outra pessoa da unidade: somente quem entrou na fila pode alterá-la.</span> : null}
    </div>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain(failure) ? ' Atualize a agenda e confira antes de repetir.' : ''}</Notice> : null}
    {held && open(entry) ? <Notice tone="warning">Não foi possível confirmar o aceite. A próxima tentativa usa o mesmo envio, sem criar outra reserva.</Notice> : null}
    {entry.createdByMe && open(entry) ? confirming
      ? <div className="cancel-confirmation" role="group" aria-label={offered ? 'Confirmação de recusa' : 'Confirmação de saída'}>
        <p>{offered ? 'Recusar esta vaga? Ela passa ao próximo da fila e você sai da fila deste período.' : 'Sair da fila deste período? Para voltar, será preciso entrar de novo no fim da fila.'}</p>
        <div className="button-row"><button className="button button-danger" type="button" disabled={busy || locked} onClick={() => void leave()}>{offered ? 'Confirmar recusa' : 'Confirmar saída'}</button>
          <button className="button button-secondary" type="button" disabled={busy} onClick={() => setConfirming(false)}>Voltar</button></div></div>
      : <div className="button-row">
        {offered ? <button className="button button-compact" type="button" disabled={busy || locked || expired} onClick={() => void accept()}>{busy ? 'Confirmando…' : held ? 'Tentar confirmar o mesmo aceite' : 'Aceitar vaga'}</button> : null}
        <button className="button button-secondary button-compact" type="button" disabled={busy || locked || held} onClick={() => setConfirming(true)}>{offered ? 'Recusar vaga' : 'Sair da fila'}</button>
      </div> : null}
  </li>;
}

/** Entrar na fila do período escolhido, quando a consulta mostra que ele está ocupado. */
export function JoinWaitlist({ scope, body, disabled }: { scope: Scope; body: JoinWaitlistBody; disabled: boolean }) {
  const queries = useQueryClient();
  const intent = useIntent();
  const [busy, setBusy] = useState(false);
  const [held, setHeld] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [joined, setJoined] = useState<{ position: number | null; replayed: boolean } | null>(null);
  async function join() {
    if (busy || disabled) return;
    setBusy(true); setFailure(null);
    const key = intent.keyFor(JSON.stringify(body));
    try {
      const result = await waitlist.join(scope.tenantId, key, body);
      setHeld(false); setJoined({ position: result.entry.position, replayed: result.replayed });
    } catch (error) {
      const shown = repeatFailure(error);
      setFailure(shown);
      settleFailure(shown, held, intent, setHeld);
      if (error instanceof ApiError && error.code === 'WAITLIST_SLOT_AVAILABLE') await queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'availability'] });
    } finally { setBusy(false); await queries.invalidateQueries({ queryKey: waitlistKey(scope) }); }
  }
  if (joined) return <Notice tone="success">{joined.replayed ? 'Você já estava na fila deste período.' : 'Você entrou na fila de espera deste período.'}{joined.position !== null ? ` Posição: ${joined.position}.` : ''} Acompanhe na seção Fila de espera.</Notice>;
  return <div className="waitlist-join">
    <p className="field-help">Se o período for liberado, a vaga é oferecida por ordem de entrada, por até 15 minutos.</p>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    <button className="button button-secondary" type="button" disabled={busy || disabled} onClick={() => void join()}>
      {busy ? 'Entrando na fila…' : held ? 'Tentar confirmar a mesma entrada' : 'Entrar na fila de espera'}</button>
  </div>;
}
