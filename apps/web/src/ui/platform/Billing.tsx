import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  billing, type BillingCharge, type BillingPeriod, type BillingPlan, type BillingSubscription, type ChargeFilter, type PaymentMethod,
} from '../../api/billing';
import { platform } from '../../api/platform';
import { displayText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { METHOD_LABELS, centsInput, formatCents, formatCivil, parseCents, perPeriod } from './money';
import { PLATFORM_ZONE, isUncertain, platformKey, react, tenantListKey, useAccessGuard, usePlatform, type Outcome } from './shared';

/**
 * Financeiro da plataforma (fase 5): planos, assinaturas, cobranças por período e pagamentos registrados à mão. A tela
 * mostra somente o que o servidor confirmou: "paga" exige pagamento registrado do mesmo valor; atraso não bloqueia o
 * condomínio; não há gateway, cobrança automática nem emissão fiscal.
 */
const views = [{ id: 'cobrancas', label: 'Cobranças' }, { id: 'assinaturas', label: 'Assinaturas' }, { id: 'planos', label: 'Planos' }] as const;
type View = typeof views[number]['id'];
const billingKey = (accountId: string, ...parts: unknown[]) => platformKey(accountId, 'billing', ...parts);
const filters: { id: ChargeFilter | ''; label: string }[] = [
  { id: 'OPEN', label: 'Em aberto' }, { id: 'OVERDUE', label: 'Vencidas' }, { id: 'PAID', label: 'Pagas' },
  { id: 'CANCELLED', label: 'Canceladas' }, { id: '', label: 'Todas' },
];
const methods = Object.entries(METHOD_LABELS) as [PaymentMethod, string][];

/** Data civil de hoje no fuso de operação (o servidor confere de novo pelo relógio do banco). */
function today(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: PLATFORM_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

export function BillingSection() {
  const [view, setView] = useState<View>('cobrancas');
  return <section className="management-section" aria-labelledby="billing-title">
    <div className="section-heading"><div><span className="section-index">02 / FINANCEIRO</span><h2 id="billing-title">Assinatura dos condomínios</h2></div></div>
    <p className="field-help">Relação comercial da assinatura CondPulse. Pagamento entra somente por registro manual, com forma, data e
      referência do comprovante; não há cobrança automática, gateway ou emissão fiscal. Cobrança vencida não bloqueia o acesso do condomínio.</p>
    <div className="button-row" role="group" aria-label="Visões do financeiro">{views.map((item) =>
      <button key={item.id} type="button" className={`button button-compact${view === item.id ? '' : ' button-secondary'}`}
        aria-pressed={view === item.id} onClick={() => setView(item.id)}>{item.label}</button>)}</div>
    {view === 'cobrancas' ? <Charges /> : view === 'assinaturas' ? <Subscriptions /> : <Plans />}
  </section>;
}

/** Envio de uma ação financeira: reage a perda de acesso, guarda a falha e sempre atualiza as listas do financeiro. */
function useBillingAction() {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
    if (pending) return undefined;
    setPending(true); setFailure(null);
    try { return await action(); }
    catch (error) { react(error, scope); setFailure(error); return undefined; }
    finally {
      setPending(false);
      void queries.invalidateQueries({ queryKey: billingKey(scope.accountId) });
    }
  }
  const notice = failure ? <Notice tone={isUncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}
    {isUncertain(failure) ? ' Atualize a lista e confira o resultado antes de repetir.' : ''}</Notice> : null;
  return { pending, failure, notice, run };
}

function Stale({ read, current, reload }: { read: number; current: number; reload(): void }) {
  if (read === current) return null;
  return <Notice tone="warning">Este registro foi alterado depois que você abriu o formulário (versão {read} → {current}).
    <button className="button button-secondary button-compact" type="button" onClick={reload}>Recarregar os dados atuais</button></Notice>;
}

function ChargeStatus({ charge }: { charge: BillingCharge }) {
  const [label, tone] = charge.status === 'PAID' ? ['Paga', 'status-ready'] : charge.status === 'CANCELLED' ? ['Cancelada', 'status-neutral']
    : charge.overdue ? ['Vencida', 'status-overdue'] : ['Em aberto', 'status-pending'];
  return <span className={`status-pill ${tone}`}>{label}</span>;
}

type ChargeAction = 'pay' | 'cancel' | 'void';
function Charges() {
  const scope = usePlatform();
  const [filter, setFilter] = useState<ChargeFilter | ''>('OPEN');
  const charges = useQuery({ queryKey: billingKey(scope.accountId, 'charges', filter), queryFn: ({ signal }) => billing.charges(filter || null, signal) });
  const [editing, setEditing] = useState<{ chargeId: string; action: ChargeAction } | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  if (useAccessGuard(charges.error)) return null;
  const manage = scope.session.capabilities.includes('billing.manage');
  return <div className="management-section">
    <div className="reservation-filter"><label htmlFor="charge-filter">Situação</label>
      <select id="charge-filter" value={filter} onChange={(event) => { setFilter(event.target.value as ChargeFilter | ''); setEditing(null); }}>
        {filters.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select>
      <button className="button button-secondary button-compact" type="button" disabled={charges.isFetching} onClick={() => void charges.refetch()}>Atualizar</button></div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {charges.isPending ? <div className="panel"><Loading label="Consultando cobranças…" /></div>
      : charges.isError && !charges.data ? <ErrorState error={charges.error} retry={() => void charges.refetch()} />
        : !charges.data || charges.data.charges.length === 0 ? <div className="panel"><Empty title="Nenhuma cobrança">Nenhuma cobrança nesta situação.</Empty></div>
          : <ol className="management-list" aria-label="Cobranças">{charges.data.charges.map((charge) => <li key={charge.chargeId} className="management-row">
            <div><strong><bdi>{charge.tenantName}</bdi></strong> <ChargeStatus charge={charge} />
              <span>Período de {formatCivil(charge.periodStart)} a {formatCivil(charge.periodEnd)} · {formatCents(charge.amountCents)} · vencimento em {formatCivil(charge.dueOn)}</span>
              {charge.payment ? <span>Pagamento registrado: {formatCents(charge.payment.amountCents)} em {formatCivil(charge.payment.paidOn)} por {METHOD_LABELS[charge.payment.method]} · comprovante <bdi>{charge.payment.reference}</bdi></span> : null}
              {charge.cancelReason ? <span>Cancelada: <bdi>{charge.cancelReason}</bdi></span> : null}</div>
            {manage && editing?.chargeId !== charge.chargeId && charge.status !== 'CANCELLED' ? <div className="button-row">
              {charge.status === 'OPEN' ? <>
                <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ chargeId: charge.chargeId, action: 'pay' }); }}>Registrar pagamento</button>
                <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ chargeId: charge.chargeId, action: 'cancel' }); }}>Cancelar cobrança</button></>
                : <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setEditing({ chargeId: charge.chargeId, action: 'void' }); }}>Estornar pagamento</button>}
            </div> : null}
            {manage && editing?.chargeId === charge.chargeId ? <ChargeEditor charge={charge} action={editing.action}
              close={(result) => { setEditing(null); if (result) setOutcome(result); }} /> : null}
          </li>)}</ol>}
    {charges.data?.truncated ? <Notice tone="warning">A lista mostra as 200 cobranças de vencimento mais recente nesta situação.</Notice> : null}
  </div>;
}

