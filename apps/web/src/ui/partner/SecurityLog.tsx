import { useState, type FormEvent } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { securityLog, securitySeverities, type SecurityLogExport, type SecurityLogFilter, type SecuritySeverity } from '../../api/securityLog';
import { Empty, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { FailureState, usePartnerAccessGuard, usePartnerScope } from './PartnerPage';
import { exportBody, exportFileName, integrityProblems, logFilter, securityLogKey, severityLabels, sourceLabels, type ExportDraft, type LogFilterDraft } from './model';

const OFF_TEXT = 'O Log de Segurança está desligado no servidor deste ambiente (CP_SECURITY_LOG_ENABLED). Quando for ligado, esta seção passa a funcionar sem mudança na tela.';
const severityTone: Record<SecuritySeverity, string> = { INFO: 'status-neutral', ATTENTION: 'status-pending', CRITICAL: 'status-overdue' };

/**
 * Log de Segurança da carteira (CPX-009/CPX-010) por condomínio alcançado. Só com security.log.read; exportação só com
 * security.log.export. Cada consulta grava SENSITIVE_VIEWED no servidor, por isso nada é consultado sem o pedido explícito.
 */
export function SecurityLogSection() {
  const { context } = usePartnerScope();
  const [tenantId, setTenantId] = useState(context.tenants[0]?.tenantId ?? '');
  const tenant = context.tenants.find((item) => item.tenantId === tenantId);
  return <section className="management-section" aria-labelledby="security-log-title">
    <div className="section-heading"><div><span className="section-index">LOG DE SEGURANÇA</span><h2 id="security-log-title">Log de Segurança</h2></div></div>
    <p className="field-help">Trilha imutável e encadeada por hash. Toda consulta, verificação ou exportação fica registrada no próprio log com o seu papel.</p>
    {context.tenants.length === 0 ? <div className="panel"><Empty icon="building" title="Nenhum condomínio alcançado agora">O log fica disponível para os condomínios com contrato ativo (e, para operação, escala vigente).</Empty></div>
      : <>{context.tenants.length > 1 ? <div className="reservation-filter"><label htmlFor="security-log-tenant">Condomínio</label>
        <select id="security-log-tenant" value={tenantId} onChange={(event) => setTenantId(event.target.value)}>
          {context.tenants.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.tenantName}</option>)}</select></div> : null}
        {tenant ? <TenantLog key={tenant.tenantId} tenantId={tenant.tenantId} tenantName={tenant.tenantName} timeZone={tenant.timeZone}
          canExport={context.capabilities.includes('security.log.export')} /> : null}</>}
  </section>;
}

function TenantLog({ tenantId, tenantName, timeZone, canExport }: { tenantId: string; tenantName: string; timeZone: string; canExport: boolean }) {
  const { accountId } = usePartnerScope();
  const [draft, setDraft] = useState<LogFilterDraft>({ type: '', severity: '' });
  const [filter, setFilter] = useState<SecurityLogFilter | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const pages = useInfiniteQuery({ queryKey: securityLogKey(accountId, tenantId, 'page', filter), enabled: filter !== null, initialPageParam: undefined as number | undefined,
    queryFn: ({ signal, pageParam }) => securityLog.page(tenantId, { ...filter, ...(pageParam !== undefined ? { beforeSeq: pageParam } : {}) }, signal),
    getNextPageParam: (last) => last.nextBeforeSeq ?? undefined, refetchOnWindowFocus: false });
  const integrity = useQuery({ queryKey: securityLogKey(accountId, tenantId, 'integrity'), enabled: checking, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => securityLog.integrity(tenantId, signal) });
  const guarded = usePartnerAccessGuard(pages.error ?? integrity.error);
  if (guarded) return null;
  function search(event: FormEvent) {
    event.preventDefault();
    const next = logFilter(draft);
    if (typeof next === 'string') { setProblem(next); return; }
    setProblem(null); setFilter(next);
  }
  const events = pages.data?.pages.flatMap((page) => page.events) ?? [];
  return <div className="stack-form">
    <form className="reservation-filter" onSubmit={search} aria-label={`Filtros do log de ${tenantName}`}>
      <label htmlFor="security-log-type">Tipo</label>
      <input id="security-log-type" value={draft.type} placeholder="Ex.: ACCESS_ENTRY" maxLength={40} spellCheck={false}
        onChange={(event) => setDraft((current) => ({ ...current, type: event.target.value }))} />
      <label htmlFor="security-log-severity">Severidade</label>
      <select id="security-log-severity" value={draft.severity} onChange={(event) => setDraft((current) => ({ ...current, severity: event.target.value as SecuritySeverity | '' }))}>
        <option value="">Todas</option>{securitySeverities.map((item) => <option key={item} value={item}>{severityLabels[item]}</option>)}</select>
      <button className="button button-compact" type="submit" disabled={pages.isFetching}>Consultar log</button>
      <button className="button button-secondary button-compact" type="button" disabled={integrity.isFetching}
        onClick={() => { if (checking) void integrity.refetch(); else setChecking(true); }}>Verificar integridade</button>
    </form>
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    {checking ? integrity.isPending ? <Loading label="Verificando a cadeia…" />
      : integrity.isError ? <FailureState error={integrity.error} retry={() => void integrity.refetch()} off={OFF_TEXT} subject="Log de Segurança" />
        : <Notice tone={integrity.data.intact ? 'success' : 'error'}>{integrity.data.intact
          ? `Cadeia íntegra: ${integrity.data.checkedEvents} eventos e ${integrity.data.anchorsChecked} âncoras conferidos até a sequência ${integrity.data.lastSeq}.`
          : `Cadeia quebrada${integrity.data.brokenSeq !== null ? ` na sequência ${integrity.data.brokenSeq}` : ''}: ${integrityProblems[integrity.data.problem ?? ''] ?? integrity.data.problem}.`}
          {' '}Verificado em {formatWhen(integrity.data.checkedAt, timeZone)}.</Notice> : null}
    {filter === null ? <div className="panel"><Empty icon="list" title="Consulta sob pedido">Escolha os filtros e consulte. Cada consulta fica registrada no log.</Empty></div>
      : pages.isPending ? <div className="panel"><Loading label="Consultando o log…" /></div>
        : pages.isError && events.length === 0 ? <FailureState error={pages.error} retry={() => void pages.refetch()} off={OFF_TEXT} subject="Log de Segurança" />
          : events.length === 0 ? <div className="panel"><Empty icon="list" title="Nenhum evento">Nenhum evento com estes filtros.</Empty></div>
            : <><ul className="management-list" aria-label={`Eventos do log de ${tenantName}`}>{events.map((event) => <li key={event.eventId} className="management-row">
              <div><strong>#{event.seq} · {event.eventType}</strong>
                <span>{formatWhen(event.occurredAt, timeZone)} · {sourceLabels[event.source] ?? event.source}{event.actorRole ? ` · ${event.actorRole}` : ''}{event.reason ? ` · ${event.reason}` : ''}</span>
                <span className={`status-pill ${severityTone[event.severity]}`}>{severityLabels[event.severity]}</span></div></li>)}</ul>
              {pages.isError ? <Notice tone="error">{errorMessage(pages.error)}</Notice> : null}
              {pages.hasNextPage ? <button className="button button-secondary" type="button" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
                {pages.isFetchingNextPage ? 'Carregando…' : 'Carregar eventos anteriores'}</button> : null}</>}
    {canExport ? <ExportForm tenantId={tenantId} timeZone={timeZone} /> : null}
  </div>;
}

