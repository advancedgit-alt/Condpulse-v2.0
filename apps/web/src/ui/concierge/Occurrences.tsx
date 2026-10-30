import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { occurrenceCategories, occurrences, type CreateOccurrence, type Occurrence, type OccurrenceCategory } from '../../api/occurrences';
import { parcels } from '../../api/parcels';
import { displayText, multilineText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { formatWhen } from '../dateTime';
import { ActionFeedback, useAccessGuard, useAction, useManagement } from '../management/shared';
import { codeOf, conciergeKey, uncertain, unitName } from './shared';

/**
 * Ocorrências da portaria (CP-027): o que precisa de verificação (equipamento, segurança, manutenção), registrado pela
 * portaria ou pela gestão e encerrado com a resolução. Registro interno da equipe: moradores não veem. Envio incerto
 * mantém o mesmo identificador: repetir confirma o mesmo registro, sem duplicar.
 */
export const categoryLabels: Record<OccurrenceCategory, string> = {
  EQUIPMENT: 'Equipamento', SECURITY: 'Segurança', MAINTENANCE: 'Manutenção', CLEANING: 'Limpeza', NOISE: 'Barulho', OTHER: 'Outro',
};
const newId = () => globalThis.crypto.randomUUID();

export function OccurrenceSection({ canPickUnit }: { canPickUnit: boolean }) {
  const scope = useManagement();
  const [resolved, setResolved] = useState(false);
  const list = useQuery({ queryKey: conciergeKey(scope, 'occurrences', resolved), queryFn: ({ signal }) => occurrences.list(scope.tenantId, resolved, signal),
    refetchInterval: 30_000 });
  const [creating, setCreating] = useState(false);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  if (useAccessGuard(list.error)) return null;
  const open = list.data?.open ?? 0;
  return <section className="management-section" aria-labelledby="occurrences-title">
    <div className="section-heading"><div><span className="section-index">OCORRÊNCIAS</span>
      <h2 id="occurrences-title">Ocorrências{open > 0 ? ` · ${open} em aberto` : ''}</h2></div>
      <div className="button-row">
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar ocorrências</button>
        {!creating ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setCreating(true); }}>Registrar ocorrência</button> : null}
      </div></div>
    <p className="field-help">Registro interno da equipe: portão, câmera, segurança ou manutenção que precisa de verificação. Moradores não veem.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {creating ? <OccurrenceForm canPickUnit={canPickUnit} close={(result) => { setCreating(false); if (result) setOutcome(result); }} /> : null}
    <label className="checkbox-row"><input type="checkbox" checked={resolved} onChange={(event) => setResolved(event.target.checked)} /> Mostrar encerradas dos últimos 30 dias</label>
    {list.isPending ? <div className="panel"><Loading label="Consultando ocorrências…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.occurrences.length === 0 ? <div className="panel"><Empty icon="alert" title="Nenhuma ocorrência em aberto">Quando algo precisar de verificação, registre aqui para a equipe acompanhar.</Empty></div>
          : <ol className="management-list" aria-label="Ocorrências">{list.data.occurrences.map((item) => <OccurrenceRow key={item.occurrenceId} item={item} done={setOutcome} />)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 primeiras.</Notice> : null}
  </section>;
}

