import { useCallback, useMemo, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import { parcels, type Parcel, type ParcelMutation, type ParcelStatus, type ReceiveBody, type RecipientRole } from '../../api/parcels';
import { collapseText, displayText } from '../../api/text';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice, errorMessage } from '../primitives';
import { dateInZone, formatWhen } from '../dateTime';
import { ActionFeedback, ManagementProvider, useAccessGuard, useAction, useManagement, type ActionResult, type ManagementScope } from '../management/shared';
import { codeOf, conciergeKey, freshKeyCodes, repeatFailure, uncertain, unitName, useIntent } from './shared';
import { ArrivalDesk } from './Arrivals';
import '../management/Management.css';

export type ConciergeSection = 'encomendas' | 'visitas';
export const conciergeSections: { id: ConciergeSection; label: string }[] = [{ id: 'encomendas', label: 'Encomendas' }, { id: 'visitas', label: 'Visitas' }];

/** Portaria do condomínio: exige vínculo CONCIERGE vigente e segundo fator; o servidor confere de novo em cada ação. */
export function ConciergePage({ section }: { section: ConciergeSection }) {
  const { identity } = useAuth();
  const { tenantId = '' } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.role === 'CONCIERGE');
  if (!identity || !membership) return <div className="panel"><Empty title="Portaria indisponível">Seu cadastro não inclui a portaria deste condomínio. Volte para escolher um acesso disponível.</Empty></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido antes de abrir a portaria.</Notice>; }
  return <ConciergeGate key={`${identity.accountId}:${tenantId}`} section={section} scope={{ accountId: identity.accountId, tenantId,
    tenantName: membership.tenantName, timeZone: membership.timeZone }} />;
}

function ConciergeGate({ scope, section }: { scope: ManagementScope; section: ConciergeSection }) {
  const queries = useQueryClient();
  const status = useQuery({ queryKey: mfaStatusKey(scope.accountId), queryFn: ({ signal }) => api.mfaStatus(signal), refetchOnWindowFocus: false });
  const [demanded, setDemanded] = useState(false);
  const [denied, setDenied] = useState<unknown>(null);
  const requireMfa = useCallback(() => setDemanded(true), []);
  const deny = useCallback((error: unknown) => {
    setDenied(error);
    const prefix = ['private', scope.accountId, 'tenant', scope.tenantId];
    void queries.cancelQueries({ queryKey: prefix }); queries.removeQueries({ queryKey: prefix });
    void queries.invalidateQueries({ queryKey: ['identity'] });
  }, [queries, scope.accountId, scope.tenantId]);
  const value = useMemo(() => ({ ...scope, requireMfa, denied: deny }), [scope, requireMfa, deny]);
  if (denied) return <div className="panel"><Notice tone="error">A portaria deste condomínio não está mais disponível para o seu vínculo. Os dados exibidos foram removidos.</Notice>
    <Link className="button button-secondary" to="/contextos">Escolher outro acesso</Link></div>;
  if (status.isPending) return <div className="panel"><Loading label="Conferindo o segundo fator…" /></div>;
  if (status.isError && !status.data) return <ErrorState error={status.error} retry={() => void status.refetch()} />;
  if (!status.data) return null;
  if (status.data.assuranceLevel !== 'AAL2' || demanded) {
    return <MfaPanel accountId={scope.accountId} onVerified={() => {
      setDemanded(false);
      void queries.invalidateQueries({ queryKey: mfaStatusKey(scope.accountId) });
      void queries.invalidateQueries({ queryKey: conciergeKey(scope) });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }} />;
  }
  return <ManagementProvider value={value}>
    <div className="residence-heading"><div><span className="eyebrow">PORTARIA</span><h1>{scope.tenantName}</h1>
      <p>{section === 'visitas'
        ? 'Chegadas, confirmações e encerramentos ficam registrados com o seu acesso. Sem resposta da unidade nada é liberado automaticamente.'
        : 'Recebimentos, retiradas e anulações ficam registrados com o seu acesso. O aviso ao morador segue em fila e nunca condiciona o registro.'} Horários em {scope.timeZone}.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Segundo fator confirmado</strong><span>Sessão da portaria</span></div></div></div>
    <nav className="management-tabs" aria-label="Seções da portaria">{conciergeSections.map((item) =>
      <Link key={item.id} to={`/portaria/${scope.tenantId}/${item.id}`} aria-current={item.id === section ? 'page' : undefined}>{item.label}</Link>)}</nav>
    {section === 'visitas' ? <ArrivalDesk /> : <><ReceiveParcel /><ParcelQueue /></>}
  </ManagementProvider>;
}

