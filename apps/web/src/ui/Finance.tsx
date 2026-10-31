import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { finance, formatCents, formatDay, type FinanceCharge } from '../api/finance';
import { useAuth } from './Auth';
import { Empty, ErrorState, Loading, Notice } from './primitives';
import { billingLabels, chargeStateLabels, kindLabels } from './management/Finance';
import './management/Management.css';

/** Quem vê o financeiro da unidade (o servidor repete a regra e esconde a unidade alugada do proprietário). */
export const financeRoles = ['RESIDENT', 'RESPONSIBLE', 'OWNER'] as const;

/**
 * Financeiro da unidade (ADR-0008): cobranças emitidas e segunda via (linha digitável do boleto ou PIX copia e cola,
 * lidos no Asaas na hora, e o link da fatura). Nada é guardado no navegador.
 */
export function UnitFinancePage() {
  const { identity } = useAuth();
  const { tenantId = '', unitId = '' } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.unitId === unitId
    && (financeRoles as readonly string[]).includes(item.role));
  if (!identity || !membership) return <div className="panel"><Empty title="Financeiro indisponível">O financeiro da unidade é do morador, do responsável ou do proprietário.</Empty></div>;
  return <UnitFinance key={`${identity.accountId}:${tenantId}:${unitId}`} accountId={identity.accountId} tenantId={tenantId} unitId={unitId}
    tenantName={membership.tenantName} />;
}

function UnitFinance({ accountId, tenantId, unitId, tenantName }: { accountId: string; tenantId: string; unitId: string; tenantName: string }) {
  const key = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'finance'] as const;
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => finance.unitCharges(tenantId, unitId, signal) });
  const code = list.error instanceof ApiError ? list.error.code : '';
  const charges = list.data?.charges ?? [];
  const open = charges.filter((item) => item.state === 'OPEN' || item.state === 'OVERDUE');
  const history = charges.filter((item) => item.state !== 'OPEN' && item.state !== 'OVERDUE');
  return <>
    <div className="residence-heading"><div><span className="eyebrow">FINANCEIRO</span><h1>Cobranças da unidade</h1>
      <p>Emitidas por {tenantName} pelo Asaas. Pague pela linha digitável, pelo PIX copia e cola ou pela fatura do Asaas.</p></div>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button></div>
    {list.isPending ? <div className="panel"><Loading label="Consultando cobranças…" /></div>
      : code === 'API_NOT_AVAILABLE' ? <div className="panel"><Empty icon="card" title="Financeiro indisponível">O financeiro ainda não está ativo neste ambiente.</Empty></div>
        : code === 'UNIT_ACCESS_DENIED' || code === 'TENANT_ACCESS_DENIED' ? <div className="panel"><Empty icon="card" title="Unidade indisponível">O financeiro desta unidade não está disponível para o seu vínculo.</Empty></div>
          : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
            : <>
              <section className="management-section" aria-labelledby="finance-open-title">
                <div className="section-heading"><div><span className="section-index">A PAGAR</span><h2 id="finance-open-title">Em aberto</h2></div></div>
                {open.length === 0 ? <div className="panel"><Empty icon="check" title="Nada em aberto">Quando houver cobrança da unidade, ela aparecerá aqui.</Empty></div>
                  : <ul className="management-list" aria-label="Cobranças em aberto">{open.map((item) =>
                    <ChargeItem key={item.chargeId} charge={item} tenantId={tenantId} unitId={unitId} />)}</ul>}
              </section>
              <section className="management-section" aria-labelledby="finance-history-title">
                <div className="section-heading"><div><span className="section-index">HISTÓRICO</span><h2 id="finance-history-title">Pagas e encerradas</h2></div></div>
                {history.length === 0 ? <p className="field-help">Nenhuma cobrança paga ou encerrada.</p>
                  : <ul className="management-list" aria-label="Histórico de cobranças">{history.map((item) =>
                    <ChargeItem key={item.chargeId} charge={item} tenantId={tenantId} unitId={unitId} />)}</ul>}
                {list.data?.truncated ? <Notice tone="warning">A lista mostra as 200 cobranças de vencimento mais recente.</Notice> : null}
              </section>
            </>}
  </>;
}

function ChargeItem({ charge, tenantId, unitId }: { charge: FinanceCharge; tenantId: string; unitId: string }) {
  const [open, setOpen] = useState(false);
  const [label, tone] = chargeStateLabels[charge.state];
  const payable = charge.state === 'OPEN' || charge.state === 'OVERDUE';
  return <li className="management-row">
    <div><strong><bdi>{charge.description}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
      <span>{kindLabels[charge.kind]} · {billingLabels[charge.billingType]} · {formatCents(charge.amountCents)} · vence em {formatDay(charge.dueDate)}</span>
      {charge.paidAt ? <span>Paga em {formatDay(charge.paidAt)}</span> : null}</div>
    {payable && !open ? <button className="button button-compact" type="button" onClick={() => setOpen(true)}>Segunda via</button> : null}
    {payable && open ? <SecondCopy charge={charge} tenantId={tenantId} unitId={unitId} /> : null}
  </li>;
}

function SecondCopy({ charge, tenantId, unitId }: { charge: FinanceCharge; tenantId: string; unitId: string }) {
  const info = useQuery({ queryKey: ['private', 'finance-payment', tenantId, unitId, charge.chargeId, charge.version],
    queryFn: ({ signal }) => finance.payment(tenantId, unitId, charge.chargeId, signal), gcTime: 0, staleTime: 0 });
  const [copied, setCopied] = useState('');
  async function copy(value: string, what: string) {
    try { await navigator.clipboard.writeText(value); setCopied(what); } catch { setCopied(''); }
  }
  if (info.isPending) return <Loading label="Buscando a segunda via no Asaas…" />;
  if (info.isError) return <ErrorState error={info.error} retry={() => void info.refetch()} />;
  const data = info.data;
  return <div className="panel" aria-label={`Segunda via de ${charge.description}`}>
    {data.identificationField ? <div className="copy-field"><span>Linha digitável:</span><code>{data.identificationField}</code>
      <button className="button button-secondary button-compact" type="button" onClick={() => void copy(data.identificationField!, 'linha digitável')}>Copiar linha</button></div> : null}
    {data.pixPayload ? <div className="copy-field"><span>PIX copia e cola:</span><code>{data.pixPayload}</code>
      <button className="button button-secondary button-compact" type="button" onClick={() => void copy(data.pixPayload!, 'código PIX')}>Copiar PIX</button></div> : null}
    {copied ? <Notice tone="success">{copied === 'código PIX' ? 'Código PIX copiado.' : 'Linha digitável copiada.'}</Notice> : null}
    <div className="button-row">
      {data.invoiceUrl ? <a className="button button-secondary button-compact" href={data.invoiceUrl} target="_blank" rel="noopener noreferrer">Abrir fatura no Asaas</a> : null}
      {data.bankSlipUrl ? <a className="button button-secondary button-compact" href={data.bankSlipUrl} target="_blank" rel="noopener noreferrer">Boleto em PDF</a> : null}
    </div>
  </div>;
}
