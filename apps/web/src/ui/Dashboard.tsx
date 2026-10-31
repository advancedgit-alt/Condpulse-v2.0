import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { Membership, Reservation } from '../api/types';
import { useAuth } from './Auth';
import { scopeKey, type Scope } from './Operations';
import { Empty, ErrorState, Icon, Loading, Notice } from './primitives';
import { dateInZone, formatWhen, queryRange } from './dateTime';
import { UnitInbox } from './Inbox';
import { UnitAnnouncements } from './Announcements';
import { UnitRequests } from './Requests';
import { UnitMoves } from './Moves';
import { residentPath, residentRoot } from './shell/paths';
import './Dashboard.css';

const residentialRoles = new Set<Membership['role']>(['RESIDENT', 'RESPONSIBLE', 'DEPENDENT']);
const upcomingStates = new Set<Reservation['status']>(['HELD', 'PENDING_APPROVAL', 'CONFIRMED']);
const statusNames: Record<Reservation['status'], string> = {
  HELD: 'Em retenção', PENDING_APPROVAL: 'Aguardando aprovação', CONFIRMED: 'Confirmada',
  REJECTED: 'Não aprovada', CANCELLED: 'Cancelada', EXPIRED: 'Expirada',
};

/** Nunca apresentar dados de outra unidade, mesmo diante de resposta incompatível. */
export function upcomingReservations(reservations: readonly Reservation[], unitId: string, now: number): Reservation[] {
  if (reservations.some(reservation => reservation.unitId !== unitId)) {
    throw new ApiError(502, 'INVALID_API_RESPONSE', 'A agenda retornada não pôde ser validada. Atualize ou procure a gestão.');
  }
  return reservations.filter(reservation => upcomingStates.has(reservation.status) && Date.parse(reservation.endsAt) > now)
    .toSorted((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt) || a.reservationId.localeCompare(b.reservationId));
}

export function DashboardPage() {
  const { identity } = useAuth();
  const { tenantId = '', unitId = '' } = useParams();
  const membership = identity?.memberships.find(item => item.tenantId === tenantId && item.unitId === unitId && residentialRoles.has(item.role));
  if (!identity || !membership) return <div className="panel"><Empty title="Unidade indisponível">Selecione uma unidade com vínculo residencial para consultar seu resumo.</Empty><Link className="button button-secondary" to={residentRoot(tenantId)}>Selecionar unidade</Link></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido pela gestão antes de consultar o resumo.</Notice>; }
  return <DashboardContext key={`${identity.accountId}:${tenantId}:${unitId}:${membership.timeZone}`} accountId={identity.accountId} membership={membership} unitId={unitId} />;
}

