import { useCallback, useMemo, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, newOperationKey } from '../../api/client';
import { parcels, type Parcel, type ParcelMutation, type ParcelStatus } from '../../api/parcels';
import { collapseText, displayText } from '../../api/text';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice, errorMessage } from '../primitives';
import { dateInZone, formatWhen } from '../dateTime';
import { ActionFeedback, ManagementProvider, useAccessGuard, useAction, useManagement, type ActionResult, type ManagementScope } from '../management/shared';
import '../management/Management.css';

/** Chaves de cache da portaria, separadas da gestão e do morador. */
const conciergeKey = (scope: Pick<ManagementScope, 'accountId' | 'tenantId'>, ...parts: unknown[]) =>
  ['private', scope.accountId, 'tenant', scope.tenantId, 'concierge', ...parts] as const;

/** Portaria do condomínio: exige vínculo CONCIERGE vigente e segundo fator; o servidor confere de novo em cada ação. */
export function ConciergePage() {
  const { identity } = useAuth();
  const { tenantId = '' } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.role === 'CONCIERGE');
  if (!identity || !membership) return <div className="panel"><Empty title="Portaria indisponível">Seu cadastro não inclui a portaria deste condomínio. Volte para escolher um acesso disponível.</Empty></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido antes de abrir a portaria.</Notice>; }
  return <ConciergeGate key={`${identity.accountId}:${tenantId}`} scope={{ accountId: identity.accountId, tenantId,
    tenantName: membership.tenantName, timeZone: membership.timeZone }} />;
}

function ConciergeGate({ scope }: { scope: ManagementScope }) {
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
      <p>Recebimentos e entregas ficam registrados com o seu acesso. O aviso ao morador segue em fila e nunca condiciona o registro. Horários em {scope.timeZone}.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Segundo fator confirmado</strong><span>Sessão da portaria</span></div></div></div>
    <ReceiveParcel />
    <ParcelQueue />
  </ManagementProvider>;
}

const unitName = (item: { unitLabel?: string; label?: string; blockName: string | null }) =>
  `${item.blockName ? `${item.blockName} · ` : ''}${item.unitLabel ?? item.label ?? ''}`;

