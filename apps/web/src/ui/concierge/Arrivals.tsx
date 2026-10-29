import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { parcels } from '../../api/parcels';
import { collapseText, displayText } from '../../api/text';
import { visits, type AccessEntryBody, type AccessEvidence, type AccessMutation, type AccessRecord, type Arrival, type ArrivalMutation,
  type ArrivalState, type RegisterArrivalBody, type VisitPermit } from '../../api/visits';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { ActionFeedback, isAccessError, useAccessGuard, useAction, useManagement, type ActionResult } from '../management/shared';
import { codeOf, conciergeKey, repeatFailure, settleFailure, uncertain, unitName, useIntent, usePendingOperation } from './shared';

/**
 * Visitas na portaria (CP-025 a CP-028): registrar quem chegou sem convite válido, acompanhar a fila das últimas 24
 * horas, conferir as visitas previstas e registrar entrada e saída. Entrada só aparece para quem tem autorização
 * vigente — chegada autorizada pela unidade ou convite em vigor —, e o servidor confere de novo. Sem resposta da unidade
 * nada é liberado: a portaria segue o interfone ou o procedimento presencial e registra a origem da confirmação.
 */
export function ArrivalDesk() {
  return <>
    <RegisterArrival />
    <ArrivalQueue />
    <ExpectedVisits />
    <AccessLog />
  </>;
}

const accessKey = (scope: { accountId: string; tenantId: string }) => conciergeKey(scope, 'access');
/** Registros de entrada e saída das últimas 24 horas (sem tempo real ainda: consultados de novo a cada 10 segundos). */
function useAccessRecords() {
  const scope = useManagement();
  return useQuery({ queryKey: accessKey(scope), queryFn: ({ signal }) => visits.records(scope.tenantId, signal),
    refetchInterval: (query) => isAccessError(query.state.error) ? false : 10_000 });
}
/** Entrada ainda válida (sem correção), da mais recente. */
const validEntries = (records: AccessRecord[] | undefined, match: (record: AccessRecord) => boolean) =>
  (records ?? []).filter((record) => record.correction === null && match(record));
const exitState = (record: AccessRecord) => record.exit !== null && record.exit.correction === null;

const arrivalsKey = (scope: { accountId: string; tenantId: string }) => conciergeKey(scope, 'arrivals');
const documentPattern = /^[0-9A-Z]{2,4}$/;

function RegisterArrival() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const intent = useIntent();
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState<string | null>(null);
  const units = useQuery({ queryKey: conciergeKey(scope, 'units', term), queryFn: ({ signal }) => parcels.units(scope.tenantId, term, signal) });
  const [unitId, setUnitId] = useState('');
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  // Trava depois de resultado incerto: nada é reenviado com outra chave sem conferir a fila.
  const [locked, setLocked] = useState(false);
  if (useAccessGuard(units.error)) return null;
  const visitor = displayText(name, 160);
  const documentValue = collapseText(document).toUpperCase();
  const documentValid = documentValue === '' || documentPattern.test(documentValue);
  const valid = Boolean(unitId) && visitor !== null && [...visitor].length >= 2 && documentValid;
  const refreshQueue = () => queries.invalidateQueries({ queryKey: arrivalsKey(scope) });
  async function submit() {
    if (!valid) return;
    const body: RegisterArrivalBody = { unitId, visitorName: visitor!, documentLast4: documentValue || null, permitId: null };
    const key = intent.keyFor(JSON.stringify(body));
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await visits.register(scope.tenantId, key, body); }
      catch (error) { failure = error; throw repeatFailure(error); }
    }, (result: ArrivalMutation): ActionResult => {
      if (result.arrival.unitId !== unitId || result.arrival.visitorName !== body.visitorName)
        return { tone: 'warning', text: 'A resposta não confirmou esta chegada. Confira a fila antes de registrar de novo.' };
      return result.replayed
        ? { tone: 'info', text: 'Esta chegada já estava registrada com os mesmos dados. Nada novo foi criado.' }
        : { tone: 'success', text: `Chegada de ${result.arrival.visitorName} registrada para ${unitName(result.arrival)}. A unidade pode responder pelo aplicativo até ${formatWhen(result.arrival.expiresAt, scope.timeZone)}; até lá, nada está autorizado.` };
    }, async () => { await refreshQueue(); });
    if (ok) {
      intent.reset(); setLocked(false); setUnitId(''); setName(''); setDocument('');
      return;
    }
    settleFailure(failure, locked, intent, setLocked);
    void refreshQueue();
  }
  function restart() { intent.reset(); setLocked(false); action.clear(); }
  const busy = action.state.pending || locked;
  return <section className="management-section" aria-labelledby="arrival-title">
    <div className="section-heading"><div><span className="section-index">01 / CHEGADA</span><h2 id="arrival-title">Registrar chegada</h2></div></div>
    <form className="panel stack-form" onSubmit={(event: FormEvent) => { event.preventDefault(); void submit(); }} aria-label="Registrar chegada">
      <Notice>Para quem chegou sem convite válido. A chegada aparece nos acessos da unidade, que tem 5 minutos para responder pelo aplicativo. O aviso por notificação ainda não está ativo e, sem resposta, nada é liberado: siga o interfone ou o procedimento do condomínio e registre a confirmação na fila.</Notice>
      <div className="reservation-filter tenant-search" role="search" aria-label="Buscar unidade de destino">
        <label htmlFor="arrival-unit-search">Buscar unidade</label>
        <input id="arrival-unit-search" type="search" value={search} onChange={(event) => setSearch(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); setTerm(collapseText(search) || null); } }} disabled={busy} />
        <button className="button button-secondary button-compact" type="button" onClick={() => setTerm(collapseText(search) || null)} disabled={busy}>Buscar</button>
      </div>
      <label htmlFor="arrival-unit">Unidade de destino</label>
      <select id="arrival-unit" required value={unitId} onChange={(event) => setUnitId(event.target.value)} disabled={busy || units.isPending}>
        <option value="">{units.isPending ? 'Consultando unidades…' : 'Selecione a unidade'}</option>
        {units.data?.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)}</option>)}</select>
      {units.isError ? <ErrorState error={units.error} retry={() => void units.refetch()} /> : null}
      {units.data && units.data.length === 0 ? <span className="field-help">Nenhuma unidade ativa encontrada nesta busca.</span> : null}
      {units.data && units.data.length >= 500 ? <span className="field-help">A lista mostra 500 unidades. Use a busca para encontrar outra.</span> : null}
      <label htmlFor="arrival-name">Nome informado pela pessoa</label>
      <input id="arrival-name" required maxLength={160} value={name} onChange={(event) => setName(event.target.value)} disabled={busy} autoComplete="off" />
      <label htmlFor="arrival-document">Final do documento (opcional)</label>
      <input id="arrival-document" maxLength={4} value={document} onChange={(event) => setDocument(event.target.value)} disabled={busy} autoComplete="off"
        aria-describedby="arrival-document-help" aria-invalid={!documentValid} />
      <span className="field-help" id="arrival-document-help">Somente os 2 a 4 últimos caracteres, quando o procedimento pedir conferência. Não registre o documento inteiro.</span>
      <ActionFeedback state={action.state} />
      {locked ? <Notice tone="warning">O resultado do último envio não foi confirmado e o formulário ficou travado. Confira a fila abaixo: se a chegada já aparece, não registre de novo.
        <span className="button-row">
          <button className="button button-secondary button-compact" type="button" onClick={() => void submit()} disabled={action.state.pending}>Tentar confirmar o mesmo envio</button>
          <button className="button button-secondary button-compact" type="button" onClick={restart} disabled={action.state.pending}>Conferi a fila: começar outro registro</button></span></Notice> : null}
      <button className="button" type="submit" disabled={!valid || busy}>{action.state.pending ? 'Registrando…' : 'Registrar chegada'}</button>
    </form>
  </section>;
}

