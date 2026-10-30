import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { moveKinds, moveStates, moves, normalizePlate, type CreateMoveBody, type Move, type MoveKind, type MoveRules, type MoveState } from '../api/moves';
import { displayText, multilineText } from '../api/text';
import { scopeKey, type Scope } from './Operations';
import { Empty, ErrorState, Loading, Notice, errorMessage } from './primitives';
import { addDays, dateInZone, formatWhen, toInstant } from './dateTime';
import { codeOf, repeatFailure, settleFailure, uncertain, usePendingOperation } from './operationIntent';
import { ActionFeedback, ReasonForm, useAccessGuard, useAction, useManagement, managementKey, type ActionResult } from './management/shared';
import { conciergeKey, unitName } from './concierge/shared';
import './management/Management.css';

export const moveKindLabels: Record<MoveKind, string> = {
  MOVE_IN: 'Mudança de entrada', MOVE_OUT: 'Mudança de saída', FURNITURE_DELIVERY: 'Entrega de móveis',
};
export const moveStateLabels: Record<MoveState, [string, string]> = {
  REQUESTED: ['Aguardando a gestão', 'status-pending'], APPROVED: ['Aprovada', 'status-ready'],
  REJECTED: ['Recusada', 'status-neutral'], CANCELLED: ['Cancelada', 'status-neutral'],
};
const weekDayNames = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'];

/** Resumo das regras como a unidade lê antes de pedir. */
export function rulesSummary(rules: MoveRules): string {
  const days = rules.weekDays.length === 7 ? 'todos os dias' : rules.weekDays.map((day) => weekDayNames[day - 1]!.toLowerCase()).join(', ');
  const hours = rules.maxDurationMinutes % 60 === 0 ? `${rules.maxDurationMinutes / 60} h` : `${rules.maxDurationMinutes} min`;
  return `${days}, das ${rules.startTime} às ${rules.endTime}; antecedência mínima de ${rules.minNoticeHours} h; até ${hours} por pedido.`;
}

function MoveDetails({ item, timeZone, showUnit }: { item: Move; timeZone: string; showUnit: boolean }) {
  const [label, tone] = moveStateLabels[item.state];
  return <div><strong>{moveKindLabels[item.kind]}</strong> <span className={`status-pill ${tone}`}>{label}</span>
    {showUnit ? <span>Unidade <bdi>{unitName(item)}</bdi></span> : null}
    <span>{formatWhen(item.startsAt, timeZone)} até {formatWhen(item.endsAt, timeZone)}</span>
    <span>Responsável: <bdi>{item.providerName}</bdi>{item.vehiclePlate ? <> · placa <bdi>{item.vehiclePlate}</bdi></> : null}</span>
    {item.items ? <span>Itens: <bdi>{item.items}</bdi></span> : null}
    {item.decisionNote ? <span>{item.state === 'CANCELLED' ? 'Motivo do cancelamento' : 'Nota da gestão'}: <bdi>{item.decisionNote}</bdi></span> : null}
  </div>;
}