function OccurrenceForm({ canPickUnit, close }: { canPickUnit: boolean; close(result: { tone: 'success' | 'info'; text: string } | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const units = useQuery({ queryKey: conciergeKey(scope, 'units', null), queryFn: ({ signal }) => parcels.units(scope.tenantId, null, signal), enabled: canPickUnit });
  const [pending, setPending] = useState<CreateOccurrence | null>(null);
  const [category, setCategory] = useState<OccurrenceCategory>('EQUIPMENT');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [unitId, setUnitId] = useState('');
  const normalizedTitle = displayText(title, 120);
  const normalizedDescription = multilineText(description, 2000);
  const locked = pending !== null;
  const valid = locked || (normalizedTitle !== null && [...normalizedTitle].length >= 3 && normalizedDescription !== null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body = pending ?? { occurrenceId: newId(), category, title: normalizedTitle!, description: normalizedDescription!, unitId: unitId || null };
    setPending(body);
    let failure: unknown = null;
    let replayed = false;
    const ok = await action.run(async () => {
      try { const result = await occurrences.create(scope.tenantId, body); replayed = result.replayed; return result; }
      catch (error) { failure = error; throw error; }
    }, '', async () => { await queries.invalidateQueries({ queryKey: conciergeKey(scope, 'occurrences') }); });
    if (ok) {
      setPending(null);
      close(replayed ? { tone: 'info', text: 'Esta ocorrência já estava registrada. Nada novo foi criado.' }
        : { tone: 'success', text: `Ocorrência registrada: ${body.title}.` });
      return;
    }
    // Resultado incerto mantém o mesmo identificador; recusa definitiva libera o formulário.
    if (!uncertain(failure)) setPending(null);
    void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'occurrences') });
  }
  const busy = action.state.pending;
  return <form className="panel stack-form" onSubmit={(event) => void submit(event)} aria-label="Registrar ocorrência">
    <label htmlFor="occurrence-category">Categoria</label>
    <select id="occurrence-category" value={pending?.category ?? category} disabled={busy || locked} onChange={(event) => setCategory(event.target.value as OccurrenceCategory)}>
      {occurrenceCategories.map((value) => <option key={value} value={value}>{categoryLabels[value]}</option>)}</select>
    <label htmlFor="occurrence-title">Título</label>
    <input id="occurrence-title" maxLength={120} value={pending?.title ?? title} disabled={busy || locked} onChange={(event) => setTitle(event.target.value)} autoComplete="off" />
    <label htmlFor="occurrence-description">Descrição</label>
    <textarea id="occurrence-description" rows={4} maxLength={2000} value={pending?.description ?? description} disabled={busy || locked}
      onChange={(event) => setDescription(event.target.value)} />
    {canPickUnit ? <>
      <label htmlFor="occurrence-unit">Unidade (opcional)</label>
      <select id="occurrence-unit" value={pending?.unitId ?? unitId} disabled={busy || locked || units.isPending} onChange={(event) => setUnitId(event.target.value)}>
        <option value="">Área comum, sem unidade</option>
        {units.data?.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)}</option>)}</select>
      {units.isError ? <ErrorState error={units.error} retry={() => void units.refetch()} /> : null}
    </> : null}
    <ActionFeedback state={action.state} success={false} />
    {codeOf(action.state.error) === 'OCCURRENCE_ID_CONFLICT' ? <Notice tone="warning">Atualize a lista antes de registrar de novo.</Notice> : null}
    {locked && !busy ? <Notice tone="warning">O resultado não foi confirmado. Use “Tentar confirmar a mesma ocorrência”: se ela já foi registrada, nada novo é criado.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || busy}>{busy ? 'Registrando…' : locked ? 'Tentar confirmar a mesma ocorrência' : 'Salvar ocorrência'}</button>
      <button className="button button-secondary" type="button" disabled={busy} onClick={() => { setPending(null); action.clear(); close(null); }}>{locked ? 'Conferi a lista: descartar' : 'Voltar'}</button>
    </div>
  </form>;
}

function OccurrenceRow({ item, done }: { item: Occurrence; done(result: { tone: 'success' | 'info'; text: string }): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [resolving, setResolving] = useState(false);
  const [resolution, setResolution] = useState('');
  const normalized = multilineText(resolution, 1000);
  const id = `occurrence-resolution-${item.occurrenceId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!normalized) return;
    const ok = await action.run(() => occurrences.resolve(scope.tenantId, item.occurrenceId, normalized, item.version),
      '', async () => { await queries.invalidateQueries({ queryKey: conciergeKey(scope, 'occurrences') }); });
    if (ok) { setResolving(false); done({ tone: 'success', text: 'Ocorrência encerrada com a resolução registrada.' }); }
    else void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'occurrences') });
  }
  return <li className="management-row">
    <div><strong><bdi>{item.title}</bdi></strong> <span className={`status-pill ${item.state === 'OPEN' ? 'status-pending' : 'status-neutral'}`}>{item.state === 'OPEN' ? 'Em aberto' : 'Encerrada'}</span>
      <span>{categoryLabels[item.category]}{item.unitLabel ? ` · unidade ${item.unitLabel}` : ' · área comum'} · registrada por {item.createdByMe ? 'você' : <bdi>{item.createdByName}</bdi>} em {formatWhen(item.createdAt, scope.timeZone)}</span>
      <p className="announcement-body"><bdi>{item.description}</bdi></p>
      {item.resolution ? <span>Resolução por <bdi>{item.resolvedByName}</bdi> em {formatWhen(item.resolvedAt!, scope.timeZone)}: <bdi>{item.resolution}</bdi></span> : null}</div>
    <ActionFeedback state={action.state} />
    {item.state === 'OPEN' ? !resolving
      ? <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => { action.clear(); setResolving(true); }}>Encerrar ocorrência</button></div>
      : <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Encerrar ${item.title}`}>
        <label htmlFor={id}>Resolução</label>
        <textarea id={id} rows={2} maxLength={1000} value={resolution} onChange={(event) => setResolution(event.target.value)} disabled={action.state.pending} />
        <div className="button-row">
          <button className="button" type="submit" disabled={!normalized || action.state.pending}>{action.state.pending ? 'Encerrando…' : 'Confirmar encerramento'}</button>
          <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={() => setResolving(false)}>Voltar</button>
        </div>
      </form> : null}
  </li>;
}
