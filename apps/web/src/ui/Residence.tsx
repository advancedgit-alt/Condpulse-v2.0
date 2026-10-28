import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { Amenity, Membership, Reservation } from '../api/types';
import { useAuth } from './Auth';
import { scopeKey, useOperations, type Scope } from './Operations';
import { Empty, ErrorState, Icon, Loading, Notice } from './primitives';
import { addDays, dateInZone, formatWhen, money, queryRange, toInstant } from './dateTime';

const roleNames: Record<string, string> = { RESIDENT: 'Morador', RESPONSIBLE: 'Responsável', DEPENDENT: 'Dependente', OWNER: 'Proprietário', CONCIERGE: 'Portaria', MANAGER: 'Gestão', SUPPORT: 'Suporte' };

function uniqueTenants(memberships: Membership[]): Membership[] {
  return [...new Map(memberships.map(membership => [membership.tenantId, membership])).values()];
}

export function ContextPage() {
  const { identity } = useAuth();
  const queries = useQueryClient();
  const operations = useOperations();
  const navigate = useNavigate();
  const tenants = uniqueTenants(identity?.memberships ?? []);
  const [tenantId, setTenantId] = useState(() => tenants[0]?.tenantId ?? '');
  const [unitId, setUnitId] = useState('');
  const [switching, setSwitching] = useState(false);
  const [unitDenied, setUnitDenied] = useState<unknown>(null);
  const selected = tenants.find(tenant => tenant.tenantId === tenantId);
  const units = useQuery({ queryKey: ['private', identity?.accountId, 'tenant', tenantId, 'units'],
    queryFn: ({ signal }) => api.units(tenantId, signal), enabled: Boolean(identity && selected) && !unitDenied });
  useEffect(() => {
    if (units.error instanceof ApiError && [403, 404].includes(units.error.status)) {
      setUnitDenied(units.error);
      void queries.cancelQueries({ queryKey: ['private', identity?.accountId, 'tenant', tenantId] });
      queries.removeQueries({ queryKey: ['private', identity?.accountId, 'tenant', tenantId] });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }
  }, [units.error, queries, identity?.accountId, tenantId]);
  async function changeTenant(next: string) {
    setUnitId(''); setUnitDenied(null);
    await queries.cancelQueries({ queryKey: ['private', identity?.accountId] });
    queries.removeQueries({ queryKey: ['private', identity?.accountId] });
    setTenantId(next);
  }
  async function enter(event: FormEvent) {
    event.preventDefault();
    if (!identity || !selected || !units.data?.some(unit => unit.id === unitId) || switching || operations.locked) return;
    setSwitching(true);
    await queries.cancelQueries({ queryKey: ['private', identity.accountId] });
    queries.removeQueries({ queryKey: ['private', identity.accountId] });
    navigate(`/morador/${tenantId}/${unitId}/reservas`);
  }
  return <div className="context-page"><div className="page-intro"><span className="eyebrow">SEU ACESSO</span><h1>Escolha seu espaço.</h1><p>Você verá apenas os condomínios e as unidades vinculados ao seu cadastro.</p></div>
    {tenants.length === 0 ? <div className="panel"><Empty title="Nenhum vínculo disponível">A gestão precisa validar seu vínculo antes de você acessar a unidade. Se seu cadastro acabou de ser aprovado, atualize o acesso.</Empty></div>
      : <div className="context-grid"><section className="panel context-form"><span className="section-index">01 / CONTEXTO</span><h2>Onde você quer acessar?</h2><form className="stack-form" onSubmit={event => void enter(event)}>
        <label htmlFor="tenant">Condomínio</label><select id="tenant" value={selected ? tenantId : ''} onChange={event => void changeTenant(event.target.value)} disabled={switching || operations.locked}><option value="">Selecione um condomínio</option>{tenants.map(tenant => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.tenantName}</option>)}</select>
        <label htmlFor="unit">Unidade</label><select id="unit" value={!unitDenied && units.isSuccess && !units.isFetching && units.data.some(unit => unit.id === unitId) ? unitId : ''} onChange={event => setUnitId(event.target.value)} disabled={!selected || units.isFetching || units.isError || Boolean(unitDenied) || switching || operations.locked}><option value="">Selecione sua unidade</option>{!unitDenied && units.isSuccess && !units.isFetching ? units.data.map(unit => <option key={unit.id} value={unit.id}>{unit.blockName ? `${unit.blockName} · ` : ''}{unit.label}</option>) : null}</select>
        {selected && units.isFetching ? <Loading label="Consultando suas unidades…" /> : null}
        {unitDenied || units.isError ? <ErrorState error={unitDenied ?? units.error} retry={() => { setUnitDenied(null); void units.refetch(); }} /> : null}
        {selected && units.isSuccess && units.data.length === 0 ? <Notice>Não há unidade ativa disponível neste vínculo. Procure a gestão do condomínio.</Notice> : null}
        <button type="submit" className="button button-wide" disabled={!unitId || !selected || units.isFetching || units.isError || Boolean(unitDenied) || switching || operations.locked}>{switching ? 'Abrindo unidade…' : 'Continuar'}<Icon name="arrow" /></button>
      </form></section><aside className="context-aside"><span className="round-icon"><Icon name="home" /></span><h2>O contexto certo.<br />A rotina mais simples.</h2><p>Reservas e regras aparecem de acordo com a unidade escolhida. Você pode trocar de unidade pelo cabeçalho.</p><div className="aside-note"><Icon name="shield" /><span>O vínculo é conferido novamente em cada operação.</span></div></aside></div>}
  </div>;
}