/** Recurso desligado ou vínculo sem acesso (dependente, unidade mudou): a seção some sem erro técnico. */
const hidden = (error: unknown) => ['API_NOT_AVAILABLE', 'UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(codeOf(error));

/**
 * Mudança e entrega de móveis da unidade (CP-042): ler as regras do condomínio, pedir o período aceitando a versão
 * vigente, acompanhar a decisão e retirar o pedido antes do início. A aprovação não reserva elevador nem área.
 */
export function UnitMoves({ scope, timeZone }: { scope: Scope; timeZone: string }) {
  const key = [...scopeKey(scope), 'moves'] as const;
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => moves.unit(scope.tenantId, scope.unitId, signal),
    refetchInterval: (query) => hidden(query.state.error) ? false : 60_000 });
  const rules = useQuery({ queryKey: [...scopeKey(scope), 'move-rules'], queryFn: ({ signal }) => moves.rules(scope.tenantId, signal),
    enabled: !hidden(list.error) });
  const [opening, setOpening] = useState(false);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  if (hidden(list.error) || hidden(rules.error)) return null;
  const current = rules.data ?? null;
  return <section className="dashboard-reservations" aria-labelledby="moves-title">
    <div className="section-heading"><div><span className="section-index">MUDANÇAS</span>
      <h2 id="moves-title">Mudança e entrega de móveis</h2></div>
      <div className="button-row">
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching || rules.isFetching}
          onClick={() => { void list.refetch(); void rules.refetch(); }}>Atualizar mudanças</button>
        {!opening && current ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setOpening(true); }}>Pedir mudança</button> : null}
      </div></div>
    {rules.isPending ? <Loading label="Consultando as regras de mudança…" />
      : rules.isError && !rules.data ? <ErrorState error={rules.error} retry={() => void rules.refetch()} />
        : !current ? <Notice tone="info">A gestão ainda não publicou as regras de mudança. Quando publicar, o pedido fica disponível aqui.</Notice>
          : <div className="panel move-rules" aria-label="Regras de mudança do condomínio">
            <strong>Regras de mudança (versão {current.version})</strong>
            <p className="field-help">{rulesSummary(current)}</p>
            <p className="announcement-body"><bdi>{current.rulesText}</bdi></p>
            <p className="field-help">A aprovação libera o período combinado com a portaria; não reserva elevador nem área.</p>
          </div>}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {opening && current ? <MoveForm scope={scope} rules={current} timeZone={timeZone} close={(result) => {
      setOpening(false);
      if (result) setOutcome(result);
    }} /> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando os pedidos de mudança…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.moves.length === 0 ? <div className="panel"><Empty title="Nenhum pedido de mudança">Os pedidos de mudança e de entrega de móveis da unidade aparecem aqui, com a decisão da gestão.</Empty></div>
          : <ol className="management-list" aria-label="Pedidos de mudança da unidade">{list.data.moves.map((item) => {
            const open = (item.state === 'REQUESTED' || item.state === 'APPROVED') && Date.parse(item.startsAt) > Date.now();
            return <li key={item.moveId} className="management-row">
              <MoveDetails item={item} timeZone={timeZone} showUnit={false} />
              {cancelling === item.moveId ? <CancelMove scope={scope} item={item} close={(result) => {
                setCancelling(null);
                if (result) setOutcome(result);
              }} /> : open ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
                onClick={() => { setOutcome(null); setCancelling(item.moveId); }}>Retirar pedido</button></div> : null}
            </li>;
          })}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 pedidos mais recentes.</Notice> : null}
  </section>;
}

interface MoveDraft { kind: MoveKind; date: string; start: string; end: string; provider: string; plate: string; items: string }