const queueFilters: { id: ArrivalState | ''; label: string; empty: string }[] = [
  { id: '', label: 'Todas (24 horas)', empty: 'Nenhuma chegada registrada nas últimas 24 horas.' },
  { id: 'WAITING', label: 'Aguardando a unidade', empty: 'Ninguém aguardando a resposta da unidade.' },
  { id: 'EXPIRED', label: 'Sem resposta no prazo', empty: 'Nenhuma chegada sem resposta no prazo.' },
  { id: 'APPROVED', label: 'Autorizadas', empty: 'Nenhuma entrada autorizada nas últimas 24 horas.' },
  { id: 'DENIED', label: 'Recusadas', empty: 'Nenhuma entrada recusada nas últimas 24 horas.' },
  { id: 'CANCELLED', label: 'Encerradas pela portaria', empty: 'Nenhuma chegada encerrada nas últimas 24 horas.' },
];
/** Aguardando ou sem resposta no prazo: a portaria ainda registra a confirmação assistida ou encerra. */
const actionable = (arrival: Arrival) => arrival.state === 'WAITING' || arrival.state === 'EXPIRED';

function ArrivalQueue() {
  const scope = useManagement();
  const [status, setStatus] = useState<ArrivalState | ''>('');
  // Sem tempo real ainda (CP-036): a fila é consultada de novo a cada 10 segundos enquanto a tela está aberta.
  const list = useQuery({ queryKey: conciergeKey(scope, 'arrivals', status || null),
    queryFn: ({ signal }) => visits.queue(scope.tenantId, status || null, signal),
    refetchInterval: (query) => isAccessError(query.state.error) ? false : 10_000 });
  const records = useAccessRecords();
  const [editing, setEditing] = useState<{ arrivalId: string; action: 'confirm' | 'cancel' | 'entry' } | null>(null);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(list.error)) return null;
  const current = queueFilters.find((item) => item.id === status)!;
  const close = (result: ActionResult | null) => { setEditing(null); if (result) setOutcome(result); };
  return <section className="management-section" aria-labelledby="arrival-queue-title">
    <div className="section-heading"><div><span className="section-index">02 / AGORA</span><h2 id="arrival-queue-title">Fila de chegadas</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button></div>
    <form className="reservation-filter" role="search" aria-label="Filtrar chegadas" onSubmit={(event) => event.preventDefault()}>
      <label htmlFor="arrival-status">Situação</label>
      <select id="arrival-status" value={status} onChange={(event) => { setStatus(event.target.value as ArrivalState | ''); setEditing(null); setOutcome(null); }}>
        {queueFilters.map((item) => <option key={item.id || 'ALL'} value={item.id}>{item.label}</option>)}</select>
    </form>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando chegadas…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.arrivals.length === 0 ? <div className="panel"><Empty title="Nenhuma chegada">{current.empty}</Empty></div>
          : <ol className="management-list" aria-label="Chegadas">{list.data.arrivals.map((arrival) => <li key={arrival.arrivalId} className="management-row">
            <ArrivalSummary arrival={arrival} timeZone={scope.timeZone} />
            {actionable(arrival) && editing?.arrivalId !== arrival.arrivalId ? <div className="button-row">
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ arrivalId: arrival.arrivalId, action: 'confirm' }); }}>Registrar confirmação da unidade</button>
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ arrivalId: arrival.arrivalId, action: 'cancel' }); }}>Encerrar chegada</button></div> : null}
            {/* Decidida por outra via enquanto o formulário estava aberto: a linha mostra o estado real e o formulário some. */}
            {actionable(arrival) && editing?.arrivalId === arrival.arrivalId && editing.action !== 'entry' ? editing.action === 'confirm'
              ? <AssistedForm arrival={arrival} close={close} /> : <CancelForm arrival={arrival} close={close} /> : null}
            {/* Entrada só para chegada autorizada pela unidade e ainda sem entrada válida; o servidor confere de novo. */}
            {arrival.state === 'APPROVED' ? records.isPending ? <span className="field-help">Conferindo os registros de entrada…</span>
              : records.isError && !records.data ? <span className="field-help">Não foi possível conferir os registros de entrada. Atualize antes de registrar.</span>
                : (() => {
                  const entry = validEntries(records.data?.records, (record) => record.arrivalId === arrival.arrivalId)[0];
                  if (entry) return <span>Entrada registrada pela portaria em {formatWhen(entry.enteredAt, scope.timeZone)}{exitState(entry)
                    ? ` · saída registrada em ${formatWhen(entry.exit!.exitedAt, scope.timeZone)}` : ' · sem registro de saída'}</span>;
                  return editing?.arrivalId === arrival.arrivalId && editing.action === 'entry'
                    ? <EntryForm basis={{ arrivalId: arrival.arrivalId, permitId: null }} visitorName={arrival.visitorName} unit={unitName(arrival)} close={close} />
                    : <div className="button-row"><button className="button button-compact" type="button"
                      onClick={() => { setOutcome(null); setEditing({ arrivalId: arrival.arrivalId, action: 'entry' }); }}>Registrar entrada</button></div>;
                })() : null}
          </li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 chegadas mais recentes deste filtro.</Notice> : null}
  </section>;
}

