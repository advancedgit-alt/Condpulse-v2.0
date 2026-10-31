import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { newOperationKey } from '../../api/client';
import {
  billingTypes, chargeKinds, chargeStates, finance, formatCents, formatDay, parseCents,
  type BillingType, type ChargeKind, type ChargeState, type FinanceCharge, type FinanceUnit,
} from '../../api/finance';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { dateInZone } from '../dateTime';
import { codeOf } from '../operationIntent';
import { ActionFeedback, ReasonForm, managementKey, useAccessGuard, useAction, useManagement, roleLabels } from './shared';

export const kindLabels: Record<ChargeKind, string> = { CONDO_FEE: 'Taxa condominial', RESERVATION: 'Reserva', FINE: 'Multa', OTHER: 'Outro' };
export const billingLabels: Record<BillingType, string> = { BOLETO: 'Boleto', PIX: 'PIX' };
export const chargeStateLabels: Record<ChargeState, [string, string]> = {
  PENDING_ISSUE: ['Aguardando emissão', 'status-pending'], OPEN: ['Em aberto', 'status-pending'], OVERDUE: ['Vencida', 'status-neutral'],
  PAID: ['Paga', 'status-ready'], CANCELLED: ['Cancelada', 'status-neutral'], REFUNDED: ['Estornada', 'status-neutral'],
};
const views = [
  { id: 'cobrancas', label: 'Cobranças' }, { id: 'nova', label: 'Nova cobrança' }, { id: 'pagadores', label: 'Pagadores' },
  { id: 'recorrentes', label: 'Taxas recorrentes' }, { id: 'inadimplencia', label: 'Inadimplência' }, { id: 'relatorio', label: 'Relatório' },
] as const;
type View = typeof views[number]['id'];
const unitName = (unit: { unitLabel: string; blockName: string | null }) => unit.blockName ? `${unit.blockName} · ${unit.unitLabel}` : unit.unitLabel;

/**
 * Financeiro do condomínio (ADR-0008, Asaas sandbox). Cada ação com motivo fica na auditoria; o servidor confere AAL2 e
 * o banco repete papel e transições. A chave do Asaas nunca chega ao navegador; documentos aparecem mascarados.
 */
export function FinanceSection() {
  const [view, setView] = useState<View>('cobrancas');
  return <section className="management-section" aria-labelledby="finance-title">
    <div className="section-heading"><div><span className="section-index">FINANCEIRO</span><h2 id="finance-title">Financeiro do condomínio</h2></div></div>
    <p className="field-help">Cobranças por boleto ou PIX emitidas pelo Asaas (ambiente de testes). A unidade vê só cobranças emitidas e pega a segunda via;
      proprietário de unidade alugada não vê o financeiro da unidade.</p>
    <div className="button-row" role="group" aria-label="Seções do financeiro">{views.map((item) =>
      <button key={item.id} type="button" className={`button button-compact ${view === item.id ? '' : 'button-secondary'}`} aria-pressed={view === item.id}
        onClick={() => setView(item.id)}>{item.label}</button>)}</div>
    {view === 'cobrancas' ? <ChargeList /> : null}
    {view === 'nova' ? <ChargeForm done={() => setView('cobrancas')} /> : null}
    {view === 'pagadores' ? <Payers /> : null}
    {view === 'recorrentes' ? <RecurringFees /> : null}
    {view === 'inadimplencia' ? <DelinquencyView /> : null}
    {view === 'relatorio' ? <ReportView /> : null}
  </section>;
}

function Unavailable() {
  return <div className="panel"><Empty icon="card" title="Financeiro indisponível">O financeiro ainda não está ativo neste ambiente.</Empty></div>;
}