function ChargeEditor({ charge, action, close }: { charge: BillingCharge; action: ChargeAction; close(result: Outcome | null): void }) {
  const { pending, notice, run } = useBillingAction();
  // Versão lida ao abrir: nova consulta da lista não troca o alvo da ação em andamento.
  const [read, setRead] = useState(charge.version);
  const [amount, setAmount] = useState(centsInput(charge.amountCents));
  const [paidOn, setPaidOn] = useState(today());
  const [method, setMethod] = useState<PaymentMethod>('PIX');
  const [reference, setReference] = useState('');
  const [reason, setReason] = useState('');
  const stale = read !== charge.version;
  const cents = parseCents(amount);
  const normalizedReference = displayText(reference, 200);
  const normalizedReason = displayText(reason, 500);
  const valid = action === 'pay'
    ? cents !== null && cents > 0 && /^\d{4}-\d{2}-\d{2}$/.test(paidOn) && paidOn <= today() && normalizedReference !== null && [...normalizedReference].length >= 3
    : normalizedReason !== null;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || stale) return;
    if (action === 'pay') {
      const result = await run(() => billing.recordPayment(charge.chargeId,
        { amountCents: cents!, paidOn, method, reference: normalizedReference!, expectedVersion: read }));
      if (result) close(result.status === 'PAID' && result.payment
        ? { tone: 'success', text: `Pagamento de ${formatCents(result.payment.amountCents)} registrado: cobrança de ${result.tenantName} paga.` }
        : { tone: 'warning', text: 'A resposta não confirmou a baixa da cobrança. Atualize a lista e confira.' });
    } else if (action === 'cancel') {
      const result = await run(() => billing.cancelCharge(charge.chargeId, { expectedVersion: read, reason: normalizedReason! }));
      if (result) close(result.status === 'CANCELLED' ? { tone: 'success', text: `Cobrança de ${result.tenantName} cancelada. O motivo ficou na auditoria.` }
        : { tone: 'warning', text: 'A resposta não confirmou o cancelamento. Atualize a lista e confira.' });
    } else if (charge.payment) {
      const paymentId = charge.payment.paymentId;
      const result = await run(() => billing.voidPayment(paymentId, { expectedChargeVersion: read, reason: normalizedReason! }));
      if (result) close(result.status === 'OPEN' && !result.payment
        ? { tone: 'success', text: `Pagamento estornado: a cobrança de ${result.tenantName} voltou a ficar em aberto.` }
        : { tone: 'warning', text: 'A resposta não confirmou o estorno. Atualize a lista e confira.' });
    }
  }
  const id = `${action}-${charge.chargeId}`;
  const labels = { pay: 'Registrar pagamento', cancel: 'Cancelar cobrança', void: 'Estornar pagamento' } as const;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`${labels[action]} de ${charge.tenantName}`}>
    <Stale read={read} current={charge.version} reload={() => setRead(charge.version)} />
    {action === 'pay' ? <>
      <p className="field-help">Registre somente pagamento já confirmado no extrato. O valor precisa ser igual ao da cobrança ({formatCents(charge.amountCents)}); pagamento parcial não é registrado.</p>
      <label htmlFor={`${id}-amount`}>Valor pago (R$)</label>
      <input id={`${id}-amount`} inputMode="decimal" required value={amount} onChange={(event) => setAmount(event.target.value)} disabled={pending} />
      <label htmlFor={`${id}-date`}>Data do pagamento</label>
      <input id={`${id}-date`} type="date" required max={today()} value={paidOn} onChange={(event) => setPaidOn(event.target.value)} disabled={pending} />
      <label htmlFor={`${id}-method`}>Forma de pagamento</label>
      <select id={`${id}-method`} value={method} onChange={(event) => setMethod(event.target.value as PaymentMethod)} disabled={pending}>
        {methods.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
      <label htmlFor={`${id}-reference`}>Referência do comprovante</label>
      <input id={`${id}-reference`} required maxLength={200} value={reference} onChange={(event) => setReference(event.target.value)} disabled={pending}
        aria-describedby={`${id}-reference-help`} />
      <span className="field-help" id={`${id}-reference-help`}>Identificador do Pix, da transferência ou do boleto, de 3 a 200 caracteres.</span>
    </> : <>
      {action === 'void' ? <Notice tone="warning">O estorno marca o pagamento como registro errado, com o seu motivo, e a cobrança volta a ficar em aberto. Nada é devolvido automaticamente.</Notice>
        : <Notice tone="warning">A cobrança cancelada não volta a ficar em aberto. Use para período cobrado por engano.</Notice>}
      <label htmlFor={`${id}-reason`}>Motivo (fica na auditoria da plataforma)</label>
      <textarea id={`${id}-reason`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    </>}
    {notice}
    <div className="button-row">
      <button className={`button${action === 'pay' ? '' : ' button-danger'}`} type="submit" disabled={!valid || pending || stale}>{pending ? 'Salvando…' : labels[action]}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

function Subscriptions() {
  const scope = usePlatform();
  const subscriptions = useQuery({ queryKey: billingKey(scope.accountId, 'subscriptions'), queryFn: ({ signal }) => billing.subscriptions(signal) });
  const [editing, setEditing] = useState<{ subscriptionId: string; action: 'charge' | 'end' } | null>(null);
  const [creating, setCreating] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  if (useAccessGuard(subscriptions.error)) return null;
  const manage = scope.session.capabilities.includes('billing.manage');
  const finish = (result: Outcome | null) => { setEditing(null); setCreating(false); if (result) setOutcome(result); };
  return <div className="management-section">
    <div className="button-row">
      {manage && !creating ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setEditing(null); setCreating(true); }}>Nova assinatura</button> : null}
      <button className="button button-secondary button-compact" type="button" disabled={subscriptions.isFetching} onClick={() => void subscriptions.refetch()}>Atualizar</button></div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {manage && creating ? <SubscriptionForm close={finish} /> : null}
    {subscriptions.isPending ? <div className="panel"><Loading label="Consultando assinaturas…" /></div>
      : subscriptions.isError && !subscriptions.data ? <ErrorState error={subscriptions.error} retry={() => void subscriptions.refetch()} />
        : !subscriptions.data || subscriptions.data.length === 0 ? <div className="panel"><Empty title="Nenhuma assinatura">As assinaturas dos condomínios aparecerão aqui.</Empty></div>
          : <ol className="management-list" aria-label="Assinaturas">{subscriptions.data.map((subscription) => <li key={subscription.subscriptionId} className="management-row">
            <div><strong><bdi>{subscription.tenantName}</bdi></strong> <span className={`status-pill ${subscription.status === 'ACTIVE' ? 'status-ready' : 'status-neutral'}`}>{subscription.status === 'ACTIVE' ? 'Ativa' : 'Encerrada'}</span>
              <span>Plano <bdi>{subscription.planName}</bdi> · {formatCents(subscription.priceCents)} por {perPeriod(subscription.period)} · início em {formatCivil(subscription.startsOn)} · vencimento no dia {subscription.dueDay}{subscription.endsOn ? ` · encerrada em ${formatCivil(subscription.endsOn)}` : ''}</span></div>
            {manage && subscription.status === 'ACTIVE' && editing?.subscriptionId !== subscription.subscriptionId ? <div className="button-row">
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setCreating(false); setEditing({ subscriptionId: subscription.subscriptionId, action: 'charge' }); }}>Gerar cobrança</button>
              <button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setCreating(false); setEditing({ subscriptionId: subscription.subscriptionId, action: 'end' }); }}>Encerrar assinatura</button></div> : null}
            {manage && editing?.subscriptionId === subscription.subscriptionId ? editing.action === 'charge'
              ? <ChargeForm subscription={subscription} close={finish} /> : <EndSubscriptionForm subscription={subscription} close={finish} /> : null}
          </li>)}</ol>}
  </div>;
}

