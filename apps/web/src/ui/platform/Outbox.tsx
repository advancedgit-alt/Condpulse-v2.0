import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { platform, type OutboxTask, type OutboxTenantQueue } from '../../api/platform';
import { displayText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { PLATFORM_ZONE, isUncertain, platformKey, react, short, useAccessGuard, usePlatform, type Outcome } from './shared';

/**
 * Fila de tarefas (CP-034): por condomínio, quantas tarefas esperam, há quanto tempo a mais antiga pronta espera (alerta
 * acima de 60 s ou com arrendamento vencido), falhas e ignoradas. Devolver para a fila exige motivo, que fica na
 * auditoria da plataforma; o conteúdo das tarefas nunca aparece aqui.
 */
type TaskState = 'FAILED' | 'SKIPPED';
const stateLabels: Record<TaskState, [string, string]> = { FAILED: ['Com falha', 'Tarefas com falha'], SKIPPED: ['Ignoradas', 'Tarefas ignoradas'] };

export function waitLabel(seconds: number | null): string {
  if (seconds === null) return 'nenhuma pronta esperando';
  if (seconds < 60) return `mais antiga pronta há ${seconds} s`;
  if (seconds < 3600) return `mais antiga pronta há ${Math.floor(seconds / 60)} min`;
  return `mais antiga pronta há ${Math.floor(seconds / 3600)} h`;
}

export function OutboxSection() {
  const scope = usePlatform();
  const queries = useQueryClient();
  const summary = useQuery({ queryKey: platformKey(scope.accountId, 'outbox'), queryFn: ({ signal }) => platform.outbox(signal), refetchInterval: 30_000 });
  const [state, setState] = useState<TaskState>('FAILED');
  const [tenant, setTenant] = useState<string | null>(null);
  const tasks = useQuery({ queryKey: platformKey(scope.accountId, 'outbox-tasks', state, tenant),
    queryFn: ({ signal }) => platform.outboxTasks(state, tenant, signal) });
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const denied = useAccessGuard(summary.error ?? tasks.error);
  if (denied) return null;
  const refresh = () => { void queries.invalidateQueries({ queryKey: platformKey(scope.accountId, 'outbox') });
    void queries.invalidateQueries({ queryKey: platformKey(scope.accountId, 'outbox-tasks') }); };
  const tenants = summary.data?.tenants ?? [];
  const name = (id: string) => tenants.find((item) => item.tenantId === id)?.tenantName ?? `condomínio ${short(id)}`;
  return <section className="management-section" aria-labelledby="outbox-title">
    <div className="section-heading"><div><span className="section-index">04 / FILA</span><h2 id="outbox-title">Fila de tarefas</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={summary.isFetching || tasks.isFetching} onClick={refresh}>Atualizar fila</button></div>
    <p className="field-help">Avisos e passadas do worker por condomínio. Alerta quando a tarefa pronta mais antiga espera mais de 60 s ou um arrendamento venceu. O conteúdo das tarefas não aparece aqui. Horários em {PLATFORM_ZONE}.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {summary.isPending ? <div className="panel"><Loading label="Consultando a fila…" /></div>
      : summary.isError && !summary.data ? <ErrorState error={summary.error} retry={() => void summary.refetch()} />
        : tenants.length === 0 ? <div className="panel"><Empty title="Fila vazia">Nenhum condomínio com tarefa pendente, com falha ou ignorada.</Empty></div>
          : <ol className="management-list" aria-label="Fila por condomínio">{tenants.map((item) => <QueueRow key={item.tenantId} item={item} />)}</ol>}
    <div className="reason-form" role="group" aria-label="Filtro das tarefas">
      <label htmlFor="outbox-state">Situação</label>
      <select id="outbox-state" value={state} onChange={(event) => { setOutcome(null); setState(event.target.value as TaskState); }}>
        {(['FAILED', 'SKIPPED'] as const).map((value) => <option key={value} value={value}>{stateLabels[value][0]}</option>)}
      </select>
      <label htmlFor="outbox-tenant">Condomínio</label>
      <select id="outbox-tenant" value={tenant ?? ''} onChange={(event) => { setOutcome(null); setTenant(event.target.value || null); }}>
        <option value="">Todos</option>
        {tenants.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.tenantName ?? `condomínio ${short(item.tenantId)}`}</option>)}
      </select>
    </div>
    {tasks.isPending ? <div className="panel"><Loading label="Consultando tarefas…" /></div>
      : tasks.isError && !tasks.data ? <ErrorState error={tasks.error} retry={() => void tasks.refetch()} />
        : !tasks.data || tasks.data.tasks.length === 0
          ? <div className="panel"><Empty title={state === 'FAILED' ? 'Nenhuma tarefa com falha' : 'Nenhuma tarefa ignorada'}>{state === 'FAILED'
            ? 'Nenhuma tarefa com falha neste filtro. Falhas aparecem aqui depois das tentativas automáticas.'
            : 'Nenhuma tarefa ignorada neste filtro. Ignorada é evento sem consumidor ou registro já encerrado quando o worker chegou.'}</Empty></div>
          : <ol className="management-list" aria-label={stateLabels[state][1]}>{tasks.data.tasks.map((task) =>
            <TaskRow key={task.taskId} task={task} tenantName={name(task.tenantId)} done={(text) => { setOutcome(text); refresh(); }} />)}</ol>}
    {tasks.data?.truncated ? <Notice tone="warning">A lista mostra as 100 tarefas mais recentes.</Notice> : null}
  </section>;
}

