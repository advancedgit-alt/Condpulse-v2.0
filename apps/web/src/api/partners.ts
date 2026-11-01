import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import { partnerRoles, type ISODateTime, type PartnerRole, type UUID } from './types';

/**
 * Painel do parceiro (V049, ADR-0011, PartnerTeamController): própria equipe, escala dos operadores e carteira de
 * condomínios. Tudo exige AAL2 e a capacidade do papel (partner.team.manage ou partner.manage); com
 * CP_PARTNERS_ENABLED=false as rotas não existem (404, API_NOT_AVAILABLE). Nenhum dado pessoal além do nome de exibição.
 */
export interface PartnerMember {
  id: UUID; partnerId: UUID; accountId: UUID; role: PartnerRole; displayName: string; status: 'ACTIVE' | 'REVOKED'; version: number;
  createdAt: ISODateTime; revokedAt: ISODateTime | null; revokeReason: string | null;
}
export interface PartnerContract {
  id: UUID; partnerId: UUID; tenantId: UUID; tenantName: string | null; service: 'SECURITY_OPERATOR'; status: 'ACTIVE' | 'ENDED'; version: number;
  startsAt: ISODateTime; endsAt: ISODateTime | null; endedAt: ISODateTime | null; endReason: string | null;
}
export interface OperatorSchedule {
  id: UUID; partnerId: UUID; tenantId: UUID; memberId: UUID; accountId: UUID; status: 'ACTIVE' | 'CANCELLED'; version: number;
  startsAt: ISODateTime; endsAt: ISODateTime; cancelledAt: ISODateTime | null;
}
/** Contexto anterior na troca (R4.4): só tipo e identificadores. */
export type ContextSwitchFrom =
  | { kind: 'ENTRY' } | { kind: 'PLATFORM' } | { kind: 'TENANT'; tenantId: UUID } | { kind: 'PARTNER'; partnerId: UUID; role: PartnerRole };
export interface ContextSwitchBody { switchId: UUID; role: PartnerRole; from: ContextSwitchFrom }
export const contextSwitchStatuses = ['RECORDED', 'ALREADY_RECORDED', 'NOT_REQUIRED', 'NO_TENANT_REACHED', 'LOG_DISABLED'] as const;
export interface ContextSwitchResult { status: typeof contextSwitchStatuses[number]; recorded: number }
export function contextSwitchResult(value: unknown): ContextSwitchResult {
  if (!record(value) || !oneOf(value.status, contextSwitchStatuses) || !isInteger(value.recorded)
      || (value.status !== 'RECORDED' && value.recorded !== 0)) throw invalidResponse(true);
  return { status: value.status, recorded: value.recorded };
}
export interface AddMemberBody { accountId: UUID; role: PartnerRole; displayName: string; reason: string }
export interface CreateScheduleBody { memberId: UUID; tenantId: UUID; startsAt?: ISODateTime; endsAt: ISODateTime; reason: string }

const nullableDate = (value: unknown) => value === null || isDate(value);
const nullableText = (value: unknown) => value === null || isString(value);
const same = (value: string, expected: string) => value.toLowerCase() === expected.toLowerCase();

export function partnerMember(value: unknown, partnerId: string, uncertain = false): PartnerMember {
  if (!record(value) || !isUUID(value.id) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || !isUUID(value.accountId)
      || !oneOf(value.role, partnerRoles) || !isString(value.displayName) || !oneOf(value.status, ['ACTIVE', 'REVOKED'] as const)
      || !isInteger(value.version) || !isDate(value.createdAt) || !nullableDate(value.revokedAt) || !nullableText(value.revokeReason))
    throw invalidResponse(uncertain);
  // Revogado tem horário; ativo não.
  if ((value.status === 'REVOKED') !== (value.revokedAt !== null)) throw invalidResponse(uncertain);
  return { id: value.id, partnerId: value.partnerId, accountId: value.accountId, role: value.role, displayName: value.displayName, status: value.status,
    version: value.version, createdAt: value.createdAt, revokedAt: value.revokedAt as string | null, revokeReason: value.revokeReason as string | null };
}

export function partnerContract(value: unknown, partnerId: string): PartnerContract {
  if (!record(value) || !isUUID(value.id) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || !isUUID(value.tenantId)
      || !nullableText(value.tenantName) || value.service !== 'SECURITY_OPERATOR' || !oneOf(value.status, ['ACTIVE', 'ENDED'] as const)
      || !isInteger(value.version) || !isDate(value.startsAt) || !nullableDate(value.endsAt) || !nullableDate(value.endedAt)
      || !nullableText(value.endReason)) throw invalidResponse();
  if ((value.status === 'ENDED') !== (value.endedAt !== null)) throw invalidResponse();
  return { id: value.id, partnerId: value.partnerId, tenantId: value.tenantId, tenantName: value.tenantName as string | null, service: 'SECURITY_OPERATOR',
    status: value.status, version: value.version, startsAt: value.startsAt, endsAt: value.endsAt as string | null, endedAt: value.endedAt as string | null,
    endReason: value.endReason as string | null };
}

