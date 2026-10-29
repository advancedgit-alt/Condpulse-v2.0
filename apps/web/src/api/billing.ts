import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/** Financeiro da plataforma: assinatura CondPulse dos condomínios. Pagamento só por registro manual com comprovante. */
export type BillingPeriod = 'MONTHLY' | 'YEARLY';
export type PaymentMethod = 'PIX' | 'TRANSFERENCIA' | 'BOLETO' | 'OUTRO';
export type ChargeStatus = 'OPEN' | 'PAID' | 'CANCELLED';
export type ChargeFilter = ChargeStatus | 'OVERDUE';
/** Data civil no formato AAAA-MM-DD (sem fuso). */
export type CivilDate = string;
export interface BillingPlan { planId: UUID; name: string; priceCents: number; period: BillingPeriod; active: boolean; version: number; createdAt: ISODateTime; updatedAt: ISODateTime }
export interface BillingPlanChange { plan: BillingPlan; applied: boolean }
export interface BillingSubscription {
  subscriptionId: UUID; tenantId: UUID; tenantName: string; planId: UUID; planName: string; priceCents: number; period: BillingPeriod;
  startsOn: CivilDate; endsOn: CivilDate | null; dueDay: number; status: 'ACTIVE' | 'ENDED'; version: number; createdAt: ISODateTime;
}
export interface BillingPayment { paymentId: UUID; amountCents: number; paidOn: CivilDate; method: PaymentMethod; reference: string; recordedBy: UUID; recordedAt: ISODateTime }
export interface BillingCharge {
  chargeId: UUID; subscriptionId: UUID; tenantId: UUID; tenantName: string; periodStart: CivilDate; periodEnd: CivilDate; amountCents: number;
  dueOn: CivilDate; status: ChargeStatus; overdue: boolean; cancelReason: string | null; version: number; createdAt: ISODateTime;
  payment: BillingPayment | null;
}
export interface BillingChargePage { charges: BillingCharge[]; truncated: boolean }

const PERIODS: readonly BillingPeriod[] = ['MONTHLY', 'YEARLY'];
const METHODS: readonly PaymentMethod[] = ['PIX', 'TRANSFERENCIA', 'BOLETO', 'OUTRO'];
/** Data civil existente (2030-02-30 é recusada): o calendário confere ano, mês e dia de volta. */
export function isCivilDate(value: unknown): value is CivilDate {
  if (!isString(value) || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day));
  return year >= 1000 && date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}
const civil = isCivilDate;
/** Valores financeiros sempre positivos: não há plano, cobrança ou pagamento de R$ 0. */
const cents = (value: unknown): value is number => isInteger(value) && value >= 1 && value <= 100_000_000;

export function billingPlan(value: unknown, uncertain = false): BillingPlan {
  if (!record(value) || !isUUID(value.planId) || !isString(value.name) || !cents(value.priceCents) || !oneOf(value.period, PERIODS)
      || typeof value.active !== 'boolean' || !isInteger(value.version) || !isDate(value.createdAt) || !isDate(value.updatedAt)) throw invalidResponse(uncertain);
  return { planId: value.planId, name: value.name, priceCents: value.priceCents, period: value.period, active: value.active,
    version: value.version, createdAt: value.createdAt, updatedAt: value.updatedAt };
}
export function billingSubscription(value: unknown, uncertain = false): BillingSubscription {
  if (!record(value) || !isUUID(value.subscriptionId) || !isUUID(value.tenantId) || !isString(value.tenantName) || !isUUID(value.planId)
      || !isString(value.planName) || !cents(value.priceCents) || !oneOf(value.period, PERIODS) || !civil(value.startsOn)
      || !(value.endsOn === null || civil(value.endsOn)) || !isInteger(value.dueDay) || value.dueDay < 1 || value.dueDay > 28
      || !oneOf(value.status, ['ACTIVE', 'ENDED']) || !isInteger(value.version) || !isDate(value.createdAt)
      || (value.status === 'ENDED') !== (value.endsOn !== null)) throw invalidResponse(uncertain);
  return { subscriptionId: value.subscriptionId, tenantId: value.tenantId, tenantName: value.tenantName, planId: value.planId,
    planName: value.planName, priceCents: value.priceCents, period: value.period, startsOn: value.startsOn, endsOn: value.endsOn as CivilDate | null,
    dueDay: value.dueDay, status: value.status, version: value.version, createdAt: value.createdAt };
}
function payment(value: unknown): BillingPayment | null {
  if (value === null) return null;
  if (!record(value) || !isUUID(value.paymentId) || !cents(value.amountCents) || !civil(value.paidOn) || !oneOf(value.method, METHODS)
      || !isString(value.reference) || !isUUID(value.recordedBy) || !isDate(value.recordedAt)) throw invalidResponse();
  return { paymentId: value.paymentId, amountCents: value.amountCents, paidOn: value.paidOn, method: value.method, reference: value.reference,
    recordedBy: value.recordedBy, recordedAt: value.recordedAt };
}
/** "Pago" só é aceito com o pagamento registrado do mesmo valor; resposta incoerente nunca vira pagamento na tela. */
export function billingCharge(value: unknown, uncertain = false): BillingCharge {
  if (!record(value) || !isUUID(value.chargeId) || !isUUID(value.subscriptionId) || !isUUID(value.tenantId) || !isString(value.tenantName)
      || !civil(value.periodStart) || !civil(value.periodEnd) || !cents(value.amountCents) || !civil(value.dueOn)
      || !oneOf(value.status, ['OPEN', 'PAID', 'CANCELLED']) || typeof value.overdue !== 'boolean'
      || !(value.cancelReason === null || isString(value.cancelReason)) || !isInteger(value.version) || !isDate(value.createdAt)
      || !('payment' in value)) throw invalidResponse(uncertain);
  let recorded: BillingPayment | null;
  try { recorded = payment(value.payment); } catch { throw invalidResponse(uncertain); }
  const consistent = value.status === 'PAID' ? recorded !== null && recorded.amountCents === value.amountCents : recorded === null;
  if (!consistent || (value.overdue && value.status !== 'OPEN') || (value.status === 'CANCELLED') !== (value.cancelReason !== null))
    throw invalidResponse(uncertain);
  return { chargeId: value.chargeId, subscriptionId: value.subscriptionId, tenantId: value.tenantId, tenantName: value.tenantName,
    periodStart: value.periodStart, periodEnd: value.periodEnd, amountCents: value.amountCents, dueOn: value.dueOn, status: value.status,
    overdue: value.overdue, cancelReason: value.cancelReason as string | null, version: value.version, createdAt: value.createdAt, payment: recorded };
}
const list = <T>(decode: (item: unknown) => T, maximum: number) => (value: unknown): T[] => {
  if (!Array.isArray(value) || value.length > maximum) throw invalidResponse();
  return value.map(decode);
};
function chargePage(value: unknown): BillingChargePage {
  if (!record(value) || typeof value.truncated !== 'boolean') throw invalidResponse();
  return { charges: list((item) => billingCharge(item), 200)(value.charges), truncated: value.truncated };
}
const mutation = <T>(decode: (value: unknown, uncertain?: boolean) => T) => (value: unknown) => decode(value, true);

