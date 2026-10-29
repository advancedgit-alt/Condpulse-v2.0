import { useSyncExternalStore } from 'react';
import { useQueryClient, type Query } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { Notice } from './primitives';

/** Consultas tratadas pelo próprio fluxo de acesso: a sessão e a disponibilidade do login têm telas próprias. */
const ownedElsewhere = new Set(['identity', 'login-availability']);

/**
 * Consulta na tela cuja última atualização falhou mas que continua mostrando os dados de antes (o TanStack Query
 * preserva `data` depois de um erro). Sessão encerrada (401) é tratada pelo acesso, não como dado antigo.
 */
export function showsStaleData(query: Query): boolean {
  const { status, data, error, fetchStatus } = query.state;
  if (status !== 'error' || data === undefined || fetchStatus === 'fetching' || query.getObserversCount() === 0) return false;
  if (ownedElsewhere.has(String(query.queryKey[0]))) return false;
  return !(error instanceof ApiError && error.status === 401);
}

/**
 * Aviso único da área autenticada (PRD, seção 15: conexão indisponível e dado antigo): quando uma lista não consegue
 * se atualizar, a tela continua mostrando o que já tinha, mas deixa claro que pode estar desatualizado.
 */
export function StaleDataNotice() {
  const client = useQueryClient();
  const cache = client.getQueryCache();
  const count = useSyncExternalStore(
    (changed) => cache.subscribe(() => changed()),
    () => cache.getAll().filter(showsStaleData).length,
    () => 0);
  if (count === 0) return null;
  return <div className="stale-data-notice"><Notice tone="warning">
    Não foi possível atualizar {count === 1 ? 'uma parte' : 'algumas partes'} desta página. O que aparece pode estar desatualizado; confira antes de agir.{' '}
    <button className="button button-secondary button-compact" type="button"
      onClick={() => void client.refetchQueries({ predicate: showsStaleData })}>Tentar atualizar de novo</button>
  </Notice></div>;
}
