import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, list, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Financeiro por condomínio com o Asaas (ADR-0008, sandbox). A gestão (AAL2) escolhe o pagador de cada unidade, cria
 * cobranças avulsas e taxas recorrentes por boleto ou PIX, cancela, sincroniza e acompanha a inadimplência. A unidade
 * vê as cobranças emitidas e pega a segunda via (linha digitável ou PIX copia e cola). Documento e e-mail do pagador
 * chegam sempre mascarados; a chave do Asaas nunca chega ao navegador.
 */
export const chargeKinds = ['CONDO_FEE', 'RESERVATION', 'FINE', 'OTHER'] as const;
export const billingTypes = ['BOLETO', 'PIX'] as const;
export const chargeStates = ['PENDING_ISSUE', 'OPEN', 'OVERDUE', 'PAID', 'CANCELLED', 'REFUNDED'] as const;
export type ChargeKind = typeof chargeKinds[number];
export type BillingType = typeof billingTypes[number];
export type ChargeState = typeof chargeStates[number];
type DateOnly = string;

export interface FinancePayer {
  payerId: UUID; membershipId: UUID; displayName: string; role: 'OWNER' | 'RESPONSIBLE'; legalName: string; document: string; email: string;
  customerSynced: boolean; createdAt: ISODateTime; version: number;
}
export interface FinanceCandidate { membershipId: UUID; displayName: string; role: 'OWNER' | 'RESPONSIBLE' }
export interface FinanceUnit {
  unitId: UUID; unitLabel: string; blockName: string | null; rented: boolean; payer: FinancePayer | null; candidates: FinanceCandidate[];
}
export interface PayerChange { membershipId: UUID; legalName: string; document: string; email: string; reason: string }
export interface FinanceCharge {
  chargeId: UUID; unitId: UUID; unitLabel: string; blockName: string | null; kind: ChargeKind; description: string; amountCents: number;
  dueDate: DateOnly; billingType: BillingType; state: ChargeState; asaasStatus: string | null; competence: DateOnly | null;
  invoiceUrl: string | null; bankSlipUrl: string | null; paidAt: DateOnly | null; paidCents: number | null; issueError: string | null;
  cancelReason: string | null; createdAt: ISODateTime; updatedAt: ISODateTime; version: number;
}
export interface ChargePage { charges: FinanceCharge[]; truncated: boolean }
export interface ChargeCreate {
  unitId: UUID; kind: ChargeKind; description: string; amountCents: number; dueDate: DateOnly; billingType: BillingType; reason: string;
}
export interface PaymentInfo {
  chargeId: UUID; billingType: BillingType; identificationField: string | null; pixPayload: string | null; invoiceUrl: string | null;
  bankSlipUrl: string | null; dueDate: DateOnly; amountCents: number;
}
export interface RecurringFee {
  feeId: UUID; unitId: UUID | null; unitLabel: string | null; kind: 'CONDO_FEE' | 'OTHER'; description: string; amountCents: number;
  dueDay: number; billingType: BillingType; state: 'ACTIVE' | 'ENDED'; createdAt: ISODateTime; version: number;
}
export interface RecurringCreate {
  unitId: UUID | null; kind: 'CONDO_FEE' | 'OTHER'; description: string; amountCents: number; dueDay: number; billingType: BillingType;
  reason: string;
}
export interface GenerateResult { created: number; skipped: number; issued: number; pending: number }
export interface IssueResult { issued: number; failed: number; pending: number }
export interface Delinquency { unitId: UUID; unitLabel: string; blockName: string | null; charges: number; totalCents: number; oldestDue: DateOnly }
export interface DelinquencyReport { referenceDate: DateOnly; units: Delinquency[]; totalCents: number }
export interface MonthSummary { month: string; issuedCents: number; paidCents: number; openCents: number; overdueCents: number; cancelled: number }

const day = (value: unknown): value is string => isString(value) && /^\d{4}-\d{2}-\d{2}$/.test(value) && isDate(value);
const nullable = <T>(check: (value: unknown) => value is T) => (value: unknown): value is T | null => value === null || check(value);
const text = (maximum: number) => (value: unknown): value is string => isString(value) && value.length > 0 && value.length <= maximum;
// Links da segunda via só do domínio do Asaas, em HTTPS (nunca outro destino vindo da resposta).
const asaasLink = (value: unknown): value is string => isString(value) && /^https:\/\/([a-z]+\.)?asaas\.com\/[A-Za-z0-9/_.-]{1,200}$/.test(value);
const money = (value: unknown): value is number => isInteger(value) && value <= 200_000_000;

