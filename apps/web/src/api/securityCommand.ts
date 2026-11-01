import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import { securityLogEvent, type SecurityLogEvent } from './securityLog';
import type { ISODateTime, UUID } from './types';

/**
 * Comando de Segurança do gestor líder (CPX-012 a CPX-014, V053, SecurityCommandController). Exige security.command e AAL2;
 * com CP_PARTNERS_ENABLED ou CP_SECURITY_LOG_ENABLED desligada as rotas não existem (404, API_NOT_AVAILABLE).
 * Ler a linha do tempo ou as aberturas remotas grava SENSITIVE_VIEWED no servidor.
 */
export const lights = ['RED', 'AMBER', 'GREEN'] as const;
export type Light = typeof lights[number];
export const lightReasons = ['CRITICAL_ALERTS_UNLINKED', 'CRITICAL_INCIDENTS_OPEN', 'DEVICES_OFFLINE', 'DEVICES_UNKNOWN', 'NO_OPERATOR_ON_DUTY',
  'SECURITY_OCCURRENCES_OPEN', 'INCIDENTS_OPEN'] as const;
export type LightReason = typeof lightReasons[number];
export const unavailableIndicators = ['DOORS_HELD_OPEN', 'STAYS_OVERDUE'] as const;
export const incidentSeverities = ['ATTENTION', 'CRITICAL'] as const;
export type IncidentSeverity = typeof incidentSeverities[number];
export const incidentOutcomes = ['RESOLVED', 'FALSE_ALARM', 'REFERRED'] as const;
export type IncidentOutcome = typeof incidentOutcomes[number];
export const reviewVerdicts = ['CONFERRED', 'IRREGULAR'] as const;
export type ReviewVerdict = typeof reviewVerdicts[number];
export const reviewFilters = ['PENDING', 'REVIEWED', 'ALL'] as const;
export type ReviewFilter = typeof reviewFilters[number];
const teamRoles = ['SECURITY_LEAD', 'SECURITY_SUPERVISOR', 'REMOTE_OPERATOR'] as const;

export interface Indicators {
  devicesTotal: number; devicesOffline: number; devicesUnknown: number; securityOccurrencesOpen: number; criticalAlertsUnlinked: number;
  incidentsOpen: number; incidentsCriticalOpen: number; remoteOpensPendingReview: number; operatorsOnDuty: number;
}
export interface PortfolioTenant { tenantId: UUID; tenantName: string; timeZone: string; light: Light; reasons: LightReason[]; indicators: Indicators }
export interface Portfolio { generatedAt: ISODateTime; criteria: string; unavailableIndicators: string[]; truncated: boolean; tenants: PortfolioTenant[] }
export interface TeamMember { memberId: UUID; accountId: UUID; role: typeof teamRoles[number]; displayName: string }
export interface Incident {
  id: UUID; tenantId: UUID; tenantName: string | null; title: string; severity: IncidentSeverity; state: 'OPEN' | 'CLOSED'; openedBy: UUID;
  openedAt: ISODateTime; assigneeMemberId: UUID | null; assignedAt: ISODateTime | null; outcome: IncidentOutcome | null; report: string | null;
  closedAt: ISODateTime | null; version: number; linkCount: number;
}
export interface IncidentLink { eventId: UUID; linkedBy: UUID; linkedAt: ISODateTime }
export interface IncidentDetail { incident: Incident; links: IncidentLink[]; timeline: SecurityLogEvent[] }
export interface DossierManifest {
  tenantId: UUID; incidentId: UUID; count: number; firstSeq: number; lastSeq: number; eventsDigest: string; canonicalization: string;
  exportedAt: ISODateTime; exportEventId: UUID;
}
/** Dossiê conferido e o corpo original, para o arquivo salvo ser exatamente o que o servidor devolveu. */
export interface Dossier { manifest: DossierManifest; events: SecurityLogEvent[]; raw: unknown }
export interface Review { reviewEventId: UUID; verdict: ReviewVerdict; note: string | null; reviewedBy: UUID | null; reviewedAt: ISODateTime }
export interface RemoteOpen {
  eventId: UUID; seq: number; occurredAt: ISODateTime; recordedAt: ISODateTime; operatorAccountId: UUID | null; operatorRole: string | null;
  reason: string | null; pointId: UUID | null; deviceId: UUID | null; evidenceRef: string | null; review: Review | null;
}
export interface RemoteOpenPage { items: RemoteOpen[]; nextBeforeSeq: number | null }
export interface OpenIncidentBody { tenantId: UUID; title: string; severity: IncidentSeverity; eventIds: UUID[] }
export interface CloseIncidentBody { outcome: IncidentOutcome; report: string; expectedVersion: number }
export interface ReviewBody { verdict: ReviewVerdict; note: string | null; correctsReviewId: UUID | null }

