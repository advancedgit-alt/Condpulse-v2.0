import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { requestCategories, requests, type OpenRequestBody, type RequestCategory, type RequestMessage, type UnitRequest } from '../api/requests';
import { displayText, multilineText } from '../api/text';
import { scopeKey, type Scope } from './Operations';
import { Empty, ErrorState, Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { codeOf, repeatFailure, settleFailure, uncertain, usePendingOperation } from './operationIntent';
import { categoryLabels, civilDate, stateLabels } from './requestLabels';
import { RequestAttachments } from './RequestAttachments';
import './management/Management.css';

/** Recurso desligado no ambiente ou vínculo que mudou: a seção some sem erro técnico (o resumo trata o vínculo). */
const hidden = (error: unknown) => ['API_NOT_AVAILABLE', 'UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(codeOf(error));

/**
 * Pedidos da unidade (CP-033): abrir pedido, acompanhar o andamento e conversar com a equipe no próprio pedido. A equipe
 * aparece como "Equipe do condomínio"; comentários internos da equipe nunca chegam aqui. Texto simples, exibido como texto.
 */
export function UnitRequests({ scope, timeZone }: { scope: Scope; timeZone: string }) {
  const key = [...scopeKey(scope), 'requests'] as const;
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => requests.unit(scope.tenantId, scope.unitId, signal),
    refetchInterval: (query) => hidden(query.state.error) ? false : 60_000 });
  const [opening, setOpening] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  if (hidden(list.error)) return null;
  const active = list.data?.requests.filter((item) => item.state !== 'CLOSED').length ?? 0;
  return <section className="dashboard-reservations" aria-labelledby="requests-title">
    <div className="section-heading"><div><span className="section-index">PEDIDOS DA UNIDADE</span>
      <h2 id="requests-title">Pedidos{active > 0 ? ` · ${active} em andamento` : ''}</h2></div>
      <div className="button-row">
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar pedidos</button>
        {!opening ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setOpening(true); }}>Abrir pedido</button> : null}
      </div></div>
    <p className="field-help">Use para manutenção, limpeza, segurança e outros assuntos com a gestão. A conversa fica no próprio pedido.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {opening ? <OpenForm scope={scope} close={(created) => {
      setOpening(false);
      if (created) { setOutcome(created.outcome); setSelected(created.requestId); }
    }} /> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando os pedidos…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.requests.length === 0 ? <div className="panel"><Empty title="Nenhum pedido">Os pedidos abertos pela unidade aparecem aqui, com o andamento informado pela gestão.</Empty></div>
          : <ol className="management-list" aria-label="Pedidos da unidade">{list.data.requests.map((item) => {
            const [label, tone] = stateLabels[item.state];
            return <li key={item.requestId} className="management-row">
              <div><strong><bdi>{item.title}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
                <span>{categoryLabels[item.category]} · aberto em {formatWhen(item.createdAt, timeZone)} · atualizado em {formatWhen(item.updatedAt, timeZone)}</span>
                {item.assigned || item.dueOn ? <span>{item.assigned ? 'Responsável da equipe definido' : 'Sem responsável definido'}{item.dueOn ? ` · prazo comunicado: ${civilDate(item.dueOn)}` : ''}</span> : null}</div>
              {selected === item.requestId ? <RequestDetail scope={scope} requestId={item.requestId} timeZone={timeZone} close={() => setSelected(null)} />
                : <div className="button-row"><button className="button button-secondary button-compact" type="button"
                  onClick={() => { setOutcome(null); setSelected(item.requestId); }}>Ver conversa</button></div>}
            </li>;
          })}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 pedidos mais recentes.</Notice> : null}
  </section>;
}

/** Abertura com chave de operação pendente por unidade: fechar e reabrir não cria um segundo pedido depois de resultado incerto. */
function OpenForm({ scope, close }: { scope: Scope; close(created: { requestId: string; outcome: { tone: 'success' | 'info'; text: string } } | null): void }) {
  const queries = useQueryClient();
  const intent = usePendingOperation(`${scope.tenantId}:${scope.unitId}:request-open`);
  const pending = intent.lockedBody as OpenRequestBody | null;
  const [category, setCategory] = useState<RequestCategory>(pending?.category ?? 'MAINTENANCE');
  const [title, setTitle] = useState(pending?.title ?? '');
  const [description, setDescription] = useState(pending?.description ?? '');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const normalizedTitle = displayText(title, 120);
  const normalizedDescription = multilineText(description, 2000);
  const valid = normalizedTitle !== null && [...normalizedTitle].length >= 3 && normalizedDescription !== null;
  const locked = intent.locked;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || busy || intent.inFlight) return;
    const body: OpenRequestBody = { category, title: normalizedTitle!, description: normalizedDescription! };
    const operationKey = intent.keyFor(JSON.stringify(body), body);
    setBusy(true); setFailure(null);
    try {
      const result = await requests.open(scope.tenantId, scope.unitId, operationKey, body);
      intent.finish();
      await queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'requests'] });
      close({ requestId: result.request.requestId, outcome: result.replayed
        ? { tone: 'info', text: `Este pedido já estava aberto: ${result.request.title} (${stateLabels[result.request.state][0].toLowerCase()}).` }
        : { tone: 'success', text: `Pedido enviado: ${result.request.title}. A gestão acompanha por aqui.` } });
    } catch (error) {
      setFailure(repeatFailure(error));
      settleFailure(error, intent.locked, intent, intent.setLocked);
      void queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'requests'] });
    } finally { intent.settle(); setBusy(false); }
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Abrir pedido">
    <label htmlFor="request-category">Categoria</label>
    <select id="request-category" value={category} disabled={busy || locked || intent.inFlight} onChange={(event) => setCategory(event.target.value as RequestCategory)}>
      {requestCategories.map((value) => <option key={value} value={value}>{categoryLabels[value]}</option>)}
    </select>
    <label htmlFor="request-title">Título</label>
    <input id="request-title" maxLength={120} value={title} onChange={(event) => setTitle(event.target.value)} disabled={busy || locked || intent.inFlight} autoComplete="off" />
    <label htmlFor="request-description">Descrição</label>
    <textarea id="request-description" maxLength={2000} rows={5} value={description} onChange={(event) => setDescription(event.target.value)}
      disabled={busy || locked || intent.inFlight} aria-describedby="request-description-help" />
    <span className="field-help" id="request-description-help">Texto simples. Não inclua senhas, documentos pessoais ou dados de outras pessoas.</span>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    {intent.completedElsewhere ? <Notice>Este pedido já foi enviado pelo formulário aberto antes. Enviar de novo só confirma o mesmo pedido, sem abrir outro; confira a lista.</Notice> : null}
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o pedido ficou travado, mesmo se você fechar o formulário. Use “Tentar confirmar o mesmo pedido”: se ele já foi enviado, nada novo é gravado.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || busy || intent.inFlight}>{busy || intent.inFlight ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo pedido' : 'Enviar pedido'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={busy}>Voltar</button>
    </div>
  </form>;
}