const arrivalPills: Record<ArrivalState, [string, string]> = {
  WAITING: ['Aguardando a unidade', 'status-pending'], EXPIRED: ['Sem resposta no prazo', 'status-overdue'],
  APPROVED: ['Entrada autorizada', 'status-ready'], DENIED: ['Entrada recusada', 'status-neutral'], CANCELLED: ['Encerrada', 'status-neutral'],
};
const sourceText = { APP: 'pela unidade no aplicativo', INTERCOM: 'pelo interfone, registrada pela portaria', IN_PERSON: 'presencialmente, registrada pela portaria',
  CONCIERGE: 'pela portaria' } as const;

function ArrivalSummary({ arrival, timeZone }: { arrival: Arrival; timeZone: string }) {
  const [label, tone] = arrivalPills[arrival.state];
  return <div><strong><bdi>{arrival.visitorName}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
    <span><bdi>{unitName(arrival)}</bdi> · registrada em {formatWhen(arrival.createdAt, timeZone)}{arrival.documentLast4 ? ` · documento final ${arrival.documentLast4}` : ''}</span>
    {arrival.permitId ? <span className="field-help">Vinculada a um convite da unidade.</span> : null}
    {arrival.state === 'WAITING' ? <span>A unidade pode responder pelo aplicativo até {formatWhen(arrival.expiresAt, timeZone)}. Ainda não há autorização.</span> : null}
    {arrival.state === 'EXPIRED' ? <span>Sem resposta pelo aplicativo no prazo: nada foi liberado. Siga o interfone ou o procedimento do condomínio e registre a confirmação.</span> : null}
    {arrival.decisionSource && arrival.decidedAt ? <span>{arrival.state === 'CANCELLED' ? 'Encerrada' : 'Respondida'} {sourceText[arrival.decisionSource]}{arrival.decidedByMe ? ' (com a sua conta)' : ''} em {formatWhen(arrival.decidedAt, timeZone)}</span> : null}
    {arrival.decisionNote ? <span><bdi>{arrival.decisionNote}</bdi></span> : null}</div>;
}

/** Recusas que mostram o estado real: a fila atualizada exibe a decisão e o formulário fecha sem sucesso falso. */
const closedCodes = ['ARRIVAL_ALREADY_DECIDED', 'ARRIVAL_CANCELLED', 'ARRIVAL_EXPIRED', 'ARRIVAL_NOT_FOUND'];

/**
 * Confirmação assistida: o que a unidade respondeu pelo interfone ou presencialmente, com a origem e quem confirmou.
 * Vale também depois do prazo do aplicativo. Sem chave de operação: a mudança é condicional, e repetir depois de um
 * resultado incerto recebe "já respondida" se o primeiro envio tiver sido gravado.
 */
