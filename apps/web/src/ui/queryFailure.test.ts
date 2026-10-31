// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { isAuthorizationError, queryFailure } from './queryFailure';

describe('falha de consulta', () => {
  it('módulo desligado pela administradora não é perda de acesso; mantém a lista em cache', () => {
    const off = new ApiError(403, 'MODULE_DISABLED', 'Módulo desligado.');
    expect(isAuthorizationError(off)).toBe(false);
    expect(isAuthorizationError(new ApiError(403, 'STAFF_ACCESS_DENIED', 'Negado.'))).toBe(true);
    expect(queryFailure([{ isError: true, error: off, data: { items: [] } }])).toBeNull();
    expect(queryFailure([{ isError: true, error: off, data: undefined }])).toBe(off);
  });
});
