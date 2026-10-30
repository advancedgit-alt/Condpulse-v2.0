import { useInfiniteQuery } from '@tanstack/react-query';
import { management } from '../../api/management';
import { Empty, ErrorState, Loading } from '../primitives';
import { formatWhen } from '../dateTime';
import { managementKey, useAccessGuard, useManagement } from './shared';

const actionLabels: Record<string, string> = {
  BLOCK_CREATED: 'Bloco criado', UNIT_CREATED: 'Unidade criada', UNIT_UPDATED: 'Unidade alterada',
  UNIT_DEACTIVATED: 'Unidade inativada', UNIT_REACTIVATED: 'Unidade reativada',
  AMENITY_CREATED: 'Área criada', AMENITY_UPDATED: 'Área alterada', AMENITY_POLICY_VERSIONED: 'Nova versão de regra',
  AMENITY_DEACTIVATED: 'Área inativada', AMENITY_REACTIVATED: 'Área reativada',
  INVITATION_CREATED: 'Convite emitido', INVITATION_REVOKED: 'Convite revogado', INVITATION_ACCEPTED: 'Convite aceito',
  MEMBERSHIP_APPROVED: 'Vínculo aprovado', MEMBERSHIP_REJECTED: 'Vínculo rejeitado', MEMBERSHIP_REVOKED: 'Vínculo revogado',
  RESERVATION_CREATED: 'Reserva criada', RESERVATION_REQUESTED: 'Pedido de reserva', RESERVATION_CANCELLED: 'Reserva cancelada pelo autor',
  RESERVATION_APPROVED: 'Pedido aprovado', RESERVATION_REJECTED: 'Pedido rejeitado',
  RESERVATION_CANCELLED_BY_MANAGEMENT: 'Reserva cancelada pela gestão', AMENITY_BLOCK_CREATED: 'Bloqueio de manutenção',
  AMENITY_BLOCK_CANCELLED: 'Bloqueio encerrado',
};
const short = (id: string) => id.slice(0, 8);

/** Trilha somente leitura do próprio condomínio, paginada pelo cursor opaco do servidor. */
export function AuditSection() {
  const scope = useManagement();
  const pages = useInfiniteQuery({
    queryKey: managementKey(scope, 'audit'),
    queryFn: ({ pageParam, signal }) => management.audit(scope.tenantId, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
  if (useAccessGuard(pages.error)) return null;
  const events = pages.data?.pages.flatMap((page) => page.events) ?? [];
  return <section className="management-section" aria-labelledby="audit-title">
    <div className="section-heading"><div><span className="section-index">AUDITORIA</span><h2 id="audit-title">Trilha de alterações</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={pages.isFetching} onClick={() => void pages.refetch()}>Atualizar</button></div>
    <p className="field-help">Registros somente leitura. Identificadores aparecem abreviados; nenhuma senha, token ou e-mail é gravado na trilha.</p>
    {pages.isPending ? <div className="panel"><Loading label="Consultando auditoria…" /></div>
      : pages.isError && events.length === 0 ? <ErrorState error={pages.error} retry={() => void pages.refetch()} />
        : events.length === 0 ? <div className="panel"><Empty icon="clock" title="Sem registros">As alterações da gestão aparecerão aqui.</Empty></div>
          : <ol className="management-list audit-list" aria-label="Eventos de auditoria">{events.map((event) => <li key={event.eventId} className="management-row">
            <div><strong>{actionLabels[event.action] ?? event.action}</strong>
              <span>{formatWhen(event.createdAt, scope.timeZone)} · {event.resourceType.toLowerCase()} {short(event.resourceId)} · ator {short(event.actorId)}</span>
              {event.reason ? <span>Motivo: {event.reason}</span> : null}</div>
          </li>)}</ol>}
    {pages.isError && events.length > 0 ? <ErrorState error={pages.error} retry={() => void pages.fetchNextPage()} /> : null}
    {pages.hasNextPage ? <button className="button button-secondary" type="button" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
      {pages.isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}</button> : null}
  </section>;
}
