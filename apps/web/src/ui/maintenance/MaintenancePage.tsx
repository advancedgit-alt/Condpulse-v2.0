import { useCallback, useMemo, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import { maintenanceTransitions, requests, type MaintenanceRequest, type MaintenanceState } from '../../api/requests';
import { multilineText } from '../../api/text';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice, type IconName } from '../primitives';
import { dateInZone, formatWhen } from '../dateTime';
import { codeOf, repeatFailure, settleFailure, usePendingOperation } from '../operationIntent';
import { categoryLabels, civilDate, stateLabels } from '../requestLabels';
import { RequestConversation } from '../RequestConversation';
import { ActionFeedback, ManagementProvider, useAccessGuard, useAction, useManagement, type ActionResult, type ManagementScope } from '../management/shared';
import { ENTRY_PATH, maintenancePath } from '../shell/paths';
import '../management/Management.css';

export const maintenanceSections = [{ id: 'pedidos', label: 'Pedidos atribuídos', icon: 'chat' }] as const satisfies readonly { id: string; label: string; icon: IconName }[];
export const maintenanceKey = (scope: Pick<ManagementScope, 'accountId' | 'tenantId'>, ...parts: unknown[]) =>
  ['private', scope.accountId, 'tenant', scope.tenantId, 'maintenance', ...parts] as const;
const accessCodes = ['MFA_REQUIRED', 'STAFF_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'];
const stateHelp: Partial<Record<MaintenanceRequest['state'], string>> = {
  OPEN: 'O pedido ainda aguarda a triagem da gestão: você pode conversar, e o andamento muda depois da triagem.',
  RESOLVED: 'Pedido concluído. A gestão encerra ou reabre; você ainda pode conversar.',
  CLOSED: 'Pedido encerrado: a conversa fica guardada e não recebe novas mensagens.',
};

/**
 * Zeladoria do condomínio (V046): só a equipe com vínculo MAINTENANCE vigente, com segundo fator. Mostra apenas os pedidos
 * atribuídos ao próprio vínculo, sem o nome de quem abriu; a zeladoria conversa (mensagem para a unidade ou comentário
 * interno) e muda o andamento pela versão lida. Responsável, prazo e encerramento ficam com a gestão. O servidor confere
 * vínculo, atribuição, AAL2 e módulo de novo em cada chamada.
 */
export function MaintenancePage() {
  const { identity } = useAuth();
  const { tenantId = '', requestId } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.role === 'MAINTENANCE');
  if (!identity || !membership) return <div className="panel"><Empty icon="shield" title="Zeladoria indisponível">Seu cadastro não inclui a zeladoria deste condomínio. Volte para escolher um acesso disponível.</Empty>
    <Link className="button button-secondary" to={ENTRY_PATH}>Ver meus acessos</Link></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido antes de abrir a zeladoria.</Notice>; }
  return <MaintenanceGate key={`${identity.accountId}:${tenantId}`} requestId={requestId ?? null} scope={{ accountId: identity.accountId, tenantId,
    tenantName: membership.tenantName, timeZone: membership.timeZone }} />;
}

function MaintenanceGate({ scope, requestId }: { scope: ManagementScope; requestId: string | null }) {
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
  // O contexto da gestão serve de porta comum da equipe: mesmas reações a segundo fator e perda de vínculo.
  const value = useMemo(() => ({ ...scope, requireMfa, denied: deny }), [scope, requireMfa, deny]);
  if (denied) return <div className="panel"><Notice tone="error">A zeladoria deste condomínio não está mais disponível para o seu vínculo. Os dados exibidos foram removidos.</Notice>
    <Link className="button button-secondary" to={ENTRY_PATH}>Escolher outro acesso</Link></div>;
  if (status.isPending) return <div className="panel"><Loading label="Conferindo o segundo fator…" /></div>;
  if (status.isError && !status.data) return <ErrorState error={status.error} retry={() => void status.refetch()} />;
  if (!status.data) return null;
  if (status.data.assuranceLevel !== 'AAL2' || demanded) {
    return <MfaPanel accountId={scope.accountId} onVerified={() => {
      setDemanded(false);
      void queries.invalidateQueries({ queryKey: mfaStatusKey(scope.accountId) });
      void queries.invalidateQueries({ queryKey: maintenanceKey(scope) });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }} />;
  }
  return <ManagementProvider value={value}>
    <div className="residence-heading"><div><span className="eyebrow">ZELADORIA</span><h1>{scope.tenantName}</h1>
      <p>Pedidos atribuídos a você pela gestão. Mensagens e andamento ficam registrados com o seu acesso. Horários em {scope.timeZone}.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Segundo fator confirmado</strong><span>Sessão da zeladoria</span></div></div></div>
    {requestId ? <MaintenanceDetail key={requestId} requestId={requestId} /> : <MaintenanceList />}
  </ManagementProvider>;
}

