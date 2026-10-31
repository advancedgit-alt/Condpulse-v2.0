import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { management } from '../../api/management';
import { rental, type ManagedRental } from '../../api/rental';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { ActionFeedback, ReasonForm, managementKey, useAccessGuard, useAction, useManagement } from './shared';

function RentalItem({ item, onEnded }: { item: ManagedRental; onEnded(): Promise<void> }) {
  const scope = useManagement();
  const action = useAction();
  const [ending, setEnding] = useState(false);
  return <li className="management-row">
    <div><strong><bdi>{item.unitLabel}</bdi></strong><span>Alugada desde {formatWhen(item.startedAt, scope.timeZone)}</span>
      <span>Proprietário{item.owners.length > 1 ? 's' : ''}: <bdi>{item.owners.join(', ')}</bdi></span></div>
    {!ending ? <button className="text-button danger-text" type="button" disabled={action.state.pending} onClick={() => { action.clear(); setEnding(true); }}
      aria-label={`Encerrar locação da unidade ${item.unitLabel}`}>Encerrar locação</button>
      : <ReasonForm id={`end-rental-${item.unitId}`} label="Motivo do encerramento" required pending={action.state.pending} confirmLabel="Confirmar encerramento"
        tone="danger" cancel={() => setEnding(false)} submit={(reason) => { if (reason) void action.run(() => rental.end(scope.tenantId, item.unitId, reason),
          `Locação da unidade ${item.unitLabel} encerrada. Os vínculos do inquilino continuam até serem revogados.`,
          async () => { setEnding(false); await onEnded(); }); }} />}
    <ActionFeedback state={action.state} />
  </li>;
}

/**
 * Locação (V034): a gestão marca a unidade como alugada; o proprietário perde a visão da unidade e só inclui e remove o
 * inquilino. Encerrar devolve ao proprietário o que o vínculo dele permite; os vínculos do inquilino seguem até a revogação.
 */
export function RentalsPanel() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const key = managementKey(scope, 'rentals');
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => rental.active(scope.tenantId, signal) });
  const units = useQuery({ queryKey: managementKey(scope, 'units'), queryFn: ({ signal }) => management.units(scope.tenantId, signal) });
  const [unitId, setUnitId] = useState('');
  const [reason, setReason] = useState('');
  if (useAccessGuard(list.error)) return null;
  const refresh = async () => { await queries.invalidateQueries({ queryKey: key }); };
  const rentedIds = new Set((list.data ?? []).map((item) => item.unitId));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!unitId || !reason.trim()) return;
    await action.run(() => rental.start(scope.tenantId, unitId, reason.trim()),
      (started) => ({ tone: 'success', text: `Unidade ${started.unitLabel} marcada como alugada. ${started.owners.join(', ')} passa a só incluir e remover o inquilino.` }),
      async () => { setUnitId(''); setReason(''); await refresh(); });
  }
  return <div className="panel" aria-labelledby="rentals-title">
    <h3 id="rentals-title">Locações</h3>
    <p className="field-help">Proprietário de unidade alugada perde a visão da unidade (reservas, encomendas, visitas, pedidos, avisos e acessos) e só
      inclui e remove o inquilino. Revogue antes o vínculo de morador do proprietário, se houver.</p>
    <form className="management-form stack-form" onSubmit={(event) => void submit(event)} aria-label="Marcar unidade como alugada">
      {units.isError ? <Notice tone="error">Não foi possível carregar as unidades: {errorMessage(units.error)}</Notice> : null}
      <label htmlFor="rental-unit">Unidade alugada</label>
      <select id="rental-unit" value={unitId} required onChange={(event) => setUnitId(event.target.value)} disabled={action.state.pending}>
        <option value="">Selecione a unidade</option>
        {(units.data?.units ?? []).filter((unit) => unit.status === 'ACTIVE' && !rentedIds.has(unit.unitId)).map((unit) =>
          <option key={unit.unitId} value={unit.unitId}>{unit.blockName ? `${unit.blockName} · ` : ''}{unit.label}</option>)}
      </select>
      <label htmlFor="rental-reason">Motivo da locação</label>
      <textarea id="rental-reason" rows={2} maxLength={500} required value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending} />
      <button className="button" type="submit" disabled={action.state.pending || !unitId || !reason.trim()}>{action.state.pending ? 'Enviando…' : 'Marcar como alugada'}</button>
      <ActionFeedback state={action.state} />
    </form>
    {list.isPending ? <Loading label="Consultando locações…" /> : list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} />
      : list.data.length === 0 ? <Empty icon="home" title="Nenhuma unidade alugada">As unidades marcadas como alugadas aparecerão aqui.</Empty>
      : <ul className="management-list" aria-label="Unidades alugadas">{list.data.map((item) => <RentalItem key={item.unitId} item={item} onEnded={refresh} />)}</ul>}
  </div>;
}