export function ReservationsPage() {
  const { identity } = useAuth();
  const { tenantId = '', unitId = '' } = useParams();
  const membership = identity?.memberships.find(item => item.tenantId === tenantId && item.unitId === unitId);
  if (!identity || !membership) return <div className="panel"><Empty title="Unidade indisponível">Selecione uma unidade vinculada ao seu cadastro para continuar.</Empty></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido pela gestão antes de acessar a agenda.</Notice>; }
  return <ReservationContext key={`${identity.accountId}:${tenantId}:${unitId}:${membership.timeZone}`} accountId={identity.accountId} membership={membership} unitId={unitId} />;
}

function ReservationContext({ accountId, membership, unitId }: { accountId: string; membership: Membership; unitId: string }) {
  const tenantId = membership.tenantId;
  const route = `/morador/${tenantId}/${unitId}/reservas`;
  const scope = useMemo<Scope>(() => ({ accountId, tenantId, unitId, route }), [accountId, tenantId, unitId, route]);
  const queries = useQueryClient();
  const operations = useOperations();
  const [date, setDate] = useState(() => dateInZone(membership.timeZone));
  const [selectedArea, setSelectedArea] = useState<string | null>(null);
  const [cancelId, setCancelId] = useState<string | null>(null);
  const [privateDenied, setPrivateDenied] = useState<unknown>(null);
  const units = useQuery({ queryKey: ['private', accountId, 'tenant', tenantId, 'units'], queryFn: ({ signal }) => api.units(tenantId, signal), enabled: !privateDenied });
  const unit = units.data?.find(item => item.id === unitId);
  const enabled = Boolean(unit) && units.isSuccess && !privateDenied;
  const areas = useQuery({ queryKey: [...scopeKey(scope), 'amenities'], queryFn: ({ signal }) => api.amenities(tenantId, signal), enabled });
  const range = useMemo(() => {
    try { return { value: queryRange(date, membership.timeZone), error: '' }; }
    catch (error) { return { value: null, error: error instanceof Error ? error.message : 'Período inválido.' }; }
  }, [date, membership.timeZone]);
  const reservations = useQuery({ queryKey: [...scopeKey(scope), 'reservations', date],
    queryFn: ({ signal }) => api.reservations(tenantId, unitId, range.value!, signal), enabled: enabled && range.value !== null });
  const denial = [units.error, areas.error, reservations.error].find(error => error instanceof ApiError && [403, 404].includes(error.status));
  useEffect(() => {
    function clear(error: unknown) {
      setPrivateDenied(error); setSelectedArea(null); setCancelId(null);
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
  useEffect(() => () => {
    void queries.cancelQueries({ queryKey: scopeKey({ accountId, tenantId, unitId }) });
    queries.removeQueries({ queryKey: scopeKey({ accountId, tenantId, unitId }) });
  }, [queries, accountId, tenantId, unitId]);
  useEffect(() => { if (operations.completed > 0) { setSelectedArea(null); setCancelId(null); } }, [operations.completed]);
  const selected = areas.isError ? undefined : areas.data?.find(area => area.amenityId === selectedArea);
  const validating = units.isFetching || areas.isFetching;
  if (privateDenied || denial) return <div className="panel"><Notice tone="error">Este contexto não está disponível para a operação. Os dados anteriores foram removidos. Selecione novamente uma unidade disponível.</Notice></div>;
  if (units.isPending) return <Loading label="Conferindo a unidade selecionada…" />;
  if (units.isError) return <ErrorState error={units.error} retry={() => void units.refetch()} />;
  if (!unit) return <div className="panel"><Empty title="Unidade indisponível">Seu vínculo ou a situação da unidade mudou. Volte para selecionar um contexto disponível.</Empty></div>;
  return <>
    <div className="residence-heading"><div><span className="eyebrow">SUA ROTINA, SEU ESPAÇO</span><h1>Reservas da unidade</h1><p>Escolha uma área, confira as regras e acompanhe a confirmação.</p></div><div className="context-badge"><Icon name="home" /><div><strong>{unit.blockName ? `${unit.blockName} · ` : ''}{unit.label}</strong><span>{roleNames[membership.role] ?? 'Vínculo ativo'}</span></div></div></div>
    <div className="reservation-layout"><div className="reservation-main"><section aria-labelledby="areas-title"><div className="section-heading"><div><span className="section-index">01 / ESCOLHA O ESPAÇO</span><h2 id="areas-title">Áreas do condomínio</h2></div><button className="button button-secondary button-compact" disabled={validating || operations.locked} onClick={() => void areas.refetch()}>{areas.isFetching ? 'Atualizando…' : 'Atualizar áreas'}</button></div>
      {areas.isPending ? <div className="panel"><Loading label="Consultando áreas e regras…" /></div> : areas.isError ? <ErrorState error={areas.error} retry={() => void areas.refetch()} /> : areas.data.length === 0 ? <div className="panel"><Empty title="Nenhuma área disponível">As áreas aparecerão aqui quando a gestão configurar o catálogo.</Empty></div> : <div className="amenity-grid">{areas.data.map(area => <AreaCard key={area.amenityId} area={area} disabled={operations.locked || validating} selected={selectedArea === area.amenityId} onChoose={() => setSelectedArea(area.amenityId)} />)}</div>}
      {selected && selected.policy && selected.bookingState === 'AVAILABLE_FOR_REQUEST' && !selected.approvalRequired ? <BookingForm key={`${selected.amenityId}:${selected.policyVersion}:${JSON.stringify([selected.policy, selected.capacity, selected.bufferMinutes, selected.feeCents])}`} area={selected} scope={scope} timeZone={membership.timeZone} validating={validating} onClose={() => setSelectedArea(null)} /> : null}
    </section>
    <section className="reservations-section" aria-labelledby="reservations-title"><div className="section-heading"><div><span className="section-index">02 / ACOMPANHE</span><h2 id="reservations-title">Agenda da unidade</h2></div><button className="button button-secondary button-compact" disabled={reservations.isFetching} onClick={() => void reservations.refetch()}>Atualizar</button></div><div className="reservation-filter"><label htmlFor="agenda-date">A partir de</label><input id="agenda-date" type="date" value={date} onChange={event => setDate(event.target.value)} /><span>Consulta de 30 dias · horários em {membership.timeZone}</span></div>
      {range.error ? <Notice tone="error">{range.error}</Notice> : reservations.isFetching || reservations.isPending ? <div className="panel"><Loading label="Consultando reservas da unidade…" /></div> : reservations.isError ? <ErrorState error={reservations.error} retry={() => void reservations.refetch()} /> : reservations.data ? <>
        {reservations.data.reservations.length === 0 ? <div className="panel"><Empty title="Nenhuma reserva neste período">As reservas confirmadas e seus estados aparecerão nesta agenda. Escolha uma área para fazer um pedido.</Empty></div> : <div className="reservation-list">{reservations.data.reservations.map(reservation => <ReservationCard key={reservation.reservationId} reservation={reservation} area={areas.data?.find(area => area.amenityId === reservation.amenityId)} scope={scope} timeZone={membership.timeZone} confirming={cancelId === reservation.reservationId} validating={validating} onAskCancel={() => setCancelId(reservation.reservationId)} onDismiss={() => setCancelId(null)} />)}</div>}
        {reservations.data.truncated ? <Notice tone="warning">Há mais reservas do que o limite desta consulta. Consulte datas mais próximas do período desejado.</Notice> : null}
      </> : null}
    </section></div><aside className="reservation-aside"><div className="guide-card"><span className="round-icon"><Icon name="calendar" /></span><h2>Reserve com tranquilidade.</h2><ol><li>Confira as condições da área.</li><li>Escolha o período no fuso do condomínio.</li><li>Aguarde a confirmação do servidor.</li></ol><div className="guide-divider" /><p><Icon name="clock" /><span>Intervalos de limpeza também ocupam a agenda.</span></p><p><Icon name="shield" /><span>O acesso e a disponibilidade são conferidos no envio.</span></p></div><p className="timezone-note">Fuso deste condomínio<br /><strong>{membership.timeZone}</strong></p></aside></div>
  </>;
}

function AreaCard({ area, disabled, selected, onChoose }: { area: Amenity; disabled: boolean; selected: boolean; onChoose(): void }) {
  const available = area.bookingState === 'AVAILABLE_FOR_REQUEST' && area.policy !== null && !area.approvalRequired;
  return <article className={`amenity-card${selected ? ' selected' : ''}`}><div className="amenity-top"><span className="amenity-symbol"><Icon name="calendar" /></span><span className={`status-pill ${available ? 'status-ready' : 'status-neutral'}`}>{available ? 'Reservas disponíveis' : 'Indisponível neste fluxo'}</span></div><h3>{area.name}</h3><p className="amenity-capacity">Capacidade: {area.capacity} pessoas</p><div className="amenity-details"><span>{area.policy ? `${area.policy.opensAt} às ${area.policy.closesAt}` : 'Regras pendentes'}</span><span>{area.feeCents > 0 ? money(area.feeCents) : 'Sem taxa informada'}</span></div><p className="amenity-note">{area.approvalRequired ? 'Esta área exige aprovação e ainda não está disponível neste fluxo.' : area.policy === null ? 'A gestão precisa concluir a configuração das regras.' : `Limpeza após o uso: ${area.bufferMinutes} min · regulamento v${area.policyVersion}`}</p><button className="button button-secondary button-wide" disabled={disabled || !available} onClick={onChoose} aria-label={`Reservar ${area.name}`}>{selected ? 'Área selecionada' : 'Reservar esta área'}<Icon name="arrow" /></button></article>;
}

function BookingForm({ area, scope, timeZone, validating, onClose }: { area: Amenity; scope: Scope; timeZone: string; validating: boolean; onClose(): void }) {
  const operations = useOperations();
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [accepted, setAccepted] = useState(false);
  const [validation, setValidation] = useState('');
  const panel = useRef<HTMLElement>(null);
  useEffect(() => { panel.current?.focus(); }, []);
  const policy = area.policy!;
  const weekDays = ['seg', 'ter', 'qua', 'qui', 'sex', 'sáb', 'dom'];
  async function submit(event: FormEvent) {
    event.preventDefault(); if (operations.locked || validating || !accepted) return;
    setValidation('');
    try {
      const startsAt = toInstant(start, timeZone); const endsAt = toInstant(end, timeZone);
      if (new Date(startsAt) >= new Date(endsAt)) throw new Error('O fim deve ser posterior ao início.');
      await operations.create(scope, { unitId: scope.unitId, amenityId: area.amenityId, startsAt, endsAt, acceptedPolicyVersion: area.policyVersion });
    } catch (error) { setValidation(error instanceof Error ? error.message : 'Confira o período informado.'); }
  }
  return <section ref={panel} tabIndex={-1} className="panel booking-panel" aria-labelledby="booking-title"><div className="section-heading"><div><span className="section-index">NOVA RESERVA</span><h3 id="booking-title">{area.name}</h3></div><button className="text-button" onClick={onClose} disabled={operations.locked}>Fechar</button></div><div className="policy-summary"><strong>Condições da versão {area.policyVersion}</strong><dl><div><dt>Horários</dt><dd>{policy.opensAt} às {policy.closesAt} · {policy.weekDays.map(day => weekDays[day - 1]).join(', ')}</dd></div><div><dt>Antecedência</dt><dd>Mínimo {policy.minimumLeadMinutes} min · até {policy.maximumAdvanceDays} dias</dd></div><div><dt>Duração e limpeza</dt><dd>Até {policy.maximumDurationMinutes} min de uso + {area.bufferMinutes} min de limpeza</dd></div><div><dt>Limite para solicitar</dt><dd>Menos de {policy.maximumActivePerUnit} reservas ativas da unidade em todas as áreas</dd></div><div><dt>Cancelamento</dt><dd>Pelo autor, até {policy.cancellationLeadMinutes} min antes do início</dd></div><div><dt>Taxa informativa</dt><dd>{money(area.feeCents)} · pagamento não é realizado por esta tela</dd></div></dl></div><form onSubmit={event => void submit(event)}><fieldset disabled={operations.locked || validating} className="booking-fields"><legend className="sr-only">Período e aceite da reserva</legend>{validating ? <Notice>Conferindo acesso e regras. Os dados preenchidos são preservados se as condições continuarem iguais.</Notice> : null}<p className="field-help">Informe os horários de {timeZone}. O fuso do seu dispositivo não será usado.</p><div className="two-fields"><div><label htmlFor="booking-start">Início</label><input id="booking-start" type="datetime-local" step="60" min={`${dateInZone(timeZone)}T00:00`} max={`${addDays(dateInZone(timeZone), policy.maximumAdvanceDays)}T23:59`} required value={start} onChange={event => setStart(event.target.value)} /></div><div><label htmlFor="booking-end">Fim</label><input id="booking-end" type="datetime-local" step="60" required value={end} onChange={event => setEnd(event.target.value)} /></div></div><label className="checkbox-label"><input type="checkbox" required checked={accepted} onChange={event => setAccepted(event.target.checked)} /><span>Li e aceito as regras da versão {area.policyVersion}.</span></label>{validation ? <Notice tone="error">{validation}</Notice> : null}<button className="button" type="submit" disabled={!accepted || operations.locked || validating}>Confirmar reserva<Icon name="check" /></button></fieldset></form></section>;
}

function ReservationCard({ reservation, area, scope, timeZone, confirming, validating, onAskCancel, onDismiss }: { reservation: Reservation; area: Amenity | undefined; scope: Scope; timeZone: string; confirming: boolean; validating: boolean; onAskCancel(): void; onDismiss(): void }) {
  const operations = useOperations();
  const states: Record<Reservation['status'], string> = { CONFIRMED: 'Confirmada', CANCELLED: 'Cancelada', EXPIRED: 'Expirada', HELD: 'Em retenção', PENDING_APPROVAL: 'Aguardando aprovação', REJECTED: 'Não aprovada' };
  const canAsk = reservation.status === 'CONFIRMED' && new Date(reservation.startsAt).getTime() > Date.now();
  async function confirm() { if (operations.locked || validating) return; await operations.cancel(scope, reservation.reservationId, { unitId: scope.unitId, expectedVersion: reservation.version }); onDismiss(); }
  return <article className="reservation-card"><div className="reservation-row"><div className="reservation-date-icon"><Icon name="calendar" /></div><div className="reservation-info"><h3>{area?.name ?? 'Área da reserva'}</h3><p>{formatWhen(reservation.startsAt, timeZone)} — {new Intl.DateTimeFormat('pt-BR', { timeZone, hour: '2-digit', minute: '2-digit' }).format(new Date(reservation.endsAt))}</p><small>Ocupação com limpeza até {new Intl.DateTimeFormat('pt-BR', { timeZone, hour: '2-digit', minute: '2-digit' }).format(new Date(reservation.occupiedUntil))} · regulamento v{reservation.acceptedPolicyVersion}</small></div><span className={`status-pill ${reservation.status === 'CONFIRMED' ? 'status-ready' : 'status-neutral'}`}>{states[reservation.status]}</span></div>{canAsk ? <div className="reservation-actions">{confirming ? <div className="cancel-confirmation" role="group" aria-label="Confirmação de cancelamento"><p>Cancelar esta reserva? Apenas o autor pode cancelar, dentro do prazo das regras aceitas.</p><div className="button-row"><button className="button button-danger" disabled={operations.locked || validating} onClick={() => void confirm()}>Confirmar cancelamento</button><button className="button button-secondary" disabled={operations.locked || validating} onClick={onDismiss}>Manter reserva</button></div></div> : <><span>O cancelamento é permitido somente ao autor.</span><button className="text-button danger-text" disabled={operations.locked || validating} aria-label={`Cancelar reserva ${formatWhen(reservation.startsAt, timeZone)}`} onClick={onAskCancel}>Cancelar reserva</button></>}</div> : null}</article>;
}