const volumes = (count: number) => `${count} ${count === 1 ? 'volume' : 'volumes'}`;

function ReceiveParcel() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const intent = useIntent();
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState<string | null>(null);
  const units = useQuery({ queryKey: conciergeKey(scope, 'units', term), queryFn: ({ signal }) => parcels.units(scope.tenantId, term, signal) });
  const [unitId, setUnitId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [description, setDescription] = useState('');
  const [location, setLocation] = useState('');
  // Aviso de duplicidade suspeita (POR-02) e trava depois de resultado incerto: nada é reenviado com outra chave sem conferir.
  const [duplicate, setDuplicate] = useState(false);
  const [locked, setLocked] = useState(false);
  if (useAccessGuard(units.error)) return null;
  const count = Number(quantity);
  const normalizedLocation = displayText(location, 160);
  const normalizedDescription = collapseText(description) ? displayText(description, 500) : '';
  const valid = Boolean(unitId) && Number.isInteger(count) && count >= 1 && count <= 1000 && normalizedLocation !== null && normalizedDescription !== null;
  const refreshQueue = () => queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') });
  async function submit(confirmDuplicate: boolean) {
    if (!valid) return;
    const body: ReceiveBody = { unitId, quantity: count, description: normalizedDescription || null, storageLocation: normalizedLocation!,
      ...(confirmDuplicate ? { confirmDuplicate: true as const } : {}) };
    const key = intent.keyFor(JSON.stringify(body));
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await parcels.receive(scope.tenantId, key, body); }
      catch (error) { failure = error; throw repeatFailure(error); }
    }, (result: ParcelMutation): ActionResult => {
      const matches = result.parcel.unitId === unitId && result.parcel.status === 'RECEIVED' && result.parcel.quantity === count;
      if (!matches) return { tone: 'warning', text: 'A resposta não confirmou este recebimento. Confira a lista antes de registrar de novo.' };
      return result.replayed
        ? { tone: 'info', text: 'Este recebimento já estava registrado com os mesmos dados. Nada novo foi criado.' }
        : { tone: 'success', text: `Encomenda registrada para ${unitName(result.parcel)}: ${volumes(result.parcel.quantity)} em ${result.parcel.storageLocation}. O aviso ao morador segue em fila.` };
    }, async () => { await refreshQueue(); });
    if (ok) {
      intent.reset(); setDuplicate(false); setLocked(false); setQuantity('1'); setDescription(''); setLocation('');
      return;
    }
    const code = codeOf(failure);
    if (code === 'PARCEL_DUPLICATE_SUSPECTED') { setDuplicate(true); return; }
    if (freshKeyCodes.includes(code)) { intent.reset(); void refreshQueue(); return; }
    if (uncertain(failure)) { setLocked(true); void refreshQueue(); }
  }
  function restart() { intent.reset(); setLocked(false); setDuplicate(false); action.clear(); }
  const busy = action.state.pending || locked;
  return <section className="management-section" aria-labelledby="receive-title">
    <div className="section-heading"><div><span className="section-index">01 / RECEBER</span><h2 id="receive-title">Receber encomenda</h2></div></div>
    <form className="panel stack-form" onSubmit={(event: FormEvent) => { event.preventDefault(); void submit(false); }} aria-label="Receber encomenda">
      <div className="reservation-filter tenant-search" role="search" aria-label="Buscar unidade">
        <label htmlFor="unit-search">Buscar unidade</label>
        <input id="unit-search" type="search" value={search} onChange={(event) => setSearch(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); setTerm(collapseText(search) || null); } }} disabled={busy} />
        <button className="button button-secondary button-compact" type="button" onClick={() => setTerm(collapseText(search) || null)} disabled={busy}>Buscar</button>
      </div>
      <label htmlFor="parcel-unit">Unidade</label>
      <select id="parcel-unit" required value={unitId} onChange={(event) => { setUnitId(event.target.value); setDuplicate(false); }} disabled={busy || units.isPending}>
        <option value="">{units.isPending ? 'Consultando unidades…' : 'Selecione a unidade'}</option>
        {units.data?.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)}</option>)}</select>
      {units.isError ? <ErrorState error={units.error} retry={() => void units.refetch()} /> : null}
      {units.data && units.data.length === 0 ? <span className="field-help">Nenhuma unidade ativa encontrada nesta busca.</span> : null}
      {units.data && units.data.length >= 500 ? <span className="field-help">A lista mostra 500 unidades. Use a busca para encontrar outra.</span> : null}
      <label htmlFor="parcel-quantity">Volumes</label>
      <input id="parcel-quantity" type="number" min={1} max={1000} required value={quantity} onChange={(event) => { setQuantity(event.target.value); setDuplicate(false); }} disabled={busy} />
      <label htmlFor="parcel-description">Descrição curta (opcional)</label>
      <input id="parcel-description" maxLength={500} value={description} onChange={(event) => { setDescription(event.target.value); setDuplicate(false); }} disabled={busy}
        placeholder="Ex.: caixa média, envelope" />
      <label htmlFor="parcel-location">Local de guarda</label>
      <input id="parcel-location" required maxLength={160} value={location} onChange={(event) => { setLocation(event.target.value); setDuplicate(false); }} disabled={busy}
        placeholder="Ex.: Prateleira B" />
      {duplicate && !action.state.pending ? <Notice tone="warning">Há uma encomenda igual registrada para esta unidade nos últimos 10 minutos. Confira na lista abaixo se não é a mesma antes de registrar outra.
        <button className="button button-secondary button-compact" type="button" onClick={() => void submit(true)}>É outra encomenda: registrar</button></Notice>
        : <ActionFeedback state={action.state} />}
      {locked ? <Notice tone="warning">O resultado do último envio não foi confirmado e o formulário ficou travado. Confira a lista abaixo: se a encomenda já aparece, não registre de novo.
        <span className="button-row">
          <button className="button button-secondary button-compact" type="button" onClick={() => void submit(false)} disabled={action.state.pending}>Tentar confirmar o mesmo envio</button>
          <button className="button button-secondary button-compact" type="button" onClick={restart} disabled={action.state.pending}>Conferi a lista: começar outro registro</button></span></Notice> : null}
      <button className="button" type="submit" disabled={!valid || busy || duplicate}>{action.state.pending ? 'Registrando…' : 'Registrar recebimento'}</button>
    </form>
  </section>;
}