function AssistedForm({ arrival, close }: { arrival: Arrival; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [decision, setDecision] = useState<'APPROVE' | 'DENY' | ''>('');
  const [source, setSource] = useState<'INTERCOM' | 'IN_PERSON' | ''>('');
  const [note, setNote] = useState('');
  const [locked, setLocked] = useState(false);
  const normalized = displayText(note, 300);
  const valid = decision !== '' && source !== '' && normalized !== null;
  const refresh = () => queries.invalidateQueries({ queryKey: arrivalsKey(scope) });
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body = { decision, source, note: normalized! };
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await visits.assisted(scope.tenantId, arrival.arrivalId, body); }
      catch (error) { failure = error; throw error; }
    }, (done: Arrival): ActionResult => {
      const expected = body.decision === 'APPROVE' ? 'APPROVED' : 'DENIED';
      result = done.arrivalId === arrival.arrivalId && done.state === expected && done.decisionSource === body.source
        ? { tone: 'success', text: body.decision === 'APPROVE'
          ? `Entrada de ${done.visitorName} autorizada pela unidade (${body.source === 'INTERCOM' ? 'interfone' : 'presencial'}). O registro ficou com a sua conta.`
          : `Entrada de ${done.visitorName} recusada pela unidade (${body.source === 'INTERCOM' ? 'interfone' : 'presencial'}). O registro ficou com a sua conta.` }
        : { tone: 'warning', text: 'A resposta não confirmou o registro. Atualize a fila e confira.' };
      return result;
    }, async () => { await refresh(); });
    if (ok) { close(result); return; }
    if (closedCodes.includes(codeOf(failure))) {
      close({ tone: 'warning', text: `${errorMessage(failure)} Nada novo foi registrado.` });
      void refresh();
      return;
    }
    if (uncertain(failure)) { setLocked(true); void refresh(); }
  }
  const id = `assisted-${arrival.arrivalId}`;
  const busy = action.state.pending || locked;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Registrar confirmação de ${arrival.visitorName}`}>
    <Notice>Registre somente o que a unidade respondeu pelo interfone ou presencialmente. A decisão fica com a sua conta e com a origem indicada; a unidade vê o registro.</Notice>
    <fieldset className="weekday-fieldset"><legend>Resposta da unidade</legend>
      <label className="weekday-option"><input type="radio" name={`${id}-decision`} value="APPROVE" checked={decision === 'APPROVE'} onChange={() => setDecision('APPROVE')} disabled={busy} />Autorizou a entrada</label>
      <label className="weekday-option"><input type="radio" name={`${id}-decision`} value="DENY" checked={decision === 'DENY'} onChange={() => setDecision('DENY')} disabled={busy} />Recusou a entrada</label>
    </fieldset>
    <fieldset className="weekday-fieldset"><legend>Como a unidade respondeu</legend>
      <label className="weekday-option"><input type="radio" name={`${id}-source`} value="INTERCOM" checked={source === 'INTERCOM'} onChange={() => setSource('INTERCOM')} disabled={busy} />Pelo interfone</label>
      <label className="weekday-option"><input type="radio" name={`${id}-source`} value="IN_PERSON" checked={source === 'IN_PERSON'} onChange={() => setSource('IN_PERSON')} disabled={busy} />Presencialmente</label>
    </fieldset>
    <label htmlFor={`${id}-note`}>Quem confirmou e como</label>
    <textarea id={`${id}-note`} required maxLength={300} rows={2} value={note} onChange={(event) => setNote(event.target.value)} disabled={busy}
      aria-describedby={`${id}-help`} />
    <span className="field-help" id={`${id}-help`}>Ex.: moradora do apartamento confirmou pelo interfone. Não registre documentos.</span>
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o registro ficou travado. Atualize a fila: se a decisão já aparece, não repita.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Registrando…' : locked ? 'Tentar confirmar o mesmo registro' : 'Registrar confirmação'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

/** Pessoa foi embora ou chegada registrada por engano: encerra com motivo; não autoriza nem recusa a entrada. */
function CancelForm({ arrival, close }: { arrival: Arrival; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [reason, setReason] = useState('');
  const [locked, setLocked] = useState(false);
  const normalized = displayText(reason, 300);
  const refresh = () => queries.invalidateQueries({ queryKey: arrivalsKey(scope) });
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await visits.cancel(scope.tenantId, arrival.arrivalId, normalized); }
      catch (error) { failure = error; throw error; }
    }, (done: Arrival): ActionResult => {
      result = done.arrivalId === arrival.arrivalId && done.state === 'CANCELLED'
        ? { tone: 'success', text: `Chegada de ${done.visitorName} encerrada. O motivo ficou registrado.` }
        : { tone: 'warning', text: 'A resposta não confirmou o encerramento. Atualize a fila e confira.' };
      return result;
    }, async () => { await refresh(); });
    if (ok) { close(result); return; }
    if (closedCodes.includes(codeOf(failure))) {
      close({ tone: 'warning', text: `${errorMessage(failure)} Nada foi encerrado.` });
      void refresh();
      return;
    }
    if (uncertain(failure)) { setLocked(true); void refresh(); }
  }
  const id = `cancel-${arrival.arrivalId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Encerrar chegada de ${arrival.visitorName}`}>
    <Notice tone="warning">Use quando a pessoa foi embora ou a chegada foi registrada por engano. Encerrar não autoriza nem recusa a entrada, e a unidade deixa de poder responder. O motivo aparece para a unidade: não registre dados pessoais.</Notice>
    <label htmlFor={`${id}-reason`}>Motivo</label>
    <textarea id={`${id}-reason`} required maxLength={300} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending || locked} />
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o motivo ficou travado. Atualize a fila antes de repetir.</Notice> : null}
    <div className="button-row">
      <button className="button button-danger" type="submit" disabled={normalized === null || action.state.pending}>{action.state.pending ? 'Encerrando…' : 'Encerrar chegada'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

function ExpectedVisits() {
  const scope = useManagement();
  const [name, setName] = useState('');
  const [unitId, setUnitId] = useState('');
  const [filter, setFilter] = useState<string | null>(null);
  const units = useQuery({ queryKey: conciergeKey(scope, 'units', null), queryFn: ({ signal }) => parcels.units(scope.tenantId, null, signal) });
  // O estado (vale agora ou agendado) vem do relógio do banco; a lista é conferida de novo a cada minuto.
  const list = useQuery({ queryKey: conciergeKey(scope, 'permits', unitId || null, filter),
    queryFn: ({ signal }) => visits.expected(scope.tenantId, { unitId: unitId || null, q: filter }, signal),
    refetchInterval: (query) => isAccessError(query.state.error) ? false : 60_000 });
  const records = useAccessRecords();
  const [editing, setEditing] = useState<{ permitId: string; action: 'ask' | 'entry' } | null>(null);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(list.error)) return null;
  const close = (result: ActionResult | null) => { setEditing(null); if (result) setOutcome(result); };
  const setAsking = (permitId: string | null) => setEditing(permitId ? { permitId, action: 'ask' } : null);
  return <section className="management-section" aria-labelledby="expected-title">
    <div className="section-heading"><div><span className="section-index">03 / PREVISTOS</span><h2 id="expected-title">Visitas previstas</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button></div>
    <p className="field-help">Convites em vigor ou que começam nas próximas 24 horas. Convite é autorização da unidade no período, não prova de identidade: confira a pessoa conforme o procedimento do condomínio.</p>
    <form className="reservation-filter" role="search" aria-label="Filtrar visitas previstas" onSubmit={(event) => { event.preventDefault(); setFilter(collapseText(name) || null); setEditing(null); }}>
      <label htmlFor="expected-name">Nome</label>
      <input id="expected-name" type="search" maxLength={80} value={name} onChange={(event) => setName(event.target.value)} />
      <label htmlFor="expected-unit">Unidade</label>
      <select id="expected-unit" value={unitId} onChange={(event) => { setUnitId(event.target.value); setEditing(null); }} disabled={units.isPending}>
        <option value="">Todas as unidades</option>
        {units.data?.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)}</option>)}</select>
      <button className="button button-secondary button-compact" type="submit">Buscar</button>
    </form>
    {units.data && units.data.length >= 500 ? <p className="field-help">O filtro de unidade mostra 500 unidades; busque pelo nome da pessoa para as demais.</p> : null}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando visitas previstas…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.permits.length === 0 ? <div className="panel"><Empty title="Nenhuma visita prevista">Nenhum convite em vigor ou para as próximas 24 horas neste filtro.</Empty></div>
          : <ol className="management-list" aria-label="Visitas previstas">{list.data.permits.map((permit) => <li key={permit.permitId} className="management-row">
            <ExpectedSummary permit={permit} timeZone={scope.timeZone} />
            {(() => {
              // Entrada pelo convite ou pela chegada pedida a partir dele (linkedPermitId).
              const last = validEntries(records.data?.records, (record) => record.permitId === permit.permitId || record.linkedPermitId === permit.permitId)[0];
              return last ? <span className="field-help">Última entrada registrada em {formatWhen(last.enteredAt, scope.timeZone)}{exitState(last)
                ? ` · saída em ${formatWhen(last.exit!.exitedAt, scope.timeZone)}` : ' · sem registro de saída'}.</span> : null;
            })()}
            {editing?.permitId === permit.permitId ? editing.action === 'ask' ? <AskUnit permit={permit} close={close} />
              : <EntryForm basis={{ arrivalId: null, permitId: permit.permitId }} visitorName={permit.visitorName} unit={unitName(permit)} close={close} />
              : <div className="button-row">
                {/* Entrada pelo convite só enquanto o servidor diz que ele vale agora. */}
                {permit.state === 'ACTIVE' && records.data ? <button className="button button-compact" type="button"
                  onClick={() => { setOutcome(null); setEditing({ permitId: permit.permitId, action: 'entry' }); }}>Registrar entrada</button> : null}
                <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setAsking(permit.permitId); }}>Pedir confirmação à unidade</button></div>}
          </li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 visitas previstas de início mais próximo. Busque pelo nome ou filtre pela unidade.</Notice> : null}
  </section>;
}

function ExpectedSummary({ permit, timeZone }: { permit: VisitPermit; timeZone: string }) {
  const current = permit.state === 'ACTIVE';
  return <div><strong><bdi>{permit.visitorName}</bdi></strong> <span className={`status-pill ${current ? 'status-ready' : 'status-pending'}`}>{current ? 'Vale agora' : 'Agendado'}</span>
    <span><bdi>{unitName(permit)}</bdi> · de {formatWhen(permit.validFrom, timeZone)} até {formatWhen(permit.validUntil, timeZone)}{permit.documentLast4 ? ` · documento final ${permit.documentLast4}` : ''}</span>
    {permit.purpose ? <span><bdi>{permit.purpose}</bdi></span> : null}
    {current ? null : <span className="field-help">Antes do início o convite não autoriza a entrada. Se a pessoa chegou antes, peça confirmação à unidade.</span>}</div>;
}

/** Chegada vinculada ao convite: a unidade confirma (pessoa adiantada ou procedimento que pede confirmação). */
function AskUnit({ permit, close }: { permit: VisitPermit; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  // Pendência por convite: fechar e reabrir depois de resultado incerto repete o mesmo envio, sem segunda chegada.
  const intent = usePendingOperation(`${scope.tenantId}:ask:${permit.permitId}`);
  const locked = intent.locked;
  const refresh = () => queries.invalidateQueries({ queryKey: arrivalsKey(scope) });
  async function submit(event: FormEvent) {
    event.preventDefault();
    const body: RegisterArrivalBody = { unitId: permit.unitId, visitorName: permit.visitorName, documentLast4: permit.documentLast4, permitId: permit.permitId };
    const key = intent.keyFor(JSON.stringify(body), body);
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await visits.register(scope.tenantId, key, body); }
      catch (error) { failure = error; throw repeatFailure(error); }
      finally { intent.settle(); }
    }, (done: ArrivalMutation): ActionResult => {
      // Repetição devolve o estado atual da chegada, que pode já ter sido respondida ou vencida.
      result = done.arrival.permitId !== permit.permitId || done.arrival.unitId !== permit.unitId
        ? { tone: 'warning', text: 'A resposta não confirmou esta chegada. Confira a fila antes de registrar de novo.' }
        : done.replayed ? { tone: 'info', text: `Esta chegada já estava registrada para ${unitName(done.arrival)}. Situação atual: ${arrivalPills[done.arrival.state][0].toLowerCase()}. Nada novo foi criado.` }
          : { tone: 'success', text: `Chegada registrada: ${done.arrival.visitorName} aguarda a resposta de ${unitName(done.arrival)}, pelo aplicativo até ${formatWhen(done.arrival.expiresAt, scope.timeZone)}. Acompanhe na fila.` };
      return result;
    }, async () => { await refresh(); });
    if (ok) { intent.finish(); close(result); return; }
    const code = codeOf(failure);
    if (['ARRIVAL_PERMIT_INVALID', 'ARRIVAL_UNIT_UNAVAILABLE', 'VISIT_PERMIT_REVOKED', 'VISIT_PERMIT_EXPIRED', 'VISIT_PERMIT_INACTIVE',
      'VISIT_PERMIT_NOT_FOUND'].includes(code)) {
      intent.finish();
      close({ tone: 'warning', text: `${errorMessage(failure)} Nada foi registrado.` });
      void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'permits') });
      return;
    }
    settleFailure(failure, locked, intent, intent.setLocked);
    void refresh();
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Pedir confirmação para ${permit.visitorName}`}>
    <Notice>Registra a chegada de <bdi>{permit.visitorName}</bdi> para <bdi>{unitName(permit)}</bdi>, vinculada ao convite, e espera a resposta da unidade. Até a resposta, nada está autorizado.</Notice>
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado. Confira a fila: se a chegada já aparece, não registre de novo.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={action.state.pending || intent.inFlight}>{action.state.pending || intent.inFlight ? 'Registrando…' : locked ? 'Tentar confirmar o mesmo envio' : 'Registrar chegada e pedir confirmação'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

const evidenceText: Record<AccessEvidence, string> = {
  DOCUMENT_CHECKED: 'documento conferido', RECOGNIZED_BY_UNIT: 'reconhecida por quem autorizou',
};
/** Recusas de estado: a lista atualizada mostra a situação real e o formulário fecha sem sucesso falso. */
const entryClosedCodes = ['ACCESS_ARRIVAL_NOT_AUTHORIZED', 'ACCESS_AUTHORIZATION_EXPIRED', 'ACCESS_UNIT_INACTIVE', 'ACCESS_PERMIT_NOT_CURRENT', 'ACCESS_ENTRY_ALREADY_RECORDED',
  'ACCESS_STATE_CHANGED', 'ARRIVAL_NOT_FOUND', 'VISIT_PERMIT_NOT_FOUND'];
const exitClosedCodes = ['ACCESS_EXIT_ALREADY_RECORDED', 'ACCESS_ENTRY_CORRECTED', 'ACCESS_EVENT_NOT_FOUND', 'ACCESS_STATE_CHANGED'];
const correctionClosedCodes = ['ACCESS_ALREADY_CORRECTED', 'ACCESS_CORRECTION_INVALID', 'ACCESS_EVENT_NOT_FOUND', 'ACCESS_STATE_CHANGED'];

/**
 * Ação com chave de operação sobre os registros de acesso: mesma intenção reaproveita a chave, resultado incerto trava
 * o envio até conferir a lista e recusa de estado fecha com aviso. A pendência fica por alvo (slot), fora do
 * formulário: fechar e reabrir mantém a trava, a chave e os dados — sem isso, uma entrada pelo convite (que admite mais
 * de uma no período) poderia ser gravada duas vezes depois de uma resposta perdida.
 */
function useAccessAction(slot: string, closedCodes: string[], close: (result: ActionResult | null) => void) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const intent = usePendingOperation(`${scope.tenantId}:${slot}`);
  const refresh = () => Promise.all([queries.invalidateQueries({ queryKey: accessKey(scope) }), queries.invalidateQueries({ queryKey: arrivalsKey(scope) })]);
  async function run(body: unknown, call: (key: string) => Promise<AccessMutation>, describe: (done: AccessMutation) => ActionResult) {
    const key = intent.keyFor(JSON.stringify(body), body);
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await call(key); }
      catch (error) { failure = error; throw repeatFailure(error); }
      finally { intent.settle(); }
    }, (done: AccessMutation): ActionResult => { result = describe(done); return result; }, async () => { await refresh(); });
    if (ok) { intent.finish(); close(result); return; }
    if (closedCodes.includes(codeOf(failure))) {
      intent.finish();
      close({ tone: 'warning', text: `${errorMessage(failure)} Nada novo foi registrado.` }); void refresh(); return;
    }
    settleFailure(failure, intent.locked, intent, intent.setLocked);
    void refresh();
  }
  return { scope, action, locked: intent.locked, inFlight: intent.inFlight, lockedBody: intent.lockedBody, run };
}

