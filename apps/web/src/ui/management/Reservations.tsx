import { useMemo, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { newOperationKey } from '../../api/client';
import { management, type ManagedReservation, type ReservationStatus } from '../../api/management';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { addDays, dateInZone, formatWhen, money, toInstant } from '../dateTime';
import { ActionFeedback, ReasonForm, managementKey, reservationStatusLabels, statusTone, useAccessGuard, useAction, useManagement, type ActionResult } from './shared';

type Mode = 'idle' | 'approve' | 'reject' | 'cancel';

/**
 * Mensagem fiel ao estado devolvido: a repetição natural do servidor devolve o estado atual sem nova versão, então
 * uma decisão ou cancelamento feito antes por outra pessoa não aparece como sucesso desta ação.
 */
export function decisionOutcome(result: ManagedReservation, decision: 'APPROVE' | 'REJECT', readVersion: number): ActionResult {
  const applied = result.version === readVersion + 1;
  if (applied && decision === 'APPROVE' && result.status === 'CONFIRMED') return { tone: 'success', text: 'Pedido aprovado. A reserva está confirmada.' };
  if (applied && decision === 'REJECT' && result.status === 'REJECTED') return { tone: 'success', text: 'Pedido rejeitado. O período foi liberado.' };
  return { tone: 'info', text: `Este pedido já estava ${(reservationStatusLabels[result.status] ?? result.status).toLowerCase()}. Nenhuma alteração foi feita e o seu motivo não foi registrado.` };
}
export function cancellationOutcome(result: ManagedReservation, reason: string, readVersion: number): ActionResult {
  if (result.status === 'CANCELLED' && result.version === readVersion + 1 && result.decisionReason === reason)
    return { tone: 'success', text: result.kind === 'BLOCK' ? 'Bloqueio encerrado. O período foi liberado.' : 'Reserva cancelada com motivo registrado.' };
  return { tone: 'warning', text: `${result.kind === 'BLOCK' ? 'O bloqueio já estava encerrado' : 'A reserva já estava cancelada'}${result.decisionReason ? ` (motivo registrado: ${result.decisionReason})` : ''}. O seu motivo não foi registrado.` };
}

function ReservationItem({ reservation, onDone }: { reservation: ManagedReservation; onDone(result: ActionResult): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [mode, setMode] = useState<Mode>('idle');
  // Versão lida ao abrir a confirmação; nova consulta da lista não troca o alvo da decisão em andamento.
  const [readVersion, setReadVersion] = useState(reservation.version);
  const block = reservation.kind === 'BLOCK';
  const pending = reservation.status === 'PENDING_APPROVAL';
  const open = new Date(reservation.endsAt).getTime() > Date.now();
  // Pedido pendente é decidido (aprovar/rejeitar); cancelamento pela gestão vale para reserva confirmada e bloqueio.
  const cancellable = open && reservation.status === 'CONFIRMED';
  const time = (value: string) => new Intl.DateTimeFormat('pt-BR', { timeZone: scope.timeZone, hour: '2-digit', minute: '2-digit' }).format(new Date(value));
  // Período de vários dias mostra a data do fim, não só o horário.
  const end = dateInZone(scope.timeZone, new Date(reservation.startsAt)) === dateInZone(scope.timeZone, new Date(reservation.endsAt))
    ? time(reservation.endsAt) : formatWhen(reservation.endsAt, scope.timeZone);
  const start = (next: Mode) => { action.clear(); setReadVersion(reservation.version); setMode(next); };
  const finish = async (result: ActionResult) => {
    setMode('idle'); onDone(result);
    await queries.invalidateQueries({ queryKey: managementKey(scope, 'reservations') });
  };
  function decide(decision: 'APPROVE' | 'REJECT', reason: string | null) {
    void action.run(() => management.decideReservation(scope.tenantId, reservation.reservationId,
      { decision, expectedVersion: readVersion, reason }), '', (result) => finish(decisionOutcome(result, decision, readVersion)));
  }
  function cancel(reason: string | null) {
    if (!reason) return;
    void action.run(() => management.cancelReservation(scope.tenantId, reservation.reservationId,
      { expectedVersion: readVersion, reason }), '', (result) => finish(cancellationOutcome(result, reason, readVersion)));
  }
  const who = block ? 'Bloqueio de manutenção' : `${reservation.unitLabel ?? 'Unidade'}${reservation.requesterName ? ` · ${reservation.requesterName}` : ''}`;
  return <article className="reservation-card management-item">
    <div className="reservation-row">
      <div className="reservation-info">
        <h3>{reservation.amenityName}</h3>
        <p>{formatWhen(reservation.startsAt, scope.timeZone)} — {end}</p>
        <small>{who}{!block ? ` · regulamento v${reservation.acceptedPolicyVersion} · taxa informativa ${money(reservation.feeCents)}` : ''}</small>
        {pending && reservation.expiresAt ? <p className="reservation-pending">Decidir até {formatWhen(reservation.expiresAt, scope.timeZone)}. Sem decisão, o pedido expira.</p> : null}
        {reservation.decisionReason ? <p className="reservation-reason">Motivo: {reservation.decisionReason}</p> : null}
      </div>
      <span className={`status-pill ${statusTone(reservation.status)}`}>{block && reservation.status === 'CONFIRMED' ? 'Bloqueio ativo' : reservationStatusLabels[reservation.status]}</span>
    </div>
    {mode === 'idle' && (pending || cancellable) ? <div className="reservation-actions">
      {pending ? <div className="button-row">
        <button className="button button-compact" type="button" disabled={action.state.pending} onClick={() => start('approve')}>Aprovar</button>
        <button className="button button-secondary button-compact" type="button" disabled={action.state.pending} onClick={() => start('reject')}>Rejeitar</button>
      </div> : <span>{block ? 'O bloqueio ocupa o período para todas as unidades.' : 'Cancelamento pela gestão exige motivo e avisa a unidade.'}</span>}
      {cancellable ? <button className="text-button danger-text" type="button" disabled={action.state.pending} onClick={() => start('cancel')}>{block ? 'Encerrar bloqueio' : 'Cancelar reserva'}</button> : null}
    </div> : null}
    {mode === 'approve' ? <ReasonForm id={`approve-${reservation.reservationId}`} label="Observação para a unidade" required={false} pending={action.state.pending}
      confirmLabel="Confirmar aprovação" submit={(reason) => decide('APPROVE', reason)} cancel={() => setMode('idle')} /> : null}
    {mode === 'reject' ? <ReasonForm id={`reject-${reservation.reservationId}`} label="Motivo da rejeição" required pending={action.state.pending}
      confirmLabel="Confirmar rejeição" tone="danger" submit={(reason) => decide('REJECT', reason)} cancel={() => setMode('idle')} /> : null}
    {mode === 'cancel' ? <ReasonForm id={`cancel-${reservation.reservationId}`} label={block ? 'Motivo do encerramento' : 'Motivo do cancelamento'} required pending={action.state.pending}
      confirmLabel={block ? 'Confirmar encerramento' : 'Confirmar cancelamento'} tone="danger" submit={cancel} cancel={() => setMode('idle')} /> : null}
    <ActionFeedback state={action.state} success={false} />
  </article>;
}

function ReservationList({ status, from, until, empty }: { status: ReservationStatus | null; from: string | null; until: string | null; empty: string }) {
  const scope = useManagement();
  const [done, setDone] = useState<ActionResult | null>(null);
  const list = useQuery({ queryKey: managementKey(scope, 'reservations', status, from, until),
    queryFn: ({ signal }) => management.reservations(scope.tenantId, { status, from, until }, signal) });
  if (useAccessGuard(list.error)) return null;
  if (list.isPending) return <div className="panel"><Loading label="Consultando reservas…" /></div>;
  if (list.isError) return <ErrorState error={list.error} retry={() => void list.refetch()} />;
  return <>
    {done ? <Notice tone={done.tone}>{done.text}</Notice> : null}
    {list.data.reservations.length === 0 ? <div className="panel"><Empty title="Nada por aqui">{empty}</Empty></div>
      : <div className="reservation-list">{list.data.reservations.map((item) => <ReservationItem key={item.reservationId} reservation={item} onDone={setDone} />)}</div>}
    {list.data.truncated ? <Notice tone="warning">Há mais registros do que o limite desta consulta. Reduza o período ou filtre a situação.</Notice> : null}
  </>;
}

export function PendingReservations() {
  return <ReservationList status="PENDING_APPROVAL" from={null} until={null} empty="Nenhum pedido de reserva aguardando decisão." />;
}

export function ReservationAgenda() {
  const scope = useManagement();
  const [date, setDate] = useState(() => dateInZone(scope.timeZone));
  const [status, setStatus] = useState<ReservationStatus | ''>('');
  const range = useMemo(() => {
    try { return { from: toInstant(`${date}T00:00`, scope.timeZone), until: toInstant(`${addDays(date, 30)}T00:00`, scope.timeZone), error: '' }; }
    catch (error) { return { from: null, until: null, error: error instanceof Error ? error.message : 'Período inválido.' }; }
  }, [date, scope.timeZone]);
  return <>
    <MaintenanceBlockForm />
    <section aria-labelledby="agenda-title" className="management-section">
      <div className="section-heading"><div><span className="section-index">AGENDA</span><h2 id="agenda-title">Reservas, pedidos e bloqueios</h2></div></div>
      <div className="reservation-filter">
        <label htmlFor="management-date">A partir de</label>
        <input id="management-date" type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        <label htmlFor="management-status">Situação</label>
        <select id="management-status" value={status} onChange={(event) => setStatus(event.target.value as ReservationStatus | '')}>
          <option value="">Todas</option>
          {Object.entries(reservationStatusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <span>Período de 30 dias · horários em {scope.timeZone}</span>
      </div>
      {range.error ? <Notice tone="error">{range.error}</Notice>
        : <ReservationList status={status || null} from={range.from} until={range.until} empty="Nenhuma reserva neste período." />}
    </section>
  </>;
}

/** Bloqueio idempotente: a mesma chave é reaproveitada enquanto área, período e motivo não mudam. */
function MaintenanceBlockForm() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const areas = useQuery({ queryKey: managementKey(scope, 'amenities'), queryFn: ({ signal }) => management.amenities(scope.tenantId, signal) });
  const [open, setOpen] = useState(false);
  const [amenityId, setAmenityId] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [reason, setReason] = useState('');
  const [intent, setIntent] = useState<{ fingerprint: string; key: string } | null>(null);
  const [validation, setValidation] = useState('');
  const active = areas.data?.filter((area) => area.active) ?? [];
  async function submit(event: FormEvent) {
    event.preventDefault();
    setValidation('');
    let startsAt: string; let endsAt: string;
    try {
      startsAt = toInstant(start, scope.timeZone); endsAt = toInstant(end, scope.timeZone);
      if (new Date(startsAt) >= new Date(endsAt)) throw new Error('O fim deve ser posterior ao início.');
    } catch (error) { setValidation(error instanceof Error ? error.message : 'Confira o período informado.'); return; }
    const body = { startsAt, endsAt, reason: reason.trim() };
    if (!amenityId || !body.reason) return;
    const fingerprint = JSON.stringify([amenityId, body]);
    const key = intent?.fingerprint === fingerprint ? intent.key : newOperationKey();
    setIntent({ fingerprint, key });
    const ok = await action.run(() => management.createMaintenanceBlock(scope.tenantId, amenityId, key, body),
      (result) => !result.replayed ? { tone: 'success', text: 'Bloqueio registrado. O período aparece ocupado para todas as unidades.' }
        : result.reservation.status === 'CONFIRMED' ? { tone: 'info', text: 'Este bloqueio já estava registrado com os mesmos dados. Nada novo foi criado.' }
          : { tone: 'warning', text: 'Este bloqueio já tinha sido registrado e depois foi encerrado. Nada novo foi criado; confira a agenda.' },
      async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'reservations') }); });
    if (ok) { setIntent(null); setStart(''); setEnd(''); setReason(''); }
  }
  return <section className="panel management-form" aria-labelledby="block-title">
    <div className="section-heading"><div><span className="section-index">MANUTENÇÃO</span><h2 id="block-title">Bloquear área</h2></div>
      <button className="button button-secondary button-compact" type="button" onClick={() => setOpen((value) => !value)}>{open ? 'Fechar' : 'Novo bloqueio'}</button></div>
    {open ? <form className="stack-form" onSubmit={(event) => void submit(event)}>
      <p className="field-help">Pedidos e reservas válidos no período precisam ser decididos ou cancelados com motivo antes do bloqueio. Horários em {scope.timeZone}.</p>
      {areas.isError ? <ErrorState error={areas.error} retry={() => void areas.refetch()} /> : null}
      <label htmlFor="block-area">Área</label>
      <select id="block-area" required value={amenityId} onChange={(event) => setAmenityId(event.target.value)} disabled={action.state.pending}>
        <option value="">Selecione a área</option>
        {active.map((area) => <option key={area.amenityId} value={area.amenityId}>{area.name}</option>)}
      </select>
      <div className="two-fields">
        <div><label htmlFor="block-start">Início</label><input id="block-start" type="datetime-local" step="60" required value={start} onChange={(event) => setStart(event.target.value)} disabled={action.state.pending} /></div>
        <div><label htmlFor="block-end">Fim</label><input id="block-end" type="datetime-local" step="60" required value={end} onChange={(event) => setEnd(event.target.value)} disabled={action.state.pending} /></div>
      </div>
      <label htmlFor="block-reason">Motivo</label>
      <textarea id="block-reason" required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending} />
      {validation ? <Notice tone="error">{validation}</Notice> : null}
      <button className="button" type="submit" disabled={action.state.pending || !amenityId || !start || !end || !reason.trim()}>{action.state.pending ? 'Registrando…' : 'Registrar bloqueio'}</button>
      <ActionFeedback state={action.state} />
    </form> : null}
  </section>;
}
