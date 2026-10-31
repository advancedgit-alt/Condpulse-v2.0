import { api, CondPulseApi, identifier, invalidResponse, isDate, isInteger, isString, isUUID, oneOf, record } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Log de Segurança do condomínio (V050, CPX-009/CPX-010, SecurityLogController). Leitura com security.log.read (parceiro)
 * ou security.log.read.tenant (gestão local), sempre AAL2; cada leitura grava SENSITIVE_VIEWED no servidor. Exportação
 * só com security.log.export e motivo. Com CP_SECURITY_LOG_ENABLED=false as rotas não existem (404, API_NOT_AVAILABLE).
 */
export const securitySeverities = ['INFO', 'ATTENTION', 'CRITICAL'] as const;
export type SecuritySeverity = typeof securitySeverities[number];
export const securitySources = ['CONCIERGE', 'REMOTE_CENTRAL', 'RESIDENT_APP', 'FACIAL', 'QR', 'DEVICE', 'SYSTEM', 'PLATFORM', 'MANAGEMENT'] as const;
export type SecuritySource = typeof securitySources[number];
export const SECURITY_LOG_PAGE = 50;

export interface SecurityLogEvent {
  eventId: UUID; seq: number; occurredAt: ISODateTime; recordedAt: ISODateTime; eventType: string; severity: SecuritySeverity; source: SecuritySource;
  partnerId: UUID | null; actorAccountId: UUID | null; actorRole: string | null; reason: string | null; prevHash: string; hash: string;
}
export interface SecurityLogPage { events: SecurityLogEvent[]; nextBeforeSeq: number | null }
export interface SecurityLogIntegrity {
  intact: boolean; checkedEvents: number; lastSeq: number; lastHash: string; brokenSeq: number | null; problem: string | null; anchorsChecked: number;
  checkedAt: ISODateTime;
}
export interface SecurityLogFilter { type?: string; severity?: SecuritySeverity; beforeSeq?: number; limit?: number }
export interface SecurityLogManifest {
  tenantId: UUID; fromSeq: number; toSeq: number; count: number; firstHash: string; lastHash: string; exportedAt: ISODateTime; exportEventId: UUID;
  canonicalization: string;
}
/** Exportação: o manifesto conferido e o corpo original da resposta, para o arquivo salvo ser exatamente o que o servidor assinou. */
export interface SecurityLogExport { manifest: SecurityLogManifest; events: SecurityLogEvent[]; raw: unknown }

const hash = (value: unknown): value is string => isString(value) && /^[0-9a-f]{64}$/.test(value);
const nullable = <T>(value: unknown, check: (item: unknown) => item is T) => value === null || value === undefined || check(value);
/** Mesmo formato do filtro do servidor (SecurityLogModels.Filter). */
export const EVENT_TYPE = /^[A-Z][A-Z0-9_]{2,39}$/;

export function securityLogEvent(value: unknown, uncertain = false): SecurityLogEvent {
  if (!record(value) || !isUUID(value.eventId) || !isInteger(value.seq) || value.seq < 1 || !isDate(value.occurredAt) || !isDate(value.recordedAt)
      || !isString(value.eventType) || !oneOf(value.severity, securitySeverities) || !oneOf(value.source, securitySources)
      || !nullable(value.partnerId, isUUID) || !nullable(value.actorAccountId, isUUID) || !nullable(value.actorRole, isString)
      || !nullable(value.reason, isString) || !hash(value.prevHash) || !hash(value.hash)) throw invalidResponse(uncertain);
  return { eventId: value.eventId, seq: value.seq, occurredAt: value.occurredAt, recordedAt: value.recordedAt, eventType: value.eventType,
    severity: value.severity, source: value.source, partnerId: (value.partnerId as string | null | undefined) ?? null,
    actorAccountId: (value.actorAccountId as string | null | undefined) ?? null, actorRole: (value.actorRole as string | null | undefined) ?? null,
    reason: (value.reason as string | null | undefined) ?? null, prevHash: value.prevHash, hash: value.hash };
}

/** Página em ordem decrescente de seq, sem repetição; o cursor seguinte é a menor seq da página. */
export function securityLogPage(value: unknown, limit = SECURITY_LOG_PAGE): SecurityLogPage {
  if (!record(value) || !Array.isArray(value.events) || value.events.length > Math.min(limit, 200)
      || !(value.nextBeforeSeq === null || isInteger(value.nextBeforeSeq))) throw invalidResponse();
  const events = value.events.map((item) => securityLogEvent(item));
  for (let index = 1; index < events.length; index++) if (events[index]!.seq >= events[index - 1]!.seq) throw invalidResponse();
  const next = value.nextBeforeSeq as number | null;
  if (next !== null && (events.length === 0 || next !== events.at(-1)!.seq)) throw invalidResponse();
  return { events, nextBeforeSeq: next };
}