/** Entrada de quem tem autorização vigente: conferência indicada pela portaria; nunca rotulada como passagem de equipamento. */
function EntryForm({ basis, visitorName, unit, close }: { basis: { arrivalId: string | null; permitId: string | null }; visitorName: string; unit: string;
  close(result: ActionResult | null): void }) {
  const slot = basis.arrivalId ? `entry:arrival:${basis.arrivalId}` : `entry:permit:${basis.permitId}`;
  const { scope, action, locked, inFlight, lockedBody, run } = useAccessAction(slot, entryClosedCodes, close);
  // Envio travado reabre com os mesmos dados (mesma chave e mesmo conteúdo).
  const pending = lockedBody as AccessEntryBody | null;
  const [evidence, setEvidence] = useState<AccessEvidence | ''>(pending?.evidence ?? '');
  const [note, setNote] = useState(pending?.note ?? '');
  const normalizedNote = collapseText(note) ? displayText(note, 300) : '';
  const valid = evidence !== '' && normalizedNote !== null;
  const id = `entry-${basis.arrivalId ?? basis.permitId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body: AccessEntryBody = { ...basis, evidence, note: normalizedNote || null };
    await run(body, (key) => visits.recordEntry(scope.tenantId, key, body), (done) =>
      done.record.arrivalId === basis.arrivalId && done.record.permitId === basis.permitId && done.record.correction === null
        ? { tone: done.replayed ? 'info' : 'success', text: `${done.replayed ? 'Esta entrada já estava registrada' : 'Entrada registrada'}: ${done.record.visitorName} em ${unitName(done.record)}, às ${formatWhen(done.record.enteredAt, scope.timeZone)}, pela portaria (${evidenceText[done.record.evidence]}).` }
        : { tone: 'warning', text: 'A resposta não confirmou esta entrada. Atualize os registros antes de repetir.' });
  }
  const busy = action.state.pending || locked || inFlight;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Registrar entrada de ${visitorName}`}>
    <Notice>Registro manual da portaria para <bdi>{visitorName}</bdi> ({unit}), com autorização vigente. Não representa passagem detectada por equipamento.</Notice>
    <fieldset className="weekday-fieldset"><legend>Como a pessoa foi conferida</legend>
      <label className="weekday-option"><input type="radio" name={`${id}-evidence`} value="DOCUMENT_CHECKED" checked={evidence === 'DOCUMENT_CHECKED'}
        onChange={() => setEvidence('DOCUMENT_CHECKED')} disabled={busy} />Conferi o documento</label>
      <label className="weekday-option"><input type="radio" name={`${id}-evidence`} value="RECOGNIZED_BY_UNIT" checked={evidence === 'RECOGNIZED_BY_UNIT'}
        onChange={() => setEvidence('RECOGNIZED_BY_UNIT')} disabled={busy} />Reconhecida por quem autorizou, na portaria</label>
    </fieldset>
    <label htmlFor={`${id}-note`}>Observação interna (opcional)</label>
    <input id={`${id}-note`} maxLength={300} value={note} onChange={(event) => setNote(event.target.value)} disabled={busy} autoComplete="off"
      aria-describedby={`${id}-note-help`} />
    <span className="field-help" id={`${id}-note-help`}>Fica com a portaria e não aparece para a unidade. Não registre documento completo nem dados sensíveis.</span>
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o registro ficou travado, mesmo se você fechar o formulário. Use “Tentar confirmar o mesmo registro”: se a entrada já foi gravada, nada novo é criado.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.state.pending || inFlight}>{action.state.pending || inFlight ? 'Registrando…' : locked ? 'Tentar confirmar o mesmo registro' : 'Registrar entrada'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

function ExitForm({ record, close }: { record: AccessRecord; close(result: ActionResult | null): void }) {
  const { scope, action, locked, inFlight, lockedBody, run } = useAccessAction(`exit:${record.entryId}`, exitClosedCodes, close);
  const pending = lockedBody as { exit: string; note: string | null } | null;
  const [note, setNote] = useState(pending?.note ?? '');
  const normalizedNote = collapseText(note) ? displayText(note, 300) : '';
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalizedNote === null) return;
    const value = normalizedNote || null;
    await run({ exit: record.entryId, note: value }, (key) => visits.recordExit(scope.tenantId, record.entryId, key, value), (done) =>
      done.record.entryId === record.entryId && done.record.exit !== null && done.record.exit.correction === null
        ? { tone: done.replayed ? 'info' : 'success', text: `${done.replayed ? 'Esta saída já estava registrada' : 'Saída registrada'}: ${done.record.visitorName}, às ${formatWhen(done.record.exit.exitedAt, scope.timeZone)}, pela portaria.` }
        : { tone: 'warning', text: 'A resposta não confirmou esta saída. Atualize os registros antes de repetir.' });
  }
  const id = `exit-${record.entryId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Registrar saída de ${record.visitorName}`}>
    <label htmlFor={`${id}-note`}>Observação interna (opcional)</label>
    <input id={`${id}-note`} maxLength={300} value={note} onChange={(event) => setNote(event.target.value)} disabled={action.state.pending || locked} autoComplete="off"
      aria-describedby={`${id}-note-help`} />
    <span className="field-help" id={`${id}-note-help`}>Fica com a portaria e não aparece para a unidade. Não registre dados sensíveis.</span>
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado. Atualize os registros: se a saída já aparece, não repita.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={normalizedNote === null || action.state.pending || inFlight}>{action.state.pending || inFlight ? 'Registrando…' : locked ? 'Tentar confirmar a mesma saída' : 'Registrar saída'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

/** Correção humana: evento adicional com motivo; o registro corrigido continua guardado e visível. */
function CorrectionForm({ record, target, close }: { record: AccessRecord; target: 'entry' | 'exit'; close(result: ActionResult | null): void }) {
  // Alvo fixado ao abrir: a lista atualizada a cada 10 s não troca a saída que está sendo corrigida.
  const [eventId] = useState(() => target === 'entry' ? record.entryId : record.exit!.eventId);
  const { scope, action, locked, inFlight, lockedBody, run } = useAccessAction(`correct:${eventId}`, correctionClosedCodes, close);
  const pending = lockedBody as { correct: string; reason: string } | null;
  const [reason, setReason] = useState(pending?.reason ?? '');
  const normalized = displayText(reason, 300);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    await run({ correct: eventId, reason: normalized }, (key) => visits.correct(scope.tenantId, eventId, key, normalized), (done) => {
      const corrected = target === 'entry' ? done.record.correction : done.record.exit?.eventId === eventId ? done.record.exit.correction : null;
      return done.record.entryId === record.entryId && corrected !== null
        ? { tone: done.replayed ? 'info' : 'success', text: `${done.replayed ? 'Esta correção já estava registrada' : 'Correção registrada'}: ${target === 'entry' ? 'entrada' : 'saída'} de ${done.record.visitorName}. O registro original continua guardado.` }
        : { tone: 'warning', text: 'A resposta não confirmou a correção. Atualize os registros antes de repetir.' };
    });
  }
  const id = `correct-${eventId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Corrigir ${target === 'entry' ? 'entrada' : 'saída'} de ${record.visitorName}`}>
    <Notice tone="warning">Use quando o registro foi feito por engano (pessoa errada, entrada ou saída que não aconteceu). A correção fica ao lado do registro original, que não é apagado. O motivo aparece para a unidade: não registre dados pessoais.</Notice>
    <label htmlFor={`${id}-reason`}>Motivo da correção</label>
    <textarea id={`${id}-reason`} required maxLength={300} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending || locked || inFlight} />
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado. Atualize os registros antes de repetir.</Notice> : null}
    <div className="button-row">
      <button className="button button-danger" type="submit" disabled={normalized === null || action.state.pending || inFlight}>{action.state.pending || inFlight ? 'Corrigindo…' : locked ? 'Tentar confirmar a mesma correção' : 'Registrar correção'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

function AccessLog() {
  const scope = useManagement();
  const records = useAccessRecords();
  const [editing, setEditing] = useState<{ entryId: string; action: 'exit' | 'correct-entry' | 'correct-exit' } | null>(null);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(records.error)) return null;
  const close = (result: ActionResult | null) => { setEditing(null); if (result) setOutcome(result); };
  const open = (entryId: string, action: 'exit' | 'correct-entry' | 'correct-exit') => { setOutcome(null); setEditing({ entryId, action }); };
  return <section className="management-section" aria-labelledby="access-log-title">
    <div className="section-heading"><div><span className="section-index">04 / REGISTROS</span><h2 id="access-log-title">Entradas e saídas</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={records.isFetching} onClick={() => void records.refetch()}>Atualizar</button></div>
    <p className="field-help">Registros manuais da portaria nas últimas 24 horas. Sem registro de saída não significa que a pessoa continua no condomínio.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {records.isPending ? <div className="panel"><Loading label="Consultando registros…" /></div>
      : records.isError && !records.data ? <ErrorState error={records.error} retry={() => void records.refetch()} />
        : !records.data || records.data.records.length === 0 ? <div className="panel"><Empty title="Nenhum registro">Nenhuma entrada registrada pela portaria nas últimas 24 horas.</Empty></div>
          : <ol className="management-list" aria-label="Registros de entrada e saída">{records.data.records.map((record) => {
            const corrected = record.correction !== null;
            const exited = exitState(record);
            return <li key={record.entryId} className="management-row">
              <AccessSummary record={record} timeZone={scope.timeZone} />
              {editing?.entryId !== record.entryId ? <div className="button-row">
                {!corrected && !exited ? <button className="button button-secondary button-compact" type="button" onClick={() => open(record.entryId, 'exit')}>Registrar saída</button> : null}
                {!corrected ? <button className="button button-secondary button-compact" type="button" onClick={() => open(record.entryId, 'correct-entry')}>Corrigir entrada</button> : null}
                {!corrected && exited ? <button className="button button-secondary button-compact" type="button" onClick={() => open(record.entryId, 'correct-exit')}>Corrigir saída</button> : null}
              </div> : editing.action === 'exit' ? <ExitForm record={record} close={close} />
                : <CorrectionForm record={record} target={editing.action === 'correct-entry' ? 'entry' : 'exit'} close={close} />}
            </li>;
          })}</ol>}
    {records.data?.truncated ? <Notice tone="warning">A lista mostra as 100 entradas mais recentes.</Notice> : null}
  </section>;
}

