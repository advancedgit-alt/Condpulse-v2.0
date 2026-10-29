import { useEffect, useMemo, useState } from 'react';
import { SafeAreaView, StyleSheet, Text } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { randomUUID } from 'expo-crypto';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { readConfig } from './config';
import { createApi } from './api/client';
import { xhrEventSource, type EventSourceFactory } from './api/events';
import { createRuntime } from './session/runtime';
import type { SessionManager } from './session/session';
import { Root } from './ui/Root';
import { colors } from './ui/theme';

/** Cache só em memória: nada de dados do condomínio é gravado no aparelho. */
export function newQueryClient(gcTime = 5 * 60_000): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: 1, gcTime, staleTime: 30_000 } } });
}

/** Chave de envio por operação (Idempotency-Key), aleatória do sistema operacional. */
const newKey = () => `mobile:${randomUUID()}`;

export default function App() {
  // O Expo só embute EXPO_PUBLIC_* lidos por nome (process.env.NOME), não o objeto inteiro.
  const config = useMemo(() => readConfig({ EXPO_PUBLIC_SUPABASE_URL: process.env.EXPO_PUBLIC_SUPABASE_URL,
    EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY: process.env.EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY, EXPO_PUBLIC_API_URL: process.env.EXPO_PUBLIC_API_URL }), []);
  const queries = useMemo(() => newQueryClient(), []);
  const [manager, setManager] = useState<SessionManager | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!config) return;
    let active = true;
    createRuntime(config, () => queries.clear()).then((created) => { if (active) setManager(created); }, () => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [config, queries]);
  const api = useMemo(() => manager && config ? createApi({ baseUrl: config.apiUrl, tokens: manager }) : null, [manager, config]);
  // Canal da unidade (CP-036): mesma origem da API, token Bearer buscado de novo a cada conexão.
  const events = useMemo<EventSourceFactory | null>(() => manager && config
    ? (path) => xhrEventSource(new URL(path, config.apiUrl).toString(), () => manager.accessToken(), () => new XMLHttpRequest())
    : null, [manager, config]);
  return <QueryClientProvider client={queries}>
    <SafeAreaView style={styles.page}>
      <StatusBar style="dark" />
      {!config ? <Text style={styles.message}>Configuração do aplicativo incompleta. Procure o suporte do CondPulse.</Text>
        : failed ? <Text style={styles.message}>Não foi possível abrir o armazenamento seguro do aparelho.</Text>
          : manager && api ? <Root manager={manager} api={api} newKey={newKey} events={events} /> : <Text style={styles.message}>Abrindo…</Text>}
    </SafeAreaView>
  </QueryClientProvider>;
}
const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: colors.background },
  message: { margin: 24, fontSize: 16, color: colors.text },
});