export function securityLogIntegrity(value: unknown): SecurityLogIntegrity {
  if (!record(value) || typeof value.intact !== 'boolean' || !isInteger(value.checkedEvents) || !isInteger(value.lastSeq)
      || !hash(value.lastHash) || !(value.brokenSeq === null || isInteger(value.brokenSeq))
      || !(value.problem === null || oneOf(value.problem, ['SEQ_GAP', 'PREV_HASH_MISMATCH', 'HASH_MISMATCH', 'ANCHOR_MISMATCH', 'HEAD_MISMATCH'] as const))
      || !isInteger(value.anchorsChecked) || !isDate(value.checkedAt)) throw invalidResponse();
  // Íntegro não aponta problema; quebrado sempre diz qual.
  if (value.intact !== (value.problem === null)) throw invalidResponse();
  return { intact: value.intact, checkedEvents: value.checkedEvents, lastSeq: value.lastSeq, lastHash: value.lastHash,
    brokenSeq: value.brokenSeq as number | null, problem: value.problem as string | null, anchorsChecked: value.anchorsChecked, checkedAt: value.checkedAt };
}

export function securityLogExport(value: unknown, tenantId: string): SecurityLogExport {
  if (!record(value) || !record(value.manifest) || !Array.isArray(value.events)) throw invalidResponse(true);
  const manifest = value.manifest;
  if (!isUUID(manifest.tenantId) || manifest.tenantId.toLowerCase() !== tenantId.toLowerCase() || !isInteger(manifest.fromSeq) || !isInteger(manifest.toSeq)
      || !isInteger(manifest.count) || !hash(manifest.firstHash) || !hash(manifest.lastHash) || !isDate(manifest.exportedAt)
      || !isUUID(manifest.exportEventId) || !isString(manifest.canonicalization)) throw invalidResponse(true);
  const events = value.events.map((item) => securityLogEvent(item, true));
  // Manifesto e eventos contam a mesma história: intervalo contíguo, encadeado, com os hashes de início e fim do manifesto.
  if (manifest.count !== events.length || manifest.toSeq - manifest.fromSeq + 1 !== events.length || events.length === 0
      || events[0]!.seq !== manifest.fromSeq || events.at(-1)!.seq !== manifest.toSeq || events[0]!.hash !== manifest.firstHash
      || events.at(-1)!.hash !== manifest.lastHash
      || events.some((event, index) => index > 0 && (event.seq !== events[index - 1]!.seq + 1 || event.prevHash !== events[index - 1]!.hash)))
    throw invalidResponse(true);
  return { manifest: { tenantId: manifest.tenantId, fromSeq: manifest.fromSeq, toSeq: manifest.toSeq, count: manifest.count, firstHash: manifest.firstHash,
    lastHash: manifest.lastHash, exportedAt: manifest.exportedAt, exportEventId: manifest.exportEventId, canonicalization: manifest.canonicalization },
  events, raw: value };
}

export function createSecurityLogApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/security-log`;
  return {
    page: async (tenantId: string, filter: SecurityLogFilter = {}, signal?: AbortSignal) => {
      const limit = filter.limit ?? SECURITY_LOG_PAGE;
      const query = new URLSearchParams();
      if (filter.type) query.set('type', filter.type);
      if (filter.severity) query.set('severity', filter.severity);
      if (filter.beforeSeq !== undefined) query.set('beforeSeq', String(filter.beforeSeq));
      query.set('limit', String(limit));
      return client.call(`${base(tenantId)}?${query.toString()}`, (value) => securityLogPage(value, limit), signal ? { signal } : {});
    },
    integrity: async (tenantId: string, signal?: AbortSignal) => client.call(`${base(tenantId)}/integrity`, securityLogIntegrity, signal ? { signal } : {}),
    export: async (tenantId: string, body: { reason: string; fromSeq?: number; toSeq?: number }) =>
      client.call(`${base(tenantId)}/exports`, (value) => securityLogExport(value, tenantId), { method: 'POST', body }),
  };
}
export const securityLog = createSecurityLogApi();
