import { useState, type FormEvent } from 'react';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { securityCommand, incidentOutcomes, incidentSeverities, reviewFilters, type Dossier, type Incident, type IncidentOutcome,
  type IncidentSeverity, type RemoteOpen, type ReviewFilter, type ReviewVerdict, type TeamMember } from '../../api/securityCommand';
import { securityLog, type SecurityLogEvent } from '../../api/securityLog';
import { Empty, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { partnerRoleLabels } from '../shell/paths';
import { FailureState, usePartnerAccessGuard, usePartnerScope } from './PartnerPage';
import { closeBody, commandKey, dossierFileName, dossierReason, incidentBody, incidentSeverityLabels, lightLabels, lightReasonLabels, lightTone,
  outcomeLabels, reachedTenant, reviewBody, reviewFilterLabels, severityLabels, sourceLabels, unavailableLabels, verdictLabels,
  type CloseDraft, type IncidentDraft, type ReviewDraft } from './model';

/**
 * Comando de Segurança do gestor líder (CPX-012 a CPX-014). Carteira com semáforo, incidentes com linha do tempo do Log de
 * Segurança e dossiê, e revisão de aberturas remotas. O servidor confere papel, contrato e segundo fator em cada chamada;
 * leituras de eventos do log gravam SENSITIVE_VIEWED, por isso só acontecem por pedido explícito.
 */
const PARTNER_ZONE = 'America/Sao_Paulo';
const OFF_TEXT = 'O Comando de Segurança depende da hierarquia de parceiros e do Log de Segurança ligados no servidor deste ambiente (CP_PARTNERS_ENABLED e CP_SECURITY_LOG_ENABLED). Quando forem ligados, esta seção passa a funcionar sem mudança na tela.';
const SUBJECT = 'Comando de Segurança';
const severityTone: Record<string, string> = { INFO: 'status-neutral', ATTENTION: 'status-pending', CRITICAL: 'status-overdue' };
const short = (id: string) => id.slice(0, 8);

interface ActionState { pending: boolean; error: unknown; done: string | null }
/** Uma ação por vez; recusa vai ao tratador único; falha incerta pede conferência antes de repetir. */
function useAction(tenantId?: string) {
  const scope = usePartnerScope();
  const [state, setState] = useState<ActionState>({ pending: false, error: null, done: null });
  async function run<T>(action: () => Promise<T>, done: (result: T) => string, after?: (result: T) => Promise<void> | void): Promise<boolean> {
    if (state.pending) return false;
    setState({ pending: true, error: null, done: null });
    try {
      const result = await action();
      await after?.(result);
      setState({ pending: false, error: null, done: done(result) });
      return true;
    } catch (error) {
      setState({ pending: false, error: scope.refuse(error, tenantId) ? null : error, done: null });
      return false;
    }
  }
  return { state, run };
}
function Feedback({ state }: { state: ActionState }) {
  if (state.error) {
    const uncertain = (state.error as { uncertain?: boolean }).uncertain;
    return <Notice tone="error">{errorMessage(state.error)}{uncertain ? ' Atualize e confira antes de repetir.' : ''}</Notice>;
  }
  return state.done ? <Notice tone="success">{state.done}</Notice> : null;
}

function useTeam() {
  const { accountId, context } = usePartnerScope();
  return useQuery({ queryKey: commandKey(accountId, context.partnerId, 'team'), queryFn: ({ signal }) => securityCommand.team(context.partnerId, signal) });
}
const memberName = (team: readonly TeamMember[] | undefined, memberId: string | null) =>
  memberId ? team?.find((member) => member.memberId.toLowerCase() === memberId.toLowerCase())?.displayName ?? `membro ${short(memberId)}` : null;

// ---- CPX-012: visão da carteira ----
export function CommandPortfolioSection() {
  const { accountId, context } = usePartnerScope();
  const portfolio = useQuery({ queryKey: commandKey(accountId, context.partnerId, 'portfolio'),
    queryFn: ({ signal }) => securityCommand.portfolio(context.partnerId, signal), refetchInterval: 60_000 });
  const guarded = usePartnerAccessGuard(portfolio.error);
  if (guarded) return null;
  const data = portfolio.data;
  return <section className="management-section" aria-labelledby="command-portfolio-title">
    <div className="section-heading"><div><span className="section-index">CARTEIRA</span><h2 id="command-portfolio-title">Visão da carteira</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={portfolio.isFetching} onClick={() => void portfolio.refetch()}>Atualizar</button></div>
    <p className="field-help">Vermelho: alerta crítico das últimas 24 h sem incidente ou incidente crítico aberto. Amarelo: equipamento fora do ar ou sem
      informação recente, central sem operador escalado agora, ocorrência de segurança aberta ou incidente aberto. Verde: nenhum dos anteriores.</p>
    {portfolio.isPending ? <div className="panel"><Loading label="Consultando a carteira…" /></div>
      : portfolio.isError || !data ? <FailureState error={portfolio.error} retry={() => void portfolio.refetch()} off={OFF_TEXT} subject={SUBJECT} />
        : <>
          {data.unavailableIndicators.length > 0 ? <Notice tone="info">Sem fonte de dados nesta versão (não entram no semáforo e não são zero):{' '}
            {data.unavailableIndicators.map((item) => unavailableLabels[item] ?? item).join(', ')}.</Notice> : null}
          {data.truncated ? <Notice tone="warning">A carteira tem mais de 200 condomínios; a lista mostra os 200 primeiros por nome.</Notice> : null}
          {data.tenants.length === 0 ? <div className="panel"><Empty icon="building" title="Nenhum condomínio com contrato ativo">Os condomínios vinculados ao parceiro aparecem aqui.</Empty></div>
            : <ul className="management-list" aria-label="Semáforo da carteira">{data.tenants.map((tenant) => <li key={tenant.tenantId} className="management-row">
              <div><strong><bdi>{tenant.tenantName}</bdi></strong>
                <span className={`status-pill ${lightTone[tenant.light]}`}>Semáforo: {lightLabels[tenant.light]}</span>
                <span>{tenant.reasons.length === 0 ? 'Sem pendência nos critérios.' : tenant.reasons.map((reason) => lightReasonLabels[reason]).join(' · ')}</span>
                <span>Equipamentos: {tenant.indicators.devicesTotal} ({tenant.indicators.devicesOffline} fora do ar, {tenant.indicators.devicesUnknown} sem informação) ·
                  {' '}operadores escalados agora: {tenant.indicators.operatorsOnDuty} · ocorrências de segurança: {tenant.indicators.securityOccurrencesOpen} ·
                  {' '}alertas críticos sem incidente: {tenant.indicators.criticalAlertsUnlinked} · incidentes abertos: {tenant.indicators.incidentsOpen} ·
                  {' '}aberturas remotas sem revisão (7 dias): {tenant.indicators.remoteOpensPendingReview}</span></div></li>)}</ul>}
          <p className="field-help">Critério {data.criteria}. Atualizado em {formatWhen(data.generatedAt, PARTNER_ZONE)}.</p></>}
  </section>;
}

// ---- CPX-013: incidentes ----
/** Eventos de atenção e críticos do log do condomínio, sob pedido (a consulta grava SENSITIVE_VIEWED), para anexar. */
function EventPicker({ tenantId, timeZone, chosen, onChange }: { tenantId: string; timeZone: string; chosen: readonly string[]; onChange(ids: string[]): void }) {
  const { accountId, context } = usePartnerScope();
  const [severity, setSeverity] = useState<'CRITICAL' | 'ATTENTION'>('CRITICAL');
  const [run, setRun] = useState(0);
  const events = useQuery({ queryKey: commandKey(accountId, context.partnerId, 'picker', tenantId.toLowerCase(), severity, run), enabled: run > 0,
    queryFn: ({ signal }) => securityLog.page(tenantId, { severity, limit: 50 }, signal), refetchOnWindowFocus: false });
  const guarded = usePartnerAccessGuard(events.error, tenantId);
  const refused = usePartnerScope().deniedTenant(tenantId);
  if (refused) return <FailureState error={refused} retry={() => undefined} off={OFF_TEXT} subject="Log de Segurança" />;
  if (guarded) return null;
  const toggle = (id: string) => onChange(chosen.includes(id) ? chosen.filter((item) => item !== id) : [...chosen, id]);
  return <fieldset className="stack-form"><legend>Eventos do log anexados ({chosen.length})</legend>
    <div className="reservation-filter"><label htmlFor={`picker-severity-${tenantId}`}>Severidade dos eventos</label>
      <select id={`picker-severity-${tenantId}`} value={severity} onChange={(event) => setSeverity(event.target.value as 'CRITICAL' | 'ATTENTION')}>
        <option value="CRITICAL">{severityLabels.CRITICAL}</option><option value="ATTENTION">{severityLabels.ATTENTION}</option></select>
      <button className="button button-secondary button-compact" type="button" disabled={events.isFetching} onClick={() => setRun((value) => value + 1)}>Buscar eventos</button></div>
    <p className="field-help">A busca consulta o log do condomínio e fica registrada nele.</p>
    {run === 0 ? null : events.isPending ? <Loading label="Consultando o log…" />
      : events.isError ? <FailureState error={events.error} retry={() => void events.refetch()} off={OFF_TEXT} subject="Log de Segurança" />
        : events.data.events.length === 0 ? <p className="field-help">Nenhum evento com esta severidade.</p>
          : <ul className="management-list" aria-label="Eventos para anexar">{events.data.events.map((event) => <li key={event.eventId} className="management-row">
            <label><input type="checkbox" checked={chosen.includes(event.eventId)} onChange={() => toggle(event.eventId)} />
              {' '}#{event.seq} · {event.eventType} · {formatWhen(event.occurredAt, timeZone)}</label></li>)}</ul>}
  </fieldset>;
}

function OpenIncidentForm({ onOpened }: { onOpened(incident: Incident): void }) {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const action = useAction();
  const [draft, setDraft] = useState<IncidentDraft>({ tenantId: context.tenants[0]?.tenantId ?? '', title: '', severity: '', eventIds: [] });
  const [problem, setProblem] = useState<string | null>(null);
  const tenant = reachedTenant(context.tenants, draft.tenantId);
  const set = (patch: Partial<IncidentDraft>) => setDraft((current) => ({ ...current, ...patch }));
  function submit(event: FormEvent) {
    event.preventDefault();
    const body = incidentBody({ ...draft, tenantId: tenant?.tenantId ?? '' }, context.tenants.map((item) => item.tenantId));
    if (typeof body === 'string') { setProblem(body); return; }
    setProblem(null);
    void action.run(() => securityCommand.open(context.partnerId, body), (result) => `Incidente aberto: ${result.incident.title}.`, async (result) => {
      setDraft({ tenantId: body.tenantId, title: '', severity: '', eventIds: [] });
      await queries.invalidateQueries({ queryKey: commandKey(accountId, context.partnerId) });
      onOpened(result.incident);
    });
  }
  if (context.tenants.length === 0) return null;
  return <form className="panel management-form stack-form" onSubmit={submit} aria-labelledby="incident-open-form">
    <h3 id="incident-open-form">Abrir incidente</h3>
    <div className="two-fields"><div><label htmlFor="incident-tenant">Condomínio</label>
      <select id="incident-tenant" value={tenant?.tenantId ?? ''} onChange={(event) => set({ tenantId: event.target.value, eventIds: [] })}>
        {context.tenants.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.tenantName}</option>)}</select></div>
      <div><label htmlFor="incident-severity">Severidade</label>
        <select id="incident-severity" value={draft.severity} onChange={(event) => set({ severity: event.target.value as IncidentSeverity | '' })}>
          <option value="">Escolha</option>{incidentSeverities.map((item) => <option key={item} value={item}>{incidentSeverityLabels[item]}</option>)}</select></div></div>
    <label htmlFor="incident-title">Título</label>
    <input id="incident-title" maxLength={120} value={draft.title} onChange={(event) => set({ title: event.target.value })} />
    <p className="field-help">Sem nome, documento ou dado de morador no título: ele aparece na lista da carteira.</p>
    {tenant ? <EventPicker key={tenant.tenantId} tenantId={tenant.tenantId} timeZone={tenant.timeZone} chosen={draft.eventIds} onChange={(ids) => set({ eventIds: ids })} /> : null}
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Enviando…' : 'Abrir incidente'}</button></div>
    <Feedback state={action.state} />
  </form>;
}

function download(name: string, content: unknown) {
  const blob = new Blob([JSON.stringify(content, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = name;
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function TimelineList({ events, timeZone }: { events: readonly SecurityLogEvent[]; timeZone: string }) {
  if (events.length === 0) return <p className="field-help">Nenhum evento no log para este incidente.</p>;
  return <ol className="management-list" aria-label="Linha do tempo do incidente">{events.map((event) => <li key={event.eventId} className="management-row">
    <div><strong>#{event.seq} · {event.eventType}</strong>
      <span>{formatWhen(event.occurredAt, timeZone)}{event.occurredAt !== event.recordedAt ? ` (gravado ${formatWhen(event.recordedAt, timeZone)})` : ''} ·
        {' '}{sourceLabels[event.source] ?? event.source}{event.actorRole ? ` · ${event.actorRole}` : ''}{event.reason ? ` · ${event.reason}` : ''}</span>
      <span className={`status-pill ${severityTone[event.severity]}`}>{severityLabels[event.severity]}</span></div></li>)}</ol>;
}

function IncidentPanel({ tenantId, incidentId, close }: { tenantId: string; incidentId: string; close(): void }) {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const team = useTeam();
  const tenant = context.tenants.find((item) => item.tenantId.toLowerCase() === tenantId.toLowerCase());
  const zone = tenant?.timeZone ?? PARTNER_ZONE;
  const detail = useQuery({ queryKey: commandKey(accountId, context.partnerId, 'incident', tenantId.toLowerCase(), incidentId),
    queryFn: ({ signal }) => securityCommand.detail(context.partnerId, tenantId, incidentId, signal), refetchOnWindowFocus: false });
  const guarded = usePartnerAccessGuard(detail.error ?? team.error, tenantId);
  const action = useAction(tenantId);
  const [assignee, setAssignee] = useState('');
  const [extra, setExtra] = useState<string[]>([]);
  const [closing, setClosing] = useState<CloseDraft>({ outcome: '', report: '' });
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [exported, setExported] = useState<Dossier | null>(null);
  const refused = usePartnerScope().deniedTenant(tenantId);
  if (refused) return <FailureState error={refused} retry={() => undefined} off={OFF_TEXT} subject={SUBJECT} />;
  if (guarded) return null;
  const refresh = async () => { await queries.invalidateQueries({ queryKey: commandKey(accountId, context.partnerId) }); };
  if (detail.isPending) return <div className="panel"><Loading label="Consultando o incidente e a linha do tempo…" /></div>;
  if (detail.isError) return <FailureState error={detail.error} retry={() => void detail.refetch()} off={OFF_TEXT} subject={SUBJECT} />;
  const { incident, timeline } = detail.data;
  const open = incident.state === 'OPEN';
  const canExport = context.capabilities.includes('security.log.export');
  function assign(event: FormEvent) {
    event.preventDefault();
    if (!assignee) { setProblem('Escolha o responsável.'); return; }
    setProblem(null);
    void action.run(() => securityCommand.assign(context.partnerId, tenantId, incidentId, assignee, incident.version), () => 'Responsável designado.', refresh);
  }
  function attach(event: FormEvent) {
    event.preventDefault();
    if (extra.length === 0) { setProblem('Escolha ao menos um evento do log.'); return; }
    setProblem(null);
    void action.run(() => securityCommand.link(context.partnerId, tenantId, incidentId, extra), () => 'Eventos anexados.', async () => { setExtra([]); await refresh(); });
  }
  function finish(event: FormEvent) {
    event.preventDefault();
    const body = closeBody(closing, incident.version);
    if (typeof body === 'string') { setProblem(body); return; }
    setProblem(null);
    void action.run(() => securityCommand.close(context.partnerId, tenantId, incidentId, body), () => 'Incidente encerrado.', refresh);
  }
  function exportDossier(event: FormEvent) {
    event.preventDefault();
    const motive = dossierReason(reason);
    if (!motive) { setProblem('Informe o motivo da exportação, com 10 a 500 caracteres.'); return; }
    setProblem(null);
    void action.run(() => securityCommand.dossier(context.partnerId, tenantId, incidentId, motive),
      (result) => `Dossiê exportado: ${result.manifest.count} eventos (sequência ${result.manifest.firstSeq} a ${result.manifest.lastSeq}).`,
      (result) => { setExported(result); download(dossierFileName(tenantId, incidentId), result.raw); });
  }
  return <section className="panel stack-form" aria-labelledby={`incident-${incidentId}`}>
    <div className="section-heading"><div><h3 id={`incident-${incidentId}`}><bdi>{incident.title}</bdi></h3>
      <span>{tenant?.tenantName ?? incident.tenantName ?? 'Condomínio'} · aberto em {formatWhen(incident.openedAt, zone)} · {incidentSeverityLabels[incident.severity]}
        {incident.assigneeMemberId ? ` · responsável: ${memberName(team.data, incident.assigneeMemberId)}` : ' · sem responsável'}
        {incident.outcome ? ` · ${outcomeLabels[incident.outcome]}` : ''}</span></div>
      <button className="button button-secondary button-compact" type="button" onClick={close}>Fechar</button></div>
    {incident.report ? <Notice tone="info">Relatório: {incident.report}</Notice> : null}
    <h4>Linha do tempo (Log de Segurança)</h4>
    <TimelineList events={timeline} timeZone={zone} />
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    <Feedback state={action.state} />
    {open ? <>
      <form className="reservation-filter" onSubmit={assign} aria-label="Designar responsável">
        <label htmlFor={`assignee-${incidentId}`}>Responsável</label>
        <select id={`assignee-${incidentId}`} value={assignee} onChange={(event) => setAssignee(event.target.value)}>
          <option value="">Escolha</option>{(team.data ?? []).map((member) => <option key={member.memberId} value={member.memberId}>{member.displayName} · {partnerRoleLabels[member.role]}</option>)}</select>
        <button className="button button-compact" type="submit" disabled={action.state.pending}>Designar</button></form>
      <form className="stack-form" onSubmit={attach} aria-label="Anexar eventos">
        {tenant ? <EventPicker tenantId={tenantId} timeZone={zone} chosen={extra} onChange={setExtra} /> : null}
        <div className="button-row"><button className="button button-secondary" type="submit" disabled={action.state.pending || extra.length === 0}>Anexar eventos</button></div></form>
      <form className="stack-form" onSubmit={finish} aria-label="Encerrar incidente">
        <label htmlFor={`outcome-${incidentId}`}>Desfecho</label>
        <select id={`outcome-${incidentId}`} value={closing.outcome} onChange={(event) => setClosing((current) => ({ ...current, outcome: event.target.value as IncidentOutcome | '' }))}>
          <option value="">Escolha</option>{incidentOutcomes.map((item) => <option key={item} value={item}>{outcomeLabels[item]}</option>)}</select>
        <label htmlFor={`report-${incidentId}`}>Relatório de encerramento</label>
        <textarea id={`report-${incidentId}`} rows={4} maxLength={4000} value={closing.report}
          onChange={(event) => setClosing((current) => ({ ...current, report: event.target.value }))} />
        <div className="button-row"><button className="button button-danger" type="submit" disabled={action.state.pending}>Encerrar incidente</button></div></form></> : null}
    {canExport ? <form className="stack-form" onSubmit={exportDossier} aria-label="Exportar dossiê">
      <h4>Dossiê com manifesto</h4>
      <p className="field-help">Exporta o incidente e os eventos da linha do tempo com o resumo de hashes (cpdx1). A exportação fica registrada no log.</p>
      <label htmlFor={`dossier-reason-${incidentId}`}>Motivo da exportação do dossiê</label>
      <textarea id={`dossier-reason-${incidentId}`} rows={2} maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
      <div className="button-row"><button className="button" type="submit" disabled={action.state.pending}>Exportar dossiê</button></div>
      {exported ? <p className="field-help">Resumo dos eventos: <code>{exported.manifest.eventsDigest}</code></p> : null}</form> : null}
  </section>;
}

export function IncidentsSection() {
  const { accountId, context } = usePartnerScope();
  const [state, setState] = useState<'OPEN' | 'CLOSED'>('OPEN');
  const [selected, setSelected] = useState<{ tenantId: string; incidentId: string } | null>(null);
  const team = useTeam();
  const incidents = useQuery({ queryKey: commandKey(accountId, context.partnerId, 'incidents', state),
    queryFn: ({ signal }) => securityCommand.incidents(context.partnerId, state, signal) });
  const guarded = usePartnerAccessGuard(incidents.error);
  if (guarded) return null;
  // Incidente selecionado só enquanto o condomínio continuar alcançado (/v1/me).
  const shown = selected && context.tenants.some((tenant) => tenant.tenantId.toLowerCase() === selected.tenantId.toLowerCase()) ? selected : null;
  return <section className="management-section" aria-labelledby="command-incidents-title">
    <div className="section-heading"><div><span className="section-index">INCIDENTES</span><h2 id="command-incidents-title">Incidentes</h2></div>
      <div className="reservation-filter"><label htmlFor="incident-state">Situação</label>
        <select id="incident-state" value={state} onChange={(event) => setState(event.target.value as 'OPEN' | 'CLOSED')}>
          <option value="OPEN">Abertos</option><option value="CLOSED">Encerrados</option></select></div></div>
    <p className="field-help">A linha do tempo é montada a partir do Log de Segurança. Abrir, designar e encerrar ficam registrados no log; abrir um incidente consulta o log e também fica registrado.</p>
    <div className="management-columns"><div>
      {incidents.isPending ? <div className="panel"><Loading label="Consultando os incidentes…" /></div>
        : incidents.isError ? <FailureState error={incidents.error} retry={() => void incidents.refetch()} off={OFF_TEXT} subject={SUBJECT} />
          : incidents.data.incidents.length === 0 ? <div className="panel"><Empty icon="alert" title={state === 'OPEN' ? 'Nenhum incidente aberto' : 'Nenhum incidente encerrado'}>Os incidentes do parceiro aparecem aqui.</Empty></div>
            : <ul className="management-list" aria-label="Incidentes">{incidents.data.incidents.map((incident) => <li key={incident.id} className="management-row">
              <div><strong><bdi>{incident.title}</bdi></strong>
                <span><bdi>{incident.tenantName ?? 'Condomínio'}</bdi> · {formatWhen(incident.openedAt, PARTNER_ZONE)} · {incident.linkCount} evento(s) anexado(s)
                  {incident.assigneeMemberId ? ` · ${memberName(team.data, incident.assigneeMemberId)}` : ''}</span>
                <span className={`status-pill ${severityTone[incident.severity]}`}>{incidentSeverityLabels[incident.severity]}</span></div>
              <div className="button-row"><button className="text-button" type="button" aria-label={`Abrir incidente ${incident.title}`}
                onClick={() => setSelected({ tenantId: incident.tenantId, incidentId: incident.id })}>Abrir</button></div></li>)}</ul>}
      {incidents.data?.truncated ? <Notice tone="warning">A lista mostra os 200 incidentes mais recentes.</Notice> : null}
      {shown ? <IncidentPanel key={shown.incidentId} tenantId={shown.tenantId} incidentId={shown.incidentId} close={() => setSelected(null)} /> : null}</div>
      <OpenIncidentForm onOpened={(incident) => { setState('OPEN'); setSelected({ tenantId: incident.tenantId, incidentId: incident.id }); }} /></div>
  </section>;
}

// ---- CPX-014: revisão de aberturas remotas ----
function ReviewForm({ item, tenantId, done }: { item: RemoteOpen; tenantId: string; done(): Promise<void> }) {
  const { context } = usePartnerScope();
  const action = useAction(tenantId);
  const [draft, setDraft] = useState<ReviewDraft>({ verdict: '', note: '' });
  const [problem, setProblem] = useState<string | null>(null);
  const correcting = item.review?.reviewEventId ?? null;
  function submit(event: FormEvent) {
    event.preventDefault();
    const body = reviewBody(draft, correcting);
    if (typeof body === 'string') { setProblem(body); return; }
    setProblem(null);
    void action.run(() => securityCommand.review(context.partnerId, tenantId, item.eventId, body),
      (result) => `Abertura #${result.seq} marcada como ${verdictLabels[result.review!.verdict].toLowerCase()}.`, done);
  }
  return <form className="reason-form" onSubmit={submit} aria-label={`${correcting ? 'Corrigir revisão' : 'Revisar'} da abertura #${item.seq}`}>
    <fieldset><legend>{correcting ? 'Corrigir a revisão' : 'Revisão'}</legend>
      {(['CONFERRED', 'IRREGULAR'] as ReviewVerdict[]).map((verdict) => <label key={verdict}>
        <input type="radio" name={`verdict-${item.eventId}`} value={verdict} checked={draft.verdict === verdict}
          onChange={() => setDraft((current) => ({ ...current, verdict }))} /> {verdictLabels[verdict]}</label>)}</fieldset>
    <label htmlFor={`review-note-${item.eventId}`}>{correcting ? 'Motivo da correção' : 'Observação (obrigatória se irregular)'}</label>
    <textarea id={`review-note-${item.eventId}`} rows={2} maxLength={500} value={draft.note} onChange={(event) => setDraft((current) => ({ ...current, note: event.target.value }))} />
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    <div className="button-row"><button className="button button-compact" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Enviando…' : 'Registrar revisão'}</button></div>
    <Feedback state={action.state} />
  </form>;
}

function TenantRemoteOpens({ tenantId, timeZone }: { tenantId: string; timeZone: string }) {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const team = useTeam();
  const [draft, setDraft] = useState<ReviewFilter>('PENDING');
  const [request, setRequest] = useState<{ status: ReviewFilter; run: number } | null>(null);
  const [reviewing, setReviewing] = useState<string | null>(null);
  const pages = useInfiniteQuery({ queryKey: commandKey(accountId, context.partnerId, 'remote-opens', tenantId.toLowerCase(), request?.status ?? null, request?.run ?? 0),
    enabled: request !== null, initialPageParam: undefined as number | undefined, refetchOnWindowFocus: false,
    queryFn: ({ signal, pageParam }) => securityCommand.remoteOpens(context.partnerId, tenantId, { status: request!.status, ...(pageParam !== undefined ? { beforeSeq: pageParam } : {}) }, signal),
    getNextPageParam: (last) => last.nextBeforeSeq ?? undefined });
  const guarded = usePartnerAccessGuard(request !== null ? pages.error : null, tenantId);
  const refused = usePartnerScope().deniedTenant(tenantId);
  if (refused) return <FailureState error={refused} retry={() => undefined} off={OFF_TEXT} subject={SUBJECT} />;
  if (guarded) return null;
  const items = pages.data?.pages.flatMap((page) => page.items) ?? [];
  const operator = (item: RemoteOpen) => item.operatorAccountId
    ? team.data?.find((member) => member.accountId.toLowerCase() === item.operatorAccountId!.toLowerCase())?.displayName ?? `conta ${short(item.operatorAccountId)}`
    : 'sem operador';
  const reload = async () => { setReviewing(null); await queries.invalidateQueries({ queryKey: commandKey(accountId, context.partnerId) }); };
  return <div className="stack-form">
    <form className="reservation-filter" aria-label="Filtro das aberturas remotas" onSubmit={(event) => { event.preventDefault(); setRequest((current) => ({ status: draft, run: (current?.run ?? 0) + 1 })); }}>
      <label htmlFor={`remote-filter-${tenantId}`}>Situação</label>
      <select id={`remote-filter-${tenantId}`} value={draft} onChange={(event) => setDraft(event.target.value as ReviewFilter)}>
        {reviewFilters.map((item) => <option key={item} value={item}>{reviewFilterLabels[item]}</option>)}</select>
      <button className="button button-compact" type="submit" disabled={pages.isFetching}>Consultar aberturas</button></form>
    {request === null ? <div className="panel"><Empty icon="door" title="Consulta sob pedido">Escolha a situação e consulte. Cada consulta fica registrada no log.</Empty></div>
      : pages.isPending ? <div className="panel"><Loading label="Consultando as aberturas remotas…" /></div>
        : pages.isError && items.length === 0 ? <FailureState error={pages.error} retry={() => void pages.refetch()} off={OFF_TEXT} subject={SUBJECT} />
          : items.length === 0 ? <div className="panel"><Empty icon="door" title="Nenhuma abertura remota">Nenhuma abertura com este filtro.</Empty></div>
            : <ul className="management-list" aria-label="Aberturas remotas">{items.map((item) => <li key={item.eventId} className="management-row">
              <div><strong>#{item.seq} · {formatWhen(item.occurredAt, timeZone)}</strong>
                <span>Operador: {operator(item)}{item.operatorRole ? ` (${item.operatorRole})` : ''} · motivo: {item.reason ?? 'não informado'}
                  {item.pointId ? ` · ponto ${short(item.pointId)}` : ''}{item.deviceId ? ` · equipamento ${short(item.deviceId)}` : ''}
                  {item.evidenceRef ? ` · evidência ${item.evidenceRef}` : ''}</span>
                {item.review ? <span className={`status-pill ${item.review.verdict === 'IRREGULAR' ? 'status-overdue' : 'status-ready'}`}>
                  {verdictLabels[item.review.verdict]}{item.review.note ? `: ${item.review.note}` : ''}</span>
                  : <span className="status-pill status-pending">Sem revisão</span>}</div>
              {reviewing === item.eventId ? <ReviewForm item={item} tenantId={tenantId} done={reload} />
                : <div className="button-row"><button className="text-button" type="button" onClick={() => setReviewing(item.eventId)}
                  aria-label={`${item.review ? 'Corrigir revisão' : 'Revisar'} da abertura #${item.seq}`}>{item.review ? 'Corrigir revisão' : 'Revisar'}</button></div>}</li>)}</ul>}
    {pages.isError && items.length > 0 ? <Notice tone="error">{errorMessage(pages.error)}</Notice> : null}
    {pages.hasNextPage ? <button className="button button-secondary" type="button" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
      {pages.isFetchingNextPage ? 'Carregando…' : 'Carregar aberturas anteriores'}</button> : null}
  </div>;
}

export function RemoteOpenSection() {
  const { context } = usePartnerScope();
  const [chosen, setChosen] = useState(context.tenants[0]?.tenantId ?? '');
  const tenant = reachedTenant(context.tenants, chosen);
  return <section className="management-section" aria-labelledby="command-remote-title">
    <div className="section-heading"><div><span className="section-index">ABERTURAS REMOTAS</span><h2 id="command-remote-title">Revisão de aberturas remotas</h2></div></div>
    <p className="field-help">Revisão por amostragem: marque como conferida ou irregular. Cada marcação grava REMOTE_OPEN_REVIEWED no log; para mudar, corrija a revisão com motivo. Ninguém revisa a própria abertura.</p>
    {context.tenants.length === 0 ? <div className="panel"><Empty icon="building" title="Nenhum condomínio alcançado agora">As aberturas ficam disponíveis para os condomínios com contrato ativo.</Empty></div>
      : <>{context.tenants.length > 1 ? <div className="reservation-filter"><label htmlFor="remote-tenant">Condomínio</label>
        <select id="remote-tenant" value={tenant?.tenantId ?? ''} onChange={(event) => setChosen(event.target.value)}>
          {context.tenants.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.tenantName}</option>)}</select></div> : null}
        {tenant ? <TenantRemoteOpens key={tenant.tenantId} tenantId={tenant.tenantId} timeZone={tenant.timeZone} /> : null}</>}
  </section>;
}