const same = (value: string, expected: string) => value.toLowerCase() === expected.toLowerCase();
const nullable = <T>(value: unknown, check: (item: unknown) => item is T) => value === null || value === undefined || check(value);
const count = (value: unknown): value is number => isInteger(value) && value >= 0;
const digest = (value: unknown): value is string => isString(value) && /^[0-9a-f]{64}$/.test(value);

function indicators(value: unknown): Indicators {
  const keys = ['devicesTotal', 'devicesOffline', 'devicesUnknown', 'securityOccurrencesOpen', 'criticalAlertsUnlinked', 'incidentsOpen',
    'incidentsCriticalOpen', 'remoteOpensPendingReview', 'operatorsOnDuty'] as const;
  if (!record(value) || keys.some((key) => !count(value[key]))) throw invalidResponse();
  return Object.fromEntries(keys.map((key) => [key, value[key]])) as unknown as Indicators;
}

export function portfolio(value: unknown, partnerId: string): Portfolio {
  if (!record(value) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || !isDate(value.generatedAt) || !isString(value.criteria)
      || !Array.isArray(value.unavailableIndicators) || !value.unavailableIndicators.every(isString) || typeof value.truncated !== 'boolean'
      || !Array.isArray(value.tenants) || value.tenants.length > 200) throw invalidResponse();
  const tenants = value.tenants.map((item): PortfolioTenant => {
    if (!record(item) || !isUUID(item.tenantId) || !isString(item.tenantName) || !isString(item.timeZone) || !oneOf(item.light, lights)
        || !Array.isArray(item.reasons) || !item.reasons.every((reason) => oneOf(reason, lightReasons))) throw invalidResponse();
    // Cor e motivos contam a mesma história: verde sem motivo; vermelho com motivo vermelho.
    const reasons = item.reasons as LightReason[];
    const red = reasons.some((reason) => reason === 'CRITICAL_ALERTS_UNLINKED' || reason === 'CRITICAL_INCIDENTS_OPEN');
    if ((item.light === 'GREEN') !== (reasons.length === 0) || (item.light === 'RED') !== red) throw invalidResponse();
    return { tenantId: item.tenantId, tenantName: item.tenantName, timeZone: item.timeZone, light: item.light, reasons, indicators: indicators(item.indicators) };
  });
  return { generatedAt: value.generatedAt, criteria: value.criteria, unavailableIndicators: value.unavailableIndicators as string[], truncated: value.truncated, tenants };
}

export function team(value: unknown, partnerId: string): TeamMember[] {
  if (!record(value) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || !Array.isArray(value.members)) throw invalidResponse();
  return value.members.map((item) => {
    if (!record(item) || !isUUID(item.memberId) || !isUUID(item.accountId) || !oneOf(item.role, teamRoles) || !isString(item.displayName)) throw invalidResponse();
    return { memberId: item.memberId, accountId: item.accountId, role: item.role, displayName: item.displayName };
  });
}

