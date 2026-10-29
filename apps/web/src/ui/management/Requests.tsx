import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { requestStates, requestTransitions, requests, type ManagedRequest, type RequestFilter, type RequestState } from '../../api/requests';
import { multilineText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { dateInZone, formatWhen } from '../dateTime';
import { codeOf, repeatFailure, settleFailure, usePendingOperation } from '../operationIntent';
import { categoryLabels, civilDate, stateLabels } from '../requestLabels';
import { ActionFeedback, useAccessGuard, useAction, useManagement, managementKey, type ActionResult } from './shared';

const accessCodes = ['MFA_REQUIRED', 'STAFF_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'];
const filters: { value: RequestFilter; label: string }[] = [
  { value: 'ACTIVE', label: 'Em andamento (todos, menos encerrados)' }, { value: 'ALL', label: 'Todos' },
  ...requestStates.map((state) => ({ value: state as RequestFilter, label: stateLabels[state][0] })),
];

/**
 * Pedidos da unidade na gestão (CP-033): acompanhar, conversar (mensagem visível ou comentário interno), definir
 * responsável da equipe e prazo comunicado e mudar o andamento pela versão lida. Ao concluir, a resolução também entra
 * na conversa visível. Comentário interno nunca aparece para a unidade.
 */
export function RequestSection() {
  const scope = useManagement();
  const [filter, setFilter] = useState<RequestFilter>('ACTIVE');
  const list = useQuery({ queryKey: managementKey(scope, 'requests', filter), queryFn: ({ signal }) => requests.managed(scope.tenantId, filter, signal) });
  const [selected, setSelected] = useState<string | null>(null);
  if (useAccessGuard(list.error)) return null;
  const unavailable = codeOf(list.error) === 'API_NOT_AVAILABLE';
  return <section className="management-section" aria-labelledby="requests-title">
    <div className="section-heading"><div><span className="section-index">PEDIDOS</span><h2 id="requests-title">Pedidos das unidades</h2></div>
      <div className="button-row">
        <label className="sr-only" htmlFor="requests-filter">Filtrar por andamento</label>
        <select id="requests-filter" value={filter} onChange={(event) => { setSelected(null); setFilter(event.target.value as RequestFilter); }}>
          {filters.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button>
      </div></div>
    <p className="field-help">A unidade vê o andamento, o prazo comunicado e as mensagens visíveis; comentários internos e o nome de quem responde pela equipe ficam só aqui.</p>
    {unavailable ? <div className="panel"><Empty title="Pedidos indisponíveis">Os pedidos ainda não estão ativos neste ambiente.</Empty></div>
      : list.isPending ? <div className="panel"><Loading label="Consultando pedidos…" /></div>
        : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
          : !list.data || list.data.requests.length === 0 ? <div className="panel"><Empty title="Nenhum pedido">Nenhum pedido neste filtro.</Empty></div>
            : <ol className="management-list" aria-label="Pedidos das unidades">{list.data.requests.map((item) => {
              const [label, tone] = stateLabels[item.state];
              return <li key={item.requestId} className="management-row">
                <div><strong><bdi>{item.title}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
                  <span>Unidade <bdi>{item.unitLabel}</bdi> · {categoryLabels[item.category]} · atualizado em {formatWhen(item.updatedAt, scope.timeZone)}</span>
                  <span>{item.assigneeName ? <>Responsável: <bdi>{item.assigneeName}</bdi></> : item.assigneeMembershipId ? 'Responsável sem vínculo vigente' : 'Sem responsável'}
                    {item.dueOn ? ` · prazo comunicado: ${civilDate(item.dueOn)}` : ''}</span></div>
                {selected === item.requestId ? <ManagedDetail requestId={item.requestId} close={() => setSelected(null)} />
                  : <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => setSelected(item.requestId)}>Abrir pedido</button></div>}
              </li>;
            })}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 pedidos atualizados mais recentemente.</Notice> : null}
  </section>;
}

function ManagedDetail({ requestId, close }: { requestId: string; close(): void }) {
  const scope = useManagement();
  const detail = useQuery({ queryKey: managementKey(scope, 'request', requestId), queryFn: ({ signal }) => requests.managedRequest(scope.tenantId, requestId, signal) });
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  if (useAccessGuard(detail.error)) return null;
  if (detail.isPending) return <div className="panel"><Loading label="Abrindo o pedido…" /></div>;
  if (detail.isError && !detail.data) return <div><ErrorState error={detail.error} retry={() => void detail.refetch()} />
    <button className="button button-secondary button-compact" type="button" onClick={close}>Fechar</button></div>;
  const request = detail.data!;
  return <div className="request-detail">
    <p className="announcement-body"><bdi>{request.description}</bdi></p>
    <span>Aberto por <bdi>{request.requesterName}</bdi> em {formatWhen(request.createdAt, scope.timeZone)}</span>
    {request.resolution ? <Notice tone="success">Resolução vigente: <bdi>{request.resolution}</bdi></Notice> : null}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {request.messagesTruncated ? <Notice tone="warning">A conversa mostra as 200 mensagens mais recentes.</Notice> : null}
    {request.messages.length === 0 ? <p className="field-help">Nenhuma mensagem ainda.</p>
      : <ol className="request-messages" aria-label="Conversa do pedido">{request.messages.map((message) =>
        <li key={message.messageId} className={message.visibility === 'INTERNAL' ? 'request-internal' : undefined}>
          <strong>{message.mine ? 'Você' : <bdi>{message.authorName}</bdi>}{message.authorRole === 'STAFF' ? ' (equipe)' : ' (unidade)'}</strong>
          <span>{formatWhen(message.createdAt, scope.timeZone)}{message.visibility === 'INTERNAL' ? ' · comentário interno, não aparece para a unidade' : ''}</span>
          <p className="announcement-body"><bdi>{message.body}</bdi></p></li>)}</ol>}
    {request.state === 'CLOSED' ? <Notice tone="info">Pedido encerrado: a conversa fica guardada e não recebe novas mensagens.</Notice>
      : <><StaffMessageForm request={request} /><UpdateForm request={request} done={setOutcome} /></>}
    <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={close}>Fechar pedido</button></div>
  </div>;
}

function StaffMessageForm({ request }: { request: ManagedRequest }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const intent = usePendingOperation(`${scope.tenantId}:request-staff-message:${request.requestId}`);
  const pending = intent.lockedBody as { body: string; visibility: 'RESIDENT' | 'INTERNAL' } | null;
  const [body, setBody] = useState(pending?.body ?? '');
  const [internal, setInternal] = useState(pending?.visibility === 'INTERNAL');
  const action = useAction();
  const normalized = multilineText(body, 2000);
  const id = `staff-message-${request.requestId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    const visibility = internal ? 'INTERNAL' as const : 'RESIDENT' as const;
    const operationKey = intent.keyFor(JSON.stringify({ body: normalized, visibility }), { body: normalized, visibility });
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await requests.staffMessage(scope.tenantId, request.requestId, operationKey, normalized, visibility); }
      catch (error) { failure = error; throw repeatFailure(error); }
      finally { intent.settle(); }
    }, (done) => ({ tone: done.replayed ? 'info' : 'success', text: done.replayed ? 'Esta mensagem já estava registrada.'
      : visibility === 'INTERNAL' ? 'Comentário interno registrado.' : 'Mensagem enviada para a unidade.' }),
    async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'request', request.requestId) }); });
    if (ok) { intent.finish(); setBody(''); return; }
    if (accessCodes.includes(codeOf(failure))) return;
    settleFailure(failure, intent.locked, intent, intent.setLocked);
    void queries.invalidateQueries({ queryKey: managementKey(scope, 'request', request.requestId) });
  }
  const busy = action.state.pending || intent.locked || intent.inFlight;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Mensagem no pedido ${request.title}`}>
    <label htmlFor={id}>Mensagem</label>
    <textarea id={id} maxLength={2000} rows={3} value={body} onChange={(event) => setBody(event.target.value)} disabled={busy} />
    <label className="weekday-option"><input type="checkbox" checked={internal} disabled={busy} onChange={(event) => setInternal(event.target.checked)} />
      Comentário interno (não aparece para a unidade)</label>
    <ActionFeedback state={action.state} />
    {intent.locked ? <Notice tone="warning">O envio não foi confirmado. Use “Tentar confirmar a mesma mensagem”: se ela já foi gravada, nada novo é enviado.</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={normalized === null || action.state.pending}>
      {action.state.pending ? 'Enviando…' : intent.locked ? 'Tentar confirmar a mesma mensagem' : internal ? 'Registrar comentário interno' : 'Enviar para a unidade'}</button></div>
  </form>;
}

function UpdateForm({ request, done }: { request: ManagedRequest; done(result: ActionResult): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [state, setState] = useState<RequestState>(request.state);
  const [assignee, setAssignee] = useState(request.assigneeMembershipId ?? '');
  const [due, setDue] = useState(request.dueOn ?? '');
  const [resolution, setResolution] = useState('');
  const today = dateInZone(scope.timeZone);
  const resolving = state === 'RESOLVED' && request.state !== 'RESOLVED';
  const normalizedResolution = resolving ? multilineText(resolution, 1000) : null;
  const assigneeKnown = assignee === '' || request.team.some((member) => member.membershipId === assignee) || assignee === request.assigneeMembershipId;
  const dueValid = due === '' || due === request.dueOn || /^\d{4}-\d{2}-\d{2}$/.test(due) && due >= today;
  const changed = state !== request.state || (assignee || null) !== request.assigneeMembershipId || (due || null) !== request.dueOn;
  const valid = changed && assigneeKnown && dueValid && (!resolving || normalizedResolution !== null);
  const id = `request-update-${request.requestId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const ok = await action.run(() => requests.update(scope.tenantId, request.requestId, { state, assigneeMembershipId: assignee || null,
      dueOn: due || null, resolution: resolving ? normalizedResolution : null, expectedVersion: request.version }),
    (updated) => ({ tone: 'success', text: `Pedido atualizado: ${stateLabels[updated.state][0].toLowerCase()}.` }),
    async () => {
      await queries.invalidateQueries({ queryKey: managementKey(scope, 'request', request.requestId) });
      await queries.invalidateQueries({ queryKey: managementKey(scope, 'requests') });
    });
    if (ok) { setResolution(''); done({ tone: 'success', text: resolving ? 'Pedido concluído; a resolução também foi enviada na conversa.' : 'Pedido atualizado.' }); }
    else void queries.invalidateQueries({ queryKey: managementKey(scope, 'request', request.requestId) });
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Atualizar pedido ${request.title}`}>
    <label htmlFor={`${id}-state`}>Andamento</label>
    <select id={`${id}-state`} value={state} disabled={action.state.pending} onChange={(event) => setState(event.target.value as RequestState)}>
      {[request.state, ...requestTransitions[request.state]].map((value) => <option key={value} value={value}>{stateLabels[value][0]}{value === request.state ? ' (atual)' : ''}</option>)}
    </select>
    <label htmlFor={`${id}-assignee`}>Responsável da equipe</label>
    <select id={`${id}-assignee`} value={assignee} disabled={action.state.pending} onChange={(event) => setAssignee(event.target.value)}>
      <option value="">Sem responsável</option>
      {request.assigneeMembershipId && !request.team.some((member) => member.membershipId === request.assigneeMembershipId)
        ? <option value={request.assigneeMembershipId}>Responsável atual (sem vínculo vigente)</option> : null}
      {request.team.map((member) => <option key={member.membershipId} value={member.membershipId}>{member.name} · {member.role === 'MANAGER' ? 'gestão' : 'portaria'}</option>)}
    </select>
    <label htmlFor={`${id}-due`}>Prazo comunicado à unidade (opcional)</label>
    <input id={`${id}-due`} type="date" min={today} value={due} disabled={action.state.pending} onChange={(event) => setDue(event.target.value)} />
    {resolving ? <><label htmlFor={`${id}-resolution`}>Resolução (aparece para a unidade)</label>
      <textarea id={`${id}-resolution`} maxLength={1000} rows={3} value={resolution} disabled={action.state.pending} onChange={(event) => setResolution(event.target.value)} /></> : null}
    {state === 'CLOSED' ? <Notice tone="warning">Encerrar é definitivo: a conversa fica guardada e não recebe novas mensagens.</Notice> : null}
    <ActionFeedback state={action.state} success={false} />
    <div className="button-row"><button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Salvando…' : 'Salvar andamento'}</button></div>
  </form>;
}