function ChargeList() {
  const scope = useManagement();
  const [state, setState] = useState<ChargeState | ''>('');
  const list = useQuery({ queryKey: managementKey(scope, 'finance', 'charges', state),
    queryFn: ({ signal }) => finance.charges(scope.tenantId, state || null, signal) });
  if (useAccessGuard(list.error)) return null;
  if (codeOf(list.error) === 'API_NOT_AVAILABLE') return <Unavailable />;
  return <div className="panel">
    <div className="button-row">
      <label htmlFor="finance-state">Situação</label>
      <select id="finance-state" value={state} onChange={(event) => setState(event.target.value as ChargeState | '')}>
        <option value="">Todas</option>
        {chargeStates.map((item) => <option key={item} value={item}>{chargeStateLabels[item][0]}</option>)}
      </select>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button>
    </div>
    {list.isPending ? <Loading label="Consultando cobranças…" />
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.charges.length === 0 ? <Empty icon="card" title="Nenhuma cobrança">Nenhuma cobrança nesta situação.</Empty>
          : <ul className="management-list" aria-label="Cobranças">{list.data.charges.map((item) => <ChargeRow key={item.chargeId} charge={item}
            refresh={async () => { await list.refetch(); }} />)}</ul>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 200 cobranças de vencimento mais recente.</Notice> : null}
  </div>;
}

function ChargeRow({ charge, refresh }: { charge: FinanceCharge; refresh(): Promise<void> }) {
  const scope = useManagement();
  const action = useAction();
  const queries = useQueryClient();
  const [cancelling, setCancelling] = useState(false);
  const [label, tone] = chargeStateLabels[charge.state];
  const after = async () => { setCancelling(false); await queries.invalidateQueries({ queryKey: managementKey(scope, 'finance') }); await refresh(); };
  const cancellable = charge.state === 'PENDING_ISSUE' || charge.state === 'OPEN' || charge.state === 'OVERDUE';
  return <li className="management-row">
    <div><strong><bdi>{charge.description}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
      <span>Unidade <bdi>{unitName(charge)}</bdi> · {kindLabels[charge.kind]} · {billingLabels[charge.billingType]} · {formatCents(charge.amountCents)}
        · vence em {formatDay(charge.dueDate)}</span>
      {charge.paidAt ? <span>Paga em {formatDay(charge.paidAt)}{charge.paidCents !== null ? ` · ${formatCents(charge.paidCents)}` : ''}</span> : null}
      {charge.issueError ? <span>Emissão não concluída ({charge.issueError === 'ASAAS_REJECTED' ? 'recusada pelo Asaas' : 'Asaas indisponível'}). Tente de novo.</span> : null}
      {charge.cancelReason ? <span>Motivo do cancelamento: <bdi>{charge.cancelReason}</bdi></span> : null}
      {charge.asaasStatus ? <span>Situação no Asaas: {charge.asaasStatus}</span> : null}
      {charge.invoiceUrl ? <a href={charge.invoiceUrl} target="_blank" rel="noopener noreferrer">Fatura no Asaas (segunda via)</a> : null}</div>
    <div className="button-row">
      {charge.state === 'PENDING_ISSUE' ? <button className="button button-compact" type="button" disabled={action.state.pending}
        onClick={() => void action.run(() => finance.issue(scope.tenantId, charge.chargeId),
          (issued) => issued.state === 'PENDING_ISSUE' ? { tone: 'warning', text: 'O Asaas ainda não emitiu. Tente novamente mais tarde.' }
            : { tone: 'success', text: 'Cobrança emitida.' }, after)}>Emitir</button> : null}
      {charge.state === 'OPEN' || charge.state === 'OVERDUE' || charge.state === 'PAID' ? <button className="button button-secondary button-compact" type="button"
        disabled={action.state.pending} onClick={() => void action.run(() => finance.sync(scope.tenantId, charge.chargeId),
          (synced) => ({ tone: 'info', text: `Situação conferida no Asaas: ${chargeStateLabels[synced.state][0]}.` }), after)}>Sincronizar</button> : null}
      {cancellable && !cancelling ? <button className="text-button danger-text" type="button" disabled={action.state.pending}
        onClick={() => { action.clear(); setCancelling(true); }}>Cancelar cobrança</button> : null}
    </div>
    {cancelling ? <ReasonForm id={`cancel-charge-${charge.chargeId}`} label="Motivo do cancelamento" required pending={action.state.pending}
      confirmLabel="Confirmar cancelamento" tone="danger" cancel={() => setCancelling(false)}
      submit={(reason) => { if (reason) void action.run(() => finance.cancel(scope.tenantId, charge.chargeId, charge.version, reason),
        'Cobrança cancelada no Asaas e no CondPulse.', after); }} /> : null}
    <ActionFeedback state={action.state} />
  </li>;
}