/** Pedido com chave de operação pendente por unidade: fechar e reabrir não cria um segundo pedido depois de resultado incerto. */
function MoveForm({ scope, rules, timeZone, close }: { scope: Scope; rules: MoveRules; timeZone: string;
  close(result: { tone: 'success' | 'info'; text: string } | null): void }) {
  const queries = useQueryClient();
  const intent = usePendingOperation(`${scope.tenantId}:${scope.unitId}:move-request`);
  const pending = intent.lockedBody as (MoveDraft & { version: number }) | null;
  const [draft, setDraft] = useState<MoveDraft>(pending ?? { kind: 'MOVE_IN', date: addDays(dateInZone(timeZone), Math.max(1, Math.ceil(rules.minNoticeHours / 24) + 1)),
    start: rules.startTime, end: rules.endTime, provider: '', plate: '', items: '' });
  // Aceite preso à versão lida: regras novas trazidas por atualização exigem novo aceite.
  const [acceptedVersion, setAcceptedVersion] = useState<number | null>(pending?.version ?? null);
  const accepted = acceptedVersion !== null && acceptedVersion === rules.version;
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const locked = intent.locked;
  const disabled = busy || locked || intent.inFlight;
  const provider = displayText(draft.provider, 160);
  const plate = normalizePlate(draft.plate);
  const items = draft.items.trim() ? multilineText(draft.items, 500) : null;
  let window: { startsAt: string; endsAt: string } | null = null;
  let windowProblem: string | null = null;
  try {
    const startsAt = toInstant(`${draft.date}T${draft.start}`, timeZone);
    const endsAt = toInstant(`${draft.date}T${draft.end}`, timeZone);
    if (Date.parse(endsAt) <= Date.parse(startsAt)) windowProblem = 'O fim precisa ser depois do início.';
    else if ((Date.parse(endsAt) - Date.parse(startsAt)) / 60_000 > rules.maxDurationMinutes) windowProblem = `As regras permitem até ${rules.maxDurationMinutes} minutos por pedido.`;
    else window = { startsAt, endsAt };
  } catch (error) { windowProblem = error instanceof Error ? error.message : 'Confira a data e o horário.'; }
  const valid = window !== null && provider !== null && [...provider].length >= 2 && plate !== '' && (draft.items.trim() === '' || items !== null)
    && (accepted || locked);
  const set = (change: Partial<MoveDraft>) => setDraft((value) => ({ ...value, ...change }));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || busy || intent.inFlight) return;
    const version = pending?.version ?? acceptedVersion!;
    const body: CreateMoveBody = { kind: draft.kind, startsAt: window!.startsAt, endsAt: window!.endsAt, providerName: provider!,
      vehiclePlate: plate, items, acceptedRulesVersion: version };
    const operationKey = intent.keyFor(JSON.stringify(body), { ...draft, version });
    setBusy(true); setFailure(null);
    try {
      const result = await moves.create(scope.tenantId, scope.unitId, operationKey, body);
      intent.finish();
      await queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'moves'] });
      close(result.replayed
        ? { tone: 'info', text: `Este pedido já estava enviado (${moveStateLabels[result.move.state][0].toLowerCase()}).` }
        : { tone: 'success', text: `Pedido de ${moveKindLabels[result.move.kind].toLowerCase()} enviado para ${formatWhen(result.move.startsAt, timeZone)}. A gestão decide por aqui.` });
    } catch (error) {
      setFailure(repeatFailure(error));
      settleFailure(error, intent.locked, intent, intent.setLocked);
      if (codeOf(error) === 'MOVE_RULES_CHANGED') { setAcceptedVersion(null); void queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'move-rules'] }); }
      void queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'moves'] });
    } finally { intent.settle(); setBusy(false); }
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Pedir mudança">
    <label htmlFor="move-kind">Tipo</label>
    <select id="move-kind" value={draft.kind} disabled={disabled} onChange={(event) => set({ kind: event.target.value as MoveKind })}>
      {moveKinds.map((value) => <option key={value} value={value}>{moveKindLabels[value]}</option>)}
    </select>
    <label htmlFor="move-date">Data</label>
    <input id="move-date" type="date" value={draft.date} disabled={disabled} onChange={(event) => set({ date: event.target.value })} />
    <label htmlFor="move-start">Início</label>
    <input id="move-start" type="time" step={60} value={draft.start} disabled={disabled} onChange={(event) => set({ start: event.target.value })} />
    <label htmlFor="move-end">Fim</label>
    <input id="move-end" type="time" step={60} value={draft.end} disabled={disabled} onChange={(event) => set({ end: event.target.value })} />
    <span className="field-help">Horário do condomínio ({timeZone}). {rulesSummary(rules)}</span>
    {windowProblem ? <span className="field-error" role="status">{windowProblem}</span> : null}
    <label htmlFor="move-provider">Responsável ou transportadora</label>
    <input id="move-provider" maxLength={160} autoComplete="off" value={draft.provider} disabled={disabled} onChange={(event) => set({ provider: event.target.value })} />
    <label htmlFor="move-plate">Placa do veículo (opcional)</label>
    <input id="move-plate" maxLength={10} autoComplete="off" value={draft.plate} disabled={disabled} onChange={(event) => set({ plate: event.target.value })} />
    {plate === '' ? <span className="field-error" role="status">Informe a placa com 5 a 8 letras e números, ou deixe em branco.</span> : null}
    <label htmlFor="move-items">Itens (opcional)</label>
    <textarea id="move-items" maxLength={500} rows={2} value={draft.items} disabled={disabled} onChange={(event) => set({ items: event.target.value })} />
    <label className="weekday-option"><input type="checkbox" checked={accepted || locked} disabled={disabled} onChange={(event) => setAcceptedVersion(event.target.checked ? rules.version : null)} />
      Li e aceito as regras de mudança (versão {pending?.version ?? rules.version})</label>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    {intent.completedElsewhere ? <Notice>Este pedido já foi enviado pelo formulário aberto antes. Enviar de novo só confirma o mesmo pedido, sem criar outro; confira a lista.</Notice> : null}
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o pedido ficou travado, mesmo se você fechar o formulário. Use “Tentar confirmar o mesmo pedido”: se ele já foi enviado, nada novo é gravado.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || busy || intent.inFlight}>{busy || intent.inFlight ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo pedido' : 'Enviar pedido de mudança'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={busy}>Voltar</button>
    </div>
  </form>;
}