function ReceiveParcel() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState<string | null>(null);
  const units = useQuery({ queryKey: conciergeKey(scope, 'units', term), queryFn: ({ signal }) => parcels.units(scope.tenantId, term, signal) });
  const [unitId, setUnitId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [description, setDescription] = useState('');
  const [location, setLocation] = useState('');
  // Mesma intenção reaproveita a chave: repetir depois de resultado incerto não cria segundo registro.
  const [intent, setIntent] = useState<{ fingerprint: string; key: string } | null>(null);
  if (useAccessGuard(units.error)) return null;
  const count = Number(quantity);
  const normalizedLocation = displayText(location, 160);
  const normalizedDescription = collapseText(description) ? displayText(description, 500) : '';
  const valid = Boolean(unitId) && Number.isInteger(count) && count >= 1 && count <= 1000 && normalizedLocation !== null && normalizedDescription !== null;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body = { unitId, quantity: count, description: normalizedDescription || null, storageLocation: normalizedLocation! };
    const fingerprint = JSON.stringify(body);
    const key = intent?.fingerprint === fingerprint ? intent.key : newOperationKey();
    setIntent({ fingerprint, key });
    const ok = await action.run(() => parcels.receive(scope.tenantId, key, body), (result: ParcelMutation): ActionResult => result.replayed
      ? { tone: 'info', text: 'Este recebimento já estava registrado com os mesmos dados. Nada novo foi criado.' }
      : { tone: 'success', text: `Encomenda registrada para ${unitName(result.parcel)}: ${result.parcel.quantity} ${result.parcel.quantity === 1 ? 'volume' : 'volumes'} em ${result.parcel.storageLocation}. O aviso ao morador segue em fila.` },
      async () => { await queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); });
    if (ok) { setIntent(null); setQuantity('1'); setDescription(''); setLocation(''); }
  }
  return <section className="management-section" aria-labelledby="receive-title">
    <div className="section-heading"><div><span className="section-index">01 / RECEBER</span><h2 id="receive-title">Receber encomenda</h2></div></div>
    <form className="panel stack-form" onSubmit={(event) => void submit(event)} aria-label="Receber encomenda">
      <div className="reservation-filter tenant-search" role="search" aria-label="Buscar unidade">
        <label htmlFor="unit-search">Buscar unidade</label>
        <input id="unit-search" type="search" value={search} onChange={(event) => setSearch(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); setTerm(collapseText(search) || null); } }} disabled={action.state.pending} />
        <button className="button button-secondary button-compact" type="button" onClick={() => setTerm(collapseText(search) || null)} disabled={action.state.pending}>Buscar</button>
      </div>
      <label htmlFor="parcel-unit">Unidade</label>
      <select id="parcel-unit" required value={unitId} onChange={(event) => setUnitId(event.target.value)} disabled={action.state.pending || units.isPending}>
        <option value="">{units.isPending ? 'Consultando unidades…' : 'Selecione a unidade'}</option>
        {units.data?.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)}</option>)}</select>
      {units.isError ? <ErrorState error={units.error} retry={() => void units.refetch()} /> : null}
      {units.data && units.data.length === 0 ? <span className="field-help">Nenhuma unidade ativa encontrada nesta busca.</span> : null}
      <label htmlFor="parcel-quantity">Volumes</label>
      <input id="parcel-quantity" type="number" min={1} max={1000} required value={quantity} onChange={(event) => setQuantity(event.target.value)} disabled={action.state.pending} />
      <label htmlFor="parcel-description">Descrição curta (opcional)</label>
      <input id="parcel-description" maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} disabled={action.state.pending}
        placeholder="Ex.: caixa média, envelope" />
      <label htmlFor="parcel-location">Local de guarda</label>
      <input id="parcel-location" required maxLength={160} value={location} onChange={(event) => setLocation(event.target.value)} disabled={action.state.pending}
        placeholder="Ex.: Prateleira B" />
      <ActionFeedback state={action.state} />
      <button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Registrando…' : 'Registrar recebimento'}</button>
    </form>
  </section>;
}

function ParcelQueue() {
  const scope = useManagement();
  const [status, setStatus] = useState<ParcelStatus>('RECEIVED');
  const [location, setLocation] = useState('');
  const [filter, setFilter] = useState<string | null>(null);
  const list = useQuery({ queryKey: conciergeKey(scope, 'parcels', status, filter),
    queryFn: ({ signal }) => parcels.list(scope.tenantId, { status, location: filter }, signal) });
  const [delivering, setDelivering] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(list.error)) return null;
  return <section className="management-section" aria-labelledby="queue-title">
    <div className="section-heading"><div><span className="section-index">02 / RETIRADA</span><h2 id="queue-title">{status === 'RECEIVED' ? 'Encomendas a retirar' : 'Retiradas registradas'}</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button></div>
    <form className="reservation-filter" role="search" aria-label="Filtrar encomendas" onSubmit={(event) => { event.preventDefault(); setFilter(collapseText(location) || null); }}>
      <label htmlFor="queue-status">Situação</label>
      <select id="queue-status" value={status} onChange={(event) => { setStatus(event.target.value as ParcelStatus); setDelivering(null); }}>
        <option value="RECEIVED">A retirar</option><option value="DELIVERED">Retiradas</option></select>
      <label htmlFor="queue-location">Local</label>
      <input id="queue-location" type="search" value={location} onChange={(event) => setLocation(event.target.value)} />
      <button className="button button-secondary button-compact" type="submit">Filtrar</button>
    </form>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando encomendas…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.parcels.length === 0 ? <div className="panel"><Empty title="Nenhuma encomenda">{status === 'RECEIVED' ? 'Não há encomenda aguardando retirada neste filtro.' : 'Nenhuma retirada registrada neste filtro.'}</Empty></div>
          : <ol className="management-list" aria-label="Encomendas">{list.data.parcels.map((parcel) => <li key={parcel.parcelId} className="management-row">
            <div><strong><bdi>{unitName(parcel)}</bdi></strong> <span className={`status-pill ${parcel.status === 'RECEIVED' ? 'status-pending' : 'status-ready'}`}>{parcel.status === 'RECEIVED' ? 'A retirar' : 'Retirada'}</span>
              <span>{parcel.quantity} {parcel.quantity === 1 ? 'volume' : 'volumes'} · {parcel.storageLocation} · recebida em {formatWhen(parcel.receivedAt, scope.timeZone)}</span>
              {parcel.description ? <span><bdi>{parcel.description}</bdi></span> : null}
              {parcel.handoff ? <span>Retirada por <bdi>{parcel.handoff.recipientName}</bdi> em {formatWhen(parcel.handoff.deliveredAt, scope.timeZone)} · documento conferido</span> : null}</div>
            {parcel.status === 'RECEIVED' && delivering !== parcel.parcelId ? <div className="button-row">
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setDelivering(parcel.parcelId); }}>Registrar retirada</button></div> : null}
            {delivering === parcel.parcelId ? <DeliverParcel parcel={parcel} close={(result) => { setDelivering(null); if (result) setOutcome(result); }} /> : null}
          </li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 encomendas mais recentes deste filtro.</Notice> : null}
  </section>;
}