function today(timeZone: string): string { return dateInZone(timeZone); }

function ChargeForm({ done }: { done(): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const units = useQuery({ queryKey: managementKey(scope, 'finance', 'units'), queryFn: ({ signal }) => finance.units(scope.tenantId, signal) });
  const [key, setKey] = useState(newOperationKey);
  const [draft, setDraft] = useState({ unitId: '', kind: 'CONDO_FEE' as ChargeKind, billingType: 'BOLETO' as BillingType, description: '',
    amount: '', dueDate: '', reason: '' });
  if (useAccessGuard(units.error)) return null;
  if (codeOf(units.error) === 'API_NOT_AVAILABLE') return <Unavailable />;
  const withPayer = (units.data ?? []).filter((unit) => unit.payer);
  const cents = parseCents(draft.amount);
  const valid = draft.unitId && cents !== null && cents >= 500 && draft.dueDate >= today(scope.timeZone) && draft.description.trim().length >= 3
    && draft.reason.trim().length >= 3;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || cents === null) return;
    const ok = await action.run(() => finance.create(scope.tenantId, key, { unitId: draft.unitId, kind: draft.kind, billingType: draft.billingType,
      description: draft.description.trim(), amountCents: cents, dueDate: draft.dueDate, reason: draft.reason.trim() }),
      (created) => created.state === 'PENDING_ISSUE'
        ? { tone: 'warning', text: 'Cobrança registrada, mas o Asaas ainda não emitiu. Use "Emitir" na lista para tentar de novo.' }
        : { tone: 'success', text: `Cobrança emitida: ${formatCents(created.amountCents)}, vencimento ${formatDay(created.dueDate)}.` },
      async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'finance') }); });
    // Mesma chave só para repetir exatamente o mesmo envio depois de falha; sucesso libera uma nova.
    if (ok) { setKey(newOperationKey()); setDraft({ ...draft, description: '', amount: '', reason: '' }); }
  }
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-label="Nova cobrança">
    {units.isPending ? <Loading label="Consultando unidades…" /> : units.isError ? <ErrorState error={units.error} retry={() => void units.refetch()} /> : null}
    {units.data && withPayer.length === 0 ? <Notice tone="warning">Nenhuma unidade tem pagador. Cadastre o pagador em "Pagadores".</Notice> : null}
    <label htmlFor="charge-unit">Unidade</label>
    <select id="charge-unit" required value={draft.unitId} onChange={(event) => setDraft({ ...draft, unitId: event.target.value })}>
      <option value="">Selecione a unidade</option>
      {withPayer.map((unit) => <option key={unit.unitId} value={unit.unitId}>{unitName(unit)} — {unit.payer!.legalName}</option>)}
    </select>
    <label htmlFor="charge-kind">Tipo</label>
    <select id="charge-kind" value={draft.kind} onChange={(event) => setDraft({ ...draft, kind: event.target.value as ChargeKind })}>
      {chargeKinds.map((kind) => <option key={kind} value={kind}>{kindLabels[kind]}</option>)}
    </select>
    <label htmlFor="charge-billing">Forma de pagamento</label>
    <select id="charge-billing" value={draft.billingType} onChange={(event) => setDraft({ ...draft, billingType: event.target.value as BillingType })}>
      {billingTypes.map((type) => <option key={type} value={type}>{billingLabels[type]}</option>)}
    </select>
    <label htmlFor="charge-description">Descrição (aparece para a unidade e no Asaas)</label>
    <input id="charge-description" maxLength={200} required value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} />
    <label htmlFor="charge-amount">Valor (R$)</label>
    <input id="charge-amount" inputMode="decimal" required placeholder="150,00" value={draft.amount} onChange={(event) => setDraft({ ...draft, amount: event.target.value })}
      aria-describedby="charge-amount-help" />
    <span id="charge-amount-help" className="field-help">De R$ 5,00 a R$ 1.000.000,00.</span>
    <label htmlFor="charge-due">Vencimento</label>
    <input id="charge-due" type="date" required min={today(scope.timeZone)} value={draft.dueDate} onChange={(event) => setDraft({ ...draft, dueDate: event.target.value })} />
    <label htmlFor="charge-reason">Motivo (auditoria)</label>
    <textarea id="charge-reason" rows={2} maxLength={480} required value={draft.reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} />
    <div className="button-row">
      <button className="button" type="submit" disabled={action.state.pending || !valid}>{action.state.pending ? 'Enviando…' : 'Criar e emitir'}</button>
      <button className="button button-secondary" type="button" onClick={done} disabled={action.state.pending}>Ver cobranças</button>
    </div>
    <ActionFeedback state={action.state} />
  </form>;
}

