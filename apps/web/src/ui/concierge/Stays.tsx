import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { stays, type Stay } from '../../api/stays';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { dateInZone, formatWhen } from '../dateTime';
import { ActionFeedback, useAccessGuard, useAction, useManagement } from '../management/shared';
import { StayList } from '../stays/StayList';
import { dayCloseDate, dayCloseRange, justificationReason, staySummary } from '../stays/model';
import { codeOf, conciergeKey } from './shared';

/**
 * Permanências na portaria (CPX-017 e CPX-019): quem está no condomínio agora, com as vencidas em destaque e a origem de
 * cada dado, justificativa com motivo e o relatório de fechamento do dia. Cada consulta grava SENSITIVE_VIEWED no Log de
 * Segurança, por isso a lista só é consultada ao abrir e ao pedir "Atualizar" (sem consulta automática).
 */
export function ConciergeStays() {
  return <>
    <StaysNow />
    <DayClose />
  </>;
}

const staysKey = (scope: { accountId: string; tenantId: string }, ...parts: unknown[]) => conciergeKey(scope, 'stays', ...parts);

function StaysNow() {
  const scope = useManagement();
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info' | 'warning'; text: string } | null>(null);
  const list = useQuery({ queryKey: staysKey(scope, 'open', overdueOnly), queryFn: ({ signal }) => stays.open(scope.tenantId, overdueOnly, signal),
    refetchOnWindowFocus: false });
  if (useAccessGuard(list.error)) return null;
  // Rota inexistente (CP_VISITS_ENABLED desligada no servidor): a seção não aparece.
  if (codeOf(list.error) === 'API_NOT_AVAILABLE') return null;
  return <section className="management-section" aria-labelledby="stays-now-title">
    <div className="section-heading"><div><span className="section-index">PERMANÊNCIAS</span>
      <h2 id="stays-now-title">Quem está no condomínio agora{list.data ? ` · ${staySummary(list.data.stays)}` : ''}</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar permanências</button></div>
    <p className="field-help">Permanências abertas pelas entradas registradas, vencidas primeiro. Cada consulta fica registrada no Log de Segurança.
      {list.data ? ` Consultado às ${formatWhen(list.data.generatedAt, scope.timeZone)}.` : ''}</p>
    <label className="checkbox-row"><input type="checkbox" checked={overdueOnly} onChange={(event) => setOverdueOnly(event.target.checked)} /> Só as vencidas</label>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando permanências…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.stays.length === 0 ? <div className="panel"><Empty icon="door" title={overdueOnly ? 'Nenhuma permanência vencida' : 'Nenhuma permanência aberta'}>
          Cada entrada registrada pela portaria abre uma permanência; a saída registrada a encerra.</Empty></div>
          : <StayList stays={list.data.stays} timeZone={scope.timeZone} label="Permanências abertas"
            action={(stay) => stay.overdue && !stay.justified ? <Justify stay={stay} done={setOutcome} /> : null} />}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 200 primeiras permanências.</Notice> : null}
  </section>;
}

/** Justificativa da permanência vencida (STAY_JUSTIFIED): encerra os alertas; a permanência segue aberta até a saída. */
function Justify({ stay, done }: { stay: Stay; done(result: { tone: 'success' | 'info' | 'warning'; text: string }): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const id = `stay-justification-${stay.stayId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    const value = justificationReason(reason);
    if (typeof value !== 'string') { setProblem(value.problem); return; }
    setProblem(null);
    let failure: unknown = null;
    const ok = await action.run(async () => {
      try { return await stays.justify(scope.tenantId, stay.stayId, value); }
      catch (error) { failure = error; throw error; }
    }, '', async () => {
      await queries.invalidateQueries({ queryKey: staysKey(scope) });
    });
    if (ok) { setOpen(false); done({ tone: 'success', text: `Permanência de ${stay.visitorName ?? 'pessoa'} justificada. Os alertas param; a saída continua pendente.` }); }
    else if (['STAY_ALREADY_JUSTIFIED', 'STAY_NOT_OPEN', 'STAY_NOT_FOUND'].includes(codeOf(failure))) void queries.invalidateQueries({ queryKey: staysKey(scope) });
  }
  if (!open) return <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => { action.clear(); setOpen(true); }}>Justificar</button></div>;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Justificar permanência de ${stay.visitorName ?? 'pessoa'}`}>
    <label htmlFor={id}>Motivo da justificativa</label>
    <input id={id} maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.state.pending} autoComplete="off" />
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    <ActionFeedback state={action.state} success={false} />
    <div className="button-row">
      <button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Registrando…' : 'Registrar justificativa'}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={() => setOpen(false)}>Voltar</button>
    </div>
  </form>;
}