function download(result: SecurityLogExport) {
  const blob = new Blob([JSON.stringify(result.raw, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = exportFileName(result.manifest.tenantId, result.manifest.fromSeq, result.manifest.toSeq);
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function ExportForm({ tenantId, timeZone }: { tenantId: string; timeZone: string }) {
  const scope = usePartnerScope();
  const [draft, setDraft] = useState<ExportDraft>({ reason: '', fromSeq: '', toSeq: '' });
  const [problem, setProblem] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<SecurityLogExport | null>(null);
  const set = (patch: Partial<ExportDraft>) => setDraft((current) => ({ ...current, ...patch }));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    const body = exportBody(draft);
    if (typeof body === 'string') { setProblem(body); return; }
    setProblem(null); setFailure(null); setDone(null); setPending(true);
    try {
      const result = await securityLog.export(tenantId, body);
      setDone(result); download(result);
    } catch (error) {
      setFailure(error);
      if ((error as { code?: string }).code === 'MFA_REQUIRED') scope.requireMfa();
    } finally { setPending(false); }
  }
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-labelledby={`security-export-${tenantId}`}>
    <h3 id={`security-export-${tenantId}`}>Exportar com manifesto</h3>
    <p className="field-help">Até 5.000 eventos por vez. Sem intervalo, exporta os mais recentes. O arquivo traz o manifesto com os hashes de início e fim.</p>
    <div className="two-fields"><div><label htmlFor={`export-from-${tenantId}`}>Sequência inicial (opcional)</label>
      <input id={`export-from-${tenantId}`} inputMode="numeric" value={draft.fromSeq} onChange={(event) => set({ fromSeq: event.target.value })} /></div>
      <div><label htmlFor={`export-to-${tenantId}`}>Sequência final (opcional)</label>
        <input id={`export-to-${tenantId}`} inputMode="numeric" value={draft.toSeq} onChange={(event) => set({ toSeq: event.target.value })} /></div></div>
    <label htmlFor={`export-reason-${tenantId}`}>Motivo da exportação</label>
    <textarea id={`export-reason-${tenantId}`} rows={2} maxLength={500} value={draft.reason} onChange={(event) => set({ reason: event.target.value })} />
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    {failure ? <Notice tone="error">{errorMessage(failure)}{(failure as { uncertain?: boolean }).uncertain ? ' A exportação pode ter sido registrada; consulte o log antes de repetir.' : ''}</Notice> : null}
    {done ? <Notice tone="success">Exportados {done.manifest.count} eventos (sequência {done.manifest.fromSeq} a {done.manifest.toSeq}) em {formatWhen(done.manifest.exportedAt, timeZone)}.</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Exportando…' : 'Exportar'}</button></div>
  </form>;
}