function ChargeForm({ subscription, close }: { subscription: BillingSubscription; close(result: Outcome | null): void }) {
  const { pending, notice, run } = useBillingAction();
  const [periodStart, setPeriodStart] = useState(subscription.startsOn);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(periodStart);
  const day = Number(subscription.startsOn.slice(8, 10));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const result = await run(() => billing.createCharge(subscription.subscriptionId, periodStart));
    if (result) close({ tone: 'success', text: `Cobrança gerada para ${result.tenantName}: período de ${formatCivil(result.periodStart)} a ${formatCivil(result.periodEnd)}, ${formatCents(result.amountCents)}, vencimento em ${formatCivil(result.dueOn)}.` });
  }
  const id = `charge-${subscription.subscriptionId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Gerar cobrança de ${subscription.tenantName}`}>
    <p className="field-help">{subscription.period === 'MONTHLY'
      ? `O período mensal começa no dia ${day} de um mês, a partir de ${formatCivil(subscription.startsOn)}.`
      : `O período anual começa em ${formatCivil(subscription.startsOn)} ou na mesma data dos anos seguintes.`} Valor contratado: {formatCents(subscription.priceCents)}. Uma cobrança por período.</p>
    <label htmlFor={`${id}-start`}>Início do período</label>
    <input id={`${id}-start`} type="date" required min={subscription.startsOn} value={periodStart} onChange={(event) => setPeriodStart(event.target.value)} disabled={pending} />
    {notice}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || pending}>{pending ? 'Gerando…' : 'Gerar cobrança'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

function EndSubscriptionForm({ subscription, close }: { subscription: BillingSubscription; close(result: Outcome | null): void }) {
  const { pending, notice, run } = useBillingAction();
  const [read, setRead] = useState(subscription.version);
  const [endsOn, setEndsOn] = useState('');
  const [reason, setReason] = useState('');
  const normalizedReason = displayText(reason, 500);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(endsOn) && endsOn > subscription.startsOn && normalizedReason !== null;
  const stale = read !== subscription.version;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || stale) return;
    const result = await run(() => billing.endSubscription(subscription.subscriptionId, { endsOn, expectedVersion: read, reason: normalizedReason! }));
    if (result) close(result.status === 'ENDED' && result.endsOn
      ? { tone: 'success', text: `Assinatura de ${result.tenantName} encerrada em ${formatCivil(result.endsOn)}. Cobranças já geradas permanecem como estão.` }
      : { tone: 'warning', text: 'A resposta não confirmou o encerramento. Atualize a lista e confira.' });
  }
  const id = `end-${subscription.subscriptionId}`;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Encerrar assinatura de ${subscription.tenantName}`}>
    <Stale read={read} current={subscription.version} reload={() => setRead(subscription.version)} />
    <Notice tone="warning">Encerrar a assinatura não suspende o condomínio. Cobrança de período depois da data precisa ser cancelada antes.</Notice>
    <label htmlFor={`${id}-date`}>Data de encerramento</label>
    <input id={`${id}-date`} type="date" required value={endsOn} onChange={(event) => setEndsOn(event.target.value)} disabled={pending} />
    <label htmlFor={`${id}-reason`}>Motivo (fica na auditoria da plataforma)</label>
    <textarea id={`${id}-reason`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {notice}
    <div className="button-row">
      <button className="button button-danger" type="submit" disabled={!valid || pending || stale}>{pending ? 'Salvando…' : 'Encerrar assinatura'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

function SubscriptionForm({ close }: { close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const { pending, notice, run } = useBillingAction();
  const catalog = useQuery({ queryKey: tenantListKey(scope.accountId, null), queryFn: ({ signal }) => platform.tenants(null, signal),
    enabled: scope.session.capabilities.includes('tenants.read') });
  const plans = useQuery({ queryKey: billingKey(scope.accountId, 'plans'), queryFn: ({ signal }) => billing.plans(signal) });
  const [tenantId, setTenantId] = useState('');
  const [planId, setPlanId] = useState('');
  const [startsOn, setStartsOn] = useState('');
  const [dueDay, setDueDay] = useState('10');
  const [reason, setReason] = useState('');
  const available = plans.data?.filter((plan) => plan.active) ?? [];
  const day = Number(dueDay);
  const normalizedReason = displayText(reason, 500);
  const valid = /^[0-9a-f-]{36}$/.test(tenantId) && available.some((plan) => plan.planId === planId)
    && /^\d{4}-\d{2}-\d{2}$/.test(startsOn) && Number(startsOn.slice(8, 10)) <= 28
    && Number.isInteger(day) && day >= 1 && day <= 28 && normalizedReason !== null;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const result = await run(() => billing.createSubscription({ tenantId, planId, startsOn, dueDay: day, reason: normalizedReason! }));
    if (result) close({ tone: 'success', text: `Assinatura criada para ${result.tenantName}: plano ${result.planName}, ${formatCents(result.priceCents)} por ${perPeriod(result.period)}, vencimento no dia ${result.dueDay}.` });
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Nova assinatura">
    <label htmlFor="subscription-tenant">Condomínio</label>
    <select id="subscription-tenant" required value={tenantId} onChange={(event) => setTenantId(event.target.value)} disabled={pending}>
      <option value="">Escolha o condomínio</option>
      {catalog.data?.tenants.map((tenant) => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.name}{tenant.active ? '' : ' (suspenso)'}</option>)}</select>
    {catalog.data?.truncated ? <span className="field-help">A lista mostra os primeiros 200 condomínios em ordem alfabética.</span> : null}
    {catalog.isError ? <Notice tone="error">Não foi possível carregar os condomínios. {errorMessage(catalog.error)}</Notice> : null}
    <label htmlFor="subscription-plan">Plano</label>
    <select id="subscription-plan" required value={planId} onChange={(event) => setPlanId(event.target.value)} disabled={pending}>
      <option value="">Escolha o plano</option>
      {available.map((plan) => <option key={plan.planId} value={plan.planId}>{plan.name} — {formatCents(plan.priceCents)} por {perPeriod(plan.period)}</option>)}</select>
    {plans.data && available.length === 0 ? <span className="field-help">Nenhum plano disponível. Cadastre um plano aprovado antes da assinatura.</span> : null}
    <label htmlFor="subscription-start">Início da assinatura (dia 1 a 28)</label>
    <input id="subscription-start" type="date" required value={startsOn} onChange={(event) => setStartsOn(event.target.value)} disabled={pending} />
    <label htmlFor="subscription-due">Dia de vencimento (1 a 28)</label>
    <input id="subscription-due" type="number" min={1} max={28} required value={dueDay} onChange={(event) => setDueDay(event.target.value)} disabled={pending} />
    <span className="field-help">O valor e o período vêm do plano no momento da contratação; mudar o plano depois não altera esta assinatura.</span>
    <label htmlFor="subscription-reason">Motivo (fica na auditoria da plataforma)</label>
    <textarea id="subscription-reason" required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {notice}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || pending}>{pending ? 'Salvando…' : 'Criar assinatura'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

function Plans() {
  const scope = usePlatform();
  const plans = useQuery({ queryKey: billingKey(scope.accountId, 'plans'), queryFn: ({ signal }) => billing.plans(signal) });
  const [editing, setEditing] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  if (useAccessGuard(plans.error)) return null;
  const manage = scope.session.capabilities.includes('billing.manage');
  const finish = (result: Outcome | null) => { setEditing(null); setCreating(false); if (result) setOutcome(result); };
  return <div className="management-section">
    <div className="button-row">
      {manage && !creating ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setEditing(null); setCreating(true); }}>Novo plano</button> : null}
      <button className="button button-secondary button-compact" type="button" disabled={plans.isFetching} onClick={() => void plans.refetch()}>Atualizar</button></div>
    <p className="field-help">Nenhum preço é presumido: cadastre somente planos com valor aprovado. Preço novo vale para assinaturas novas.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {manage && creating ? <PlanForm close={finish} /> : null}
    {plans.isPending ? <div className="panel"><Loading label="Consultando planos…" /></div>
      : plans.isError && !plans.data ? <ErrorState error={plans.error} retry={() => void plans.refetch()} />
        : !plans.data || plans.data.length === 0 ? <div className="panel"><Empty title="Nenhum plano">Os planos aprovados aparecerão aqui.</Empty></div>
          : <ol className="management-list" aria-label="Planos">{plans.data.map((plan) => <li key={plan.planId} className="management-row">
            <div><strong><bdi>{plan.name}</bdi></strong> <span className={`status-pill ${plan.active ? 'status-ready' : 'status-neutral'}`}>{plan.active ? 'Disponível' : 'Indisponível'}</span>
              <span>{formatCents(plan.priceCents)} por {perPeriod(plan.period)} · versão {plan.version}</span></div>
            {manage && editing !== plan.planId ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
              onClick={() => { setOutcome(null); setCreating(false); setEditing(plan.planId); }}>Alterar plano</button></div> : null}
            {manage && editing === plan.planId ? <PlanForm plan={plan} close={finish} /> : null}
          </li>)}</ol>}
  </div>;
}

function PlanForm({ plan, close }: { plan?: BillingPlan; close(result: Outcome | null): void }) {
  const { pending, notice, run } = useBillingAction();
  const [read, setRead] = useState(plan ? { version: plan.version, name: plan.name, priceCents: plan.priceCents, active: plan.active } : null);
  const [name, setName] = useState(plan?.name ?? '');
  const [price, setPrice] = useState(plan ? centsInput(plan.priceCents) : '');
  const [period, setPeriod] = useState<BillingPeriod>(plan?.period ?? 'MONTHLY');
  const [active, setActive] = useState(plan?.active ?? true);
  const [reason, setReason] = useState('');
  const normalizedName = displayText(name, 120);
  const cents = parseCents(price);
  const normalizedReason = displayText(reason, 500);
  const valid = normalizedName !== null && cents !== null && normalizedReason !== null;
  const stale = plan !== undefined && read !== null && read.version !== plan.version;
  function reload() {
    if (!plan) return;
    setRead({ version: plan.version, name: plan.name, priceCents: plan.priceCents, active: plan.active });
    setName(plan.name); setPrice(centsInput(plan.priceCents)); setActive(plan.active);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || stale) return;
    if (!plan || !read) {
      const result = await run(() => billing.createPlan({ name: normalizedName!, priceCents: cents!, period, reason: normalizedReason! }));
      if (result) close({ tone: 'success', text: `Plano ${result.name} criado: ${formatCents(result.priceCents)} por ${perPeriod(result.period)}.` });
      return;
    }
    const result = await run(() => billing.updatePlan(plan.planId,
      { name: normalizedName!, priceCents: cents!, active, expectedVersion: read.version, reason: normalizedReason! }));
    if (result) close(result.applied
      ? { tone: 'success', text: `Plano atualizado: ${result.plan.name}, ${formatCents(result.plan.priceCents)} por ${perPeriod(result.plan.period)}${result.plan.active ? '' : ', indisponível para novas assinaturas'}. Assinaturas existentes mantêm o valor contratado.` }
      : { tone: 'info', text: 'O plano já estava assim. Nada foi alterado e o seu motivo não foi registrado.' });
  }
  const id = plan ? `plan-${plan.planId}` : 'plan-new';
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={plan ? `Alterar plano ${plan.name}` : 'Novo plano'}>
    {plan && read ? <Stale read={read.version} current={plan.version} reload={reload} /> : null}
    <label htmlFor={`${id}-name`}>Nome do plano</label>
    <input id={`${id}-name`} required value={name} onChange={(event) => setName(event.target.value)} disabled={pending} />
    <label htmlFor={`${id}-price`}>Valor (R$)</label>
    <input id={`${id}-price`} inputMode="decimal" required value={price} onChange={(event) => setPrice(event.target.value)} disabled={pending}
      aria-describedby={`${id}-price-help`} aria-invalid={price !== '' && cents === null} />
    <span className="field-help" id={`${id}-price-help`}>Formato brasileiro, por exemplo 1.234,56; até R$ 1.000.000,00.</span>
    {plan ? <fieldset className="weekday-fieldset"><legend>Disponibilidade</legend>
      <label className="weekday-option"><input type="radio" name={`${id}-active`} checked={active} onChange={() => setActive(true)} disabled={pending} />Disponível</label>
      <label className="weekday-option"><input type="radio" name={`${id}-active`} checked={!active} onChange={() => setActive(false)} disabled={pending} />Indisponível</label>
    </fieldset> : <>
      <label htmlFor={`${id}-period`}>Período</label>
      <select id={`${id}-period`} value={period} onChange={(event) => setPeriod(event.target.value as BillingPeriod)} disabled={pending}>
        <option value="MONTHLY">Mensal</option><option value="YEARLY">Anual</option></select></>}
    <label htmlFor={`${id}-reason`}>Motivo e referência da aprovação (fica na auditoria da plataforma)</label>
    <textarea id={`${id}-reason`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {notice}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || pending || stale}>{pending ? 'Salvando…' : plan ? 'Salvar plano' : 'Criar plano'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

const billingActionLabels: Record<string, string> = {
  BILLING_PLAN_CREATED: 'Plano criado', BILLING_PLAN_UPDATED: 'Plano alterado', BILLING_SUBSCRIPTION_CREATED: 'Assinatura criada',
  BILLING_SUBSCRIPTION_ENDED: 'Assinatura encerrada', BILLING_CHARGE_CREATED: 'Cobrança gerada', BILLING_CHARGE_CANCELLED: 'Cobrança cancelada',
  BILLING_PAYMENT_RECORDED: 'Pagamento registrado', BILLING_PAYMENT_VOIDED: 'Pagamento estornado',
};
export function billingActionLabel(action: string): string | undefined { return billingActionLabels[action]; }
/** Resumo dos detalhes financeiros da trilha (valores em centavos viram reais; datas civis no formato brasileiro). */
export function billingAuditDetails(details: Record<string, string>): ReactNode {
  const parts: string[] = [];
  const money = (value: string | undefined) => value && /^\d{1,9}$/.test(value) ? formatCents(Number(value)) : null;
  const civil = (value: string | undefined) => value && /^\d{4}-\d{2}-\d{2}$/.test(value) ? formatCivil(value) : null;
  if (details.name) parts.push(`plano ${details.name}`);
  if (details.planName) parts.push(`plano ${details.planName}`);
  const amount = money(details.amountCents) ?? money(details.priceCents);
  if (amount) parts.push(amount);
  const start = civil(details.periodStart), end = civil(details.periodEnd);
  if (start && end) parts.push(`período de ${start} a ${end}`);
  const paid = civil(details.paidOn);
  if (paid) parts.push(`pago em ${paid}`);
  if (details.method && details.method in METHOD_LABELS) parts.push(METHOD_LABELS[details.method as PaymentMethod]);
  const ends = civil(details.endsOn);
  if (ends) parts.push(`encerramento em ${ends}`);
  return parts.length ? <span>{parts.join(' · ')}</span> : null;
}
