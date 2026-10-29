import { api, identifier, invalidResponse, isDate, isInteger, isString, isUUID, list, oneOf, record, type CondPulseApi } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Importação de blocos e unidades em staging (CP-016). Abrir um lote só grava a prévia; aprovar cria o que falta.
 * Pessoas e vínculos nunca vêm da planilha. Toda resposta é validada antes da interface.
 */
export type ImportState = 'PREVIEW' | 'APPROVED' | 'DISCARDED';
export interface ImportBatch {
  batchId: UUID; source: string; fileHash: string; mappingVersion: 1; state: ImportState; totalRows: number; validRows: number;
  invalidRows: number; duplicateRows: number; partial: boolean; createdUnits: number | null; existingUnits: number | null;
  createdBlocks: number | null; createdAt: ISODateTime; decidedAt: ISODateTime | null; version: number;
}
export interface ImportRow {
  line: number; block: string | null; unit: string | null; externalId: string | null; outcome: 'VALID' | 'INVALID' | 'DUPLICATE';
  errorCode: string | null; alreadyExists: boolean; applied: 'CREATED' | 'EXISTING' | null; unitId: UUID | null;
}
export interface ImportDetail { batch: ImportBatch; rows: ImportRow[] }
/** `created` false: o mesmo arquivo já tinha lote vivo; `applied` false: o lote já estava nesse estado (repetição). */
export interface ImportOpened { detail: ImportDetail; created: boolean }
export interface ImportDecided { detail: ImportDetail; applied: boolean | null }

export const IMPORT_MAX_BYTES = 256 * 1024;
const nullableString = (value: unknown): value is string | null => value === null || isString(value);
const nullableCount = (value: unknown): value is number | null => value === null || isInteger(value);

export function importBatch(value: unknown, uncertain = false): ImportBatch {
  if (!record(value) || !isUUID(value.batchId) || !isString(value.source) || !isString(value.fileHash) || !/^[0-9a-f]{64}$/.test(value.fileHash)
    || value.mappingVersion !== 1 || !oneOf(value.state, ['PREVIEW', 'APPROVED', 'DISCARDED'] as const)
    || !isInteger(value.totalRows) || !isInteger(value.validRows) || !isInteger(value.invalidRows) || !isInteger(value.duplicateRows)
    || typeof value.partial !== 'boolean' || !nullableCount(value.createdUnits ?? null) || !nullableCount(value.existingUnits ?? null)
    || !nullableCount(value.createdBlocks ?? null) || !isDate(value.createdAt) || !(value.decidedAt == null || isDate(value.decidedAt))
    || !isInteger(value.version) || value.validRows + value.invalidRows + value.duplicateRows !== value.totalRows) throw invalidResponse(uncertain);
  return { batchId: value.batchId, source: value.source, fileHash: value.fileHash, mappingVersion: 1, state: value.state,
    totalRows: value.totalRows, validRows: value.validRows, invalidRows: value.invalidRows, duplicateRows: value.duplicateRows,
    partial: value.partial, createdUnits: (value.createdUnits ?? null) as number | null, existingUnits: (value.existingUnits ?? null) as number | null,
    createdBlocks: (value.createdBlocks ?? null) as number | null, createdAt: value.createdAt,
    decidedAt: (value.decidedAt ?? null) as string | null, version: value.version };
}
function importRow(value: unknown, uncertain = false): ImportRow {
  if (!record(value) || !isInteger(value.line) || !nullableString(value.block ?? null) || !nullableString(value.unit ?? null)
    || !nullableString(value.externalId ?? null) || !oneOf(value.outcome, ['VALID', 'INVALID', 'DUPLICATE'] as const)
    || !nullableString(value.errorCode ?? null) || typeof value.alreadyExists !== 'boolean'
    || !(value.applied == null || oneOf(value.applied, ['CREATED', 'EXISTING'] as const)) || !(value.unitId == null || isUUID(value.unitId)))
    throw invalidResponse(uncertain);
  return { line: value.line, block: (value.block ?? null) as string | null, unit: (value.unit ?? null) as string | null,
    externalId: (value.externalId ?? null) as string | null, outcome: value.outcome, errorCode: (value.errorCode ?? null) as string | null,
    alreadyExists: value.alreadyExists, applied: (value.applied ?? null) as ImportRow['applied'], unitId: (value.unitId ?? null) as string | null };
}
export function importDetail(value: unknown, uncertain = false): ImportDetail {
  if (!record(value)) throw invalidResponse(uncertain);
  return { batch: importBatch(value.batch, uncertain), rows: list(value.rows, (item) => importRow(item, uncertain)) };
}
const flag = (response: Response, name: string): boolean | null => {
  const header = response.headers.get(name);
  return header === 'true' ? true : header === 'false' ? false : null;
};

export function createImportApi(client: CondPulseApi = api) {
  const base = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}/management/imports`;
  return {
    batches: async (tenantId: string, signal?: AbortSignal) =>
      client.call(base(tenantId), (value) => {
        if (!record(value)) throw invalidResponse();
        return list(value.batches, (item) => importBatch(item));
      }, signal ? { signal } : {}),
    detail: async (tenantId: string, batchId: string, signal?: AbortSignal) =>
      client.call(`${base(tenantId)}/${identifier(batchId)}`, (value) => importDetail(value), signal ? { signal } : {}),
    open: async (tenantId: string, body: { fileName: string; content: string }) =>
      client.call(base(tenantId), (value, response): ImportOpened => ({ detail: importDetail(value, true),
        created: flag(response, 'Import-Batch-Created') ?? response.status === 201 }), { method: 'POST', body }),
    approve: async (tenantId: string, batchId: string, body: { expectedVersion: number; acceptPartial: boolean }) =>
      client.call(`${base(tenantId)}/${identifier(batchId)}/approve`, (value, response): ImportDecided => ({
        detail: importDetail(value, true), applied: flag(response, 'Import-Decision-Applied') }), { method: 'POST', body }),
    discard: async (tenantId: string, batchId: string, body: { expectedVersion: number }) =>
      client.call(`${base(tenantId)}/${identifier(batchId)}/discard`, (value, response): ImportDecided => ({
        detail: importDetail(value, true), applied: flag(response, 'Import-Decision-Applied') }), { method: 'POST', body }),
  };
}
export const imports = createImportApi();
