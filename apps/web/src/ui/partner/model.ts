import { ApiError, isUUID } from '../../api/client';
import { displayText } from '../../api/text';
import { partnerRoles, type PartnerRole } from '../../api/types';
import type { AddMemberBody, CreateScheduleBody } from '../../api/partners';
import { EVENT_TYPE, type SecurityLogFilter, type SecuritySeverity } from '../../api/securityLog';
import { toInstant } from '../dateTime';
import type { CloseIncidentBody, IncidentOutcome, IncidentSeverity, Light, LightReason, OpenIncidentBody, ReviewBody, ReviewFilter, ReviewVerdict } from '../../api/securityCommand';

/**
 * Regras puras dos painéis de parceiro (CPX-004/CPX-005): leitura de falhas, validação local dos formulários e chaves
 * de cache. O servidor valida tudo de novo; aqui só se evita enviar o que ele recusaria com certeza.
 */
export const partnerKey = (accountId: string, partnerId: string, ...parts: unknown[]) => ['private', accountId, 'partner', partnerId.toLowerCase(), ...parts] as const;
export const securityLogKey = (accountId: string, tenantId: string, ...parts: unknown[]) => ['private', accountId, 'security-log', tenantId.toLowerCase(), ...parts] as const;
/** Permanências de um condomínio alcançado (CPX-017), apagadas junto com o log na recusa do condomínio ou do parceiro. */
export const partnerStaysKey = (accountId: string, tenantId: string, ...parts: unknown[]) => ['private', accountId, 'stays', tenantId.toLowerCase(), ...parts] as const;

export type FailureKind = 'off' | 'mfa' | 'denied' | 'unavailable' | 'other';
/**
 * Como a tela reage a uma falha de consulta. 404 sem código conhecido (API_NOT_AVAILABLE): rota inexistente porque a
 * flag do servidor está desligada. 503 ou rede: indisponível agora, com nova tentativa. Recusa de papel, parceiro ou
 * condomínio fora do alcance: acesso negado, sem dado na tela.
 */
export function failureKind(error: unknown): FailureKind {
  if (!(error instanceof ApiError)) return 'other';
  if (error.code === 'API_NOT_AVAILABLE') return 'off';
  if (error.code === 'MFA_REQUIRED') return 'mfa';
  if (['PARTNER_ACCESS_DENIED', 'PARTNER_NOT_FOUND', 'PARTNER_FORBIDDEN', 'TENANT_ACCESS_DENIED', 'STAFF_ACCESS_DENIED', 'STAY_FORBIDDEN'].includes(error.code)) return 'denied';
  if (error.status >= 500 || error.status === 0) return 'unavailable';
  return 'other';
}

/**
 * Tratador único de recusa de autorização das telas de parceiro (consultas e mutações). MFA_REQUIRED abre o segundo
 * fator; recusa do parceiro (papel revogado, parceiro suspenso) derruba o painel inteiro; recusa de um condomínio
 * (contrato encerrado, fora da escala) derruba só os dados daquele condomínio. Pedido sem condomínio recusado por
 * motivo de condomínio também derruba o painel: na dúvida, nada sensível fica na tela.
 */
export type Refusal = { kind: 'mfa' } | { kind: 'partner' } | { kind: 'tenant'; tenantId: string };
export function refusalOf(error: unknown, tenantId?: string): Refusal | null {
  const kind = failureKind(error);
  if (kind === 'mfa') return { kind: 'mfa' };
  if (kind !== 'denied') return null;
  const code = (error as ApiError).code;
  if (!code.startsWith('PARTNER_') && tenantId) return { kind: 'tenant', tenantId: tenantId.toLowerCase() };
  return { kind: 'partner' };
}

/** Estado da porta do painel diante de recusas: segundo fator, painel derrubado ou condomínios derrubados. */
export interface RefusalState { mfa: boolean; partnerDenied: unknown; deniedTenants: ReadonlyMap<string, unknown> }
export const initialRefusalState = (): RefusalState => ({ mfa: false, partnerDenied: null, deniedTenants: new Map() });
/**
 * Efeito de uma recusa: novo estado da porta e prefixos de cache a apagar. MFA e recusa do parceiro apagam todos os
 * dados do painel (parceiro e log de cada condomínio alcançado); recusa de condomínio apaga o log dele e os dados do
 * Comando de Segurança (que misturam condomínios).
 */
