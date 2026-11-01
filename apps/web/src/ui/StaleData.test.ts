// @vitest-environment node
import { describe, expect, it } from 'vitest';
import type { Query } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { showsStaleData } from './StaleData';

function query(key: string, error: unknown, ...rest: [data?: unknown]): Query {
  const data = rest.length ? rest[0] : { items: [] };
  return { queryKey: [key], state: { status: 'error', data, error, fetchStatus: 'idle' }, getObserversCount: () => 1 } as unknown as Query;
}

describe('aviso de dado antigo', () => {
  it('falha de rede ou do servidor com dados em cache conta como dado antigo', () => {
    expect(showsStaleData(query('private', new TypeError('rede')))).toBe(true);
    expect(showsStaleData(query('private', new ApiError(503, 'UNAVAILABLE', 'Indisponível.')))).toBe(true);
  });
  it('segundo fator, papel perdido ou sessão encerrada têm tela própria e não aparecem como dado antigo', () => {
    expect(showsStaleData(query('private', new ApiError(403, 'MFA_REQUIRED', 'Confirme o segundo fator.')))).toBe(false);
    expect(showsStaleData(query('private', new ApiError(403, 'PLATFORM_ACCESS_DENIED', 'Área restrita.')))).toBe(false);
    expect(showsStaleData(query('private', new ApiError(403, 'STAFF_ACCESS_DENIED', 'Negado.')))).toBe(false);
    expect(showsStaleData(query('private', new ApiError(401, 'SESSION_EXPIRED', 'Sessão encerrada.')))).toBe(false);
  });
  it('módulo desligado com dados em cache continua como dado antigo (a tela mostra o estado do módulo)', () => {
    expect(showsStaleData(query('private', new ApiError(403, 'MODULE_DISABLED', 'Módulo desligado.')))).toBe(true);
  });
  it('sem dados anteriores ou consulta do próprio acesso não há aviso', () => {
    expect(showsStaleData(query('private', new TypeError('rede'), undefined))).toBe(false);
    expect(showsStaleData(query('identity', new TypeError('rede')))).toBe(false);
  });
});
