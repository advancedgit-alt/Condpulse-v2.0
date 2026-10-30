import type { QueryClient, QueryKey } from '@tanstack/react-query';

/**
 * Invalida o que o canal da unidade disse que mudou. Consulta que ainda está na primeira carga (sem dados) não é
 * reiniciada pelo TanStack: a invalidação aproveita a resposta em andamento, que pode ter sido calculada antes da mudança.
 * Essas consultas são feitas de novo quando a primeira termina, para o aviso do canal não se perder.
 */
export async function invalidateFresh(client: QueryClient, queryKey: QueryKey): Promise<void> {
  const loading = client.getQueryCache().findAll({ queryKey })
    .filter((query) => query.state.fetchStatus === 'fetching' && query.state.data === undefined);
  await client.invalidateQueries({ queryKey });
  await Promise.all(loading.filter((query) => query.getObserversCount() > 0)
    .map((query) => client.refetchQueries({ queryKey: query.queryKey, exact: true })));
}