/** R$ 1.234,56 a partir de centavos. */
export function formatCents(cents: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(cents / 100);
}
/**
 * Valor em reais (formato brasileiro) para centavos; null quando inválido ou ambíguo. Vírgula é sempre o separador
 * decimal e ponto, com vírgula ou em grupos de três ("12.345", "1.234.567"), é separador de milhar. Sem vírgula, ponto
 * só vale como decimal com 1 ou 2 casas ("150.5"); qualquer outro uso é recusado.
 */
export function parseCents(value: string): number | null {
  const cleaned = value.trim().replace(/^R\$\s*/, '');
  let reais: string; let fraction = '';
  if (/^(\d{1,3}(\.\d{3})+|\d+),\d{1,2}$/.test(cleaned)) [reais, fraction] = cleaned.split(',') as [string, string];
  else if (/^\d{1,3}(\.\d{3})+$/.test(cleaned) || /^\d+$/.test(cleaned)) reais = cleaned;
  else if (/^\d+\.\d{1,2}$/.test(cleaned)) [reais, fraction] = cleaned.split('.') as [string, string];
  else return null;
  const cents = Number(reais.replace(/\./g, '')) * 100 + Number(fraction.padEnd(2, '0') || '0');
  return Number.isSafeInteger(cents) ? cents : null;
}
/** Data civil AAAA-MM-DD como DD/MM/AAAA, sem conversão de fuso. */
export function formatDay(value: string): string {
  const [year, month, date] = value.split('-');
  return `${date}/${month}/${year}`;
}