const queueStatuses: { id: ParcelStatus; label: string; title: string; empty: string }[] = [
  { id: 'RECEIVED', label: 'A retirar', title: 'Encomendas a retirar', empty: 'Não há encomenda aguardando retirada neste filtro.' },
  { id: 'DELIVERED', label: 'Retiradas', title: 'Retiradas registradas', empty: 'Nenhuma retirada registrada neste filtro.' },
  { id: 'VOIDED', label: 'Anuladas', title: 'Registros anulados', empty: 'Nenhum registro anulado neste filtro.' },
];

function ParcelQueue() {
  const scope = useManagement();
  const [status, setStatus] = useState<ParcelStatus>('RECEIVED');
  const [unitId, setUnitId] = useState('');
  const [location, setLocation] = useState('');
  const [filter, setFilter] = useState<string | null>(null);
  const units = useQuery({ queryKey: conciergeKey(scope, 'units', null), queryFn: ({ signal }) => parcels.units(scope.tenantId, null, signal) });
  const list = useQuery({ queryKey: conciergeKey(scope, 'parcels', status, unitId || null, filter),
    queryFn: ({ signal }) => parcels.list(scope.tenantId, { status, unitId: unitId || null, location: filter }, signal) });
  const [editing, setEditing] = useState<{ parcelId: string; action: 'deliver' | 'void' } | null>(null);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(list.error)) return null;
  const current = queueStatuses.find((item) => item.id === status)!;
  const close = (result: ActionResult | null) => { setEditing(null); if (result) setOutcome(result); };
  return <section className="management-section" aria-labelledby="queue-title">
    <div className="section-heading"><div><span className="section-index">02 / RETIRADA</span><h2 id="queue-title">{current.title}</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button></div>
    <form className="reservation-filter" role="search" aria-label="Filtrar encomendas" onSubmit={(event) => { event.preventDefault(); setFilter(collapseText(location) || null); }}>
      <label htmlFor="queue-status">Situação</label>
      <select id="queue-status" value={status} onChange={(event) => { setStatus(event.target.value as ParcelStatus); setEditing(null); }}>
        {queueStatuses.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select>
      <label htmlFor="queue-unit">Unidade</label>
      <select id="queue-unit" value={unitId} onChange={(event) => { setUnitId(event.target.value); setEditing(null); }} disabled={units.isPending}>
        <option value="">Todas as unidades</option>
        {units.data?.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)}</option>)}</select>
      <label htmlFor="queue-location">Local</label>
      <input id="queue-location" type="search" value={location} onChange={(event) => setLocation(event.target.value)} />
      <button className="button button-secondary button-compact" type="submit">Filtrar</button>
    </form>
    {units.data && units.data.length >= 500 ? <p className="field-help">O filtro de unidade mostra 500 unidades; use a busca do recebimento para as demais.</p> : null}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando encomendas…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.parcels.length === 0 ? <div className="panel"><Empty title="Nenhuma encomenda">{current.empty}</Empty></div>
          : <ol className="management-list" aria-label="Encomendas">{list.data.parcels.map((parcel) => <li key={parcel.parcelId} className="management-row">
            <div><strong><bdi>{unitName(parcel)}</bdi></strong> <ParcelPill parcel={parcel} />
              <span>{volumes(parcel.quantity)} · <bdi>{parcel.storageLocation}</bdi> · recebida em {formatWhen(parcel.receivedAt, scope.timeZone)}</span>
              {parcel.description ? <span><bdi>{parcel.description}</bdi></span> : null}
              {parcel.handoff ? <span>Retirada por <bdi>{parcel.handoff.recipientName}</bdi> em {formatWhen(parcel.handoff.deliveredAt, scope.timeZone)} · documento conferido</span> : null}
              {parcel.voidReason && parcel.voidedAt ? <span>Anulada em {formatWhen(parcel.voidedAt, scope.timeZone)}: <bdi>{parcel.voidReason}</bdi></span> : null}</div>
            {parcel.status === 'RECEIVED' && editing?.parcelId !== parcel.parcelId ? <div className="button-row">
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ parcelId: parcel.parcelId, action: 'deliver' }); }}>Registrar retirada</button>
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ parcelId: parcel.parcelId, action: 'void' }); }}>Anular registro</button></div> : null}
            {editing?.parcelId === parcel.parcelId ? editing.action === 'deliver' ? <DeliverParcel parcel={parcel} close={close} /> : <VoidParcel parcel={parcel} close={close} /> : null}
          </li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">{status === 'RECEIVED'
      ? 'A lista mostra as 100 encomendas mais antigas aguardando retirada neste filtro. Registre as retiradas ou filtre por unidade para ver as demais.'
      : 'A lista mostra os 100 registros mais recentes deste filtro.'}</Notice> : null}
  </section>;
}