export function incident(value: unknown, uncertain = false): Incident {
  if (!record(value) || !isUUID(value.id) || !isUUID(value.tenantId) || !nullable(value.tenantName, isString) || !isString(value.title)
      || !oneOf(value.severity, incidentSeverities) || !oneOf(value.state, ['OPEN', 'CLOSED'] as const) || !isUUID(value.openedBy) || !isDate(value.openedAt)
      || !nullable(value.assigneeMemberId, isUUID) || !nullable(value.assignedAt, isDate) || !(value.outcome === null || value.outcome === undefined || oneOf(value.outcome, incidentOutcomes))
      || !nullable(value.report, isString) || !nullable(value.closedAt, isDate) || !count(value.version) || !count(value.linkCount)) throw invalidResponse(uncertain);
  // Encerrado tem desfecho, relatório e horário; aberto não.
  const closed = value.state === 'CLOSED';
  if (closed !== (value.outcome != null && value.report != null && value.closedAt != null)) throw invalidResponse(uncertain);
  return { id: value.id, tenantId: value.tenantId, tenantName: (value.tenantName as string | null | undefined) ?? null, title: value.title, severity: value.severity,
    state: value.state, openedBy: value.openedBy, openedAt: value.openedAt, assigneeMemberId: (value.assigneeMemberId as string | null | undefined) ?? null,
    assignedAt: (value.assignedAt as string | null | undefined) ?? null, outcome: (value.outcome as IncidentOutcome | null | undefined) ?? null,
    report: (value.report as string | null | undefined) ?? null, closedAt: (value.closedAt as string | null | undefined) ?? null, version: value.version, linkCount: value.linkCount };
}

export function incidentPage(value: unknown, partnerId: string): { truncated: boolean; incidents: Incident[] } {
  if (!record(value) || !isUUID(value.partnerId) || !same(value.partnerId, partnerId) || typeof value.truncated !== 'boolean'
      || !Array.isArray(value.incidents) || value.incidents.length > 200) throw invalidResponse();
  return { truncated: value.truncated, incidents: value.incidents.map((item) => incident(item)) };
}

function links(value: unknown, uncertain: boolean): IncidentLink[] {
  if (!Array.isArray(value)) throw invalidResponse(uncertain);
  return value.map((item) => {
    if (!record(item) || !isUUID(item.eventId) || !isUUID(item.linkedBy) || !isDate(item.linkedAt)) throw invalidResponse(uncertain);
    return { eventId: item.eventId, linkedBy: item.linkedBy, linkedAt: item.linkedAt };
  });
}

export function incidentDetail(value: unknown, tenantId: string, uncertain = false): IncidentDetail {
  if (!record(value) || !Array.isArray(value.timeline) || value.timeline.length > 500) throw invalidResponse(uncertain);
  const parsed = incident(value.incident, uncertain);
  if (!same(parsed.tenantId, tenantId)) throw invalidResponse(uncertain);
  const timeline = value.timeline.map((item) => securityLogEvent(item, uncertain));
  // Linha do tempo em ordem de seq, sem repetição.
  if (timeline.some((event, index) => index > 0 && event.seq <= timeline[index - 1]!.seq)) throw invalidResponse(uncertain);
  return { incident: parsed, links: links(value.links, uncertain), timeline };
}

export function dossier(value: unknown, tenantId: string, incidentId: string): Dossier {
  if (!record(value) || !record(value.manifest) || !Array.isArray(value.events)) throw invalidResponse(true);
  const manifest = value.manifest;
  if (!isUUID(manifest.tenantId) || !same(manifest.tenantId, tenantId) || !isUUID(manifest.incidentId) || !same(manifest.incidentId, incidentId)
      || !isInteger(manifest.count) || !isInteger(manifest.firstSeq) || !isInteger(manifest.lastSeq) || !digest(manifest.eventsDigest)
      || !isString(manifest.canonicalization) || !isDate(manifest.exportedAt) || !isUUID(manifest.exportEventId)) throw invalidResponse(true);
  const events = value.events.map((item) => securityLogEvent(item, true));
  if (events.length === 0 || manifest.count !== events.length || events[0]!.seq !== manifest.firstSeq || events.at(-1)!.seq !== manifest.lastSeq
      || events.some((event, index) => index > 0 && event.seq <= events[index - 1]!.seq)) throw invalidResponse(true);
  return { manifest: { tenantId: manifest.tenantId, incidentId: manifest.incidentId, count: manifest.count, firstSeq: manifest.firstSeq,
    lastSeq: manifest.lastSeq, eventsDigest: manifest.eventsDigest, canonicalization: manifest.canonicalization, exportedAt: manifest.exportedAt,
    exportEventId: manifest.exportEventId }, events, raw: value };
}