export function applyRefusal(state: RefusalState, refusal: Refusal, scope: { accountId: string; partnerId: string; tenantIds: readonly string[] },
  error: unknown): { state: RefusalState; forget: (readonly unknown[])[] } {
  const all = [partnerKey(scope.accountId, scope.partnerId), ...scope.tenantIds.flatMap((tenantId) => [securityLogKey(scope.accountId, tenantId),
    partnerStaysKey(scope.accountId, tenantId)])];
  if (refusal.kind === 'mfa') return { state: { ...state, mfa: true }, forget: all };
  if (refusal.kind === 'partner') return { state: { ...state, partnerDenied: error }, forget: all };
  // Dados do Comando de Segurança misturam condomínios (carteira, incidentes): saem todos e são relidos sem o recusado.
  return { state: { ...state, deniedTenants: new Map(state.deniedTenants).set(refusal.tenantId, error) },
    forget: [securityLogKey(scope.accountId, refusal.tenantId), partnerStaysKey(scope.accountId, refusal.tenantId),
      partnerKey(scope.accountId, scope.partnerId, 'command')] };
}

/**
 * Condomínio exibido a partir da escolha guardada e da lista atual de /v1/me: se o escolhido saiu do alcance (contrato
 * encerrado, escala vencida), cai no primeiro ainda alcançado; sem nenhum, null (estado vazio). Nunca um que saiu.
 */
export function reachedTenant<T extends { tenantId: string }>(tenants: readonly T[], chosen: string): T | null {
  return tenants.find((tenant) => tenant.tenantId.toLowerCase() === chosen.toLowerCase()) ?? tenants[0] ?? null;
}
/** Escolha de formulário que só vale se ainda estiver na lista atual; senão volta a vazio ('Escolha'). */
export const stillReached = (tenantIds: Iterable<string>, chosen: string) =>
  [...tenantIds].some((tenantId) => tenantId.toLowerCase() === chosen.toLowerCase()) ? chosen : '';

export const memberStatusLabels: Record<string, string> = { ACTIVE: 'Ativo', REVOKED: 'Revogado' };
export const scheduleStatusLabels: Record<string, string> = { ACTIVE: 'Ativa', CANCELLED: 'Cancelada' };
export const contractStatusLabels: Record<string, string> = { ACTIVE: 'Ativo', ENDED: 'Encerrado' };
export const severityLabels: Record<SecuritySeverity, string> = { INFO: 'Informativo', ATTENTION: 'Atenção', CRITICAL: 'Crítico' };
export const sourceLabels: Record<string, string> = { CONCIERGE: 'Portaria', REMOTE_CENTRAL: 'Central remota', RESIDENT_APP: 'App do morador',
  FACIAL: 'Facial', QR: 'QR', DEVICE: 'Equipamento', SYSTEM: 'Sistema', PLATFORM: 'Plataforma', MANAGEMENT: 'Gestão' };
export const integrityProblems: Record<string, string> = { SEQ_GAP: 'buraco na sequência', PREV_HASH_MISMATCH: 'elo anterior diferente',
  HASH_MISMATCH: 'conteúdo diferente do hash', ANCHOR_MISMATCH: 'âncora diária diferente', HEAD_MISMATCH: 'cabeça da cadeia diferente' };

const reasonOf = (value: string) => {
  const reason = displayText(value, 500);
  return reason !== null && Array.from(reason).length >= 3 ? reason : null;
};

export interface MemberDraft { accountId: string; role: PartnerRole | ''; displayName: string; reason: string }
export const emptyMemberDraft = (): MemberDraft => ({ accountId: '', role: '', displayName: '', reason: '' });
/** Corpo do novo papel ou a mensagem do primeiro problema. O gestor não muda o próprio papel (o servidor recusa). */
export function memberBody(draft: MemberDraft, ownAccountId: string): AddMemberBody | string {
  const accountId = draft.accountId.trim().toLowerCase();
  if (!isUUID(accountId)) return 'Informe a conta pelo identificador (UUID) informado pela plataforma.';
  if (accountId === ownAccountId.toLowerCase()) return 'O gestor não altera o próprio papel; peça à plataforma.';
  if (!(partnerRoles as readonly string[]).includes(draft.role)) return 'Escolha o papel.';
  const displayName = displayText(draft.displayName, 160);
  if (!displayName) return 'Informe o nome de exibição, com até 160 caracteres.';
  const reason = reasonOf(draft.reason);
  if (!reason) return 'Informe o motivo, com 3 a 500 caracteres.';
  return { accountId, role: draft.role as PartnerRole, displayName, reason };
}

export interface ScheduleDraft { memberId: string; tenantId: string; startsAt: string; endsAt: string; reason: string }
export const emptyScheduleDraft = (): ScheduleDraft => ({ memberId: '', tenantId: '', startsAt: '', endsAt: '', reason: '' });
const MAX_SCHEDULE_MS = 31 * 24 * 3_600_000;
/**
 * Corpo da escala, com horários de parede no fuso do condomínio convertidos para instante. Sem início, o servidor usa o
 * relógio do banco. Fim depois do início e no máximo 31 dias, como no servidor.
 */