export function financePayer(value: unknown, uncertain = false): FinancePayer {
  if (!record(value) || !isUUID(value.payerId) || !isUUID(value.membershipId) || !text(160)(value.displayName)
      || !oneOf(value.role, ['OWNER', 'RESPONSIBLE'] as const) || !text(120)(value.legalName) || !text(40)(value.document)
      || !text(254)(value.email) || typeof value.customerSynced !== 'boolean' || !isDate(value.createdAt) || !isInteger(value.version)
      || /\d{3}\.?\d{3}\.?\d{3}-?\d{2}$/.test(value.document)) throw invalidResponse(uncertain);
  return { payerId: value.payerId, membershipId: value.membershipId, displayName: value.displayName, role: value.role, legalName: value.legalName,
    document: value.document, email: value.email, customerSynced: value.customerSynced, createdAt: value.createdAt, version: value.version };
}
function financeUnit(value: unknown): FinanceUnit {
  if (!record(value) || !isUUID(value.unitId) || !text(40)(value.unitLabel) || !nullable(text(60))(value.blockName)
      || typeof value.rented !== 'boolean') throw invalidResponse();
  const candidates = list(value.candidates, (item) => {
    if (!record(item) || !isUUID(item.membershipId) || !text(160)(item.displayName) || !oneOf(item.role, ['OWNER', 'RESPONSIBLE'] as const))
      throw invalidResponse();
    return { membershipId: item.membershipId, displayName: item.displayName, role: item.role };
  });
  return { unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null, rented: value.rented,
    payer: value.payer === null ? null : financePayer(value.payer), candidates };
}
export function financeCharge(value: unknown, uncertain = false): FinanceCharge {
  if (!record(value) || !isUUID(value.chargeId) || !isUUID(value.unitId) || !text(40)(value.unitLabel) || !nullable(text(60))(value.blockName)
      || !oneOf(value.kind, chargeKinds) || !text(200)(value.description) || !money(value.amountCents) || !day(value.dueDate)
      || !oneOf(value.billingType, billingTypes) || !oneOf(value.state, chargeStates) || !nullable(text(40))(value.asaasStatus)
      || !nullable(day)(value.competence) || !nullable(asaasLink)(value.invoiceUrl) || !nullable(asaasLink)(value.bankSlipUrl)
      || !nullable(day)(value.paidAt) || !nullable(money)(value.paidCents) || !nullable(text(64))(value.issueError)
      || !nullable(text(500))(value.cancelReason) || !isDate(value.createdAt) || !isDate(value.updatedAt) || !isInteger(value.version))
    throw invalidResponse(uncertain);
  if ((value.state === 'PAID' || value.state === 'REFUNDED') && value.paidAt === null) throw invalidResponse(uncertain);
  return { chargeId: value.chargeId, unitId: value.unitId, unitLabel: value.unitLabel, blockName: value.blockName as string | null,
    kind: value.kind, description: value.description, amountCents: value.amountCents, dueDate: value.dueDate, billingType: value.billingType,
    state: value.state, asaasStatus: value.asaasStatus as string | null, competence: value.competence as string | null,
    invoiceUrl: value.invoiceUrl as string | null, bankSlipUrl: value.bankSlipUrl as string | null, paidAt: value.paidAt as string | null,
    paidCents: value.paidCents as number | null, issueError: value.issueError as string | null, cancelReason: value.cancelReason as string | null,
    createdAt: value.createdAt, updatedAt: value.updatedAt, version: value.version };
}
function chargePage(value: unknown): ChargePage {
  if (!record(value) || typeof value.truncated !== 'boolean' || !Array.isArray(value.charges) || value.charges.length > 200) throw invalidResponse();
  return { charges: list(value.charges, (item) => financeCharge(item)), truncated: value.truncated };
}
export function paymentInfo(value: unknown, chargeId: string): PaymentInfo {
  if (!record(value) || value.chargeId !== chargeId || !oneOf(value.billingType, billingTypes)
      || !nullable((v: unknown): v is string => isString(v) && /^[0-9 .]{20,64}$/.test(v))(value.identificationField)
      || !nullable((v: unknown): v is string => isString(v) && /^[\x20-\x7e]{20,1024}$/.test(v))(value.pixPayload)
      || !nullable(asaasLink)(value.invoiceUrl) || !nullable(asaasLink)(value.bankSlipUrl) || !day(value.dueDate) || !money(value.amountCents))
    throw invalidResponse();
  if (value.billingType === 'BOLETO' ? value.identificationField === null : value.pixPayload === null) throw invalidResponse();
  return { chargeId, billingType: value.billingType, identificationField: value.identificationField as string | null,
    pixPayload: value.pixPayload as string | null, invoiceUrl: value.invoiceUrl as string | null, bankSlipUrl: value.bankSlipUrl as string | null,
    dueDate: value.dueDate, amountCents: value.amountCents };
}
function recurringFee(value: unknown, uncertain = false): RecurringFee {
  if (!record(value) || !isUUID(value.feeId) || !nullable(isUUID)(value.unitId) || !nullable(text(40))(value.unitLabel)
      || !oneOf(value.kind, ['CONDO_FEE', 'OTHER'] as const) || !text(200)(value.description) || !money(value.amountCents)
      || !isInteger(value.dueDay) || value.dueDay < 1 || value.dueDay > 28 || !oneOf(value.billingType, billingTypes)
      || !oneOf(value.state, ['ACTIVE', 'ENDED'] as const) || !isDate(value.createdAt) || !isInteger(value.version)) throw invalidResponse(uncertain);
  return { feeId: value.feeId, unitId: value.unitId as string | null, unitLabel: value.unitLabel as string | null, kind: value.kind,
    description: value.description, amountCents: value.amountCents, dueDay: value.dueDay, billingType: value.billingType, state: value.state,
    createdAt: value.createdAt, version: value.version };
}
const counts = <K extends string>(keys: readonly K[]) => (value: unknown): Record<K, number> => {
  if (!record(value) || !keys.every((key) => isInteger(value[key]))) throw invalidResponse(true);
  return Object.fromEntries(keys.map((key) => [key, value[key] as number])) as Record<K, number>;
};
function delinquency(value: unknown): DelinquencyReport {
  if (!record(value) || !day(value.referenceDate) || !money(value.totalCents)) throw invalidResponse();
  const units = list(value.units, (item) => {
    if (!record(item) || !isUUID(item.unitId) || !text(40)(item.unitLabel) || !nullable(text(60))(item.blockName) || !isInteger(item.charges)
        || item.charges < 1 || !money(item.totalCents) || !day(item.oldestDue) || item.oldestDue >= (value.referenceDate as string)) throw invalidResponse();
    return { unitId: item.unitId, unitLabel: item.unitLabel, blockName: item.blockName as string | null, charges: item.charges,
      totalCents: item.totalCents, oldestDue: item.oldestDue };
  });
  return { referenceDate: value.referenceDate, units, totalCents: value.totalCents };
}
function report(value: unknown): MonthSummary[] {
  if (!record(value)) throw invalidResponse();
  return list(value.months, (item) => {
    if (!record(item) || !isString(item.month) || !/^\d{4}-\d{2}$/.test(item.month) || !money(item.issuedCents) || !money(item.paidCents)
        || !money(item.openCents) || !money(item.overdueCents) || !isInteger(item.cancelled)) throw invalidResponse();
    return { month: item.month, issuedCents: item.issuedCents, paidCents: item.paidCents, openCents: item.openCents,
      overdueCents: item.overdueCents, cancelled: item.cancelled };
  });
}