export function remoteOpen(value: unknown, uncertain = false): RemoteOpen {
  if (!record(value) || !isUUID(value.eventId) || !isInteger(value.seq) || value.seq < 1 || !isDate(value.occurredAt) || !isDate(value.recordedAt)
      || !nullable(value.operatorAccountId, isUUID) || !nullable(value.operatorRole, isString) || !nullable(value.reason, isString)
      || !nullable(value.pointId, isUUID) || !nullable(value.deviceId, isUUID) || !nullable(value.evidenceRef, isString)) throw invalidResponse(uncertain);
  let review: Review | null = null;
  if (value.review !== null && value.review !== undefined) {
    const item = value.review;
    if (!record(item) || !isUUID(item.reviewEventId) || !oneOf(item.verdict, reviewVerdicts) || !nullable(item.note, isString)
        || !nullable(item.reviewedBy, isUUID) || !isDate(item.reviewedAt)) throw invalidResponse(uncertain);
    review = { reviewEventId: item.reviewEventId, verdict: item.verdict, note: (item.note as string | null | undefined) ?? null,
      reviewedBy: (item.reviewedBy as string | null | undefined) ?? null, reviewedAt: item.reviewedAt };
  }
  return { eventId: value.eventId, seq: value.seq, occurredAt: value.occurredAt, recordedAt: value.recordedAt,
    operatorAccountId: (value.operatorAccountId as string | null | undefined) ?? null, operatorRole: (value.operatorRole as string | null | undefined) ?? null,
    reason: (value.reason as string | null | undefined) ?? null, pointId: (value.pointId as string | null | undefined) ?? null,
    deviceId: (value.deviceId as string | null | undefined) ?? null, evidenceRef: (value.evidenceRef as string | null | undefined) ?? null, review };
}

/** Página em ordem decrescente de seq; o cursor seguinte é a menor seq da página. */
export function remoteOpenPage(value: unknown, tenantId: string, limit: number): RemoteOpenPage {
  if (!record(value) || !isUUID(value.tenantId) || !same(value.tenantId, tenantId) || !Array.isArray(value.items) || value.items.length > limit
      || !(value.nextBeforeSeq === null || isInteger(value.nextBeforeSeq))) throw invalidResponse();
  const items = value.items.map((item) => remoteOpen(item));
  if (items.some((item, index) => index > 0 && item.seq >= items[index - 1]!.seq)) throw invalidResponse();
  const next = value.nextBeforeSeq as number | null;
  if (next !== null && (items.length === 0 || next !== items.at(-1)!.seq)) throw invalidResponse();
  return { items, nextBeforeSeq: next };
}

export const REMOTE_OPEN_PAGE = 50;