function CancelMove({ scope, item, close }: { scope: Scope; item: Move; close(result: { tone: 'success' | 'info'; text: string } | null): void }) {
  const queries = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function confirm() {
    if (busy) return;
    setBusy(true); setFailure(null);
    try {
      await moves.cancel(scope.tenantId, item.moveId, item.version);
      close({ tone: 'success', text: 'Pedido de mudança retirado.' });
    } catch (error) { setFailure(error); }
    finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scopeKey(scope), 'moves'] }); }
  }
  return <div className="reason-form" role="group" aria-label="Confirmar retirada do pedido">
    <p>Retirar este pedido? A gestão e a portaria deixam de considerar o período.</p>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}</Notice> : null}
    <div className="button-row">
      <button className="button button-danger" type="button" disabled={busy} onClick={() => void confirm()}>{busy ? 'Retirando…' : 'Confirmar retirada'}</button>
      <button className="button button-secondary" type="button" disabled={busy} onClick={() => close(null)}>Voltar</button>
    </div>
  </div>;
}

const accessCodes = ['MFA_REQUIRED', 'STAFF_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'];
const filters: { value: MoveState | 'ALL'; label: string }[] = [
  { value: 'REQUESTED', label: 'Aguardando decisão' }, ...moveStates.filter((state) => state !== 'REQUESTED')
    .map((state) => ({ value: state, label: moveStateLabels[state][0] + 's' })), { value: 'ALL', label: 'Todos (últimos 30 dias e próximos)' },
];

/** Gestão (CP-042): publica as regras de mudança e decide os pedidos; recusa e cancelamento exigem motivo. */
export function MoveManagementSection() {
  const scope = useManagement();
  const [filter, setFilter] = useState<MoveState | 'ALL'>('REQUESTED');
  const list = useQuery({ queryKey: managementKey(scope, 'moves', filter),
    queryFn: ({ signal }) => moves.managed(scope.tenantId, filter === 'ALL' ? null : filter, signal) });
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  const [acting, setActing] = useState<{ moveId: string; action: 'approve' | 'reject' | 'cancel' } | null>(null);
  if (useAccessGuard(list.error)) return null;
  const unavailable = codeOf(list.error) === 'API_NOT_AVAILABLE';
  return <section className="management-section" aria-labelledby="moves-management-title">
    <div className="section-heading"><div><span className="section-index">MUDANÇAS</span><h2 id="moves-management-title">Mudanças e entregas de móveis</h2></div>
      <div className="button-row">
        <label className="sr-only" htmlFor="moves-filter">Filtrar pedidos de mudança</label>
        <select id="moves-filter" value={filter} onChange={(event) => { setActing(null); setFilter(event.target.value as MoveState | 'ALL'); }}>
          {filters.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button>
      </div></div>
    {filter === 'REQUESTED' ? <p className="field-help">Pedidos sem decisão cujo período já começou não podem mais ser decididos: saem desta fila e continuam em “Todos”.</p> : null}
    {unavailable ? <div className="panel"><Empty title="Mudanças indisponíveis">As mudanças ainda não estão ativas neste ambiente.</Empty></div> : <>
      <MoveRulesEditor />
      {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
      {list.isPending ? <div className="panel"><Loading label="Consultando pedidos de mudança…" /></div>
        : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
          : !list.data || list.data.moves.length === 0 ? <div className="panel"><Empty title="Nenhum pedido de mudança">Nenhum pedido neste filtro.</Empty></div>
            : <ol className="management-list" aria-label="Pedidos de mudança">{list.data.moves.map((item) => {
              const current = acting?.moveId === item.moveId ? acting.action : null;
              return <li key={item.moveId} className="management-row">
                <MoveDetails item={item} timeZone={scope.timeZone} showUnit />
                {current ? <MoveDecision item={item} action={current} close={(result) => { setActing(null); if (result) setOutcome(result); }} />
                  : <div className="button-row">
                    {item.state === 'REQUESTED' ? <>
                      <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setActing({ moveId: item.moveId, action: 'approve' }); }}>Aprovar</button>
                      <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setActing({ moveId: item.moveId, action: 'reject' }); }}>Recusar</button></> : null}
                    {item.state === 'REQUESTED' || item.state === 'APPROVED' ? <button className="button button-secondary button-compact" type="button"
                      onClick={() => { setOutcome(null); setActing({ moveId: item.moveId, action: 'cancel' }); }}>Cancelar pedido</button> : null}
                  </div>}
              </li>;
            })}</ol>}
      {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 primeiros pedidos deste filtro.</Notice> : null}
    </>}
  </section>;
}

