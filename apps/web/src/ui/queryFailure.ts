import { ApiError } from '../api/client';

interface QueryLike { isError: boolean; error: unknown; data: unknown }

/** Falha de autorização (sessão, segundo fator, vínculo, concessão ou escopo): os dados em cache deixam de valer. */
export function isAuthorizationError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 || error.code === 'TENANT_ACCESS_DENIED');
}

/**
 * Falha que a tela precisa tratar. Autorização vale sempre, mesmo com dados em cache (que somem da tela e seguem para a
 * porta de acesso); as demais falhas só contam sem dados, para manter a última lista válida enquanto o servidor se recupera.
 */
export function queryFailure(queries: readonly QueryLike[]): unknown {
  const denied = queries.find((query) => query.isError && isAuthorizationError(query.error));
  if (denied) return denied.error;
  return queries.find((query) => query.isError && query.data === undefined)?.error ?? null;
}