export function createFinanceApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/management/finance`;
  const unit = (tenantId: string, unitId: string) => `/v1/tenants/${identifier(tenantId)}/units/${identifier(unitId)}/finance/charges`;
  const options = (signal?: AbortSignal) => signal ? { signal } : {};
  const same = (chargeId: string) => (value: unknown) => {
    const charge = financeCharge(value, true);
    if (charge.chargeId !== chargeId) throw invalidResponse(true);
    return charge;
  };
  return {
    units: async (tenantId: string, signal?: AbortSignal) => client.call(`${base(tenantId)}/units`, (value) => list(value, financeUnit), options(signal)),
    setPayer: async (tenantId: string, unitId: string, body: PayerChange) =>
      client.call(`${base(tenantId)}/units/${identifier(unitId)}/payer`, (value) => {
        const payer = financePayer(value, true);
        if (payer.membershipId !== body.membershipId) throw invalidResponse(true);
        return payer;
      }, { method: 'PUT', body }),
    charges: async (tenantId: string, state: ChargeState | null, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/charges${state ? `?state=${encodeURIComponent(state)}` : ''}`, (value) => {
        const page = chargePage(value);
        if (state && page.charges.some((item) => item.state !== state)) throw invalidResponse();
        return page;
      }, options(signal)),
    create: async (tenantId: string, key: string, body: ChargeCreate) =>
      client.call(`${base(tenantId)}/charges`, (value) => {
        const charge = financeCharge(value, true);
        if (charge.unitId !== body.unitId || charge.amountCents !== body.amountCents || charge.dueDate !== body.dueDate
          || charge.billingType !== body.billingType) throw invalidResponse(true);
        return charge;
      }, { method: 'POST', body, key }),
    issue: async (tenantId: string, chargeId: string) =>
      client.call(`${base(tenantId)}/charges/${identifier(chargeId)}/issue`, same(chargeId), { method: 'POST', body: {} }),
    issuePending: async (tenantId: string) =>
      client.call(`${base(tenantId)}/charges/issue-pending`, counts(['issued', 'failed', 'pending'] as const), { method: 'POST', body: {} }),
    cancel: async (tenantId: string, chargeId: string, expectedVersion: number, reason: string) =>
      client.call(`${base(tenantId)}/charges/${identifier(chargeId)}/cancel`, (value) => {
        const charge = same(chargeId)(value);
        if (charge.state !== 'CANCELLED') throw invalidResponse(true);
        return charge;
      }, { method: 'POST', body: { reason, expectedVersion } }),
    sync: async (tenantId: string, chargeId: string) =>
      client.call(`${base(tenantId)}/charges/${identifier(chargeId)}/sync`, same(chargeId), { method: 'POST', body: {} }),
    fees: async (tenantId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/recurring-fees`, (value) => list(value, (item) => recurringFee(item)), options(signal)),
    createFee: async (tenantId: string, body: RecurringCreate) =>
      client.call(`${base(tenantId)}/recurring-fees`, (value) => {
        const fee = recurringFee(value, true);
        if (fee.amountCents !== body.amountCents || fee.dueDay !== body.dueDay || fee.state !== 'ACTIVE') throw invalidResponse(true);
        return fee;
      }, { method: 'POST', body }),
    endFee: async (tenantId: string, feeId: string, reason: string) =>
      client.call(`${base(tenantId)}/recurring-fees/${identifier(feeId)}/end`, (value) => {
        const fee = recurringFee(value, true);
        if (fee.feeId !== feeId || fee.state !== 'ENDED') throw invalidResponse(true);
        return fee;
      }, { method: 'POST', body: { reason } }),
    generate: async (tenantId: string, competence: string, reason: string) =>
      client.call(`${base(tenantId)}/recurring-fees/generate`, counts(['created', 'skipped', 'issued', 'pending'] as const),
        { method: 'POST', body: { competence, reason } }),
    delinquency: async (tenantId: string, signal?: AbortSignal) => client.call(`${base(tenantId)}/delinquency`, delinquency, options(signal)),
    report: async (tenantId: string, from: string, until: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/report?from=${encodeURIComponent(from)}&until=${encodeURIComponent(until)}`, report, options(signal)),
    // Unidade.
    unitCharges: async (tenantId: string, unitId: string, signal?: AbortSignal) =>
      client.call(unit(tenantId, unitId), (value) => {
        const page = chargePage(value);
        if (page.charges.some((item) => item.unitId !== unitId || item.state === 'PENDING_ISSUE')) throw invalidResponse();
        return page;
      }, options(signal)),
    payment: async (tenantId: string, unitId: string, chargeId: string, signal?: AbortSignal) =>
      client.call(`${unit(tenantId, unitId)}/${identifier(chargeId)}/payment`, (value) => paymentInfo(value, chargeId), options(signal)),
  };
}
export const finance = createFinanceApi();
