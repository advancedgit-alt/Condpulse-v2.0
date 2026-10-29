// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createImportApi, importBatch, importDetail } from './imports';

const json = (value: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const tenant = '00000000-0000-4000-8000-000000000001';
const batchId = 'a1000000-0000-4000-8000-000000000001';
const batch = { batchId, source: 'unidades.csv', fileHash: 'a'.repeat(64), mappingVersion: 1, state: 'PREVIEW', totalRows: 3, validRows: 1,
  invalidRows: 1, duplicateRows: 1, partial: false, createdUnits: null, existingUnits: null, createdBlocks: null,
  createdAt: '2030-01-01T12:00:00Z', decidedAt: null, version: 0 };
const rows = [
  { line: 2, block: 'Bloco A', unit: '101', externalId: null, outcome: 'VALID', errorCode: null, alreadyExists: true, applied: null, unitId: null },
  { line: 3, block: null, unit: null, externalId: null, outcome: 'INVALID', errorCode: 'UNIT_LABEL_REQUIRED', alreadyExists: false, applied: null, unitId: null },
  { line: 4, block: 'bloco a', unit: '101', externalId: null, outcome: 'DUPLICATE', errorCode: null, alreadyExists: false, applied: null, unitId: null },
];

describe('contrato da importação de unidades', () => {
  it('lote só é aceito com totais coerentes, hash e estado conhecidos', () => {
    expect(importBatch(batch).state).toBe('PREVIEW');
    expect(() => importBatch({ ...batch, validRows: 2 })).toThrow();
    expect(() => importBatch({ ...batch, fileHash: 'xyz' })).toThrow();
    expect(() => importBatch({ ...batch, state: 'APPLIED' })).toThrow();
    expect(() => importBatch({ ...batch, mappingVersion: 2 })).toThrow();
    expect(importDetail({ batch, rows }).rows).toHaveLength(3);
    expect(() => importDetail({ batch, rows: [{ ...rows[0], outcome: 'OK' }] })).toThrow();
    expect(() => importDetail({ batch, rows: [{ ...rows[0], unitId: 'nao-e-uuid' }] })).toThrow();
  });
  it('envia o arquivo, lê se o lote é novo e se a decisão foi aplicada agora', async () => {
    let reply: () => Response = () => json({ batch, rows }, 201, { 'Import-Batch-Created': 'true' });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createImportApi(new CondPulseApi({ fetch: fetcher }));
    const opened = await client.open(tenant, { fileName: 'unidades.csv', content: 'unidade\n101\n' });
    expect(opened.created).toBe(true);
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/management/imports`);
    expect(JSON.parse(String(call[1]?.body))).toEqual({ fileName: 'unidades.csv', content: 'unidade\n101\n' });
    reply = () => json({ batch, rows }, 200, { 'Import-Batch-Created': 'false' });
    expect((await client.open(tenant, { fileName: 'copia.csv', content: 'unidade\n101\n' })).created).toBe(false);
    const approved = { ...batch, state: 'APPROVED', partial: true, createdUnits: 0, existingUnits: 1, createdBlocks: 0, decidedAt: '2030-01-01T12:01:00Z', version: 1 };
    reply = () => json({ batch: approved, rows }, 200, { 'Import-Decision-Applied': 'false' });
    expect((await client.approve(tenant, batchId, { expectedVersion: 0, acceptPartial: true })).applied).toBe(false);
    reply = () => json({ batch: approved, rows }, 200);
    expect((await client.approve(tenant, batchId, { expectedVersion: 0, acceptPartial: true })).applied).toBeNull();
    // Resposta de decisão que não fecha é resultado incerto, nunca sucesso.
    reply = () => json({ batch: { ...approved, totalRows: 9 }, rows }, 200, { 'Import-Decision-Applied': 'true' });
    await expect(client.discard(tenant, batchId, { expectedVersion: 1 })).rejects.toMatchObject({ uncertain: true });
    await expect(client.detail(tenant, '../outro')).rejects.toBeTruthy();
  });
});
