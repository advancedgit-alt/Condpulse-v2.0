import { useEffect, useRef, useState } from 'react';
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
  WAITLIST_OFFERED: 'Vaga da fila de espera',
};
/** Página do detalhe e rótulo do botão de cada tipo. */
const destination: Record<InboxNotification['kind'], [string, string]> = {
  ARRIVAL_WAITING: ['acessos', 'Abrir e ver acessos'], PARCEL_RECEIVED: ['encomendas', 'Abrir e ver encomendas'],
  WAITLIST_OFFERED: ['reservas', 'Abrir e ver reservas'],
};
const resourceTexts: Record<InboxNotification['kind'], Record<string, string>> = {
  ARRIVAL_WAITING: {
    WAITING: 'Situação atual: aguardando resposta.', APPROVED: 'Situação atual: entrada autorizada pela unidade.',
    DENIED: 'Situação atual: entrada recusada pela unidade.', EXPIRED: 'Situação atual: prazo de resposta encerrado; nada foi liberado.',
    CANCELLED: 'Situação atual: chegada encerrada pela portaria.',
  },
  PARCEL_RECEIVED: { RECEIVED: 'Situação atual: aguardando retirada.', DELIVERED: 'Situação atual: retirada registrada.' },
  WAITLIST_OFFERED: {
    OFFERED: 'Situação atual: oferta aguardando o seu aceite, com prazo.', ACCEPTED: 'Situação atual: oferta aceita.',
    LEFT: 'Situação atual: você saiu da fila ou recusou a oferta.', EXPIRED: 'Situação atual: prazo da oferta encerrado; a vaga seguiu na fila.',
    CLOSED: 'Situação atual: o período começou e a entrada foi encerrada.',
  },
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
  // Abertura pendente não leva a pessoa de volta a uma unidade que ela já deixou: a navegação só acontece se esta caixa
  // continua na tela e no mesmo condomínio e unidade de quando o botão foi clicado.
  const active = useRef({ mounted: true, scope: `${scope.tenantId}:${scope.unitId}` });
  active.current.scope = `${scope.tenantId}:${scope.unitId}`;
  useEffect(() => { const state = active.current; state.mounted = true; return () => { state.mounted = false; }; }, []);
  if (hidden(inbox.error)) return null;
  async function open(item: InboxNotification) {
    if (opening) return;
    setOpening(item.notificationId); setFailure(null);
    const origin = `${scope.tenantId}:${scope.unitId}`;
    try {
      await notifications.open(scope.tenantId, item.notificationId);
      await queries.invalidateQueries({ queryKey: key });
      if (!active.current.mounted || active.current.scope !== origin) return;
      navigate(`/morador/${scope.tenantId}/${scope.unitId}/${destination[item.kind][0]}`);
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
        : !inbox.data || inbox.data.items.length === 0 ? <div className="panel"><Empty title="Nenhum aviso">Chegadas que pedem a sua resposta, encomendas recebidas pela portaria e vagas da fila de espera aparecem aqui.</Empty></div>
          : <ol className="management-list" aria-label="Avisos da unidade">{inbox.data.items.map((item) => <li key={item.notificationId} className="management-row">
            <div><strong><Icon name={item.kind === 'ARRIVAL_WAITING' ? 'door' : item.kind === 'WAITLIST_OFFERED' ? 'calendar' : 'box'} /> {kindText[item.kind]}</strong>{' '}
              {item.state === 'AVAILABLE' ? <span className="status-pill status-pending">Não aberto</span> : <span className="status-pill status-neutral">Aberto</span>}
              <span>Gerado em {formatWhen(item.createdAt, timeZone)}{item.readAt ? ` · aberto por você em ${formatWhen(item.readAt, timeZone)}` : ''}</span>
              <span className="field-help">{item.resourceState ? resourceTexts[item.kind][item.resourceState] : 'Este registro não está mais disponível para o seu vínculo.'}</span></div>
            <div className="button-row"><button className="button button-compact" type="button" disabled={opening !== null}
              onClick={() => void open(item)}>{opening === item.notificationId ? 'Abrindo…' : destination[item.kind][1]}</button></div>
          </li>)}</ol>}
  </section>;
}