function AccessSummary({ record, timeZone }: { record: AccessRecord; timeZone: string }) {
  const corrected = record.correction !== null;
  const [label, tone] = corrected ? ['Entrada corrigida', 'status-neutral'] : exitState(record) ? ['Saída registrada', 'status-ready'] : ['Sem registro de saída', 'status-pending'];
  return <div><strong><bdi>{record.visitorName}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
    <span><bdi>{unitName(record)}</bdi> · {record.arrivalId ? 'chegada autorizada pela unidade' : 'convite da unidade'}{record.documentLast4 ? ` · documento final ${record.documentLast4}` : ''}</span>
    <span>Entrada registrada pela portaria em {formatWhen(record.enteredAt, timeZone)}{record.recordedByMe ? ' (com a sua conta)' : ''} · {evidenceText[record.evidence]}</span>
    {record.note ? <span><bdi>{record.note}</bdi></span> : null}
    {record.correction ? <span>Entrada corrigida em {formatWhen(record.correction.correctedAt, timeZone)}: <bdi>{record.correction.reason}</bdi></span> : null}
    {record.exit ? <span>Saída registrada pela portaria em {formatWhen(record.exit.exitedAt, timeZone)}{record.exit.recordedByMe ? ' (com a sua conta)' : ''}{record.exit.note ? ` · ${record.exit.note}` : ''}</span> : null}
    {record.exit?.correction ? <span>Saída corrigida em {formatWhen(record.exit.correction.correctedAt, timeZone)}: <bdi>{record.exit.correction.reason}</bdi></span> : null}</div>;
}