/** CPX-019: entradas do dia sem saída registrada até o fim do dia; o servidor grava a consulta com o hash da lista. */
function DayClose() {
  const scope = useManagement();
  const today = dateInZone(scope.timeZone);
  const range = dayCloseRange(today);
  const [draft, setDraft] = useState(today);
  const [requested, setRequested] = useState<{ date: string; run: number } | null>(null);
  const report = useQuery({ queryKey: staysKey(scope, 'day-close', requested?.date ?? null, requested?.run ?? 0), enabled: requested !== null,
    queryFn: ({ signal }) => stays.dayClose(scope.tenantId, requested!.date, signal), refetchOnWindowFocus: false });
  if (useAccessGuard(report.error)) return null;
  if (codeOf(report.error) === 'API_NOT_AVAILABLE') return <section className="management-section" aria-labelledby="day-close-title">
    <div className="section-heading"><div><span className="section-index">FECHAMENTO</span><h2 id="day-close-title">Fechamento do dia</h2></div></div>
    <p className="field-help">O relatório de fechamento ainda não está ativo neste ambiente.</p></section>;
  const date = dayCloseDate(draft, today);
  return <section className="management-section" aria-labelledby="day-close-title">
    <div className="section-heading"><div><span className="section-index">FECHAMENTO</span><h2 id="day-close-title">Fechamento do dia</h2></div></div>
    <p className="field-help">Lista as entradas do dia sem saída registrada até o fim do dia ({scope.timeZone}). A consulta fica no Log de Segurança com o hash da lista, como evidência.</p>
    <form className="reservation-filter" aria-label="Relatório de fechamento do dia" onSubmit={(event) => { event.preventDefault(); if (date) setRequested((current) => ({ date, run: (current?.run ?? 0) + 1 })); }}>
      <label htmlFor="day-close-date">Dia</label>
      <input id="day-close-date" type="date" value={draft} min={range.min} max={range.max} onChange={(event) => setDraft(event.target.value)} />
      <button className="button button-compact" type="submit" disabled={!date || report.isFetching}>Gerar relatório</button>
      {report.data ? <button className="button button-secondary button-compact" type="button" onClick={() => window.print()}>Imprimir</button> : null}
    </form>
    {requested === null ? null : report.isPending ? <div className="panel"><Loading label="Gerando relatório…" /></div>
      : report.isError && !report.data ? <ErrorState error={report.error} retry={() => void report.refetch()} />
        : report.data ? <>
          <Notice tone={report.data.entries.length === 0 ? 'success' : 'warning'}>
            {report.data.entries.length === 0 ? `Dia ${report.data.date}: todas as entradas têm saída registrada.`
              : `Dia ${report.data.date}: ${report.data.entries.length} ${report.data.entries.length === 1 ? 'entrada' : 'entradas'} sem saída registrada até o fim do dia; ${report.data.stillOpen} ainda ${report.data.stillOpen === 1 ? 'aberta' : 'abertas'}.`}
            {' '}Gerado às {formatWhen(report.data.generatedAt, scope.timeZone)}. Hash da lista: <code>{report.data.listHash.slice(0, 16)}…</code></Notice>
          {report.data.entries.length > 0 ? <StayList stays={report.data.entries} timeZone={scope.timeZone} label={`Entradas sem saída em ${report.data.date}`} /> : null}
          {report.data.truncated ? <Notice tone="warning">O relatório mostra as 500 primeiras entradas.</Notice> : null}
        </> : null}
  </section>;
}