export function scheduleBody(draft: ScheduleDraft, timeZone: string | undefined): CreateScheduleBody | string {
  if (!isUUID(draft.memberId)) return 'Escolha o operador ou supervisor.';
  if (!isUUID(draft.tenantId) || !timeZone) return 'Escolha o condomínio.';
  if (!draft.endsAt) return 'Informe o fim da escala.';
  let startsAt: string | undefined; let endsAt: string;
  try {
    startsAt = draft.startsAt ? toInstant(draft.startsAt, timeZone) : undefined;
    endsAt = toInstant(draft.endsAt, timeZone);
  } catch { return 'Data e hora inválidas.'; }
  if (startsAt !== undefined) {
    const span = Date.parse(endsAt) - Date.parse(startsAt);
    if (!(span > 0)) return 'O fim precisa ser depois do início.';
    if (span > MAX_SCHEDULE_MS) return 'Cada escala cobre no máximo 31 dias.';
  }
  const reason = reasonOf(draft.reason);
  if (!reason) return 'Informe o motivo, com 3 a 500 caracteres.';
  return { memberId: draft.memberId.toLowerCase(), tenantId: draft.tenantId.toLowerCase(), ...(startsAt ? { startsAt } : {}), endsAt, reason };
}
/** Só operador remoto e supervisor recebem escala (regra do banco, V049). */
export const scheduledRoles: readonly PartnerRole[] = ['SECURITY_SUPERVISOR', 'REMOTE_OPERATOR'];

export interface LogFilterDraft { type: string; severity: SecuritySeverity | '' }
/** Filtro do log ou a mensagem do problema; tipo em maiúsculas, como o servidor exige. */
export function logFilter(draft: LogFilterDraft): SecurityLogFilter | string {
  const type = draft.type.trim().toUpperCase();
  if (type && !EVENT_TYPE.test(type)) return 'Tipo de evento: 3 a 40 letras maiúsculas, números ou _, começando por letra.';
  return { ...(type ? { type } : {}), ...(draft.severity ? { severity: draft.severity } : {}) };
}

/**
 * Pedido de consulta do log: cada envio do formulário é uma consulta nova (contador na chave), mesmo com os mesmos
 * filtros; a chave do React Query igual não reaproveita a página antiga em silêncio. Começa sempre da primeira página.
 */
export interface LogRequest { filter: SecurityLogFilter; run: number }
export const logRequest = (current: LogRequest | null, filter: SecurityLogFilter): LogRequest => ({ filter, run: (current?.run ?? 0) + 1 });
export const logRequestKey = (accountId: string, tenantId: string, request: LogRequest | null) =>
  securityLogKey(accountId, tenantId, 'page', request?.filter ?? null, request?.run ?? 0);

/**
 * Falha de autorização que muda a tela do log (segundo fator ou acesso recusado), considerando só as consultas pedidas
 * nesta montagem: erro antigo em cache de consulta não pedida (ex.: integridade antes do MFA) não reabre o segundo fator.
 */
export function logAccessFailure(requested: { pages: boolean; integrity: boolean }, pagesError: unknown, integrityError: unknown): unknown {
  const errors = [requested.pages ? pagesError : null, requested.integrity ? integrityError : null];
  return errors.find((error) => error !== null && ['denied', 'mfa'].includes(failureKind(error))) ?? null;
}

export interface ExportDraft { reason: string; fromSeq: string; toSeq: string }
export function exportBody(draft: ExportDraft): { reason: string; fromSeq?: number; toSeq?: number } | string {
  const reason = displayText(draft.reason, 500);
  if (!reason || Array.from(reason).length < 10) return 'Informe o motivo da exportação, com 10 a 500 caracteres.';
  const parse = (value: string) => value.trim() === '' ? undefined : /^[1-9][0-9]{0,15}$/.test(value.trim()) ? Number(value.trim()) : NaN;
  const fromSeq = parse(draft.fromSeq); const toSeq = parse(draft.toSeq);
  if (Number.isNaN(fromSeq) || Number.isNaN(toSeq)) return 'Sequência inicial e final são números inteiros a partir de 1.';
  if (fromSeq !== undefined && toSeq !== undefined && toSeq < fromSeq) return 'A sequência final não pode ser menor que a inicial.';
  if (fromSeq !== undefined && toSeq !== undefined && toSeq - fromSeq + 1 > 5000) return 'Exporte no máximo 5.000 eventos por vez.';
  return { reason, ...(fromSeq !== undefined ? { fromSeq } : {}), ...(toSeq !== undefined ? { toSeq } : {}) };
}
/** Nome do arquivo exportado: condomínio e intervalo, sem dado pessoal. */
export const exportFileName = (tenantId: string, fromSeq: number, toSeq: number) => `log-seguranca-${tenantId.slice(0, 8)}-${fromSeq}-${toSeq}.json`;

