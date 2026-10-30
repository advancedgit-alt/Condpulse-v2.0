import { useEffect } from 'react';
import { AppState } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { connectUnitEvents, unitEventsPath, type EventSourceFactory, type Timers, type Topic } from '../api/events';

/** Partes da chave de consulta de cada assunto no escopo da unidade; assuntos sem tela no celular não consultam nada. */
export const topicKeys: Partial<Record<Topic, string[]>> = { reservations: ['reservations', 'availability', 'waitlist'], parcels: ['parcels'] };

/**
 * Mantém a unidade aberta atualizada (CP-036) enquanto o aplicativo está em primeiro plano. Em segundo plano o canal é
 * fechado (bateria e dados); ao voltar, tudo da unidade é consultado de novo e o canal reabre. Sem canal (função
 * desligada, rede ruim), a tela segue com consulta ao abrir e botões de atualizar.
 */
export function useUnitEvents({ accountId, tenantId, unitId, create, timers }: {
  accountId: string; tenantId: string; unitId: string; create: EventSourceFactory | null; timers?: Timers;
}) {
  const queries = useQueryClient();
  useEffect(() => {
    if (!create) return undefined;
    const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId] as const;
    let stop: (() => void) | null = null;
    const start = () => {
      stop?.();
      stop = connectUnitEvents(unitEventsPath(tenantId, unitId), {
        changed: (topics) => {
          for (const part of new Set(topics.flatMap((topic) => topicKeys[topic] ?? []))) void queries.invalidateQueries({ queryKey: [...scope, part] });
        },
        resync: () => { void queries.invalidateQueries({ queryKey: scope }); },
        ended: (reason) => { void queries.invalidateQueries({ queryKey: reason === 'revoked' ? ['private', accountId, 'identity'] : scope }); },
      }, create, timers);
    };
    if (AppState.currentState === 'active') start();
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        if (!stop) { void queries.invalidateQueries({ queryKey: scope }); start(); }
      } else { stop?.(); stop = null; }
    });
    return () => { subscription.remove(); stop?.(); stop = null; };
  }, [accountId, tenantId, unitId, create, queries, timers]);
}