function DashboardContext({ accountId, membership, unitId }: { accountId: string; membership: Membership; unitId: string }) {
  const tenantId = membership.tenantId;
  const route = residentPath(tenantId, unitId);
  const agenda = residentPath(tenantId, unitId, 'reservas');
  const queries = useQueryClient();
  const scope = useMemo<Scope>(() => ({ accountId, tenantId, unitId, route }), [accountId, tenantId, unitId, route]);
  const [now, setNow] = useState(Date.now);
  const [privateDenied, setPrivateDenied] = useState<unknown>(null);
  const range = useMemo(() => {
    try { return { value: queryRange(dateInZone(membership.timeZone, new Date(now)), membership.timeZone), error: '' }; }
    catch { return { value: null, error: 'O período no fuso do condomínio precisa ser conferido pela gestão.' }; }
  }, [membership.timeZone, now]);
  const units = useQuery({ queryKey: ['private', accountId, 'tenant', tenantId, 'units'],
    queryFn: ({ signal }) => api.units(tenantId, signal), enabled: !privateDenied });
  const unit = units.data?.find(item => item.id === unitId);
  const enabled = Boolean(unit) && units.isSuccess && !units.isFetching && !privateDenied;
  const areas = useQuery({ queryKey: [...scopeKey(scope), 'amenities'],
    queryFn: ({ signal }) => api.amenities(tenantId, signal), enabled });
  const reservations = useQuery({ queryKey: [...scopeKey(scope), 'dashboard', range.value?.from],
    queryFn: ({ signal }) => api.reservations(tenantId, unitId, range.value!, signal), enabled: enabled && range.value !== null });
  const denial = [units.error, areas.error, reservations.error].find(error => error instanceof ApiError && [403, 404].includes(error.status));
  useEffect(() => {
    function clear(error: unknown) {
      setPrivateDenied(error);
      const prefix = ['private', accountId, 'tenant', tenantId];
      void queries.cancelQueries({ queryKey: prefix }); queries.removeQueries({ queryKey: prefix });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }
    if (denial) clear(denial);
    const handle = (event: Event) => {
      const detail = (event as CustomEvent<{ scope: Scope; error: unknown }>).detail;
      if (detail?.scope.accountId === accountId && detail.scope.tenantId === tenantId && detail.scope.unitId === unitId) clear(detail.error);
    };
    window.addEventListener('condpulse:context-denied', handle);
    return () => window.removeEventListener('condpulse:context-denied', handle);
  }, [denial, queries, accountId, tenantId, unitId]);
  useEffect(() => {
    const refreshClock = () => setNow(Date.now());
    window.addEventListener('focus', refreshClock);
    const timer = window.setInterval(refreshClock, 60_000);
    return () => { window.removeEventListener('focus', refreshClock); window.clearInterval(timer); };
  }, []);
  useEffect(() => () => {
    void queries.cancelQueries({ queryKey: scopeKey({ accountId, tenantId, unitId }) });
    queries.removeQueries({ queryKey: scopeKey({ accountId, tenantId, unitId }) });
  }, [queries, accountId, tenantId, unitId]);
  const validating = units.isFetching || areas.isFetching || reservations.isFetching;
  async function refresh() {
    setNow(Date.now());
    await queries.invalidateQueries({ queryKey: ['private', accountId, 'tenant', tenantId] });
  }
  let upcoming: Reservation[] = [];
  let incompatible: unknown;
  if (reservations.data) {
    try { upcoming = upcomingReservations(reservations.data.reservations, unitId, now); }
    catch (error) { incompatible = error; }
  }
  if (privateDenied || denial) return <div className="panel"><Notice tone="error">Este contexto deixou de estar disponível. Os dados anteriores foram removidos. Selecione uma unidade disponível para continuar.</Notice><Link className="button button-secondary dashboard-context-link" to={residentRoot(tenantId)}>Selecionar unidade</Link></div>;
  if (units.isPending) return <Loading label="Conferindo sua unidade." />;
  if (units.isError) return <ErrorState error={units.error} retry={() => void units.refetch()} />;
  if (!unit) return <div className="panel"><Empty title="Unidade indisponível">Seu vínculo ou a situação da unidade mudou. Selecione outro contexto para continuar.</Empty><Link className="button button-secondary" to={residentRoot(tenantId)}>Selecionar unidade</Link></div>;
  const unitLabel = unit.blockName ? `${unit.blockName} · ${unit.label}` : unit.label;
  const areaNames = new Map(areas.data?.map(area => [area.amenityId, area.name]));
  const displayed = upcoming.slice(0, 3);
  const stale = reservations.dataUpdatedAt > 0 && now - reservations.dataUpdatedAt >= 60_000;
  return <div className="dashboard-page">
    <div className="residence-heading"><div><span className="eyebrow">SEU DIA A DIA</span><h1>Sua unidade, em dia.</h1><p>Confira suas próximas reservas e encontre o caminho para organizar sua rotina.</p></div><div className="context-badge"><Icon name="home" /><div><strong>{unitLabel}</strong><span>{membership.tenantName}</span></div></div></div>
    <div className="dashboard-grid">
      <section className="panel dashboard-welcome" aria-labelledby="dashboard-shortcuts"><span className="section-index">SEU CONTEXTO</span><h2 id="dashboard-shortcuts">Um espaço para a sua rotina.</h2><p>Você está consultando a unidade <strong>{unitLabel}</strong>. O acesso é conferido pelo servidor em cada consulta.</p><Link className="button" to={agenda}><Icon name="calendar" />Abrir reservas<Icon name="arrow" /></Link>{membership.role !== 'DEPENDENT' ? <Link className="text-button" to={residentPath(tenantId, unitId, 'financeiro')}>Ver cobranças da unidade<Icon name="arrow" /></Link> : null}<p className="dashboard-context-note">Horários do condomínio: <strong>{membership.timeZone}</strong></p></section>
      <aside className="panel dashboard-guide" aria-label="Sobre este resumo"><span className="round-icon"><Icon name="shield" /></span><h2>Apenas o seu contexto.</h2><p>Este resumo reúne solicitações da unidade selecionada. A agenda de outras unidades não aparece aqui.</p><p>A confirmação de uma nova reserva depende das regras e da disponibilidade conferidas no envio.</p></aside>
    </div>
    <UnitInbox scope={scope} timeZone={membership.timeZone} />
    <UnitAnnouncements scope={scope} timeZone={membership.timeZone} />
    <UnitRequests scope={scope} timeZone={membership.timeZone} />
    <UnitMoves scope={scope} timeZone={membership.timeZone} />
    <section className="dashboard-reservations" aria-labelledby="dashboard-reservations-title"><div className="section-heading"><div><span className="section-index">PRÓXIMOS COMPROMISSOS</span><h2 id="dashboard-reservations-title">Próximas reservas da unidade</h2></div><button className="button button-secondary button-compact" disabled={validating} onClick={() => void refresh()}>{validating ? 'Atualizando.' : 'Atualizar resumo'}</button></div>
      <p className="dashboard-window">Consulta de 30 dias locais, iniciada em {new Intl.DateTimeFormat('pt-BR', { timeZone: membership.timeZone, day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(range.value?.from ?? now))}. Horários em {membership.timeZone}.</p>
      {stale && !validating && !denial ? <Notice tone="warning">Este resumo usa a última consulta, feita há mais de um minuto. Atualize antes de tomar uma decisão.</Notice> : null}
      {range.error ? <Notice tone="error">{range.error}</Notice>
        : units.isFetching || reservations.isPending || reservations.isFetching ? <div className="panel"><Loading label="Conferindo suas próximas reservas." /></div>
          : incompatible ? <ErrorState error={incompatible} retry={() => void reservations.refetch()} />
            : reservations.isError ? <ErrorState error={reservations.error} retry={() => void reservations.refetch()} />
              : displayed.length === 0 ? <div className="panel"><Empty title={reservations.data?.truncated ? 'Nenhuma reserva ativa no recorte retornado' : 'Nenhuma reserva futura neste período'}>{reservations.data?.truncated ? 'A consulta atingiu o limite de resultados. Consulte datas mais próximas na agenda para conferir outras solicitações.' : 'Suas solicitações ativas aparecerão aqui após a confirmação do servidor. Consulte a agenda para escolher outro período ou fazer uma reserva.'}</Empty><Link className="button button-secondary" to={agenda}>Consultar agenda</Link></div>
                : <div className="reservation-list">{displayed.map(reservation => <article className="reservation-card" key={reservation.reservationId}><div className="reservation-row"><div className="reservation-date-icon"><Icon name="calendar" /></div><div className="reservation-info"><h3>{areaNames.get(reservation.amenityId) ?? 'Área da reserva'}</h3><p>{formatWhen(reservation.startsAt, membership.timeZone)} até {formatWhen(reservation.endsAt, membership.timeZone)}</p><small>Regulamento aceito: versão {reservation.acceptedPolicyVersion}</small></div><span className={`status-pill ${reservation.status === 'CONFIRMED' ? 'status-ready' : 'status-neutral'}`}><Icon name={reservation.status === 'CONFIRMED' ? 'check' : 'clock'} />{statusNames[reservation.status]}</span></div></article>)}</div>}
      {areas.isError && !denial ? <div className="dashboard-catalog-warning"><Notice tone="warning">Os nomes das áreas não puderam ser atualizados. Os períodos e estados exibidos vêm da consulta de reservas.</Notice></div> : null}
      {reservations.data && (upcoming.length > 3 || reservations.data.truncated) && !validating && !reservations.isError && !incompatible ? <div className="dashboard-more"><Notice>Este resumo mostra até três próximas reservas. Consulte a agenda para conferir todas as solicitações e seus estados no período.</Notice></div> : null}
      <div className="dashboard-footer-link"><Link className="text-button" to={agenda}>Ver agenda e regras das áreas<Icon name="arrow" /></Link></div>
    </section>
  </div>;
}