export function operatorSchedule(value: unknown, partnerId: string, uncertain = false): OperatorSchedule {
  if (!record(value) || !isUUID(value.id) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || !isUUID(value.tenantId)
      || !isUUID(value.memberId) || !isUUID(value.accountId) || !oneOf(value.status, ['ACTIVE', 'CANCELLED'] as const) || !isInteger(value.version)
      || !isDate(value.startsAt) || !isDate(value.endsAt) || Date.parse(value.endsAt) <= Date.parse(value.startsAt) || !nullableDate(value.cancelledAt))
    throw invalidResponse(uncertain);
  if ((value.status === 'CANCELLED') !== (value.cancelledAt !== null)) throw invalidResponse(uncertain);
  return { id: value.id, partnerId: value.partnerId, tenantId: value.tenantId, memberId: value.memberId, accountId: value.accountId, status: value.status,
    version: value.version, startsAt: value.startsAt, endsAt: value.endsAt, cancelledAt: value.cancelledAt as string | null };
}

function page<T>(value: unknown, partnerId: string, key: string, decode: (item: unknown) => T): T[] {
  if (!record(value) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || !Array.isArray(value[key]) || (value[key] as unknown[]).length > 2000)
    throw invalidResponse();
  return (value[key] as unknown[]).map(decode);
}

export function createPartnersApi(client: CondPulseApi = api) {
  const base = (partnerId: string) => `/v1/partners/${identifier(partnerId)}`;
  return {
    team: async (partnerId: string, signal?: AbortSignal) => client.call(`${base(partnerId)}/team`,
      (value) => page(value, partnerId, 'members', (item) => partnerMember(item, partnerId)), signal ? { signal } : {}),
    addMember: async (partnerId: string, body: AddMemberBody) => client.call(`${base(partnerId)}/team`, (value) => {
      const member = partnerMember(value, partnerId, true);
      if (!same(member.accountId, body.accountId) || member.role !== body.role || member.status !== 'ACTIVE') throw invalidResponse(true);
      return member;
    }, { method: 'POST', body: { ...body, accountId: identifier(body.accountId) } }),
    /**
     * Central (partners.manage, AAL2): concede papel no parceiro a uma conta existente. Usado para vincular, de forma
     * explícita e separada, uma pessoa real ao parceiro do condomínio demo (CPX-046).
     */
    platformAddMember: async (partnerId: string, body: AddMemberBody) => client.call(`/v1/platform/partners/${identifier(partnerId)}/members`, (value) => {
      const member = partnerMember(value, partnerId, true);
      if (!same(member.accountId, body.accountId) || member.role !== body.role || member.status !== 'ACTIVE') throw invalidResponse(true);
      return member;
    }, { method: 'POST', body: { ...body, accountId: identifier(body.accountId) } }),
    revokeMember: async (partnerId: string, memberId: string, reason: string) => client.call(`${base(partnerId)}/team/${identifier(memberId)}/revoke`, (value) => {
      const member = partnerMember(value, partnerId, true);
      if (!same(member.id, memberId) || member.status !== 'REVOKED') throw invalidResponse(true);
      return member;
    }, { method: 'POST', body: { reason } }),
    schedules: async (partnerId: string, signal?: AbortSignal) => client.call(`${base(partnerId)}/schedules`,
      (value) => page(value, partnerId, 'schedules', (item) => operatorSchedule(item, partnerId)), signal ? { signal } : {}),
    createSchedule: async (partnerId: string, body: CreateScheduleBody) => client.call(`${base(partnerId)}/schedules`, (value) => {
      const schedule = operatorSchedule(value, partnerId, true);
      if (!same(schedule.memberId, body.memberId) || !same(schedule.tenantId, body.tenantId) || schedule.status !== 'ACTIVE') throw invalidResponse(true);
      return schedule;
    }, { method: 'POST', body: { ...body, memberId: identifier(body.memberId), tenantId: identifier(body.tenantId) } }),
    cancelSchedule: async (partnerId: string, scheduleId: string, reason: string) => client.call(`${base(partnerId)}/schedules/${identifier(scheduleId)}/cancel`, (value) => {
      const schedule = operatorSchedule(value, partnerId, true);
      if (!same(schedule.id, scheduleId) || schedule.status !== 'CANCELLED') throw invalidResponse(true);
      return schedule;
    }, { method: 'POST', body: { reason } }),
    /** R4.4: registra a entrada no painel (CONTEXT_SWITCHED para papéis de segurança); mesma chave não grava de novo. */
    switchContext: async (partnerId: string, body: ContextSwitchBody) => client.call(`${base(partnerId)}/context-switches`, contextSwitchResult,
      { method: 'POST', body: { switchId: identifier(body.switchId), role: body.role, from: body.from.kind === 'TENANT'
        ? { kind: 'TENANT', tenantId: identifier(body.from.tenantId) } : body.from.kind === 'PARTNER'
          ? { kind: 'PARTNER', partnerId: identifier(body.from.partnerId), role: body.from.role } : { kind: body.from.kind } } }),
    portfolio: async (partnerId: string, signal?: AbortSignal) => client.call(`${base(partnerId)}/portfolio`,
      (value) => page(value, partnerId, 'contracts', (item) => partnerContract(item, partnerId)), signal ? { signal } : {}),
  };
}
export const partners = createPartnersApi();