function MoveDecision({ item, action, close }: { item: Move; action: 'approve' | 'reject' | 'cancel'; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const run = useAction();
  const labels = { approve: ['Nota para a unidade', 'Confirmar aprovação'], reject: ['Motivo da recusa', 'Confirmar recusa'],
    cancel: ['Motivo do cancelamento', 'Confirmar cancelamento'] } as const;
  async function submit(note: string | null) {
    const ok = await run.run(() => action === 'cancel' ? moves.cancel(scope.tenantId, item.moveId, item.version, note)
      : moves.decide(scope.tenantId, item.moveId, action === 'approve' ? 'APPROVE' : 'REJECT', note, item.version),
    (result) => ({ tone: 'success', text: `Pedido de ${unitName(result)}: ${moveStateLabels[result.state][0].toLowerCase()}.` }),
    () => queries.invalidateQueries({ queryKey: managementKey(scope, 'moves') }));
    if (ok) close({ tone: 'success', text: action === 'approve' ? 'Mudança aprovada. A portaria já vê o período.'
      : action === 'reject' ? 'Pedido recusado. A unidade vê o motivo.' : 'Pedido cancelado. A unidade vê o motivo.' });
    else void queries.invalidateQueries({ queryKey: managementKey(scope, 'moves') });
  }
  return <div>
    <ReasonForm id={`move-${action}-${item.moveId}`} label={labels[action][0]} required={action !== 'approve'} pending={run.state.pending}
      confirmLabel={labels[action][1]} tone={action === 'approve' ? 'primary' : 'danger'} submit={(note) => void submit(note)} cancel={() => close(null)} />
    <ActionFeedback state={run.state} success={false} />
  </div>;
}

function MoveRulesEditor() {
  const scope = useManagement();
  const queries = useQueryClient();
  const rules = useQuery({ queryKey: managementKey(scope, 'move-rules'), queryFn: ({ signal }) => moves.rules(scope.tenantId, signal) });
  const [editing, setEditing] = useState(false);
  if (accessCodes.includes(codeOf(rules.error))) return null;
  if (rules.isPending) return <div className="panel"><Loading label="Consultando as regras de mudança…" /></div>;
  if (rules.isError && !rules.data) return <ErrorState error={rules.error} retry={() => void rules.refetch()} />;
  const current = rules.data ?? null;
  return <div className="panel move-rules" aria-label="Regras de mudança">
    <strong>{current ? `Regras de mudança (versão ${current.version})` : 'Regras de mudança ainda não publicadas'}</strong>
    {current ? <><p className="field-help">{rulesSummary(current)}</p><p className="announcement-body"><bdi>{current.rulesText}</bdi></p></>
      : <p className="field-help">Sem regras publicadas, as unidades não conseguem pedir mudança.</p>}
    {editing ? <RulesForm current={current} close={(saved) => {
      setEditing(false);
      if (saved) void queries.invalidateQueries({ queryKey: managementKey(scope, 'move-rules') });
    }} /> : <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => setEditing(true)}>
      {current ? 'Editar regras' : 'Publicar regras'}</button></div>}
  </div>;
}

