import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { connectUnitEvents, unitEventsUrl, type EventSourceFactory, type Topic } from '../api/events';
import { scopeKey } from './Operations';

/** Partes da chave de consulta de cada assunto, dentro do escopo da unidade. */
export const topicKeys: Record<Topic, string[]> = {
  reservations: ['reservations', 'dashboard', 'availability'],
  parcels: ['parcels'],
  arrivals: ['visits'],
  notifications: ['inbox'],
  announcements: ['announcements'],
  requests: ['requests', 'request'],
};

const browserSource: EventSourceFactory = (url) => new EventSource(url);

/**
 * Mantém a unidade aberta atualizada (CP-036). Não desenha nada: sem o canal (função desligada, rede ruim) a tela continua
 * como antes, com consulta ao abrir e botões de atualizar.
 */
export function UnitEvents({ accountId, tenantId, unitId, create = browserSource }:
  { accountId: string; tenantId: string; unitId: string; create?: EventSourceFactory }) {
  const queries = useQueryClient();
  useEffect(() => {
    if (typeof window === 'undefined' || (create === browserSource && typeof EventSource === 'undefined')) return undefined;
    const scope = scopeKey({ accountId, tenantId, unitId });
    return connectUnitEvents(unitEventsUrl(tenantId, unitId), {
      changed: (topics) => {
        for (const part of new Set(topics.flatMap((topic) => topicKeys[topic]))) void queries.invalidateQueries({ queryKey: [...scope, part] });
      },
      resync: () => { void queries.invalidateQueries({ queryKey: scope }); },
      // Revogado: a identidade decide para onde a tela vai. Expirado: a consulta seguinte renova a sessão.
      ended: (reason) => { void queries.invalidateQueries({ queryKey: reason === 'revoked' ? ['identity'] : scope }); },
    }, create);
  }, [accountId, tenantId, unitId, queries, create]);
  return null;
}