function author(message: RequestMessage): string {
  if (message.mine) return 'Você';
  return message.authorRole === 'STAFF' ? 'Equipe do condomínio' : message.authorName ?? 'Unidade';
}

function RequestDetail({ scope, requestId, timeZone, close }: { scope: Scope; requestId: string; timeZone: string; close(): void }) {
  const detail = useQuery({ queryKey: [...scopeKey(scope), 'request', requestId],
    queryFn: ({ signal }) => requests.request(scope.tenantId, requestId, signal) });
  if (detail.isPending) return <div className="panel"><Loading label="Abrindo o pedido…" /></div>;
  if (detail.isError && !detail.data) return <div><ErrorState error={detail.error} retry={() => void detail.refetch()} />
    <button className="button button-secondary button-compact" type="button" onClick={close}>Fechar</button></div>;
  const request = detail.data!;
  return <div className="request-detail">
    <p className="announcement-body"><bdi>{request.description}</bdi></p>
    <span>Aberto por {request.openedByMe ? 'você' : <bdi>{request.requesterName}</bdi>}</span>
    {request.resolution ? <Notice tone="success">Resolução: <bdi>{request.resolution}</bdi></Notice> : null}
    {request.messagesTruncated ? <Notice tone="warning">A conversa mostra as 200 mensagens mais recentes.</Notice> : null}
    {request.messages.length === 0 ? <p className="field-help">Nenhuma mensagem ainda.</p>
      : <ol className="request-messages" aria-label="Conversa do pedido">{request.messages.map((message) =>
        <li key={message.messageId}><strong>{author(message)}</strong> <span>{formatWhen(message.createdAt, timeZone)}</span>
          <p className="announcement-body"><bdi>{message.body}</bdi></p></li>)}</ol>}
    <RequestAttachments tenantId={scope.tenantId} requestId={request.requestId} queryKey={scopeKey(scope)} canUpload={request.state !== 'CLOSED'} timeZone={timeZone} />
    {request.state === 'CLOSED' ? <Notice tone="info">Pedido encerrado. A conversa fica guardada; se precisar, abra um novo pedido.</Notice>
      : <ReplyForm scope={scope} request={request} />}
    <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={close}>Fechar conversa</button></div>
  </div>;
}

function ReplyForm({ scope, request }: { scope: Scope; request: UnitRequest }) {
  const queries = useQueryClient();
  const intent = usePendingOperation(`${scope.tenantId}:request-message:${request.requestId}`);
  const pending = intent.lockedBody as { body: string } | null;
  const [body, setBody] = useState(pending?.body ?? '');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const normalized = multilineText(body, 2000);
  const id = `request-reply-${request.requestId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null || busy || intent.inFlight) return;
    const operationKey = intent.keyFor(normalized, { body: normalized });
    setBusy(true); setFailure(null);
    try {
      await requests.unitMessage(scope.tenantId, request.requestId, operationKey, normalized);
      intent.finish(); setBody('');
    } catch (error) {
      setFailure(repeatFailure(error));
      settleFailure(error, intent.locked, intent, intent.setLocked);
    } finally {
      intent.settle(); setBusy(false);
      void queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'request', request.requestId] });
      void queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'requests'] });
    }
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Responder no pedido ${request.title}`}>
    <label htmlFor={id}>Mensagem para a gestão</label>
    <textarea id={id} maxLength={2000} rows={3} value={body} onChange={(event) => setBody(event.target.value)} disabled={busy || intent.locked || intent.inFlight} />
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    {intent.completedElsewhere ? <Notice>Este envio já foi concluído pela conversa aberta antes. Enviar de novo só confirma o mesmo envio, sem gravar outra vez; confira a conversa.</Notice> : null}
    {intent.locked ? <Notice tone="warning">O envio não foi confirmado. Use “Tentar confirmar a mesma mensagem”: se ela já foi gravada, nada novo é enviado.</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={normalized === null || busy || intent.inFlight}>
      {busy || intent.inFlight ? 'Enviando…' : intent.locked ? 'Tentar confirmar a mesma mensagem' : 'Enviar mensagem'}</button></div>
  </form>;
}