export function createBillingApi(client: CondPulseApi = api) {
  const base = '/v1/platform/billing';
  return {
    plans: async (signal?: AbortSignal) => client.call(`${base}/plans`, list((item) => billingPlan(item), 500), signal ? { signal } : {}),
    createPlan: async (body: { name: string; priceCents: number; period: BillingPeriod; reason: string }) =>
      client.call(`${base}/plans`, mutation(billingPlan), { method: 'POST', body }),
    updatePlan: async (planId: string, body: { name: string; priceCents: number; active: boolean; expectedVersion: number; reason: string }) =>
      client.call(`${base}/plans/${identifier(planId)}`, (value): BillingPlanChange => {
        if (!record(value) || typeof value.applied !== 'boolean') throw invalidResponse(true);
        return { plan: billingPlan(value.plan, true), applied: value.applied };
      }, { method: 'PUT', body }),
    subscriptions: async (signal?: AbortSignal) =>
      client.call(`${base}/subscriptions`, list((item) => billingSubscription(item), 500), signal ? { signal } : {}),
    createSubscription: async (body: { tenantId: string; planId: string; startsOn: CivilDate; dueDay: number; reason: string }) =>
      client.call(`${base}/subscriptions`, mutation(billingSubscription), { method: 'POST', body }),
    endSubscription: async (subscriptionId: string, body: { endsOn: CivilDate; expectedVersion: number; reason: string }) =>
      client.call(`${base}/subscriptions/${identifier(subscriptionId)}/end`, mutation(billingSubscription), { method: 'POST', body }),
    createCharge: async (subscriptionId: string, periodStart: CivilDate) =>
      client.call(`${base}/subscriptions/${identifier(subscriptionId)}/charges`, mutation(billingCharge), { method: 'POST', body: { periodStart } }),
    charges: async (filter: ChargeFilter | null, signal?: AbortSignal) =>
      client.call(`${base}/charges${filter ? `?status=${filter}` : ''}`, chargePage, signal ? { signal } : {}),
    cancelCharge: async (chargeId: string, body: { expectedVersion: number; reason: string }) =>
      client.call(`${base}/charges/${identifier(chargeId)}/cancel`, mutation(billingCharge), { method: 'POST', body }),
    recordPayment: async (chargeId: string, body: { amountCents: number; paidOn: CivilDate; method: PaymentMethod; reference: string; expectedVersion: number }) =>
      client.call(`${base}/charges/${identifier(chargeId)}/payments`, mutation(billingCharge), { method: 'POST', body }),
    voidPayment: async (paymentId: string, body: { expectedChargeVersion: number; reason: string }) =>
      client.call(`${base}/payments/${identifier(paymentId)}/void`, mutation(billingCharge), { method: 'POST', body }),
  };
}
export const billing = createBillingApi();
