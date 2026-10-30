import { ContractError } from './reservations';

/**
 * Histórico de entradas e saídas da unidade no aplicativo (CP-028), validado como na web. Só o que a portaria registrou
 * manualmente (MANUAL_IN, nunca passagem detectada por equipamento); saída e correção são eventos adicionais e nada é
 * apagado. Na visão da unidade as observações internas da portaria chegam nulas: uma resposta com observação é recusada.
 */
export interface AccessCorrection { correctedAt: string; reason: string }
export interface AccessExit { exitedAt: string; correction: AccessCorrection | null }
export interface AccessRecord {
  entryId: string; unitId: string; visitorName: string; documentLast4: string | null; viaArrival: boolean; evidence: 'DOCUMENT_CHECKED' | 'RECOGNIZED_BY_UNIT';
  enteredAt: string; correction: AccessCorrection | null; exit: AccessExit | null;
}
export interface AccessRecordPage { records: AccessRecord[]; truncated: boolean }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value));
const text = (value: unknown, min: number, max: number): value is string => typeof value === 'string' && [...value].length >= min && [...value].length <= max;
function check(condition: boolean): asserts condition { if (!condition) throw new ContractError(); }

function correction(value: unknown): AccessCorrection | null {
  if (value === null) return null;
  check(record(value) && uuid(value.eventId) && date(value.correctedAt) && text(value.reason, 1, 300) && typeof value.recordedByMe === 'boolean');
  return { correctedAt: value.correctedAt as string, reason: value.reason as string };
}
export function accessRecord(value: unknown, unitId: string): AccessRecord {
  check(record(value) && uuid(value.entryId) && uuid(value.unitId) && value.unitId.toLowerCase() === unitId.toLowerCase() && text(value.visitorName, 2, 160)
    && (value.documentLast4 === null || (typeof value.documentLast4 === 'string' && /^[0-9A-Z]{2,4}$/.test(value.documentLast4)))
    && (value.arrivalId === null || uuid(value.arrivalId)) && (value.permitId === null || uuid(value.permitId))
    && (value.arrivalId === null) !== (value.permitId === null) && value.eventType === 'MANUAL_IN' && value.source === 'CONCIERGE'
    && (value.evidence === 'DOCUMENT_CHECKED' || value.evidence === 'RECOGNIZED_BY_UNIT') && value.note === null && date(value.enteredAt)
    && typeof value.recordedByMe === 'boolean' && value.correction !== undefined && value.exit !== undefined);
  let exit: AccessExit | null = null;
  if (value.exit !== null) {
    const raw = value.exit;
    check(record(raw) && uuid(raw.eventId) && date(raw.exitedAt) && raw.note === null && typeof raw.recordedByMe === 'boolean' && raw.correction !== undefined);
    exit = { exitedAt: raw.exitedAt as string, correction: correction(raw.correction) };
  }
  return { entryId: value.entryId as string, unitId: value.unitId as string, visitorName: value.visitorName as string,
    documentLast4: value.documentLast4 as string | null, viaArrival: value.arrivalId !== null, evidence: value.evidence as AccessRecord['evidence'],
    enteredAt: value.enteredAt as string, correction: correction(value.correction), exit };
}
export function accessRecordPage(value: unknown, unitId: string): AccessRecordPage {
  check(record(value) && Array.isArray(value.records) && value.records.length <= 100 && typeof value.truncated === 'boolean');
  return { records: (value.records as unknown[]).map((item) => accessRecord(item, unitId)), truncated: value.truncated as boolean };
}