function ParcelPill({ parcel }: { parcel: Parcel }) {
  const [label, tone] = parcel.status === 'RECEIVED' ? ['A retirar', 'status-pending'] : parcel.status === 'DELIVERED' ? ['Retirada', 'status-ready'] : ['Anulada', 'status-neutral'];
  return <span className={`status-pill ${tone}`}>{label}</span>;
}

const roleLabels: Record<RecipientRole, string> = { RESIDENT: 'Morador(a)', RESPONSIBLE: 'Responsável', DEPENDENT: 'Dependente autorizado(a)' };
/** Recusas que mudam o estado real: a lista atualizada mostra a situação e o formulário fecha sem sucesso falso. */
const stateCodes = ['PARCEL_ALREADY_DELIVERED', 'PARCEL_VOIDED', 'PARCEL_CHANGED', 'PARCEL_NOT_FOUND'];

function DeliverParcel({ parcel, close }: { parcel: Parcel; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const intent = useIntent();
  const [read] = useState(parcel.version);
  const recipients = useQuery({ queryKey: conciergeKey(scope, 'recipients', parcel.parcelId), queryFn: ({ signal }) => parcels.recipients(scope.tenantId, parcel.parcelId, signal) });
  const [recipient, setRecipient] = useState('');
  const [checked, setChecked] = useState(false);
  const [locked, setLocked] = useState(false);
  if (useAccessGuard(recipients.error)) return null;
  const chosen = recipients.data?.find((item) => item.membershipId === recipient);
  const valid = chosen !== undefined && checked;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body = { recipientMembershipId: chosen!.membershipId, identityChecked: true as const, expectedVersion: read };
    const key = intent.keyFor(JSON.stringify(body));
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await parcels.deliver(scope.tenantId, parcel.parcelId, key, body); }
      catch (error) { failure = error; throw repeatFailure(error); }
    }, (done: ParcelMutation): ActionResult => {
      result = done.parcel.parcelId === parcel.parcelId && done.parcel.status === 'DELIVERED' && done.parcel.handoff
        ? { tone: done.replayed ? 'info' : 'success', text: `${done.replayed ? 'Esta retirada já estava registrada' : 'Retirada registrada'}: ${done.parcel.handoff.recipientName} retirou a encomenda de ${unitName(done.parcel)} em ${formatWhen(done.parcel.handoff.deliveredAt, scope.timeZone)}.` }
        : { tone: 'warning', text: 'A resposta não confirmou a retirada. Atualize a lista e confira.' };
      return result;
    }, async () => { await queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); });
    if (ok) { close(result); return; }
    const code = codeOf(failure);
    if (stateCodes.includes(code)) {
      close({ tone: 'warning', text: `${errorMessage(failure)} Nenhuma nova retirada foi registrada.` });
      void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') });
      return;
    }
    if (code === 'PARCEL_RECIPIENT_NOT_ALLOWED') { setRecipient(''); setChecked(false); intent.reset(); void recipients.refetch(); return; }
    if (freshKeyCodes.includes(code)) { intent.reset(); void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); return; }
    if (uncertain(failure)) { setLocked(true); void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); }
  }
  const id = `deliver-${parcel.parcelId}`;
  const busy = action.state.pending || locked;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Registrar retirada de ${unitName(parcel)}`}>
    <Notice>Entregue somente a uma das pessoas abaixo, conferindo o documento com o nome escolhido. Saber o nome do morador não basta; retirada por outra pessoa depende de delegação, que ainda não está disponível.</Notice>
    {recipients.isPending ? <Loading label="Consultando quem pode retirar…" />
      : recipients.isError ? <ErrorState error={recipients.error} retry={() => void recipients.refetch()} />
        : recipients.data.length === 0 ? <Notice tone="warning">Nenhuma pessoa com vínculo vigente pode retirar encomendas desta unidade. A gestão precisa validar o vínculo antes da retirada.</Notice>
          : <fieldset className="weekday-fieldset"><legend>Quem retira</legend>
            {recipients.data.map((item) => <label key={item.membershipId} className="weekday-option"><input type="radio" name={`${id}-recipient`} value={item.membershipId}
              checked={recipient === item.membershipId} onChange={() => setRecipient(item.membershipId)} disabled={busy} /><bdi>{item.displayName}</bdi> · {roleLabels[item.role]}</label>)}
          </fieldset>}
    {recipients.data && recipients.data.length >= 100 ? <span className="field-help">A lista mostra 100 pessoas, em ordem alfabética.</span> : null}
    <label className="checkbox-label" htmlFor={`${id}-checked`}><input id={`${id}-checked`} type="checkbox" checked={checked}
      onChange={(event) => setChecked(event.target.checked)} disabled={busy} />Conferi o documento da pessoa escolhida</label>
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado e a escolha ficou travada. Atualize a lista: se a encomenda aparecer como retirada, não repita.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Registrando…' : locked ? 'Tentar confirmar a mesma retirada' : 'Confirmar retirada'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}

/** Registro indevido (unidade errada, duplicidade): anula com motivo; a unidade deixa de vê-lo e nada é apagado. */
function VoidParcel({ parcel, close }: { parcel: Parcel; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const intent = useIntent();
  const [read] = useState(parcel.version);
  const [reason, setReason] = useState('');
  const [locked, setLocked] = useState(false);
  const normalized = displayText(reason, 500);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    const body = { reason: normalized, expectedVersion: read };
    const key = intent.keyFor(JSON.stringify(body));
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await parcels.voidParcel(scope.tenantId, parcel.parcelId, key, body); }
      catch (error) { failure = error; throw repeatFailure(error); }
    }, (done: ParcelMutation): ActionResult => {
      result = done.parcel.parcelId === parcel.parcelId && done.parcel.status === 'VOIDED'
        ? { tone: done.replayed ? 'info' : 'success', text: `${done.replayed ? 'Este registro já estava anulado' : 'Registro anulado'}: encomenda de ${unitName(done.parcel)} (${volumes(done.parcel.quantity)} em ${done.parcel.storageLocation}). O motivo ficou na auditoria.` }
        : { tone: 'warning', text: 'A resposta não confirmou a anulação. Atualize a lista e confira.' };
      return result;
    }, async () => { await queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); });
    if (ok) { close(result); return; }
    const code = codeOf(failure);
    if (stateCodes.includes(code)) {
      close({ tone: 'warning', text: `${errorMessage(failure)} Nada foi anulado.` });
      void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') });
      return;
    }
    if (freshKeyCodes.includes(code)) { intent.reset(); void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); return; }
    if (uncertain(failure)) { setLocked(true); void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); }
  }
  const id = `void-${parcel.parcelId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Anular registro de ${unitName(parcel)}`}>
    <Notice tone="warning">Use para registro feito na unidade errada ou em duplicidade. A encomenda deixa de aparecer para a unidade e não pode mais ser retirada; registre de novo na unidade certa, se for o caso.</Notice>
    <label htmlFor={`${id}-reason`}>Motivo (fica na auditoria)</label>
    <textarea id={`${id}-reason`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending || locked} />
    <ActionFeedback state={action.state} success={false} />
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o motivo ficou travado. Atualize a lista antes de repetir.</Notice> : null}
    <div className="button-row">
      <button className="button button-danger" type="submit" disabled={normalized === null || action.state.pending}>{action.state.pending ? 'Anulando…' : 'Anular registro'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}