function Payers() {
  const scope = useManagement();
  const units = useQuery({ queryKey: managementKey(scope, 'finance', 'units'), queryFn: ({ signal }) => finance.units(scope.tenantId, signal) });
  if (useAccessGuard(units.error)) return null;
  if (codeOf(units.error) === 'API_NOT_AVAILABLE') return <Unavailable />;
  return <div className="panel">
    <p className="field-help">O pagador é o proprietário ou o responsável ativo da unidade. Unidade alugada: a gestão escolhe quem paga; se for o
      proprietário, ele não vê a cobrança no aplicativo (envie o link da fatura por outro canal).</p>
    {units.isPending ? <Loading label="Consultando unidades…" /> : units.isError && !units.data ? <ErrorState error={units.error} retry={() => void units.refetch()} />
      : !units.data || units.data.length === 0 ? <Empty icon="building" title="Nenhuma unidade">Cadastre as unidades antes do financeiro.</Empty>
        : <ul className="management-list" aria-label="Pagadores por unidade">{units.data.map((unit) => <PayerRow key={unit.unitId} unit={unit} />)}</ul>}
  </div>;
}

function PayerRow({ unit }: { unit: FinanceUnit }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState({ membershipId: '', legalName: '', document: '', email: '', reason: '' });
  const candidate = unit.candidates.find((item) => item.membershipId === draft.membershipId);
  const valid = candidate && draft.legalName.trim().length >= 3 && draft.document.replace(/\D/g, '').length >= 11 && draft.email.includes('@')
    && draft.reason.trim().length >= 3;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    await action.run(() => finance.setPayer(scope.tenantId, unit.unitId, { membershipId: draft.membershipId, legalName: draft.legalName.trim(),
      document: draft.document.trim(), email: draft.email.trim(), reason: draft.reason.trim() }),
      (payer) => payer.customerSynced ? { tone: 'success', text: `Pagador definido: ${payer.legalName}.` }
        : { tone: 'warning', text: `Pagador definido: ${payer.legalName}. O cadastro no Asaas será concluído na primeira emissão.` },
      async () => { setEditing(false); await queries.invalidateQueries({ queryKey: managementKey(scope, 'finance') }); });
  }
  const id = `payer-${unit.unitId}`;
  return <li className="management-row">
    <div><strong><bdi>{unitName(unit)}</bdi></strong>{unit.rented ? <span className="status-pill status-pending">Alugada</span> : null}
      {unit.payer ? <span>Pagador: <bdi>{unit.payer.legalName}</bdi> ({roleLabels[unit.payer.role]}) · {unit.payer.document} · {unit.payer.email}
        {unit.payer.customerSynced ? '' : ' · cadastro no Asaas pendente'}</span> : <span>Sem pagador.</span>}
      {unit.rented && unit.payer?.role === 'OWNER' ? <span>Unidade alugada com o proprietário como pagador: ele não vê a cobrança no aplicativo.</span> : null}</div>
    {!editing ? <button className="button button-secondary button-compact" type="button" disabled={unit.candidates.length === 0}
      onClick={() => { action.clear(); setEditing(true); }}>{unit.payer ? 'Trocar pagador' : 'Definir pagador'}</button> : null}
    {unit.candidates.length === 0 ? <span className="field-help">Sem proprietário ou responsável ativo na unidade.</span> : null}
    {editing ? <form className="management-form stack-form" onSubmit={(event) => void submit(event)} aria-label={`Pagador da unidade ${unitName(unit)}`}>
      <label htmlFor={`${id}-member`}>Quem paga</label>
      <select id={`${id}-member`} required value={draft.membershipId} onChange={(event) => {
        const chosen = unit.candidates.find((item) => item.membershipId === event.target.value);
        setDraft({ ...draft, membershipId: event.target.value, legalName: draft.legalName || chosen?.displayName || '' });
      }}>
        <option value="">Selecione</option>
        {unit.candidates.map((item) => <option key={item.membershipId} value={item.membershipId}>{item.displayName} ({roleLabels[item.role]})</option>)}
      </select>
      <label htmlFor={`${id}-name`}>Nome completo ou razão social</label>
      <input id={`${id}-name`} maxLength={120} required value={draft.legalName} onChange={(event) => setDraft({ ...draft, legalName: event.target.value })} />
      <label htmlFor={`${id}-document`}>CPF ou CNPJ</label>
      <input id={`${id}-document`} inputMode="numeric" autoComplete="off" required value={draft.document} onChange={(event) => setDraft({ ...draft, document: event.target.value })} />
      <label htmlFor={`${id}-email`}>E-mail</label>
      <input id={`${id}-email`} type="email" autoComplete="off" required value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} />
      <label htmlFor={`${id}-reason`}>Motivo (auditoria)</label>
      <textarea id={`${id}-reason`} rows={2} maxLength={480} required value={draft.reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} />
      <div className="button-row">
        <button className="button" type="submit" disabled={action.state.pending || !valid}>{action.state.pending ? 'Enviando…' : 'Salvar pagador'}</button>
        <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={() => setEditing(false)}>Voltar</button>
      </div>
    </form> : null}
    <ActionFeedback state={action.state} />
  </li>;
}