function RulesForm({ current, close }: { current: MoveRules | null; close(saved: boolean): void }) {
  const scope = useManagement();
  const action = useAction();
  const [text, setText] = useState(current?.rulesText ?? '');
  const [days, setDays] = useState<number[]>(current?.weekDays ?? [1, 2, 3, 4, 5, 6]);
  const [start, setStart] = useState(current?.startTime ?? '08:00');
  const [end, setEnd] = useState(current?.endTime ?? '18:00');
  const [notice, setNotice] = useState(String(current?.minNoticeHours ?? 48));
  const [duration, setDuration] = useState(String(current?.maxDurationMinutes ?? 240));
  const normalized = multilineText(text, 2000);
  const noticeHours = /^\d{1,3}$/.test(notice) ? Number(notice) : -1;
  const durationMinutes = /^\d{1,3}$/.test(duration) ? Number(duration) : -1;
  const valid = normalized !== null && days.length > 0 && /^\d\d:\d\d$/.test(start) && /^\d\d:\d\d$/.test(end) && start < end
    && noticeHours >= 0 && noticeHours <= 720 && durationMinutes >= 30 && durationMinutes <= 720;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || action.state.pending) return;
    const ok = await action.run(() => moves.updateRules(scope.tenantId, { rulesText: normalized!, weekDays: [...days].sort(), startTime: start, endTime: end,
      minNoticeHours: noticeHours, maxDurationMinutes: durationMinutes, expectedVersion: current?.version ?? null }), 'Regras publicadas.');
    if (ok) close(true);
  }
  const toggle = (day: number, on: boolean) => setDays((value) => on ? [...new Set([...value, day])] : value.filter((item) => item !== day));
  const pending = action.state.pending;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Editar regras de mudança">
    <label htmlFor="move-rules-text">Regras exibidas às unidades</label>
    <textarea id="move-rules-text" maxLength={2000} rows={4} value={text} disabled={pending} onChange={(event) => setText(event.target.value)} />
    <fieldset className="weekday-picker" disabled={pending}><legend>Dias permitidos</legend>
      {weekDayNames.map((name, index) => <label key={name}><input type="checkbox" checked={days.includes(index + 1)}
        onChange={(event) => toggle(index + 1, event.target.checked)} />{name}</label>)}
    </fieldset>
    <label htmlFor="move-rules-start">Início do horário</label>
    <input id="move-rules-start" type="time" step={60} value={start} disabled={pending} onChange={(event) => setStart(event.target.value)} />
    <label htmlFor="move-rules-end">Fim do horário</label>
    <input id="move-rules-end" type="time" step={60} value={end} disabled={pending} onChange={(event) => setEnd(event.target.value)} />
    <label htmlFor="move-rules-notice">Antecedência mínima (horas)</label>
    <input id="move-rules-notice" inputMode="numeric" value={notice} disabled={pending} onChange={(event) => setNotice(event.target.value)} />
    <label htmlFor="move-rules-duration">Duração máxima por pedido (minutos)</label>
    <input id="move-rules-duration" inputMode="numeric" value={duration} disabled={pending} onChange={(event) => setDuration(event.target.value)} />
    <span className="field-help">Antecedência de 0 a 720 horas; duração de 30 a 720 minutos. Mudar as regras pede novo aceite nos pedidos seguintes.</span>
    <ActionFeedback state={action.state} success={false} />
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || pending}>{pending ? 'Salvando…' : 'Salvar regras'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={() => close(false)}>Voltar</button>
    </div>
  </form>;
}

/** Portaria (CP-042): mudanças aprovadas do dia escolhido no fuso do condomínio. */
export function ConciergeMoves() {
  const scope = useManagement();
  const [date, setDate] = useState(() => dateInZone(scope.timeZone));
  let range: { from: string; until: string } | null = null;
  try { range = { from: toInstant(`${date}T00:00`, scope.timeZone), until: toInstant(`${addDays(date, 1)}T00:00`, scope.timeZone) }; }
  catch { range = null; }
  const list = useQuery({ queryKey: conciergeKey(scope, 'moves', range?.from ?? ''), enabled: range !== null,
    queryFn: ({ signal }) => moves.concierge(scope.tenantId, range!.from, range!.until, signal), refetchInterval: 60_000 });
  if (useAccessGuard(list.error)) return null;
  if (codeOf(list.error) === 'API_NOT_AVAILABLE') return null;
  return <section className="management-section" aria-labelledby="concierge-moves-title">
    <div className="section-heading"><div><span className="section-index">MUDANÇAS</span><h2 id="concierge-moves-title">Mudanças aprovadas</h2></div>
      <div className="button-row">
        <label htmlFor="concierge-moves-date">Dia</label>
        <input id="concierge-moves-date" type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching || range === null} onClick={() => void list.refetch()}>Atualizar</button>
      </div></div>
    <p className="field-help">Somente pedidos aprovados pela gestão. Confira o responsável e a placa na chegada.</p>
    {range === null ? <Notice tone="error">Escolha um dia válido.</Notice>
      : list.isPending ? <div className="panel"><Loading label="Consultando mudanças…" /></div>
        : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
          : !list.data || list.data.moves.length === 0 ? <div className="panel"><Empty title="Nenhuma mudança aprovada">Nenhuma mudança aprovada neste dia.</Empty></div>
            : <ol className="management-list" aria-label="Mudanças aprovadas do dia">{list.data.moves.map((item) =>
              <li key={item.moveId} className="management-row"><MoveDetails item={item} timeZone={scope.timeZone} showUnit /></li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 primeiras mudanças do dia.</Notice> : null}
  </section>;
}