function MaintenanceList() {
  const scope = useManagement();
  const list = useQuery({ queryKey: maintenanceKey(scope, 'requests'), queryFn: ({ signal }) => requests.maintenance(scope.tenantId, signal) });
  if (useAccessGuard(list.error)) return null;
  const unavailable = codeOf(list.error) === 'API_NOT_AVAILABLE';
  return <section className="management-section" aria-labelledby="maintenance-title">
    <div className="section-heading"><div><span className="section-index">PEDIDOS</span><h2 id="maintenance-title">Pedidos atribuídos a você</h2></div>
      <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>
        {list.isFetching && !list.isPending ? 'Atualizando…' : 'Atualizar'}</button></div></div>
    <p className="field-help">Aparecem só os pedidos em andamento que a gestão atribuiu a você. Pedidos encerrados saem da lista.</p>
    {unavailable ? <div className="panel"><Empty icon="chat" title="Pedidos indisponíveis">Os pedidos ainda não estão ativos neste ambiente.</Empty></div>
      : list.isPending ? <div className="panel"><Loading label="Consultando seus pedidos…" /></div>
        : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
          : !list.data || list.data.requests.length === 0 ? <div className="panel"><Empty icon="chat" title="Nenhum pedido atribuído">Quando a gestão atribuir um pedido a você, ele aparece aqui.</Empty></div>
            : <ol className="management-list" aria-label="Pedidos atribuídos">{list.data.requests.map((item) => {
              const [label, tone] = stateLabels[item.state];
              return <li key={item.requestId} className="management-row">
                <div><strong><bdi>{item.title}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
                  <span>Unidade <bdi>{item.unitLabel}</bdi> · {categoryLabels[item.category]} · atualizado em {formatWhen(item.updatedAt, scope.timeZone)}
                    {item.dueOn ? ` · prazo comunicado: ${civilDate(item.dueOn)}` : ''}</span></div>
                <div className="button-row"><Link className="button button-secondary button-compact" to={maintenancePath(scope.tenantId, item.requestId)}
                  aria-label={`Abrir pedido ${item.title}`}>Abrir pedido</Link></div>
              </li>;
            })}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 pedidos atualizados mais recentemente.</Notice> : null}
  </section>;
}