function RecurringFees() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const fees = useQuery({ queryKey: managementKey(scope, 'finance', 'fees'), queryFn: ({ signal }) => finance.fees(scope.tenantId, signal) });
  const [draft, setDraft] = useState({ description: 'Taxa condominial', amount: '', dueDay: '10', billingType: 'BOLETO' as BillingType, reason: '' });
  const [generation, setGeneration] = useState({ competence: today(scope.timeZone).slice(0, 7), reason: '' });
  const [ending, setEnding] = useState<string | null>(null);
  // Mesma chave enquanto o resultado for incerto: repetir nunca cria outra taxa ativa; sucesso libera uma nova.
  const [feeKey, setFeeKey] = useState(newOperationKey);
  if (useAccessGuard(fees.error)) return null;
  if (codeOf(fees.error) === 'API_NOT_AVAILABLE') return <Unavailable />;
  const refresh = async () => { setEnding(null); await queries.invalidateQueries({ queryKey: managementKey(scope, 'finance') }); };
  const cents = parseCents(draft.amount);
  const day = Number(draft.dueDay);
  const valid = cents !== null && cents >= 500 && Number.isInteger(day) && day >= 1 && day <= 28 && draft.description.trim().length >= 3
    && draft.reason.trim().length >= 3;
  async function create(event: FormEvent) {
    event.preventDefault();
    if (!valid || cents === null) return;
    await action.run(() => finance.createFee(scope.tenantId, feeKey, { unitId: null, kind: 'CONDO_FEE', description: draft.description.trim(), amountCents: cents,
      dueDay: day, billingType: draft.billingType, reason: draft.reason.trim() }), 'Taxa recorrente criada para todas as unidades com pagador.',
      async () => { setFeeKey(newOperationKey()); setDraft({ ...draft, amount: '', reason: '' }); await refresh(); });
  }
  async function generate(event: FormEvent) {
    event.preventDefault();
    if (!/^\d{4}-\d{2}$/.test(generation.competence) || generation.reason.trim().length < 3) return;
    await action.run(() => finance.generate(scope.tenantId, generation.competence, generation.reason.trim()),
      (result) => ({ tone: result.pending > 0 ? 'warning' : 'success', text: `Competência ${generation.competence}: ${result.created} criada(s), `
        + `${result.skipped} já existente(s) ou sem pagador/vencimento passado, ${result.issued} emitida(s)${result.pending ? `, ${result.pending} pendente(s) de emissão` : ''}.` }),
      refresh);
  }
  return <div className="panel">
    {fees.isPending ? <Loading label="Consultando taxas…" /> : fees.isError && !fees.data ? <ErrorState error={fees.error} retry={() => void fees.refetch()} />
      : !fees.data || fees.data.length === 0 ? <Empty icon="card" title="Nenhuma taxa recorrente">Cadastre a taxa condominial mensal abaixo.</Empty>
        : <ul className="management-list" aria-label="Taxas recorrentes">{fees.data.map((fee) => <li key={fee.feeId} className="management-row">
          <div><strong><bdi>{fee.description}</bdi></strong> <span className={`status-pill ${fee.state === 'ACTIVE' ? 'status-ready' : 'status-neutral'}`}>{fee.state === 'ACTIVE' ? 'Ativa' : 'Encerrada'}</span>
            <span>{formatCents(fee.amountCents)} · dia {fee.dueDay} · {billingLabels[fee.billingType]} · {fee.unitLabel ? <>unidade <bdi>{fee.unitLabel}</bdi></> : 'todas as unidades com pagador'}</span></div>
          {fee.state === 'ACTIVE' && ending !== fee.feeId ? <button className="text-button danger-text" type="button" onClick={() => { action.clear(); setEnding(fee.feeId); }}>Encerrar</button> : null}
          {ending === fee.feeId ? <ReasonForm id={`end-fee-${fee.feeId}`} label="Motivo do encerramento" required pending={action.state.pending} confirmLabel="Encerrar taxa"
            tone="danger" cancel={() => setEnding(null)} submit={(reason) => { if (reason) void action.run(() => finance.endFee(scope.tenantId, fee.feeId, reason),
              'Taxa encerrada. Cobranças já geradas continuam.', refresh); }} /> : null}
        </li>)}</ul>}
    <form className="management-form stack-form" onSubmit={(event) => void create(event)} aria-label="Nova taxa recorrente">
      <h3>Nova taxa recorrente</h3>
      <label htmlFor="fee-description">Descrição</label>
      <input id="fee-description" maxLength={200} required value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} />
      <label htmlFor="fee-amount">Valor mensal (R$)</label>
      <input id="fee-amount" inputMode="decimal" required value={draft.amount} onChange={(event) => setDraft({ ...draft, amount: event.target.value })} />
      <label htmlFor="fee-day">Dia do vencimento (1 a 28)</label>
      <input id="fee-day" type="number" min={1} max={28} required value={draft.dueDay} onChange={(event) => setDraft({ ...draft, dueDay: event.target.value })} />
      <label htmlFor="fee-billing">Forma de pagamento</label>
      <select id="fee-billing" value={draft.billingType} onChange={(event) => setDraft({ ...draft, billingType: event.target.value as BillingType })}>
        {billingTypes.map((type) => <option key={type} value={type}>{billingLabels[type]}</option>)}
      </select>
      <label htmlFor="fee-reason">Motivo (auditoria)</label>
      <textarea id="fee-reason" rows={2} maxLength={480} required value={draft.reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} />
      <button className="button" type="submit" disabled={action.state.pending || !valid}>Criar taxa</button>
    </form>
    <form className="management-form stack-form" onSubmit={(event) => void generate(event)} aria-label="Gerar competência">
      <h3>Gerar cobranças da competência</h3>
      <p className="field-help">Gera uma cobrança por unidade com pagador para cada taxa ativa; repetir não duplica. Emite até 20 por vez.</p>
      <label htmlFor="generate-competence">Competência</label>
      <input id="generate-competence" type="month" required value={generation.competence} onChange={(event) => setGeneration({ ...generation, competence: event.target.value })} />
      <label htmlFor="generate-reason">Motivo (auditoria)</label>
      <textarea id="generate-reason" rows={2} maxLength={480} required value={generation.reason} onChange={(event) => setGeneration({ ...generation, reason: event.target.value })} />
      <div className="button-row">
        <button className="button" type="submit" disabled={action.state.pending || generation.reason.trim().length < 3}>Gerar e emitir</button>
        <button className="button button-secondary" type="button" disabled={action.state.pending}
          onClick={() => void action.run(() => finance.issuePending(scope.tenantId), (result) => ({ tone: result.failed || result.pending ? 'warning' : 'success',
            text: `${result.issued} emitida(s), ${result.failed} com falha, ${result.pending} ainda pendente(s).` }), refresh)}>Emitir pendentes</button>
      </div>
    </form>
    <ActionFeedback state={action.state} />
  </div>;
}