function DeliverParcel({ parcel, close }: { parcel: Parcel; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [read] = useState(parcel.version);
  const [recipient, setRecipient] = useState('');
  const [checked, setChecked] = useState(false);
  const [intent, setIntent] = useState<{ fingerprint: string; key: string } | null>(null);
  const normalized = displayText(recipient, 160);
  const valid = normalized !== null && checked;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body = { recipientName: normalized!, identityChecked: true as const, expectedVersion: read };
    const fingerprint = JSON.stringify(body);
    const key = intent?.fingerprint === fingerprint ? intent.key : newOperationKey();
    setIntent({ fingerprint, key });
    let result: ActionResult | null = null;
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await parcels.deliver(scope.tenantId, parcel.parcelId, key, body); }
      catch (error) { failure = error; throw error; }
    }, (done: ParcelMutation): ActionResult => {
      result = done.parcel.status === 'DELIVERED' && done.parcel.handoff
        ? { tone: done.replayed ? 'info' : 'success', text: `${done.replayed ? 'Esta retirada já estava registrada' : 'Retirada registrada'}: ${done.parcel.handoff.recipientName} retirou a encomenda de ${unitName(done.parcel)} em ${formatWhen(done.parcel.handoff.deliveredAt, scope.timeZone)}.` }
        : { tone: 'warning', text: 'A resposta não confirmou a retirada. Atualize a lista e confira.' };
      return result;
    }, async () => { await queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') }); });
    if (ok) { close(result); return; }
    // Entrega feita por outra pessoa ou encomenda alterada: a lista atualizada mostra o estado real; a recusa fica visível.
    if (failure instanceof ApiError && ['PARCEL_ALREADY_DELIVERED', 'PARCEL_CHANGED', 'PARCEL_NOT_FOUND'].includes(failure.code)) {
      close({ tone: 'warning', text: `${errorMessage(failure)} Nenhuma nova retirada foi registrada.` });
      void queries.invalidateQueries({ queryKey: conciergeKey(scope, 'parcels') });
    }
  }
  const id = `deliver-${parcel.parcelId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Registrar retirada de ${unitName(parcel)}`}>
    <Notice>Entregue somente a quem tem vínculo com a unidade, conferindo o documento. Saber o nome do morador não basta.</Notice>
    <label htmlFor={`${id}-recipient`}>Nome de quem retira</label>
    <input id={`${id}-recipient`} required maxLength={160} value={recipient} onChange={(event) => setRecipient(event.target.value)} disabled={action.state.pending} />
    <label className="checkbox-label" htmlFor={`${id}-checked`}><input id={`${id}-checked`} type="checkbox" checked={checked}
      onChange={(event) => setChecked(event.target.checked)} disabled={action.state.pending} />Conferi o documento da pessoa que retira</label>
    <ActionFeedback state={action.state} success={false} />
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Registrando…' : 'Confirmar retirada'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.state.pending}>Voltar</button>
    </div>
  </form>;
}
