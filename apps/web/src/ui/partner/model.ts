import { ApiError, isUUID } from '../../api/client';
import { displayText } from '../../api/text';
import { partnerRoles, type PartnerRole } from '../../api/types';
import type { AddMemberBody, CreateScheduleBody } from '../../api/partners';
import { EVENT_TYPE, type SecurityLogFilter, type SecuritySeverity } from '../../api/securityLog';
import { toInstant } from '../dateTime';

/**
 * Regras puras dos painéis de parceiro (CPX-004/CPX-005): leitura de falhas, validação local dos formulários e chaves
 * de cache. O servidor valida tudo de novo; aqui só se evita enviar o que ele recusaria com certeza.
 */
export const partnerKey = (accountId: string, partnerId: string, ...parts: unknown[]) => ['private', accountId, 'partner', partnerId.toLowerCase(), ...parts] as const;
export const securityLogKey = (accountId: string, tenantId: string, ...parts: unknown[]) => ['private', accountId, 'security-log', tenantId.toLowerCase(), ...parts] as const;

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
  if (['PARTNER_ACCESS_DENIED', 'PARTNER_NOT_FOUND', 'PARTNER_FORBIDDEN', 'TENANT_ACCESS_DENIED', 'STAFF_ACCESS_DENIED'].includes(error.code)) return 'denied';
  if (error.status >= 500 || error.status === 0) return 'unavailable';
  return 'other';
}

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