function DelinquencyView() {
  const scope = useManagement();
  const report = useQuery({ queryKey: managementKey(scope, 'finance', 'delinquency'), queryFn: ({ signal }) => finance.delinquency(scope.tenantId, signal) });
  if (useAccessGuard(report.error)) return null;
  if (codeOf(report.error) === 'API_NOT_AVAILABLE') return <Unavailable />;
  return <div className="panel">
    {report.isPending ? <Loading label="Consultando inadimplência…" /> : report.isError && !report.data ? <ErrorState error={report.error} retry={() => void report.refetch()} />
      : !report.data || report.data.units.length === 0 ? <Empty icon="check" title="Nenhuma unidade inadimplente">Não há cobranças vencidas em aberto.</Empty>
        : <>
          <p><strong>Total vencido em aberto: {formatCents(report.data.totalCents)}</strong> · referência {formatDay(report.data.referenceDate)}</p>
          <table className="data-table"><caption className="sr-only">Inadimplência por unidade</caption>
            <thead><tr><th scope="col">Unidade</th><th scope="col">Cobranças</th><th scope="col">Total</th><th scope="col">Vencimento mais antigo</th></tr></thead>
            <tbody>{report.data.units.map((item) => <tr key={item.unitId}><th scope="row"><bdi>{unitName(item)}</bdi></th><td>{item.charges}</td>
              <td>{formatCents(item.totalCents)}</td><td>{formatDay(item.oldestDue)}</td></tr>)}</tbody></table>
        </>}
  </div>;
}