function QueueRow({ item }: { item: OutboxTenantQueue }) {
  return <li className="management-row">
    <div><strong><bdi>{item.tenantName ?? `condomínio ${short(item.tenantId)}`}</bdi></strong>{' '}
      <span className={`status-pill ${item.stale ? 'status-pending' : 'status-ready'}`}>{item.stale ? 'Fila atrasada' : 'Em dia'}</span>
      <span>{item.pending} pendentes ({item.ready} prontas) · {waitLabel(item.oldestReadySeconds)}{item.expiredLeases > 0 ? ` · ${item.expiredLeases} arrendamentos vencidos` : ''}</span>
      <span>{item.failed} com falha · {item.skipped} ignoradas</span></div>
  </li>;
}

function TaskRow({ task, tenantName, done }: { task: OutboxTask; tenantName: string; done(outcome: Outcome): void }) {
  const scope = usePlatform();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const normalized = displayText(reason, 500);
  const id = `replay-${task.taskId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!normalized || busy) return;
    setBusy(true); setFailure(null);
    try {
      await platform.replayTask(task.taskId, normalized);
      setOpen(false); setReason('');
      done({ tone: 'success', text: 'Tarefa devolvida para a fila: o worker processa de novo, sem repetir aviso já entregue.' });
    } catch (error) { react(error, scope); setFailure(error); }
    finally { setBusy(false); }
  }
  return <li className="management-row">
    <div><strong>{task.eventType}</strong> <span><bdi>{tenantName}</bdi> · registro {short(task.aggregateId)} · {task.attempts} tentativas</span>
      <span>Criada em {formatWhen(task.createdAt, PLATFORM_ZONE)}{task.processedAt ? ` · encerrada em ${formatWhen(task.processedAt, PLATFORM_ZONE)}` : ''}</span>
      {task.lastError ? <span>Erro: {task.lastError}</span> : null}</div>
    {!open ? <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => { setFailure(null); setOpen(true); }}>Devolver para a fila</button></div>
      : <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Devolver ${task.eventType} para a fila`}>
        <label htmlFor={id}>Motivo (fica na auditoria da plataforma)</label>
        <input id={id} value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} disabled={busy} autoComplete="off" />
        {failure ? <Notice tone={isUncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}{isUncertain(failure) ? ' Atualize a fila e confira antes de repetir.' : ''}</Notice> : null}
        <div className="button-row">
          <button className="button" type="submit" disabled={!normalized || busy}>{busy ? 'Devolvendo…' : 'Confirmar devolução'}</button>
          <button className="button button-secondary" type="button" disabled={busy} onClick={() => setOpen(false)}>Voltar</button>
        </div>
      </form>}
  </li>;
}