// ---- Comando de Segurança (CPX-012 a CPX-014) ----
export const commandKey = (accountId: string, partnerId: string, ...parts: unknown[]) => partnerKey(accountId, partnerId, 'command', ...parts);
export const lightLabels: Record<Light, string> = { RED: 'Vermelho', AMBER: 'Amarelo', GREEN: 'Verde' };
export const lightTone: Record<Light, string> = { RED: 'status-overdue', AMBER: 'status-pending', GREEN: 'status-ready' };
export const lightReasonLabels: Record<LightReason, string> = {
  CRITICAL_ALERTS_UNLINKED: 'alerta crítico sem incidente (24 h)', CRITICAL_INCIDENTS_OPEN: 'incidente crítico aberto',
  DEVICES_OFFLINE: 'equipamento fora do ar', DEVICES_UNKNOWN: 'equipamento sem informação recente', NO_OPERATOR_ON_DUTY: 'central sem operador escalado agora',
  SECURITY_OCCURRENCES_OPEN: 'ocorrência de segurança aberta', INCIDENTS_OPEN: 'incidente aberto' };
export const unavailableLabels: Record<string, string> = { DOORS_HELD_OPEN: 'portas abertas', STAYS_OVERDUE: 'permanências vencidas' };
export const incidentSeverityLabels: Record<IncidentSeverity, string> = { ATTENTION: 'Atenção', CRITICAL: 'Crítico' };
export const outcomeLabels: Record<IncidentOutcome, string> = { RESOLVED: 'Resolvido', FALSE_ALARM: 'Alarme falso', REFERRED: 'Encaminhado (protocolo externo)' };
export const verdictLabels: Record<ReviewVerdict, string> = { CONFERRED: 'Conferida', IRREGULAR: 'Irregular' };
export const reviewFilterLabels: Record<ReviewFilter, string> = { PENDING: 'Sem revisão', REVIEWED: 'Revisadas', ALL: 'Todas' };

export interface IncidentDraft { tenantId: string; title: string; severity: IncidentSeverity | ''; eventIds: readonly string[] }
/** Corpo do incidente novo ou a mensagem do primeiro problema; o servidor confere tudo de novo. */
export function incidentBody(draft: IncidentDraft, tenantIds: Iterable<string>): OpenIncidentBody | string {
  if (!isUUID(draft.tenantId) || !stillReached(tenantIds, draft.tenantId)) return 'Escolha o condomínio.';
  const title = displayText(draft.title, 120);
  if (!title || Array.from(title).length < 3) return 'Informe o título, com 3 a 120 caracteres.';
  if (draft.severity !== 'ATTENTION' && draft.severity !== 'CRITICAL') return 'Escolha a severidade.';
  const eventIds = [...new Set(draft.eventIds.map((id) => id.toLowerCase()))];
  if (eventIds.length > 50 || eventIds.some((id) => !isUUID(id))) return 'Anexe no máximo 50 eventos do log.';
  return { tenantId: draft.tenantId.toLowerCase(), title, severity: draft.severity, eventIds };
}
export interface CloseDraft { outcome: IncidentOutcome | ''; report: string }
export function closeBody(draft: CloseDraft, expectedVersion: number): CloseIncidentBody | string {
  if (!draft.outcome) return 'Escolha o desfecho.';
  const report = draft.report.replace(/\r\n?/g, '\n').trim();
  if (Array.from(report).length < 10 || Array.from(report).length > 4000) return 'Escreva o relatório de encerramento, com 10 a 4.000 caracteres.';
  return { outcome: draft.outcome, report, expectedVersion };
}
export interface ReviewDraft { verdict: ReviewVerdict | ''; note: string }
/** Irregular e correção de revisão exigem motivo (3 a 500); conferida aceita observação opcional. */
export function reviewBody(draft: ReviewDraft, correctsReviewId: string | null): ReviewBody | string {
  if (draft.verdict !== 'CONFERRED' && draft.verdict !== 'IRREGULAR') return 'Escolha conferida ou irregular.';
  const note = draft.note.trim() === '' ? null : displayText(draft.note, 500);
  if (draft.note.trim() !== '' && (!note || Array.from(note).length < 3)) return 'A observação tem de 3 a 500 caracteres.';
  if (!note && (draft.verdict === 'IRREGULAR' || correctsReviewId)) return 'Informe o motivo, com 3 a 500 caracteres.';
  return { verdict: draft.verdict, note, correctsReviewId };
}
export function dossierReason(value: string): string | null {
  const reason = displayText(value, 500);
  return reason && Array.from(reason).length >= 10 ? reason : null;
}
/** Nome do arquivo do dossiê: condomínio e incidente, sem dado pessoal. */
export const dossierFileName = (tenantId: string, incidentId: string) => `dossie-incidente-${tenantId.slice(0, 8)}-${incidentId.slice(0, 8)}.json`;