export function createSecurityCommandApi(client: CondPulseApi = api) {
  const base = (partnerId: string) => `/v1/partners/${identifier(partnerId)}/command`;
  const incidentPath = (partnerId: string, tenantId: string, incidentId: string) =>
    `${base(partnerId)}/tenants/${identifier(tenantId)}/incidents/${identifier(incidentId)}`;
  const signalOf = (signal?: AbortSignal) => signal ? { signal } : {};
  return {
    portfolio: async (partnerId: string, signal?: AbortSignal) => client.call(`${base(partnerId)}/portfolio`, (value) => portfolio(value, partnerId), signalOf(signal)),
    team: async (partnerId: string, signal?: AbortSignal) => client.call(`${base(partnerId)}/team`, (value) => team(value, partnerId), signalOf(signal)),
    incidents: async (partnerId: string, state: 'OPEN' | 'CLOSED', signal?: AbortSignal) =>
      client.call(`${base(partnerId)}/incidents?state=${state}`, (value) => incidentPage(value, partnerId), signalOf(signal)),
    open: async (partnerId: string, body: OpenIncidentBody) => client.call(`${base(partnerId)}/incidents`, (value) => {
      const detail = incidentDetail(value, body.tenantId, true);
      if (detail.incident.state !== 'OPEN' || detail.incident.title !== body.title) throw invalidResponse(true);
      return detail;
    }, { method: 'POST', body: { ...body, tenantId: identifier(body.tenantId), eventIds: body.eventIds.map(identifier) } }),
    detail: async (partnerId: string, tenantId: string, incidentId: string, signal?: AbortSignal) =>
      client.call(incidentPath(partnerId, tenantId, incidentId), (value) => incidentDetail(value, tenantId), signalOf(signal)),
    assign: async (partnerId: string, tenantId: string, incidentId: string, memberId: string, expectedVersion: number) =>
      client.call(`${incidentPath(partnerId, tenantId, incidentId)}/assignment`, (value) => {
        const detail = incidentDetail(value, tenantId, true);
        if (!same(detail.incident.assigneeMemberId ?? '', memberId)) throw invalidResponse(true);
        return detail;
      }, { method: 'POST', body: { memberId: identifier(memberId), expectedVersion } }),
    link: async (partnerId: string, tenantId: string, incidentId: string, eventIds: string[]) =>
      client.call(`${incidentPath(partnerId, tenantId, incidentId)}/links`, (value) => incidentDetail(value, tenantId, true),
        { method: 'POST', body: { eventIds: eventIds.map(identifier) } }),
    close: async (partnerId: string, tenantId: string, incidentId: string, body: CloseIncidentBody) =>
      client.call(`${incidentPath(partnerId, tenantId, incidentId)}/close`, (value) => {
        const detail = incidentDetail(value, tenantId, true);
        if (detail.incident.state !== 'CLOSED') throw invalidResponse(true);
        return detail;
      }, { method: 'POST', body }),
    dossier: async (partnerId: string, tenantId: string, incidentId: string, reason: string) =>
      client.call(`${incidentPath(partnerId, tenantId, incidentId)}/dossier`, (value) => dossier(value, tenantId, incidentId), { method: 'POST', body: { reason } }),
    remoteOpens: async (partnerId: string, tenantId: string, filter: { status: ReviewFilter; beforeSeq?: number }, signal?: AbortSignal) => {
      const query = new URLSearchParams({ status: filter.status, limit: String(REMOTE_OPEN_PAGE) });
      if (filter.beforeSeq !== undefined) query.set('beforeSeq', String(filter.beforeSeq));
      return client.call(`${base(partnerId)}/tenants/${identifier(tenantId)}/remote-opens?${query.toString()}`,
        (value) => remoteOpenPage(value, tenantId, REMOTE_OPEN_PAGE), signalOf(signal));
    },
    review: async (partnerId: string, tenantId: string, eventId: string, body: ReviewBody) =>
      client.call(`${base(partnerId)}/tenants/${identifier(tenantId)}/remote-opens/${identifier(eventId)}/reviews`, (value) => {
        const item = remoteOpen(value, true);
        if (!same(item.eventId, eventId) || item.review?.verdict !== body.verdict) throw invalidResponse(true);
        return item;
      }, { method: 'POST', body: { ...body, correctsReviewId: body.correctsReviewId ? identifier(body.correctsReviewId) : null } }),
  };
}
export const securityCommand = createSecurityCommandApi();
