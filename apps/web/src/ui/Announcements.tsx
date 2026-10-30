import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { announcements, type OpenedAnnouncement } from '../api/announcements';
import { scopeKey, type Scope } from './Operations';
import { Empty, ErrorState, Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { codeOf, uncertain } from './operationIntent';
import './management/Management.css';

/** Recurso desligado no ambiente ou vínculo que mudou: a seção some sem erro técnico (o resumo trata o vínculo). */
const hidden = (error: unknown) => ['API_NOT_AVAILABLE', 'UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(codeOf(error));

/**
 * Avisos do condomínio para a unidade (CP-032). A lista não traz o texto; "Abrir" busca o texto e registra a abertura
 * com a conta de quem abriu (uma vez). Abertura não é ciência jurídica nem confirmação de recebimento. Texto simples,
 * exibido como texto.
 */
export function UnitAnnouncements({ scope, timeZone }: { scope: Scope; timeZone: string }) {
  const queries = useQueryClient();
  const key = [...scopeKey(scope), 'announcements'] as const;
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => announcements.unit(scope.tenantId, scope.unitId, signal),
    refetchInterval: (query) => hidden(query.state.error) ? false : 60_000 });
  const [opened, setOpened] = useState<Record<string, OpenedAnnouncement>>({});
  const [opening, setOpening] = useState<string | null>(null);
  const [failure, setFailure] = useState<{ id: string; error: unknown } | null>(null);
  if (hidden(list.error)) return null;
  async function open(id: string) {
    if (opening) return;
    setOpening(id); setFailure(null);
    try {
      const result = await announcements.open(scope.tenantId, id);
      setOpened((previous) => ({ ...previous, [id]: result }));
      void queries.invalidateQueries({ queryKey: key });
    } catch (error) {
      setFailure({ id, error });
      if (codeOf(error) === 'ANNOUNCEMENT_NOT_FOUND') void queries.invalidateQueries({ queryKey: key });
    } finally { setOpening(null); }
  }
  const unread = list.data?.announcements.filter((item) => item.openedAt === null && !opened[item.announcementId]).length ?? 0;
  return <section className="dashboard-reservations" aria-labelledby="announcements-title">
    <div className="section-heading"><div><span className="section-index">AVISOS DO CONDOMÍNIO</span>
      <h2 id="announcements-title">Avisos{unread > 0 ? ` · ${unread} não ${unread === 1 ? 'aberto' : 'abertos'}` : ''}</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar avisos</button></div>
    <p className="field-help">Abrir mostra o texto e registra a abertura com a sua conta. Abertura não é ciência jurídica nem confirmação de recebimento.</p>
    {list.isPending ? <div className="panel"><Loading label="Consultando os avisos…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.announcements.length === 0 ? <div className="panel"><Empty icon="bell" title="Nenhum aviso">Os avisos publicados pela gestão para esta unidade aparecem aqui.</Empty></div>
          : <ol className="management-list" aria-label="Avisos do condomínio">{list.data.announcements.map((item) => {
            const content = opened[item.announcementId];
            const openedAt = content?.openedAt ?? item.openedAt;
            return <li key={item.announcementId} className="management-row">
              <div><strong><bdi>{item.title}</bdi></strong>{' '}
                {openedAt ? <span className="status-pill status-neutral">Aberto</span> : <span className="status-pill status-pending">Não aberto</span>}
                <span>Publicado em {formatWhen(item.publishedAt, timeZone)}{openedAt ? ` · aberto por você em ${formatWhen(openedAt, timeZone)}` : ''}</span>
                {content ? <p className="announcement-body"><bdi>{content.body}</bdi></p> : null}</div>
              {failure?.id === item.announcementId ? <Notice tone={uncertain(failure.error) ? 'warning' : 'error'}>{errorMessage(failure.error)}</Notice> : null}
              {content ? null : <div className="button-row"><button className="button button-compact" type="button" disabled={opening !== null}
                onClick={() => void open(item.announcementId)}>{opening === item.announcementId ? 'Abrindo…' : 'Abrir aviso'}</button></div>}
            </li>;
          })}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 avisos mais recentes.</Notice> : null}
  </section>;
}
