import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { notifications, type InboxNotification } from '../api/notifications';
import { scopeKey, type Scope } from './Operations';
import { Empty, ErrorState, Icon, Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { codeOf, uncertain } from './operationIntent';
import './management/Management.css';

const kindText: Record<InboxNotification['kind'], string> = {
  ARRIVAL_WAITING: 'Chegada aguardando resposta da unidade', PARCEL_RECEIVED: 'Encomenda recebida na portaria',
};
const resourceText: Record<string, string> = {
  WAITING: 'Situação atual: aguardando resposta.', APPROVED: 'Situação atual: entrada autorizada pela unidade.',
  DENIED: 'Situação atual: entrada recusada pela unidade.', EXPIRED: 'Situação atual: prazo de resposta encerrado; nada foi liberado.',
  CANCELLED: 'Situação atual: chegada encerrada pela portaria.', RECEIVED: 'Situação atual: aguardando retirada.',
  DELIVERED: 'Situação atual: retirada registrada.',
};
/** Recurso desligado no ambiente ou vínculo que mudou: a caixa some sem erro técnico (o resumo trata o vínculo). */
const hidden = (error: unknown) => ['API_NOT_AVAILABLE', 'UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(codeOf(error));

/**
 * Caixa da unidade (parte do CP-035): avisos gerados pelo worker para quem pode agir na unidade. Aparecer aqui não é
 * leitura; só "Abrir" registra a abertura, com horário do servidor. Sem push nem e-mail neste recorte.
 */
export function UnitInbox({ scope, timeZone }: { scope: Scope; timeZone: string }) {
  const queries = useQueryClient();
  const navigate = useNavigate();
  const key = [...scopeKey(scope), 'inbox'] as const;
  const inbox = useQuery({ queryKey: key, queryFn: ({ signal }) => notifications.unit(scope.tenantId, scope.unitId, signal),
    refetchInterval: (query) => hidden(query.state.error) ? false : 30_000 });
  const [opening, setOpening] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  if (hidden(inbox.error)) return null;
  async function open(item: InboxNotification) {
    if (opening) return;
    setOpening(item.notificationId); setFailure(null);
    try {
      await notifications.open(scope.tenantId, item.notificationId);
      await queries.invalidateQueries({ queryKey: key });
      navigate(`/morador/${scope.tenantId}/${scope.unitId}/${item.kind === 'ARRIVAL_WAITING' ? 'acessos' : 'encomendas'}`);
    } catch (error) {
      setFailure(error);
      void queries.invalidateQueries({ queryKey: key });
    } finally { setOpening(null); }
  }
  return <section className="dashboard-reservations" aria-labelledby="inbox-title">
    <div className="section-heading"><div><span className="section-index">AVISOS DA UNIDADE</span>
      <h2 id="inbox-title">Caixa da unidade{inbox.data && inbox.data.unread > 0 ? ` · ${inbox.data.unread} ${inbox.data.unread === 1 ? 'novo' : 'novos'}` : ''}</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={inbox.isFetching} onClick={() => void inbox.refetch()}>Atualizar</button></div>
    <p className="field-help">Avisos da portaria para quem pode agir nesta unidade. Aparecer aqui não quer dizer que alguém leu: só abrir o aviso registra a leitura. Ainda não há notificação no celular.</p>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}</Notice> : null}
    {inbox.isPending ? <div className="panel"><Loading label="Consultando os avisos da unidade…" /></div>
      : inbox.isError && !inbox.data ? <ErrorState error={inbox.error} retry={() => void inbox.refetch()} />
        : !inbox.data || inbox.data.items.length === 0 ? <div className="panel"><Empty title="Nenhum aviso">Chegadas que pedem a sua resposta e encomendas recebidas pela portaria aparecem aqui.</Empty></div>
          : <ol className="management-list" aria-label="Avisos da unidade">{inbox.data.items.map((item) => <li key={item.notificationId} className="management-row">
            <div><strong><Icon name={item.kind === 'ARRIVAL_WAITING' ? 'door' : 'box'} /> {kindText[item.kind]}</strong>{' '}
              {item.state === 'AVAILABLE' ? <span className="status-pill status-pending">Não aberto</span> : <span className="status-pill status-neutral">Aberto</span>}
              <span>Gerado em {formatWhen(item.createdAt, timeZone)}{item.readAt ? ` · aberto por você em ${formatWhen(item.readAt, timeZone)}` : ''}</span>
              <span className="field-help">{item.resourceState ? resourceText[item.resourceState] : 'Este registro não está mais disponível para o seu vínculo.'}</span></div>
            <div className="button-row"><button className="button button-compact" type="button" disabled={opening !== null}
              onClick={() => void open(item)}>{opening === item.notificationId ? 'Abrindo…' : item.kind === 'ARRIVAL_WAITING' ? 'Abrir e ver acessos' : 'Abrir e ver encomendas'}</button></div>
          </li>)}</ol>}
  </section>;
}