function MaintenanceDetail({ requestId }: { requestId: string }) {
  const scope = useManagement();
  const detail = useQuery({ queryKey: maintenanceKey(scope, 'request', requestId), queryFn: ({ signal }) => requests.maintenanceRequest(scope.tenantId, requestId, signal) });
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  const back = <Link className="button button-secondary button-compact" to={maintenancePath(scope.tenantId)}>Voltar aos pedidos</Link>;
  if (useAccessGuard(detail.error)) return null;
  if (detail.isPending) return <div className="panel"><Loading label="Abrindo o pedido…" /></div>;
  if (codeOf(detail.error) === 'SERVICE_REQUEST_NOT_FOUND') return <div className="panel"><Empty icon="chat" title="Pedido não encontrado">
    Este pedido não está atribuído a você. A gestão pode ter mudado o responsável.</Empty>{back}</div>;
  if (detail.isError && !detail.data) return <div><ErrorState error={detail.error} retry={() => void detail.refetch()} />{back}</div>;
  const request = detail.data!;
  const [label, tone] = stateLabels[request.state];
  return <section className="management-section" aria-labelledby="maintenance-request-title">
    <div className="section-heading"><div><span className="section-index">PEDIDO · UNIDADE <bdi>{request.unitLabel}</bdi></span>
      <h2 id="maintenance-request-title"><bdi>{request.title}</bdi></h2></div>
      <div className="button-row"><span className={`status-pill ${tone}`}>{label}</span>{back}</div></div>
    <div className="request-detail">
      <p className="announcement-body"><bdi>{request.description}</bdi></p>
      <span className="field-help">{categoryLabels[request.category]} · aberto em {formatWhen(request.createdAt, scope.timeZone)}
        {request.dueOn ? ` · prazo comunicado à unidade: ${civilDate(request.dueOn)}` : ''}</span>
      {request.resolution ? <Notice tone="success">Resolução vigente: <bdi>{request.resolution}</bdi></Notice> : null}
      {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
      <RequestConversation messages={request.messages} truncated={request.messagesTruncated}
        load={(before) => requests.maintenanceOlderMessages(scope.tenantId, request.requestId, before)}
        render={(message) =>
          <li key={message.messageId} className={message.visibility === 'INTERNAL' ? 'request-internal' : undefined}>
            <strong>{message.mine ? 'Você' : <bdi>{message.authorName}</bdi>}{message.authorRole === 'STAFF' ? ' (equipe)' : ' (unidade)'}</strong>
            <span>{formatWhen(message.createdAt, scope.timeZone)}{message.visibility === 'INTERNAL' ? ' · comentário interno, não aparece para a unidade' : ''}</span>
            <p className="announcement-body"><bdi>{message.body}</bdi></p></li>} />
      {stateHelp[request.state] ? <Notice tone="info">{stateHelp[request.state]}</Notice> : null}
      {request.state === 'CLOSED' ? null : <><MaintenanceMessageForm request={request} />
        {maintenanceTransitions(request.state).length > 0 ? <MaintenanceStateForm key={request.version} request={request} done={setOutcome} /> : null}</>}
    </div>
  </section>;
}

function MaintenanceMessageForm({ request }: { request: MaintenanceRequest }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const intent = usePendingOperation(`${scope.tenantId}:maintenance-message:${request.requestId}`);
  const pending = intent.lockedBody as { body: string; visibility: 'RESIDENT' | 'INTERNAL' } | null;
  const [body, setBody] = useState(pending?.body ?? '');
  const [internal, setInternal] = useState(pending?.visibility === 'INTERNAL');
  const action = useAction();
  const normalized = multilineText(body, 2000);
  const id = `maintenance-message-${request.requestId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    const visibility = internal ? 'INTERNAL' as const : 'RESIDENT' as const;
    const operationKey = intent.keyFor(JSON.stringify({ body: normalized, visibility }), { body: normalized, visibility });
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await requests.maintenanceMessage(scope.tenantId, request.requestId, operationKey, normalized, visibility); }
      catch (error) { failure = error; throw repeatFailure(error); }
      finally { intent.settle(); }
    }, (done) => ({ tone: done.replayed ? 'info' : 'success', text: done.replayed ? 'Esta mensagem já estava registrada.'
      : visibility === 'INTERNAL' ? 'Comentário interno registrado.' : 'Mensagem enviada para a unidade.' }),
    async () => { await queries.invalidateQueries({ queryKey: maintenanceKey(scope, 'request', request.requestId) }); });
    if (ok) { intent.finish(); setBody(''); return; }
    if (accessCodes.includes(codeOf(failure))) return;
    settleFailure(failure, intent.locked, intent, intent.setLocked);
    void queries.invalidateQueries({ queryKey: maintenanceKey(scope, 'request', request.requestId) });
  }
  const busy = action.state.pending || intent.locked || intent.inFlight;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Mensagem no pedido ${request.title}`}>
    <label htmlFor={id}>Mensagem</label>
    <textarea id={id} maxLength={2000} rows={3} value={body} onChange={(event) => setBody(event.target.value)} disabled={busy} />
    <label className="weekday-option"><input type="checkbox" checked={internal} disabled={busy} onChange={(event) => setInternal(event.target.checked)} />
      Comentário interno (não aparece para a unidade)</label>
    <ActionFeedback state={action.state} />
    {intent.completedElsewhere ? <Notice>Este envio já foi concluído pela conversa aberta antes. Enviar de novo só confirma o mesmo envio, sem gravar outra vez; confira a conversa.</Notice> : null}
    {intent.locked ? <Notice tone="warning">O envio não foi confirmado. Use “Tentar confirmar a mesma mensagem”: se ela já foi gravada, nada novo é enviado.</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={normalized === null || action.state.pending}>
      {action.state.pending ? 'Enviando…' : intent.locked ? 'Tentar confirmar a mesma mensagem' : internal ? 'Registrar comentário interno' : 'Enviar para a unidade'}</button></div>
  </form>;
}

function MaintenanceStateForm({ request, done }: { request: MaintenanceRequest; done(result: ActionResult): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const targets = maintenanceTransitions(request.state);
  const [state, setState] = useState<MaintenanceState>(targets[0]!);
  const [resolution, setResolution] = useState('');
  const resolving = state === 'RESOLVED';
  const normalizedResolution = resolving ? multilineText(resolution, 1000) : null;
  const valid = targets.includes(state) && (!resolving || normalizedResolution !== null);
  const id = `maintenance-state-${request.requestId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const ok = await action.run(() => requests.maintenanceUpdate(scope.tenantId, request.requestId,
      { state, resolution: resolving ? normalizedResolution : null, expectedVersion: request.version }),
    (updated) => ({ tone: 'success', text: `Andamento salvo: ${stateLabels[updated.state][0].toLowerCase()}.` }),
    async () => {
      await queries.invalidateQueries({ queryKey: maintenanceKey(scope, 'request', request.requestId) });
      await queries.invalidateQueries({ queryKey: maintenanceKey(scope, 'requests') });
    });
    if (ok) done({ tone: 'success', text: resolving ? 'Pedido concluído; a resolução também foi enviada na conversa.' : `Andamento salvo: ${stateLabels[state][0].toLowerCase()}.` });
    else void queries.invalidateQueries({ queryKey: maintenanceKey(scope, 'request', request.requestId) });
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Andamento do pedido ${request.title}`}>
    <label htmlFor={`${id}-state`}>Novo andamento</label>
    <select id={`${id}-state`} value={state} disabled={action.state.pending} onChange={(event) => setState(event.target.value as MaintenanceState)}>
      {targets.map((value) => <option key={value} value={value}>{stateLabels[value][0]}</option>)}
    </select>
    {resolving ? <><label htmlFor={`${id}-resolution`}>Resolução (aparece para a unidade)</label>
      <textarea id={`${id}-resolution`} maxLength={1000} rows={3} value={resolution} disabled={action.state.pending} onChange={(event) => setResolution(event.target.value)} /></> : null}
    <ActionFeedback state={action.state} success={false} />
    <div className="button-row"><button className="button" type="submit" disabled={!valid || action.state.pending}>{action.state.pending ? 'Salvando…' : 'Salvar andamento'}</button></div>
  </form>;
}