function shiftMonth(month: string, delta: number): string {
  const [year, value] = month.split('-').map(Number);
  const date = new Date(Date.UTC(year!, value! - 1 + delta, 1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}

function ReportView() {
  const scope = useManagement();
  const current = today(scope.timeZone).slice(0, 7);
  const [range, setRange] = useState({ from: shiftMonth(current, -5), until: current });
  const report = useQuery({ queryKey: managementKey(scope, 'finance', 'report', range.from, range.until),
    queryFn: ({ signal }) => finance.report(scope.tenantId, range.from, range.until, signal) });
  if (useAccessGuard(report.error)) return null;
  if (codeOf(report.error) === 'API_NOT_AVAILABLE') return <Unavailable />;
  return <div className="panel">
    <div className="button-row">
      <label htmlFor="report-from">De</label>
      <input id="report-from" type="month" value={range.from} onChange={(event) => setRange({ ...range, from: event.target.value })} />
      <label htmlFor="report-until">Até</label>
      <input id="report-until" type="month" value={range.until} onChange={(event) => setRange({ ...range, until: event.target.value })} />
    </div>
    <p className="field-help">Por mês de vencimento. Pago usa o valor recebido informado pelo Asaas.</p>
    {report.isPending ? <Loading label="Consultando relatório…" /> : report.isError && !report.data ? <ErrorState error={report.error} retry={() => void report.refetch()} />
      : !report.data || report.data.length === 0 ? <Empty icon="list" title="Sem cobranças no período">Nenhuma cobrança vence nesses meses.</Empty>
        : <table className="data-table"><caption className="sr-only">Resumo financeiro por mês</caption>
          <thead><tr><th scope="col">Mês</th><th scope="col">Emitido</th><th scope="col">Pago</th><th scope="col">A vencer</th><th scope="col">Vencido</th><th scope="col">Canceladas</th></tr></thead>
          <tbody>{report.data.map((month) => <tr key={month.month}><th scope="row">{month.month.split('-').reverse().join('/')}</th><td>{formatCents(month.issuedCents)}</td>
            <td>{formatCents(month.paidCents)}</td><td>{formatCents(month.openCents)}</td><td>{formatCents(month.overdueCents)}</td><td>{month.cancelled}</td></tr>)}</tbody></table>}
  </div>;
}
